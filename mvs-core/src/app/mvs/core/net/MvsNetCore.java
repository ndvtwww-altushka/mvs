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
import java.util.function.Supplier;

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
        /**
         * How many data centres one {@code sendWithFailover} call may touch, counting the
         * first attempt. The preferred (home) data centre is always first.
         */
        public int maxFailoverAttempts = 3;
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
            copy.maxFailoverAttempts = maxFailoverAttempts;
            copy.nativeDcRouting = nativeDcRouting;
            copy.validate();
            return copy;
        }

        private void validate() {
            if (maxInFlight < 1 || perDcInFlight < 1 || queueCap < 1 || updateQueueCap < 1)
                throw new IllegalArgumentException("queue and concurrency limits must be > 0");
            if (maxFailoverAttempts < 1)
                throw new IllegalArgumentException("maxFailoverAttempts must be > 0");
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

    /**
     * Failover entry point: tries at most {@link Config#maxFailoverAttempts} data centres in
     * order and asks {@code factory} for a brand new payload on every attempt.
     *
     * <p>A payload is not reusable across attempts: the native engine frees a reply when its
     * callback returns, and a request object that was already submitted must not be replayed.
     * The Android host therefore runs this core with {@code maxRetries = 0} and lets this
     * method own the only repeat.</p>
     *
     * <p>Only transport-shaped failures move on: a timeout, {@code IOException}, or an
     * {@link RpcException} that is not authoritative (negative codes, {@code 0}, 5xx).
     * 4xx answers, flood waits and {@code 303 MIGRATE_*} stay final, because switching the
     * data centre cannot fix a rejected request and migration belongs to the native engine.</p>
     */
    public CompletableFuture<Object> sendWithFailover(String method, Supplier<Object> factory,
                                                      CallScheduler.Options options) {
        if (!running.get()) return failed(new IllegalStateException("network core is shut down"));
        if (factory == null) return failed(new IllegalArgumentException("factory == null"));
        if (options == null) return failed(new IllegalArgumentException("options == null"));
        if (method == null || method.trim().isEmpty()) return failed(new IllegalArgumentException("method is empty"));
        if (options.timeoutMs < 1) return failed(new IllegalArgumentException("timeoutMs must be > 0"));
        if (supervisor.isOffline()) return failed(new IllegalStateException("offline (kill-switch)"));

        int[] order = failoverOrder(options.dc);
        if (order.length == 0) return failed(new IllegalStateException("no healthy dc"));
        CompletableFuture<Object> result = new CompletableFuture<>();
        failoverAttempt(method, factory, options, order, 0, result);
        return result;
    }

    /** Ordered data centres for one failover request; empty when every breaker is open. */
    private int[] failoverOrder(int preferred) {
        int limit = Math.min(Math.max(1, cfg.maxFailoverAttempts), cfg.dcCandidates.length + 1);
        List<Integer> order = new ArrayList<>();
        boolean pinned = preferred >= 1 && preferred != CallScheduler.AUTO_DC;
        if (pinned && supervisor.breaker(preferred).peek()) order.add(preferred);
        int[] remaining = cfg.dcCandidates.clone();
        while (order.size() < limit && remaining.length > 0) {
            int next = supervisor.select(0, remaining);
            if (next < 0) break;
            if (!order.contains(next)) order.add(next);
            remaining = without(remaining, next);
        }
        int[] result = new int[order.size()];
        for (int i = 0; i < result.length; i++) result[i] = order.get(i);
        return result;
    }

    private static int[] without(int[] values, int skip) {
        int[] kept = new int[values.length];
        int size = 0;
        for (int value : values) if (value != skip) kept[size++] = value;
        int[] result = new int[size];
        System.arraycopy(kept, 0, result, 0, size);
        return result;
    }

    private void failoverAttempt(String method, Supplier<Object> factory,
                                 CallScheduler.Options base, int[] order, int index,
                                 CompletableFuture<Object> sink) {
        if (sink.isDone()) return;
        if (index >= order.length) {
            sink.completeExceptionally(new IllegalStateException("no dc accepted the request"));
            return;
        }
        final int dc = order[index];
        final Object payload;
        try {
            payload = factory.get();
        } catch (RuntimeException failure) {
            sink.completeExceptionally(failure);
            return;
        }
        if (payload == null) {
            sink.completeExceptionally(new IllegalArgumentException("factory returned null"));
            return;
        }
        final long probe = supervisor.breaker(dc).allow();
        if (probe < 0) { // breaker rejected this data centre: try the next one
            failoverAttempt(method, factory, base, order, index + 1, sink);
            return;
        }

        CallScheduler.Options attempt = base.copy();
        attempt.dc = dc;
        final long startedNanos = System.nanoTime();
        send(method, payload, attempt).whenComplete((value, error) -> {
            if (sink.isDone()) {
                supervisor.onCancelled(dc, probe);
                return;
            }
            Throwable cause = unwrap(error);
            if (cause == null) {
                supervisor.onResult(dc, true, elapsedMs(startedNanos), probe);
                sink.complete(value);
                return;
            }
            supervisor.onResult(dc, false, elapsedMs(startedNanos), probe);
            if (cause instanceof CancellationException) {
                sink.cancel(false);
                return;
            }
            if (isFailoverCandidate(cause) && index + 1 < order.length) {
                failoverAttempt(method, factory, base, order, index + 1, sink);
                return;
            }
            sink.completeExceptionally(cause);
        });
    }

    private static boolean isFailoverCandidate(Throwable failure) {
        if (failure instanceof TimeoutException) return true;
        if (failure instanceof java.io.IOException) return true;
        if (failure instanceof RpcException) {
            RpcException rpc = (RpcException) failure;
            if (rpc.retryAfterMs > 0) return false;
            if (rpc.code == 303) return false;
            if (rpc.code >= 400 && rpc.code < 500) return false;
            return true;
        }
        return false;
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
