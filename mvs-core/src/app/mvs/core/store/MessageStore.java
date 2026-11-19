package app.mvs.core.store;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiPredicate;

public final class MessageStore {
    private static final int PUT = 1, DEL = 2;
    public static final class Entry {
        public final long dialog; public final int msg; public final byte[] data;
        Entry(long d, int m, byte[] b) { dialog = d; msg = m; data = b; }
    }
    private static final class Key {
        final long d; final int m;
        Key(long d, int m) { this.d = d; this.m = m; }
        public boolean equals(Object o) { return o instanceof Key && ((Key)o).d == d && ((Key)o).m == m; }
        public int hashCode() { return (int)(d * 31 + m); }
    }
    private static final class Op {
        final boolean del; final byte[] payload; final CompletableFuture<Void> fut = new CompletableFuture<>();
        Op(boolean del, byte[] p) { this.del = del; payload = p; }
    }
    private final SegmentLog log; private final CipherPort cipher;
    private final Map<Long, TreeMap<Integer, byte[]>> index = new HashMap<>();
    private final LinkedHashMap<Key, Op> pending = new LinkedHashMap<>();
    private final Object lock = new Object();
    private final ThreadPoolExecutor writer;
    private final ScheduledExecutorService timers;
    private final int batchMax = 64; private final long batchDelayMs = 5;
    private volatile boolean drainScheduled, closed;

    public MessageStore(File dir, CipherPort cipher, ScheduledExecutorService timers) throws Exception {
        dir.mkdirs();
        this.cipher = cipher; this.timers = timers;
        this.log = new SegmentLog(new File(dir, "messages.log"));
        this.writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4096), r -> { Thread t = new Thread(r, "mvs-store-writer"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.AbortPolicy());
        log.scan((type, data) -> {
            ByteBuffer b = ByteBuffer.wrap(data);
            long d = b.getLong(); int m = b.getInt();
            if (type == PUT) {
                byte[] sealed = new byte[b.remaining()]; b.get(sealed);
                index.computeIfAbsent(d, k -> new TreeMap<>()).put(m, cipher.open(sealed));
            } else if (type == DEL) {
                TreeMap<Integer, byte[]> tm = index.get(d);
                if (tm != null) tm.remove(m);
            }
        });
    }

    public CompletableFuture<Void> put(long dialog, int msg, byte[] payload) { return enqueue(dialog, msg, new Op(false, payload.clone())); }
    public CompletableFuture<Void> delete(long dialog, int msg) { return enqueue(dialog, msg, new Op(true, null)); }

    private CompletableFuture<Void> enqueue(long d, int m, Op op) {
        if (closed) { op.fut.completeExceptionally(new IOException("store closed")); return op.fut; }
        boolean immediate;
        synchronized (lock) {
            if (pending.size() >= 8192) { op.fut.completeExceptionally(new IOException("store backpressure")); return op.fut; }
            Op prev = pending.put(new Key(d, m), op);
            if (prev != null)   // 1a: прежний Future завершается вместе с новым (last-writer-wins)
                op.fut.whenComplete((r, e) -> { if (e == null) prev.fut.complete(null); else prev.fut.completeExceptionally(e); });
            immediate = pending.size() >= batchMax;
            if (!drainScheduled && !immediate) {
                drainScheduled = true;
                timers.schedule(() -> submitDrain(), batchDelayMs, TimeUnit.MILLISECONDS);
            }
        }
        if (immediate) submitDrain();
        return op.fut;
    }

    private void submitDrain() {
        try { writer.submit(this::drain); }
        catch (RejectedExecutionException e) { failPending(e); }
    }

    private void drain() {
        LinkedHashMap<Key, Op> batch;
        synchronized (lock) {
            drainScheduled = false;
            if (pending.isEmpty()) return;
            batch = new LinkedHashMap<>(pending);
            pending.clear();
        }
        List<SegmentLog.Rec> recs = new ArrayList<>(batch.size());
        List<Map.Entry<Key, Op>> applied = new ArrayList<>(batch.size());
        for (Map.Entry<Key, Op> e : batch.entrySet()) {
            try {
                Key k = e.getKey(); Op op = e.getValue();
                ByteBuffer hdr = ByteBuffer.allocate(12).putLong(k.d).putInt(k.m);
                byte[] data;
                if (op.del) data = hdr.array();
                else {
                    byte[] sealed = cipher.seal(op.payload);
                    data = ByteBuffer.allocate(12 + sealed.length).put(hdr.array()).put(sealed).array();
                }
                recs.add(new SegmentLog.Rec(op.del ? DEL : PUT, data));
                applied.add(e);
            } catch (GeneralSecurityException ex) { e.getValue().fut.completeExceptionally(ex); }
        }
        try {
            log.append(recs, true);
        } catch (IOException ex) {
            for (Map.Entry<Key, Op> e : applied) e.getValue().fut.completeExceptionally(ex);
            return;
        }
        for (Map.Entry<Key, Op> e : applied) {
            Key k = e.getKey(); Op op = e.getValue();
            if (op.del) {
                TreeMap<Integer, byte[]> tm = index.get(k.d);
                if (tm != null) tm.remove(k.m);
            } else {
                index.computeIfAbsent(k.d, x -> new TreeMap<>()).put(k.m, op.payload);
            }
            op.fut.complete(null);
        }
    }

    public CompletableFuture<List<Entry>> page(long dialog, int beforeMsg, int limit) {
        return read(() -> {
            TreeMap<Integer, byte[]> tm = index.get(dialog);
            List<Entry> out = new ArrayList<>();
            if (tm == null) return out;
            for (Map.Entry<Integer, byte[]> e : tm.headMap(beforeMsg, false).descendingMap().entrySet()) {
                if (out.size() >= limit) break;
                out.add(new Entry(dialog, e.getKey(), e.getValue().clone()));
            }
            return out;
        });
    }

    public CompletableFuture<byte[]> get(long dialog, int msg) {
        return read(() -> {
            TreeMap<Integer, byte[]> tm = index.get(dialog);
            byte[] b = tm == null ? null : tm.get(msg);
            return b == null ? null : b.clone();
        });
    }

    public CompletableFuture<Void> compact(BiPredicate<Long, Integer> keep) {
        CompletableFuture<Void> out = new CompletableFuture<>();
        try {
            writer.submit(() -> {
                try {
                    drain();                                   // 1c: pending применяем до снапшота
                    List<SegmentLog.Rec> live = new ArrayList<>();
                    List<Key> drop = new ArrayList<>();
                    for (Map.Entry<Long, TreeMap<Integer, byte[]>> de : index.entrySet())
                        for (Map.Entry<Integer, byte[]> me : de.getValue().entrySet()) {
                            if (!keep.test(de.getKey(), me.getKey())) { drop.add(new Key(de.getKey(), me.getKey())); continue; }
                            byte[] sealed = cipher.seal(me.getValue());
                            byte[] data = ByteBuffer.allocate(12 + sealed.length)
                                    .putLong(de.getKey()).putInt(me.getKey()).put(sealed).array();
                            live.add(new SegmentLog.Rec(PUT, data));
                        }
                    log.rewrite(live);                         // 1b: может бросить - индекс ещё не тронут
                    for (Key k : drop) {                       // мутируем индекс только после успеха
                        TreeMap<Integer, byte[]> tm = index.get(k.d);
                        if (tm != null) { tm.remove(k.m); if (tm.isEmpty()) index.remove(k.d); }
                    }
                    out.complete(null);
                } catch (Exception e) { out.completeExceptionally(e); }
            });
        } catch (RejectedExecutionException e) { out.completeExceptionally(e); }
        return out;
    }

    public CompletableFuture<Void> flush() {
        CompletableFuture<Void> out = new CompletableFuture<>();
        submitDrain();
        try { writer.submit(() -> out.complete(null)); }
        catch (RejectedExecutionException e) { out.completeExceptionally(e); }
        return out;
    }

    public void close() throws Exception {                    // 1d: идемпотентно, всегда освобождаем ресурсы
        synchronized (this) { if (closed) return; closed = true; }
        Exception flushErr = null;
        try { flush().get(5, TimeUnit.SECONDS); } catch (Exception e) { flushErr = e; }
        writer.shutdown();
        if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
            writer.shutdownNow();
            writer.awaitTermination(5, TimeUnit.SECONDS);
        }
        failPending(new IOException("store closed"));
        log.close();
        if (flushErr != null) throw flushErr;
    }

    private <T> CompletableFuture<T> read(Callable<T> task) {
        CompletableFuture<T> out = new CompletableFuture<>();
        submitDrain();
        try { writer.submit(() -> { try { out.complete(task.call()); } catch (Exception e) { out.completeExceptionally(e); } }); }
        catch (RejectedExecutionException e) { out.completeExceptionally(e); }
        return out;
    }

    private void failPending(Exception e) {
        synchronized (lock) { drainScheduled = false; for (Op op : pending.values()) op.fut.completeExceptionally(e); pending.clear(); }
    }
}
