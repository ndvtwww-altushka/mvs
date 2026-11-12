package app.mvs.core.net;

import app.mvs.core.util.Backoff;
import app.mvs.core.util.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** High-level network core. Actual MTProto encryption/routing remains in the native client. */
public final class MvsNetCore {
    public static final class Config {
        public int maxInFlight = 32;
        public int perDcInFlight = 8;
        public int queueCap = 4096;
        public int[] dcCandidates = {1, 2, 3, 4, 5};
        public int maxRetries = 3;
        public long retryBaseMs = 200;
        public long retryCapMs = 5000;
        public long floodWaitCapMs = 10_000;
        public double retryBudgetTokens = 20;
        public double retryBudgetRefillPerSec = 4;
        public int updateQueueCap = 1024;
        /** False until the adapter/native engine provides authoritative per-DC routing. */
        public boolean nativeDcRouting = false;

        private Config snapshot() {
            Config copy = new Config();
            copy.maxInFlight = maxInFlight;
            copy.perDcInFlight = perDcInFlight;
            copy.queueCap = queueCap;
            copy.dcCandidates = dcCandidates == null ? null : dcCandidates.clone();
            copy.maxRetries = maxRetries;
            copy.retryBaseMs = retryBaseMs;
            copy.retryCapMs = retryCapMs;
            copy.floodWaitCapMs = floodWaitCapMs;
            copy.retryBudgetTokens = retryBudgetTokens;
            copy.retryBudgetRefillPerSec = retryBudgetRefillPerSec;
            copy.updateQueueCap = updateQueueCap;
            copy.nativeDcRouting = nativeDcRouting;
            copy.validate();
            return copy;
        }

        private void validate() {
            if (maxInFlight < 1 || perDcInFlight < 1 || queueCap < 1 || updateQueueCap < 1)
                throw new IllegalArgumentException("queue and concurrency limits must be > 0");
            if (maxRetries < 0 || retryBaseMs < 0 || retryCapMs < 0
                    || floodWaitCapMs < 0 || retryCapMs < retryBaseMs)
                throw new IllegalArgumentException("invalid retry limits");
            if (!Double.isFinite(retryBudgetTokens) || retryBudgetTokens < 0
                    || !Double.isFinite(retryBudgetRefillPerSec) || retryBudgetRefillPerSec < 0)
                throw new IllegalArgumentException("invalid retry budget");
            if (dcCandidates == null || dcCandidates.length == 0)
                throw new IllegalArgumentException("dcCandidates must not be empty");
            for (int dc : dcCandidates) if (dc < 1) throw new IllegalArgumentException("DC ids must be > 0");
        }
    }

    public final DcSupervisor supervisor;
    public final CallScheduler scheduler;
    private final RequestPipeline pipeline;
    private final ScheduledExecutorService timers;
    private final Config cfg;
    private final CopyOnWriteArrayList<Consumer<Object>> updateListeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<Object> updateQueue = new ArrayDeque<>();
    private final Object updateLock = new Object();
    private final MtprotoPort port;
    private final Thread updateDispatcher;
    private final AtomicBoolean running = new AtomicBoolean(true);
    public final AtomicLong updatesDropped = new AtomicLong();

    public MvsNetCore(MtprotoPort port, Config config, Clock clock) {
        if (port == null || config == null || clock == null)
            throw new IllegalArgumentException("port, config and clock are required");
        this.port = port;
        this.cfg = config.snapshot();
        this.timers = Executors.newScheduledThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "mvs-net-timers");
            thread.setDaemon(true);
            return thread;
        });
        this.supervisor = new DcSupervisor(clock);
        this.scheduler = new CallScheduler(port, cfg.maxInFlight, cfg.perDcInFlight, cfg.queueCap, timers);
        List<RequestPipeline.Interceptor> chain = new ArrayList<>();
        chain.add(new RetryInterceptor(cfg.maxRetries, cfg.retryBaseMs, cfg.retryCapMs,
                cfg.floodWaitCapMs,
                new Backoff.Budget(cfg.retryBudgetTokens, cfg.retryBudgetRefillPerSec, Clock.MONOTONIC),
                timers));
        this.pipeline = new RequestPipeline(chain,
                request -> scheduler.submit(request.method, request.tl, request.opts));
        this.updateDispatcher = new Thread(this::dispatchUpdates, "mvs-updates");
        this.updateDispatcher.setDaemon(true);
        this.updateDispatcher.start();
        try {
            port.setUpdatesListener(this::enqueueUpdate);
        } catch (RuntimeException failure) {
            running.set(false);
            synchronized (updateLock) { updateLock.notifyAll(); }
            scheduler.shutdown();
            timers.shutdownNow();
            throw failure;
        }
    }

    public CompletableFuture<Object> send(String method, Object tl, CallScheduler.Options options) {
        if (!running.get()) return failed(new IllegalStateException("network core is shut down"));
        if (options == null) return failed(new IllegalArgumentException("options == null"));
        if (method == null || method.trim().isEmpty()) return failed(new IllegalArgumentException("method is empty"));
        if (supervisor.isOffline()) return failed(new IllegalStateException("offline (kill-switch)"));

        CallScheduler.Options requestOptions = options.copy();
        if (requestOptions.dc < 1) return failed(new IllegalArgumentException("dc must be > 0"));
        // Do not pretend to fail over when the native engine still chooses the physical DC.
        int selectedDc = cfg.nativeDcRouting
                ? supervisor.select(requestOptions.dc, cfg.dcCandidates)
                : requestOptions.dc;
        if (selectedDc < 0) return failed(new IllegalStateException("no healthy dc"));
        requestOptions.dc = selectedDc;

        int breakerId = cfg.nativeDcRouting ? selectedDc : 0;
        long probeToken = supervisor.breaker(breakerId).allow();
        if (probeToken < 0) return failed(new IllegalStateException("connection busy"));

        long startedNanos = System.nanoTime();
        final CompletableFuture<Object> future;
        try {
            future = pipeline.execute(new RequestPipeline.Request(method, tl, requestOptions));
        } catch (RuntimeException failure) {
            supervisor.onResult(breakerId, false, elapsedMs(startedNanos), probeToken);
            return failed(failure);
        }
        future.whenComplete((value, error) -> {
            Throwable cause = unwrap(error);
            if (cause instanceof CancellationException) {
                supervisor.onCancelled(breakerId, probeToken);
            } else {
                supervisor.onResult(breakerId, error == null, elapsedMs(startedNanos), probeToken);
            }
        });
        return future;
    }

    public void addUpdateListener(Consumer<Object> listener) {
        if (listener == null) throw new IllegalArgumentException("listener == null");
        synchronized (updateLock) {
            if (!running.get()) throw new IllegalStateException("network core is shut down");
            updateListeners.addIfAbsent(listener);
        }
    }

    public void removeUpdateListener(Consumer<Object> listener) {
        if (listener != null) updateListeners.remove(listener);
    }

    public void setOffline(boolean value) { supervisor.setOffline(value); }
    public boolean isRunning() { return running.get(); }

    /** Idempotent shutdown: stop ingress, cancel requests, drain queued updates, stop timers. */
    public void shutdown(long graceMs) {
        if (graceMs < 0) graceMs = 0;
        if (!running.compareAndSet(true, false)) return;
        try { port.setUpdatesListener(null); } catch (RuntimeException ignored) { }
        scheduler.shutdown();
        synchronized (updateLock) { updateLock.notifyAll(); }
        if (Thread.currentThread() != updateDispatcher) {
            try {
                updateDispatcher.join(graceMs);
                if (updateDispatcher.isAlive()) {
                    updateDispatcher.interrupt();
                    updateDispatcher.join(200);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        timers.shutdownNow();
    }

    private void enqueueUpdate(Object update) {
        if (update == null || !running.get()) return;
        synchronized (updateLock) {
            if (!running.get()) return;
            if (updateQueue.size() >= cfg.updateQueueCap) {
                updateQueue.removeFirst();
                updatesDropped.incrementAndGet();
            }
            updateQueue.addLast(update);
            updateLock.notifyAll();
        }
    }

    private void dispatchUpdates() {
        while (true) {
            Object update;
            synchronized (updateLock) {
                while (running.get() && updateQueue.isEmpty()) {
                    try { updateLock.wait(); }
                    catch (InterruptedException interrupted) {
                        if (!running.get()) break;
                    }
                }
                if (!running.get() && updateQueue.isEmpty()) return;
                update = updateQueue.pollFirst();
            }
            if (update == null) continue;
            for (Consumer<Object> listener : updateListeners) {
                try { listener.accept(update); }
                catch (ThreadDeath | VirtualMachineError fatal) { throw fatal; }
                catch (Throwable ignored) { /* A consumer must not kill the dispatcher. */ }
            }
        }
    }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        return error;
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }

    private static CompletableFuture<Object> failed(Exception error) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }
}
