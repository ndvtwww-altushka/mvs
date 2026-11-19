package app.mvs.core.store;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiPredicate;

/** Single-writer encrypted message journal with bounded batching and read barriers. */
public final class MessageStore {
    private static final int PUT = 1;
    private static final int DEL = 2;
    private static final int BATCH_MAX = 64;
    private static final int PENDING_MAX = 8192;
    private static final long BATCH_DELAY_MS = 5;

    public static final class Entry {
        public final long dialog;
        public final int msg;
        public final byte[] data;
        Entry(long dialog, int message, byte[] data) {
            this.dialog = dialog;
            this.msg = message;
            this.data = data;
        }
    }

    private static final class Key {
        final long dialog;
        final int message;
        Key(long dialog, int message) { this.dialog = dialog; this.message = message; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return key.dialog == dialog && key.message == message;
        }
        @Override public int hashCode() { return (int) (dialog ^ (dialog >>> 32)) * 31 + message; }
    }

    private static final class Op {
        final boolean delete;
        final byte[] payload;
        final CompletableFuture<Void> future = new CompletableFuture<>();
        Op(boolean delete, byte[] payload) { this.delete = delete; this.payload = payload; }
    }

    private final SegmentLog log;
    private final CipherPort cipher;
    private final Map<Long, TreeMap<Integer, byte[]>> index = new HashMap<>();
    private final LinkedHashMap<Key, Op> pending = new LinkedHashMap<>();
    private final Object lock = new Object();
    private final ThreadPoolExecutor writer;
    private final ScheduledExecutorService timers;
    private ScheduledFuture<?> drainTask;
    private boolean drainScheduled;
    private volatile boolean closed;

    public MessageStore(File directory, CipherPort cipher, ScheduledExecutorService timers) throws Exception {
        if (directory == null || cipher == null || timers == null)
            throw new IllegalArgumentException("directory, cipher and timers are required");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
            throw new IOException("cannot create message store directory: " + directory);
        this.cipher = cipher;
        this.timers = timers;
        this.log = new SegmentLog(new File(directory, "messages.log"));
        this.writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4096), runnable -> {
                    Thread thread = new Thread(runnable, "mvs-store-writer");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        try {
            log.scan((type, data) -> {
                if (data == null || data.length < 12) throw new IOException("short message journal record");
                ByteBuffer input = ByteBuffer.wrap(data);
                long dialog = input.getLong();
                int message = input.getInt();
                if (type == PUT) {
                    if (input.remaining() < 28) throw new IOException("short encrypted message record");
                    byte[] sealed = new byte[input.remaining()];
                    input.get(sealed);
                    index.computeIfAbsent(dialog, ignored -> new TreeMap<>()).put(message, cipher.open(sealed));
                } else if (type == DEL) {
                    if (input.hasRemaining()) throw new IOException("invalid delete record");
                    TreeMap<Integer, byte[]> messages = index.get(dialog);
                    if (messages != null) messages.remove(message);
                } else {
                    throw new IOException("unknown message journal record type: " + type);
                }
            });
        } catch (Exception recoveryFailure) {
            try { log.close(); } catch (IOException ignored) { }
            writer.shutdownNow();
            throw recoveryFailure;
        }
    }

    public CompletableFuture<Void> put(long dialog, int message, byte[] payload) {
        if (payload == null) return failedFuture(new IllegalArgumentException("payload == null"));
        return enqueue(dialog, message, new Op(false, payload.clone()));
    }

    public CompletableFuture<Void> delete(long dialog, int message) {
        return enqueue(dialog, message, new Op(true, null));
    }

    private CompletableFuture<Void> enqueue(long dialog, int message, Op operation) {
        boolean immediate;
        RuntimeException schedulingFailure = null;
        synchronized (lock) {
            if (closed) return fail(operation, new IOException("store closed"));
            Key key = new Key(dialog, message);
            if (pending.size() >= PENDING_MAX && !pending.containsKey(key))
                return fail(operation, new IOException("store backpressure"));
            Op previous = pending.put(key, operation);
            if (previous != null) {
                // Last-writer-wins, but every caller observes the result of the winning write.
                operation.future.whenComplete((value, error) -> {
                    if (error == null) previous.future.complete(null);
                    else previous.future.completeExceptionally(unwrap(error));
                });
            }
            immediate = pending.size() >= BATCH_MAX;
            if (!drainScheduled && !immediate) {
                drainScheduled = true;
                try {
                    drainTask = timers.schedule(this::runScheduledDrain, BATCH_DELAY_MS, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException rejected) {
                    drainScheduled = false;
                    drainTask = null;
                    schedulingFailure = rejected;
                }
            }
        }
        if (schedulingFailure != null) failPending(schedulingFailure);
        else if (immediate) submitDrain();
        return operation.future;
    }

    private void runScheduledDrain() {
        synchronized (lock) {
            drainScheduled = false;
            drainTask = null;
            if (closed) return;
        }
        submitDrain();
    }

    private void submitDrain() {
        ScheduledFuture<?> scheduled;
        synchronized (lock) {
            scheduled = drainTask;
            drainTask = null;
            drainScheduled = false;
        }
        if (scheduled != null) scheduled.cancel(false);
        try {
            writer.execute(this::drain);
        } catch (RejectedExecutionException rejected) {
            failPending(rejected);
        }
    }

    /** Runs only on the single writer thread. Returns a journal failure, if any. */
    private Exception drain() {
        LinkedHashMap<Key, Op> batch;
        synchronized (lock) {
            if (pending.isEmpty()) return null;
            batch = new LinkedHashMap<>(pending);
            pending.clear();
        }

        List<SegmentLog.Rec> records = new ArrayList<>(batch.size());
        List<Map.Entry<Key, Op>> applied = new ArrayList<>(batch.size());
        for (Map.Entry<Key, Op> entry : batch.entrySet()) {
            try {
                Key key = entry.getKey();
                Op operation = entry.getValue();
                ByteBuffer header = ByteBuffer.allocate(12).putLong(key.dialog).putInt(key.message);
                byte[] data;
                if (operation.delete) {
                    data = header.array();
                } else {
                    byte[] encrypted = cipher.seal(operation.payload);
                    data = ByteBuffer.allocate(12 + encrypted.length).put(header.array()).put(encrypted).array();
                }
                records.add(new SegmentLog.Rec(operation.delete ? DEL : PUT, data));
                applied.add(entry);
            } catch (GeneralSecurityException failure) {
                entry.getValue().future.completeExceptionally(failure);
            } catch (RuntimeException failure) {
                entry.getValue().future.completeExceptionally(failure);
            }
        }

        try {
            log.append(records, true);
        } catch (IOException failure) {
            for (Map.Entry<Key, Op> entry : applied) entry.getValue().future.completeExceptionally(failure);
            return failure;
        }

        for (Map.Entry<Key, Op> entry : applied) {
            Key key = entry.getKey();
            Op operation = entry.getValue();
            if (operation.delete) {
                TreeMap<Integer, byte[]> messages = index.get(key.dialog);
                if (messages != null) {
                    messages.remove(key.message);
                    if (messages.isEmpty()) index.remove(key.dialog);
                }
            } else {
                index.computeIfAbsent(key.dialog, ignored -> new TreeMap<>())
                        .put(key.message, operation.payload);
            }
            operation.future.complete(null);
        }
        return null;
    }

    public CompletableFuture<List<Entry>> page(long dialog, int beforeMessage, int limit) {
        if (limit < 0) return failedFuture(new IllegalArgumentException("limit must be >= 0"));
        return read(() -> {
            TreeMap<Integer, byte[]> messages = index.get(dialog);
            List<Entry> output = new ArrayList<>();
            if (messages == null || limit == 0) return output;
            for (Map.Entry<Integer, byte[]> entry : messages.headMap(beforeMessage, false).descendingMap().entrySet()) {
                if (output.size() >= limit) break;
                output.add(new Entry(dialog, entry.getKey(), entry.getValue().clone()));
            }
            return output;
        });
    }

    public CompletableFuture<byte[]> get(long dialog, int message) {
        return read(() -> {
            TreeMap<Integer, byte[]> messages = index.get(dialog);
            byte[] data = messages == null ? null : messages.get(message);
            return data == null ? null : data.clone();
        });
    }

    public CompletableFuture<Void> compact(BiPredicate<Long, Integer> keep) {
        if (keep == null) return failedFuture(new IllegalArgumentException("keep == null"));
        CompletableFuture<Void> output = new CompletableFuture<>();
        synchronized (lock) {
            if (closed) return failedFuture(new IOException("store closed"));
            try {
                // Queue insertion is serialized with close(): compaction can never run after log.close().
                writer.execute(() -> {
                    try {
                        Exception drainFailure = drain();
                        if (drainFailure != null) throw drainFailure;
                        List<SegmentLog.Rec> live = new ArrayList<>();
                        List<Key> drop = new ArrayList<>();
                        for (Map.Entry<Long, TreeMap<Integer, byte[]>> dialog : index.entrySet()) {
                            for (Map.Entry<Integer, byte[]> message : dialog.getValue().entrySet()) {
                                if (!keep.test(dialog.getKey(), message.getKey())) {
                                    drop.add(new Key(dialog.getKey(), message.getKey()));
                                    continue;
                                }
                                byte[] encrypted = cipher.seal(message.getValue());
                                byte[] data = ByteBuffer.allocate(12 + encrypted.length)
                                        .putLong(dialog.getKey()).putInt(message.getKey()).put(encrypted).array();
                                live.add(new SegmentLog.Rec(PUT, data));
                            }
                        }
                        log.rewrite(live);
                        for (Key key : drop) {
                            TreeMap<Integer, byte[]> messages = index.get(key.dialog);
                            if (messages != null) {
                                messages.remove(key.message);
                                if (messages.isEmpty()) index.remove(key.dialog);
                            }
                        }
                        output.complete(null);
                    } catch (Exception failure) {
                        output.completeExceptionally(failure);
                    }
                });
            } catch (RejectedExecutionException rejected) {
                output.completeExceptionally(rejected);
            }
        }
        return output;
    }

    /** A barrier: all operations accepted before this call are journaled before completion. */
    public CompletableFuture<Void> flush() {
        CompletableFuture<Void> output = new CompletableFuture<>();
        synchronized (lock) {
            if (closed) return failedFuture(new IOException("store closed"));
            try {
                writer.execute(() -> {
                    Exception failure = drain();
                    if (failure == null) output.complete(null);
                    else output.completeExceptionally(failure);
                });
            } catch (RejectedExecutionException rejected) {
                output.completeExceptionally(rejected);
            }
        }
        return output;
    }

    /** Stop accepting writes, flush once, stop the writer, then close the journal. */
    public synchronized void close() throws Exception {
        ScheduledFuture<?> scheduled;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            scheduled = drainTask;
            drainTask = null;
            drainScheduled = false;
        }
        if (scheduled != null) scheduled.cancel(false);

        Exception closeFailure = null;
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        try {
            writer.execute(() -> {
                Exception failure = drain();
                if (failure == null) barrier.complete(null);
                else barrier.completeExceptionally(failure);
            });
            barrier.get(10, TimeUnit.SECONDS);
        } catch (RejectedExecutionException rejected) {
            failPending(rejected);
            closeFailure = rejected;
        } catch (TimeoutException timeout) {
            writer.shutdownNow();
            if (!writer.awaitTermination(2, TimeUnit.SECONDS))
                throw new IOException("message-store writer did not stop; journal left open to avoid concurrent close", timeout);
            failPending(timeout);
            log.close();
            throw timeout;
        } catch (ExecutionException failure) {
            Throwable cause = unwrap(failure);
            closeFailure = cause instanceof Exception ? (Exception) cause : new IOException("store flush failed", cause);
        }

        writer.shutdown();
        if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
            writer.shutdownNow();
            if (!writer.awaitTermination(2, TimeUnit.SECONDS))
                throw new IOException("message-store writer did not stop; journal left open");
        }
        failPending(new IOException("store closed"));
        log.close();
        if (closeFailure != null) throw closeFailure;
    }

    private <T> CompletableFuture<T> read(Callable<T> task) {
        CompletableFuture<T> output = new CompletableFuture<>();
        synchronized (lock) {
            if (closed) return failedFuture(new IOException("store closed"));
            try {
                writer.execute(() -> {
                    Exception failure = drain();
                    if (failure != null) {
                        output.completeExceptionally(failure);
                        return;
                    }
                    try { output.complete(task.call()); }
                    catch (Exception error) { output.completeExceptionally(error); }
                });
            } catch (RejectedExecutionException rejected) {
                output.completeExceptionally(rejected);
            }
        }
        return output;
    }

    private void failPending(Exception failure) {
        List<Op> operations;
        synchronized (lock) {
            operations = new ArrayList<>(pending.values());
            pending.clear();
        }
        for (Op operation : operations) operation.future.completeExceptionally(failure);
    }

    private static CompletableFuture<Void> fail(Op operation, Exception failure) {
        operation.future.completeExceptionally(failure);
        return operation.future;
    }

    private static <T> CompletableFuture<T> failedFuture(Exception failure) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(failure);
        return future;
    }

    private static Throwable unwrap(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
}
