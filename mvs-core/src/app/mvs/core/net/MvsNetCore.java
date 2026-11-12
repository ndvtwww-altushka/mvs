package app.mvs.core.net;

import app.mvs.core.util.Backoff;
import app.mvs.core.util.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class MvsNetCore {
    public static final class Config {
        public int maxInFlight = 32, perDcInFlight = 8, queueCap = 4096;
        public int[] dcCandidates = {1,2,3,4,5};
        public int maxRetries = 3;
        public long retryBaseMs = 200, retryCapMs = 5000, floodWaitCapMs = 10000;
        public double retryBudgetTokens = 20, retryBudgetRefillPerSec = 4;
        public int updateQueueCap = 1024;
        /** 7c: per-DC failover НЕ реализован до нативной маршрутизации; false = статистика по логическому соединению. */
        public boolean nativeDcRouting = false;
    }

    public final DcSupervisor supervisor;
    public final CallScheduler scheduler;
    private final RequestPipeline pipeline;
    private final ScheduledExecutorService timers;
    private final Config cfg;
    private final CopyOnWriteArrayList<Consumer<Object>> updateListeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<Object> updateQueue = new ArrayDeque<>();
    private final Thread updateDispatcher;
    private volatile boolean running = true;
    public final AtomicLong updatesDropped = new AtomicLong();

    public MvsNetCore(MtprotoPort port, Config cfg, Clock clock) {
        this.cfg = cfg;
        this.timers = Executors.newScheduledThreadPool(1, r -> named(r, "mvs-net-timers"));
        this.supervisor = new DcSupervisor(clock);
        this.scheduler = new CallScheduler(port, cfg.maxInFlight, cfg.perDcInFlight, cfg.queueCap, timers);
        List<RequestPipeline.Interceptor> chain = new ArrayList<>();
        chain.add(new RetryInterceptor(cfg.maxRetries, cfg.retryBaseMs, cfg.retryCapMs, cfg.floodWaitCapMs,
                new Backoff.Budget(cfg.retryBudgetTokens, cfg.retryBudgetRefillPerSec, Clock.MONOTONIC), timers)); // 5b
        this.pipeline = new RequestPipeline(chain, r -> scheduler.submit(r.method, r.tl, r.opts));
        port.setUpdatesListener(this::enqueueUpdate);
        this.updateDispatcher = named(new Thread(this::dispatchUpdates), "mvs-updates");
        this.updateDispatcher.setDaemon(true);
        this.updateDispatcher.start();
    }

    public CompletableFuture<Object> send(String method, Object tl, CallScheduler.Options opts) {
        if (supervisor.isOffline()) return failed(new IllegalStateException("offline (kill-switch)"));
        int dc = supervisor.select(opts.dc, cfg.dcCandidates);   // advisory
        if (dc < 0) return failed(new IllegalStateException("no healthy dc"));
        int logical = cfg.nativeDcRouting ? dc : 0;              // 7c: честно - одно логическое соединение
        long token = supervisor.breaker(logical).allow();
        if (token < 0) return failed(new IllegalStateException("connection busy"));
        CallScheduler.Options o = opts.copy(); o.dc = dc;
        long t0 = System.nanoTime();
        CompletableFuture<Object> f = pipeline.execute(new RequestPipeline.Request(method, tl, o));
        f.whenComplete((r, e) -> supervisor.onResult(logical, e == null, (System.nanoTime() - t0) / 1_000_000, token));
        return f;
    }

    public void addUpdateListener(Consumer<Object> l) { updateListeners.add(l); }
    public void setOffline(boolean v) { supervisor.setOffline(v); }

    public void shutdown(long graceMs) {
        running = false;
        scheduler.shutdown();
        synchronized (updateQueue) { updateQueue.notifyAll(); }
        try {
            updateDispatcher.join(graceMs);
            if (updateDispatcher.isAlive()) { updateDispatcher.interrupt(); updateDispatcher.join(200); }
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        timers.shutdownNow();
    }

    private void enqueueUpdate(Object tl) {
        synchronized (updateQueue) {
            if (updateQueue.size() >= cfg.updateQueueCap) { updateQueue.removeFirst(); updatesDropped.incrementAndGet(); }
            updateQueue.addLast(tl);
            updateQueue.notifyAll();
        }
    }

    private void dispatchUpdates() {
        while (true) {                                            // 7a: дренаж хвоста после running=false
            Object u;
            synchronized (updateQueue) {
                while (running && updateQueue.isEmpty())
                    try { updateQueue.wait(200); } catch (InterruptedException e) { return; }
                if (!running && updateQueue.isEmpty()) return;
                u = updateQueue.pollFirst();
            }
            if (u == null) continue;
            for (Consumer<Object> l : updateListeners)
                try { l.accept(u); } catch (Throwable ignored) {}
        }
    }

    private static CompletableFuture<Object> failed(Exception e) {
        CompletableFuture<Object> f = new CompletableFuture<>(); f.completeExceptionally(e); return f;
    }
    private static Thread named(Thread t, String n) { t.setName(n); return t; }
    private static Runnable named(Runnable r, String n) { return () -> { Thread.currentThread().setName(n); r.run(); }; }
}
