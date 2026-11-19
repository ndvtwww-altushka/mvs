package app.mvs.core.net;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, priority-aware scheduler for requests sent through the MTProto port. */
public final class CallScheduler {
    public static final class Options {
        /** Preserve the host client's native/default DC selection unless a caller pins one. */
        public static final int AUTO_DC = Integer.MAX_VALUE;
        public int priority = 1;
        public long timeoutMs = 15_000;
        public String dedupKey;
        public int dc = AUTO_DC;

        public Options copy() {
            Options copy = new Options();
            copy.priority = priority;
            copy.timeoutMs = timeoutMs;
            copy.dedupKey = dedupKey;
            copy.dc = dc;
            return copy;
        }
    }

    public static final class OverflowException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public OverflowException(String message) { super(message); }
    }

    private static final class Call {
        final String method;
        final Object tl;
        final Options options;
        final CompletableFuture<Object> future = new CompletableFuture<>();
        final AtomicBoolean done = new AtomicBoolean();
        final AtomicBoolean handleCancellationSent = new AtomicBoolean();
        volatile boolean cancelRequested;
        volatile MtprotoPort.Handle handle;
        volatile ScheduledFuture<?> timer;
        boolean reserved; // guarded by CallScheduler.this
        boolean queued;   // guarded by CallScheduler.this

        Call(String method, Object tl, Options options) {
            this.method = method;
            this.tl = tl;
            this.options = options;
        }
    }

    private final MtprotoPort port;
    private final int maxInFlight, perDc, queueCap;
    private final ScheduledExecutorService timers;
    @SuppressWarnings({"unchecked", "rawtypes"})
    private final ArrayDeque<Call>[] buckets = new ArrayDeque[4];
    private final int[] weights = {8, 4, 2, 1};
    private final int[] deficit = new int[4];
    private int wrrIndex;
    private final Map<String, CompletableFuture<Object>> dedup = new HashMap<>();
    private final Map<Integer, Integer> inFlightDc = new HashMap<>();
    private final Set<Call> live = new HashSet<>();
    private int inFlight, queued;
    private boolean shutdown;
    private final ArrayDeque<Call> dispatchQueue = new ArrayDeque<>();
    private final AtomicBoolean draining = new AtomicBoolean();

    public final AtomicLong completed = new AtomicLong();
    public final AtomicLong failed = new AtomicLong();
    public final AtomicLong timedOut = new AtomicLong();
    public final AtomicLong rejected = new AtomicLong();
    public final AtomicLong cancelled = new AtomicLong();

    public CallScheduler(MtprotoPort port, int maxInFlight, int perDc, int queueCap,
                         ScheduledExecutorService timers) {
        if (port == null || timers == null) throw new IllegalArgumentException("port/timers == null");
        if (maxInFlight < 1 || perDc < 1 || queueCap < 1)
            throw new IllegalArgumentException("scheduler limits must be > 0");
        this.port = port;
        this.maxInFlight = maxInFlight;
        this.perDc = perDc;
        this.queueCap = queueCap;
        this.timers = timers;
        for (int i = 0; i < buckets.length; i++) buckets[i] = new ArrayDeque<>();
    }

    public CompletableFuture<Object> submit(String method, Object tl, Options options) {
        if (method == null || method.trim().isEmpty()) return failedFuture(new IllegalArgumentException("method is empty"));
        if (options == null) return failedFuture(new IllegalArgumentException("options == null"));
        Options snapshot = options.copy();
        if (snapshot.timeoutMs < 1) return failedFuture(new IllegalArgumentException("timeoutMs must be > 0"));
        if (snapshot.dc < 1) return failedFuture(new IllegalArgumentException("dc must be > 0"));

        Call call = new Call(method, tl, snapshot);
        List<Call> dispatch = new ArrayList<>();
        synchronized (this) {
            if (shutdown) return failNow(call, new IllegalStateException("scheduler shutdown"));
            if (snapshot.dedupKey != null) {
                CompletableFuture<Object> existing = dedup.get(snapshot.dedupKey);
                if (existing != null && !existing.isDone()) return existing;
            }
            if (queued >= queueCap) {
                rejected.incrementAndGet();
                return failNow(call, new OverflowException("queue full"));
            }

            live.add(call);
            if (snapshot.dedupKey != null) dedup.put(snapshot.dedupKey, call.future);
            int bucket = clamp(snapshot.priority);
            buckets[bucket].addLast(call);
            call.queued = true;
            queued++;
            call.future.whenComplete((value, error) -> {
                if (call.future.isCancelled()) onCancel(call);
            });

            try {
                call.timer = timers.schedule(() -> onTimeout(call), snapshot.timeoutMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException rejectedTimer) {
                removeQueuedLocked(call);
                live.remove(call);
                if (snapshot.dedupKey != null) dedup.remove(snapshot.dedupKey, call.future);
                call.done.set(true);
                failed.incrementAndGet();
                call.future.completeExceptionally(new IllegalStateException("scheduler timer is unavailable", rejectedTimer));
                return call.future;
            }
            pumpLocked(dispatch);
        }
        dispatchAll(dispatch);
        return call.future;
    }

    public void shutdown() {
        List<Call> snapshot;
        synchronized (this) {
            if (shutdown) return;
            shutdown = true;
            snapshot = new ArrayList<>(live);
            for (ArrayDeque<Call> bucket : buckets) bucket.clear();
            queued = 0;
            dedup.clear();
        }
        for (Call call : snapshot) {
            if (cleanup(call, true)) {
                failed.incrementAndGet();
                call.future.completeExceptionally(new IllegalStateException("scheduler shutdown"));
            }
        }
    }

    public synchronized int queuedCount() { return queued; }
    public synchronized int inFlightCount() { return inFlight; }

    private CompletableFuture<Object> failNow(Call call, Exception error) {
        call.future.completeExceptionally(error);
        return call.future;
    }

    private static CompletableFuture<Object> failedFuture(Exception error) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }

    private static int clamp(int priority) { return priority < 0 ? 0 : Math.min(priority, 3); }

    private void pumpLocked(List<Call> output) {
        if (shutdown) return;
        while (inFlight < maxInFlight) {
            Call call = pickLocked();
            if (call == null) break;
            call.queued = false;
            call.reserved = true;
            inFlight++;
            inFlightDc.merge(call.options.dc, 1, Integer::sum);
            output.add(call);
        }
    }

    private Call pickLocked() {
        if (queued == 0) return null;
        for (int spin = 0; spin < 8; spin++) {
            int bucketIndex = wrrIndex;
            if (deficit[bucketIndex] == 0) deficit[bucketIndex] = weights[bucketIndex];
            Iterator<Call> iterator = buckets[bucketIndex].iterator();
            while (iterator.hasNext()) {
                Call call = iterator.next();
                if (call.done.get()) {
                    iterator.remove();
                    call.queued = false;
                    queued--;
                    continue;
                }
                if (deficit[bucketIndex] > 0
                        && inFlightDc.getOrDefault(call.options.dc, 0) < perDc) {
                    iterator.remove();
                    call.queued = false;
                    queued--;
                    deficit[bucketIndex]--;
                    return call;
                }
            }
            deficit[bucketIndex] = 0;
            wrrIndex = (bucketIndex + 1) % buckets.length;
        }
        return null;
    }

    private void removeQueuedLocked(Call call) {
        if (!call.queued) return;
        if (buckets[clamp(call.options.priority)].remove(call)) queued--;
        call.queued = false;
    }

    /** Iterative dispatch trampoline: synchronous port callbacks cannot grow the Java stack. */
    private void dispatchAll(List<Call> calls) {
        if (!calls.isEmpty()) {
            synchronized (dispatchQueue) { dispatchQueue.addAll(calls); }
        }
        while (draining.compareAndSet(false, true)) {
            try {
                while (true) {
                    Call next;
                    synchronized (dispatchQueue) { next = dispatchQueue.pollFirst(); }
                    if (next == null) break;
                    dispatchOne(next);
                }
            } finally {
                draining.set(false);
            }
            synchronized (dispatchQueue) {
                if (dispatchQueue.isEmpty()) return;
            }
        }
    }

    private void dispatchOne(Call call) {
        if (call.done.get()) { releaseAndPump(call); return; }
        final MtprotoPort.Handle handle;
        try {
            handle = port.send(call.options.dc, call.method, call.tl, new MtprotoPort.Callback() {
                @Override public void onReply(Object value) {
                    if (cleanup(call, false)) {
                        completed.incrementAndGet();
                        call.future.complete(value); // null is a valid successful response
                    }
                }

                @Override public void onError(int code, String message, long retryAfterMs) {
                    if (cleanup(call, false)) {
                        failed.incrementAndGet();
                        call.future.completeExceptionally(new RpcException(code, message, retryAfterMs));
                    }
                }
            });
        } catch (Throwable failure) {
            if (failure instanceof ThreadDeath) throw (ThreadDeath) failure;
            if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
            if (cleanup(call, true)) {
                failed.incrementAndGet();
                call.future.completeExceptionally(failure instanceof Exception
                        ? (Exception) failure : new RuntimeException(failure));
            }
            return;
        }

        call.handle = handle;
        // Timeout/cancel may win while port.send() is still creating the native handle.
        if (call.cancelRequested && handle != null) cancelHandleOnce(call, handle);
    }

    private void onTimeout(Call call) {
        if (cleanup(call, true)) {
            timedOut.incrementAndGet();
            call.future.completeExceptionally(new TimeoutException("deadline " + call.options.timeoutMs + "ms"));
        }
    }

    private void onCancel(Call call) {
        if (cleanup(call, true)) cancelled.incrementAndGet();
    }

    private boolean cleanup(Call call, boolean cancelHandle) {
        if (!call.done.compareAndSet(false, true)) return false;
        ScheduledFuture<?> timer = call.timer;
        if (timer != null) timer.cancel(false);

        MtprotoPort.Handle handle = null;
        if (cancelHandle) {
            call.cancelRequested = true;
            handle = call.handle;
        }
        if (handle != null) cancelHandleOnce(call, handle);
        releaseAndPump(call);
        return true;
    }

    private void releaseAndPump(Call call) {
        List<Call> dispatch = new ArrayList<>();
        synchronized (this) {
            removeQueuedLocked(call);
            if (call.reserved) {
                call.reserved = false;
                inFlight--;
                int remaining = inFlightDc.getOrDefault(call.options.dc, 1) - 1;
                if (remaining <= 0) inFlightDc.remove(call.options.dc);
                else inFlightDc.put(call.options.dc, remaining);
            }
            live.remove(call);
            if (call.options.dedupKey != null) dedup.remove(call.options.dedupKey, call.future);
            pumpLocked(dispatch);
        }
        dispatchAll(dispatch);
    }

    private static void cancelHandleOnce(Call call, MtprotoPort.Handle handle) {
        if (!call.handleCancellationSent.compareAndSet(false, true)) return;
        try { handle.cancel(); } catch (RuntimeException ignored) { }
    }
}
