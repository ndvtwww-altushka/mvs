package app.mvs.core.net;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class CallScheduler {
    public static final class Options {
        public int priority = 1; public long timeoutMs = 15000; public String dedupKey; public int dc = 1;
        public Options copy() {
            Options o = new Options();
            o.priority = priority; o.timeoutMs = timeoutMs; o.dedupKey = dedupKey; o.dc = dc;
            return o;
        }
    }
    public static final class OverflowException extends RuntimeException { public OverflowException(String m){super(m);} }
    private static final class Call {
        final String method; final Object tl; final Options o;
        final CompletableFuture<Object> fut = new CompletableFuture<>();
        final AtomicBoolean done = new AtomicBoolean();
        final AtomicBoolean cancelRequested = new AtomicBoolean();
        volatile MtprotoPort.Handle handle; volatile ScheduledFuture<?> timer; boolean reserved;
        Call(String m, Object t, Options o) { method = m; tl = t; this.o = o; }
    }
    private final MtprotoPort port; private final int maxInFlight, perDc, queueCap;
    private final ScheduledExecutorService timers;
    @SuppressWarnings("unchecked")
    private final ArrayDeque<Call>[] buckets = new ArrayDeque[4];
    private final int[] weights = {8,4,2,1}, deficit = new int[4];
    private int wrrIndex;
    private final Map<String, CompletableFuture<Object>> dedup = new HashMap<>();
    private final Map<Integer, Integer> inFlightDc = new HashMap<>();
    private final Set<Call> live = new HashSet<>();
    private int inFlight, queued; private boolean shutdown;
    private final ArrayDeque<Call> dispatchQ = new ArrayDeque<>();
    private final AtomicBoolean draining = new AtomicBoolean();
    public final AtomicLong completed = new AtomicLong(), failed = new AtomicLong(),
            timedOut = new AtomicLong(), rejected = new AtomicLong(), cancelled = new AtomicLong();

    public CallScheduler(MtprotoPort port, int maxInFlight, int perDc, int queueCap, ScheduledExecutorService timers) {
        this.port = port; this.maxInFlight = maxInFlight; this.perDc = perDc; this.queueCap = queueCap; this.timers = timers;
        for (int i = 0; i < 4; i++) buckets[i] = new ArrayDeque<>();
    }

    public CompletableFuture<Object> submit(String method, Object tl, Options o) {
        Call c = new Call(method, tl, o.copy());
        List<Call> dispatch = new ArrayList<>();
        synchronized (this) {
            if (shutdown) return failNow(c, new IllegalStateException("scheduler shutdown"));
            if (o.dedupKey != null) {
                CompletableFuture<Object> ex = dedup.get(o.dedupKey);
                if (ex != null && !ex.isDone()) return ex;
            }
            if (queued >= queueCap) { rejected.incrementAndGet(); return failNow(c, new OverflowException("queue full")); }
            if (o.dedupKey != null) dedup.put(o.dedupKey, c.fut);
            live.add(c);
            buckets[clamp(o.priority)].addLast(c); queued++;
            c.timer = timers.schedule(() -> onTimeout(c), c.o.timeoutMs, TimeUnit.MILLISECONDS); // 3b: с момента создания
            c.fut.whenComplete((r, e) -> { if (c.fut.isCancelled()) onCancel(c); });
            pumpLocked(dispatch);
        }
        dispatchAll(dispatch);
        return c.fut;
    }

    public void shutdown() {
        List<Call> snapshot;
        synchronized (this) {
            if (shutdown) return;
            shutdown = true;
            snapshot = new ArrayList<>(live);
            for (ArrayDeque<Call> b : buckets) b.clear();
            queued = 0; dedup.clear();
        }
        for (Call c : snapshot)                                   // 3e: и queued, и in-flight
            if (cleanup(c, true)) { failed.incrementAndGet(); c.fut.completeExceptionally(new IllegalStateException("scheduler shutdown")); }
    }

    private CompletableFuture<Object> failNow(Call c, Exception e) { c.fut.completeExceptionally(e); return c.fut; }
    private static int clamp(int p) { return p < 0 ? 0 : Math.min(p, 3); }

    private void pumpLocked(List<Call> out) {
        while (inFlight < maxInFlight) {
            Call c = pickLocked();
            if (c == null) break;
            inFlight++; c.reserved = true;
            inFlightDc.merge(c.o.dc, 1, Integer::sum);
            out.add(c);
        }
    }

    private Call pickLocked() {
        if (queued == 0) return null;
        for (int spin = 0; spin < 8; spin++) {
            int i = wrrIndex;
            if (deficit[i] == 0) deficit[i] = weights[i];
            Iterator<Call> it = buckets[i].iterator();
            while (it.hasNext()) {
                Call c = it.next();
                if (c.done.get()) { it.remove(); queued--; continue; }
                if (deficit[i] > 0 && inFlightDc.getOrDefault(c.o.dc, 0) < perDc) {
                    it.remove(); queued--; deficit[i]--;
                    return c;
                }
            }
            deficit[i] = 0; wrrIndex = (i + 1) % 4;
        }
        return null;
    }

    /** 3d: итеративный трамплин - синхронные callback не растят стек. */
    private void dispatchAll(List<Call> list) {
        if (!list.isEmpty()) synchronized (dispatchQ) { dispatchQ.addAll(list); }
        while (draining.compareAndSet(false, true)) {
            try {
                while (true) {
                    Call c;
                    synchronized (dispatchQ) { c = dispatchQ.pollFirst(); }
                    if (c == null) break;
                    dispatchOne(c);
                }
            } finally { draining.set(false); }
            synchronized (dispatchQ) { if (dispatchQ.isEmpty()) return; }
        }
    }

    private void dispatchOne(Call c) {
        if (c.done.get()) { releaseAndPump(c); return; }
        MtprotoPort.Handle h;
        try {
            h = port.send(c.o.dc, c.method, c.tl, new MtprotoPort.Callback() {
                public void onReply(Object tl) { if (cleanup(c, false)) { completed.incrementAndGet(); c.fut.complete(tl); } } // 3a: null тоже успех
                public void onError(int code, String msg, long retryAfterMs) {
                    if (cleanup(c, false)) { failed.incrementAndGet(); c.fut.completeExceptionally(new RpcException(code, msg, retryAfterMs)); }
                }
            });
        } catch (Throwable t) {
            if (cleanup(c, true)) { failed.incrementAndGet(); c.fut.completeExceptionally(t instanceof Exception ? (Exception)t : new RuntimeException(t)); }
            return;
        }
        c.handle = h;
        if (c.cancelRequested.get() && h != null) try { h.cancel(); } catch (Throwable ignored) {} // 3c: гонка закрыта
    }

    private void onTimeout(Call c) { if (cleanup(c, true)) { timedOut.incrementAndGet(); c.fut.completeExceptionally(new TimeoutException("deadline " + c.o.timeoutMs + "ms")); } }
    private void onCancel(Call c) { if (cleanup(c, true)) cancelled.incrementAndGet(); }

    private boolean cleanup(Call c, boolean cancelHandle) {
        if (!c.done.compareAndSet(false, true)) return false;
        ScheduledFuture<?> t = c.timer; if (t != null) t.cancel(false);
        if (cancelHandle) {
            c.cancelRequested.set(true);
            MtprotoPort.Handle h = c.handle;
            if (h != null) try { h.cancel(); } catch (Throwable ignored) {}
        }
        releaseAndPump(c);
        return true;
    }

    private void releaseAndPump(Call c) {
        List<Call> dispatch = new ArrayList<>();
        synchronized (this) {
            if (c.reserved) { c.reserved = false; inFlight--; inFlightDc.merge(c.o.dc, -1, Integer::sum); }
            live.remove(c);
            if (c.o.dedupKey != null) dedup.remove(c.o.dedupKey, c.fut);
            pumpLocked(dispatch);
        }
        dispatchAll(dispatch);
    }
}
