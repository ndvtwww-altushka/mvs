package app.mvs.core;

import app.mvs.core.net.*;
import app.mvs.core.store.*;
import app.mvs.core.util.*;
import app.mvs.core.wire.FrameCodec;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Focused regression suite for bounds, lifecycle, cancellation, retries and persistence. */
public final class RegressionTest {
    private static int passed;
    private static int failed;

    private interface Body { void run() throws Exception; }

    private static final class ManualClock implements Clock {
        private long now = 1_000_000;
        @Override public synchronized long nowMs() { return now; }
        synchronized void advance(long millis) { now += millis; }
        synchronized void set(long millis) { now = millis; }
    }

    private static final class HoldPort implements MtprotoPort {
        final AtomicInteger sent = new AtomicInteger();
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile Callback callback;
        volatile Consumer<Object> updates;
        @Override public Handle send(int dc, String method, Object tl, Callback cb) {
            sent.incrementAndGet();
            callback = cb;
            return () -> cancelled.set(true);
        }
        @Override public void setUpdatesListener(Consumer<Object> sink) { updates = sink; }
    }

    private static void test(String name, Body body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable error) {
            failed++;
            System.out.println("FAIL " + name + " : " + error);
            error.printStackTrace(System.out);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(3, TimeUnit.SECONDS);
    }

    public static void main(String[] args) throws Exception {
        test("AES-GCM authenticates and uses fresh nonces", () -> {
            AesGcmCipher cipher = new AesGcmCipher(new byte[32]);
            byte[] plain = "private payload".getBytes("UTF-8");
            byte[] a = cipher.seal(plain);
            byte[] b = cipher.seal(plain);
            check(!Arrays.equals(a, b), "nonce/ciphertext repeated");
            check(Arrays.equals(plain, cipher.open(a)), "round trip");
            a[a.length - 1] ^= 1;
            try { cipher.open(a); throw new AssertionError("tampering accepted"); }
            catch (GeneralSecurityException expected) { }
        });

        test("frame codec handles split and coalesced frames", () -> {
            FrameCodec.Frame one = new FrameCodec.Frame(FrameCodec.REQ, (byte) 0, 12, new byte[]{1, 2, 3});
            FrameCodec.Frame two = new FrameCodec.Frame(FrameCodec.RES, (byte) 1, 13, new byte[]{4, 5});
            byte[] a = FrameCodec.encode(one), b = FrameCodec.encode(two);
            byte[] joined = new byte[a.length + b.length];
            System.arraycopy(a, 0, joined, 0, a.length);
            System.arraycopy(b, 0, joined, a.length, b.length);
            FrameCodec.Decoder decoder = new FrameCodec.Decoder();
            check(decoder.feed(joined, 0, 5).isEmpty(), "partial frame emitted");
            List<FrameCodec.Frame> decoded = decoder.feed(joined, 5, joined.length - 5);
            check(decoded.size() == 2 && decoded.get(0).seq == 12 && decoded.get(1).seq == 13, "bad frame sequence");
        });

        test("decoder has a hard accumulation bound", () -> {
            FrameCodec.Decoder decoder = new FrameCodec.Decoder();
            byte[] prefix = new byte[FrameCodec.HEADER];
            prefix[0] = 'M'; prefix[1] = 'V'; prefix[2] = 1;
            int size = FrameCodec.MAX_PAYLOAD;
            prefix[9] = (byte) (size >>> 24); prefix[10] = (byte) (size >>> 16);
            prefix[11] = (byte) (size >>> 8); prefix[12] = (byte) size;
            decoder.feed(prefix, 0, prefix.length);
            byte[] chunk = new byte[FrameCodec.MAX_FEED_BYTES];
            try {
                for (int i = 0; i < 8; i++) decoder.feed(chunk, 0, chunk.length);
                throw new AssertionError("unbounded data accepted");
            } catch (FrameCodec.ProtocolException expected) {
                check(decoder.bufferedBytes() == 0, "decoder not reset after malformed stream");
            }
        });

        test("fragmentation reassembles out of order", () -> {
            byte[] payload = new byte[4096];
            new Random(7).nextBytes(payload);
            List<FrameCodec.Frame> parts = FrameCodec.fragment(FrameCodec.EVT, 50, payload, 777);
            FrameCodec.Reassembler reassembler = new FrameCodec.Reassembler();
            FrameCodec.Frame complete = null;
            for (int i = parts.size() - 1; i >= 0; i--) {
                FrameCodec.Frame result = reassembler.feed(parts.get(i));
                if (result != null) complete = result;
            }
            check(complete != null && Arrays.equals(payload, complete.payload), "reassembly mismatch");
            check(reassembler.pendingBytes() == 0, "memory counter leaked");
        });

        test("conflicting duplicate fragment discards assembly", () -> {
            List<FrameCodec.Frame> parts = FrameCodec.fragment(FrameCodec.EVT, 51, new byte[100], 50);
            FrameCodec.Reassembler reassembler = new FrameCodec.Reassembler();
            reassembler.feed(parts.get(0));
            byte[] conflictingPayload = parts.get(0).payload.clone();
            conflictingPayload[conflictingPayload.length - 1] ^= 1;
            FrameCodec.Frame conflicting = new FrameCodec.Frame(FrameCodec.FRAG, (byte) 0, 51, conflictingPayload);
            try { reassembler.feed(conflicting); throw new AssertionError("conflicting duplicate accepted"); }
            catch (FrameCodec.ProtocolException expected) {
                check(reassembler.pendingCount() == 0 && reassembler.pendingBytes() == 0, "assembly retained after conflict");
            }
        });

        test("reassembler expires abandoned partial messages", () -> {
            ManualClock clock = new ManualClock();
            FrameCodec.Reassembler reassembler = new FrameCodec.Reassembler(clock, 100, 2, 4096, 8192);
            reassembler.feed(FrameCodec.fragment(FrameCodec.EVT, 52, new byte[100], 50).get(0));
            clock.advance(101);
            reassembler.feed(FrameCodec.fragment(FrameCodec.EVT, 53, new byte[100], 50).get(0));
            check(reassembler.pendingCount() == 1, "stale item not evicted");
        });

        test("circuit breaker admits one probe and expires stuck probe", () -> {
            ManualClock clock = new ManualClock();
            CircuitBreaker breaker = new CircuitBreaker(clock, 8, 4, 50, 100, 800, 50);
            for (int i = 0; i < 4; i++) breaker.record(false);
            check(breaker.state() == CircuitBreaker.State.OPEN, "did not open");
            clock.advance(100);
            long probe = breaker.allow();
            check(probe > 0 && breaker.allow() < 0, "probe not exclusive");
            clock.advance(51);
            check(breaker.allow() < 0 && breaker.state() == CircuitBreaker.State.OPEN, "stuck probe not reclaimed");
            clock.advance(200);
            long nextProbe = breaker.allow();
            check(nextProbe > 0 && nextProbe != probe, "new probe token missing");
            breaker.record(true, probe);
            check(breaker.state() == CircuitBreaker.State.HALF_OPEN, "stale token changed state");
            breaker.record(true, nextProbe);
            check(breaker.state() == CircuitBreaker.State.CLOSED, "valid probe did not close");
        });

        test("cancelled half-open probe is abandoned without false failure", () -> {
            ManualClock clock = new ManualClock();
            CircuitBreaker breaker = new CircuitBreaker(clock, 8, 4, 50, 100, 800, 50);
            for (int i = 0; i < 4; i++) breaker.record(false);
            clock.advance(100);
            long probe = breaker.allow();
            check(probe > 0 && breaker.state() == CircuitBreaker.State.HALF_OPEN, "probe not acquired");
            breaker.abandonProbe(probe);
            check(breaker.state() == CircuitBreaker.State.OPEN, "cancelled probe left breaker half-open");
            check(breaker.allow() < 0, "cancelled probe immediately reopened traffic");
            clock.advance(200);
            long next = breaker.allow();
            check(next > 0 && next != probe, "breaker did not issue a fresh probe");
            breaker.record(true, next);
            check(breaker.state() == CircuitBreaker.State.CLOSED, "fresh probe did not close breaker");
        });

        test("scheduler dispatches synchronous callbacks without recursion", () -> {
            ScheduledExecutorService timers = Executors.newScheduledThreadPool(1);
            try {
                MtprotoPort sync = (dc, method, tl, callback) -> {
                    callback.onReply(new byte[]{1});
                    return () -> { };
                };
                CallScheduler scheduler = new CallScheduler(sync, 4, 4, 6000, timers);
                CompletableFuture<Object> last = null;
                for (int i = 0; i < 5000; i++) last = scheduler.submit("method", new byte[0], new CallScheduler.Options());
                await(last);
                check(scheduler.completed.get() == 5000, "not all calls completed");
                scheduler.shutdown();
            } finally { timers.shutdownNow(); }
        });

        test("scheduler fails cleanly when its timer executor is stopped", () -> {
            ScheduledExecutorService stopped = Executors.newSingleThreadScheduledExecutor();
            stopped.shutdownNow();
            CallScheduler scheduler = new CallScheduler((dc, method, tl, callback) -> () -> { },
                    1, 1, 10, stopped);
            try {
                await(scheduler.submit("method", new byte[0], new CallScheduler.Options()));
                throw new AssertionError("request unexpectedly succeeded");
            } catch (ExecutionException expected) {
                check(scheduler.queuedCount() == 0 && scheduler.inFlightCount() == 0, "scheduler leaked reservation");
            }
            scheduler.shutdown();
        });

        test("scheduler cancellation releases queued slot and native handle", () -> {
            ScheduledExecutorService timers = Executors.newScheduledThreadPool(1);
            try {
                HoldPort port = new HoldPort();
                CallScheduler scheduler = new CallScheduler(port, 1, 1, 10, timers);
                CompletableFuture<Object> first = scheduler.submit("one", new byte[0], new CallScheduler.Options());
                CompletableFuture<Object> second = scheduler.submit("two", new byte[0], new CallScheduler.Options());
                second.cancel(true);
                check(scheduler.queuedCount() == 0, "cancelled queued call still consumes capacity");
                first.cancel(true);
                check(port.cancelled.get(), "in-flight native request not cancelled");
                scheduler.shutdown();
            } finally { timers.shutdownNow(); }
        });

        test("scheduler timeout racing handle creation cancels late handle", () -> {
            ScheduledExecutorService timers = Executors.newScheduledThreadPool(1);
            try {
                AtomicBoolean cancelled = new AtomicBoolean();
                MtprotoPort slowStart = (dc, method, tl, callback) -> {
                    try { Thread.sleep(120); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    return () -> cancelled.set(true);
                };
                CallScheduler scheduler = new CallScheduler(slowStart, 1, 1, 10, timers);
                CallScheduler.Options options = new CallScheduler.Options(); options.timeoutMs = 20;
                try { await(scheduler.submit("slow", new byte[0], options)); }
                catch (ExecutionException expected) { }
                Thread.sleep(160);
                check(cancelled.get(), "late handle not cancelled");
                scheduler.shutdown();
            } finally { timers.shutdownNow(); }
        });

        test("scheduler shutdown cancels in-flight calls", () -> {
            ScheduledExecutorService timers = Executors.newScheduledThreadPool(1);
            try {
                HoldPort port = new HoldPort();
                CallScheduler scheduler = new CallScheduler(port, 1, 1, 10, timers);
                CompletableFuture<Object> call = scheduler.submit("pending", new byte[0], new CallScheduler.Options());
                scheduler.shutdown();
                try { await(call); throw new AssertionError("future not completed"); }
                catch (ExecutionException expected) { check(port.cancelled.get(), "handle not cancelled"); }
            } finally { timers.shutdownNow(); }
        });

        test("retry interceptor retries 5xx once", () -> {
            ScheduledExecutorService timers = Executors.newScheduledThreadPool(1);
            try {
                AtomicInteger calls = new AtomicInteger();
                MtprotoPort port = (dc, method, tl, callback) -> {
                    if (calls.incrementAndGet() == 1) callback.onError(503, "temporary", 0);
                    else callback.onReply("ok");
                    return () -> { };
                };
                CallScheduler scheduler = new CallScheduler(port, 2, 2, 20, timers);
                RequestPipeline pipeline = new RequestPipeline(Collections.singletonList(
                        new RetryInterceptor(2, 1, 2, 100, new Backoff.Budget(5, 5, Clock.MONOTONIC), timers)),
                        request -> scheduler.submit(request.method, request.tl, request.opts));
                Object result = await(pipeline.execute(new RequestPipeline.Request("method", new byte[0], new CallScheduler.Options())));
                check("ok".equals(result) && calls.get() == 2, "retry did not happen exactly once");
                scheduler.shutdown();
            } finally { timers.shutdownNow(); }
        });

        test("FLOOD_WAIT is never shortened to configured cap", () -> {
            ScheduledExecutorService timers = Executors.newScheduledThreadPool(1);
            try {
                AtomicInteger calls = new AtomicInteger();
                MtprotoPort port = (dc, method, tl, callback) -> {
                    calls.incrementAndGet(); callback.onError(420, "FLOOD_WAIT_5", 5000); return () -> { };
                };
                CallScheduler scheduler = new CallScheduler(port, 1, 1, 10, timers);
                RequestPipeline pipeline = new RequestPipeline(Collections.singletonList(
                        new RetryInterceptor(3, 1, 2, 50, new Backoff.Budget(5, 5, Clock.MONOTONIC), timers)),
                        request -> scheduler.submit(request.method, request.tl, request.opts));
                try { await(pipeline.execute(new RequestPipeline.Request("method", new byte[0], new CallScheduler.Options()))); }
                catch (ExecutionException expected) { check(calls.get() == 1, "retried a long flood wait"); }
                scheduler.shutdown();
            } finally { timers.shutdownNow(); }
        });

        test("retry chain respects one end-to-end deadline", () -> {
            ScheduledExecutorService timers = Executors.newScheduledThreadPool(1);
            try {
                AtomicInteger calls = new AtomicInteger();
                MtprotoPort port = (dc, method, tl, callback) -> {
                    calls.incrementAndGet();
                    callback.onError(420, "FLOOD_WAIT_70", 70);
                    return () -> { };
                };
                CallScheduler scheduler = new CallScheduler(port, 1, 1, 100, timers);
                RequestPipeline pipeline = new RequestPipeline(Collections.singletonList(
                        new RetryInterceptor(20, 1, 2, 100, new Backoff.Budget(50, 50, Clock.MONOTONIC), timers)),
                        request -> scheduler.submit(request.method, request.tl, request.opts));
                CallScheduler.Options options = new CallScheduler.Options(); options.timeoutMs = 100;
                long started = System.nanoTime();
                try { await(pipeline.execute(new RequestPipeline.Request("method", new byte[0], options))); }
                catch (ExecutionException expected) { }
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                check(elapsed < 250, "retry extended deadline: " + elapsed + "ms");
                check(calls.get() == 2, "deadline was not enforced across retries: " + calls.get());
                scheduler.shutdown();
            } finally { timers.shutdownNow(); }
        });

        test("message store last-write-wins and persists encrypted data", () -> {
            ScheduledExecutorService timers = Executors.newScheduledThreadPool(1);
            File directory = Files.createTempDirectory("mvs-store").toFile();
            AesGcmCipher cipher = new AesGcmCipher(new byte[32]);
            try {
                MessageStore store = new MessageStore(directory, cipher, timers);
                CompletableFuture<Void> first = store.put(7, 11, "old".getBytes("UTF-8"));
                CompletableFuture<Void> second = store.put(7, 11, "new".getBytes("UTF-8"));
                await(first); await(second);
                check("new".equals(new String(await(store.get(7, 11)), "UTF-8")), "last write not kept");
                store.close();
                MessageStore reopened = new MessageStore(directory, cipher, timers);
                check("new".equals(new String(await(reopened.get(7, 11)), "UTF-8")), "value not recovered");
                reopened.close();
            } finally { timers.shutdownNow(); }
        });

        test("message store close drains pending writes and rejects later writes", () -> {
            ScheduledExecutorService timers = Executors.newScheduledThreadPool(1);
            File directory = Files.createTempDirectory("mvs-store-close").toFile();
            try {
                MessageStore store = new MessageStore(directory, new AesGcmCipher(new byte[32]), timers);
                CompletableFuture<Void> pending = store.put(9, 1, "persist me".getBytes("UTF-8"));
                store.close();
                await(pending);
                try { await(store.put(9, 2, new byte[]{1})); throw new AssertionError("write accepted after close"); }
                catch (ExecutionException expected) { }
            } finally { timers.shutdownNow(); }
        });

        test("message store compaction preserves kept rows and removes dropped rows", () -> {
            ScheduledExecutorService timers = Executors.newScheduledThreadPool(1);
            File directory = Files.createTempDirectory("mvs-store-compact").toFile();
            try {
                AesGcmCipher cipher = new AesGcmCipher(new byte[32]);
                MessageStore store = new MessageStore(directory, cipher, timers);
                await(store.put(1, 1, new byte[]{1}));
                await(store.put(1, 2, new byte[]{2}));
                await(store.compact((dialog, message) -> message == 2));
                check(await(store.get(1, 1)) == null, "dropped row remains");
                check(Arrays.equals(await(store.get(1, 2)), new byte[]{2}), "kept row missing");
                store.close();
                MessageStore reopened = new MessageStore(directory, cipher, timers);
                check(Arrays.equals(await(reopened.get(1, 2)), new byte[]{2}), "compaction not durable");
                reopened.close();
            } finally { timers.shutdownNow(); }
        });

        test("segment log truncates a damaged tail", () -> {
            File directory = Files.createTempDirectory("mvs-log").toFile();
            File file = new File(directory, "records.log");
            SegmentLog log = new SegmentLog(file);
            log.append(Arrays.asList(new SegmentLog.Rec(1, new byte[]{1}), new SegmentLog.Rec(1, new byte[]{2})), true);
            log.close();
            try (RandomAccessFileAccess ignored = new RandomAccessFileAccess(file)) {
                ignored.flip(12);
            }
            SegmentLog recovered = new SegmentLog(file);
            check(recovered.recoveredRecords == 1 && recovered.truncatedBytes > 0, "bad tail not truncated");
            recovered.close();
        });

        test("segment log refuses oversized records before write", () -> {
            File file = new File(Files.createTempDirectory("mvs-log-size").toFile(), "records.log");
            SegmentLog log = new SegmentLog(file);
            try {
                log.append(Collections.singletonList(new SegmentLog.Rec(1, new byte[SegmentLog.MAX_DATA + 1])), true);
                throw new AssertionError("oversized record accepted");
            } catch (IOException expected) { check(file.length() == 0, "partial record written"); }
            log.close();
        });

        test("core drains accepted updates and rejects sends after shutdown", () -> {
            HoldPort port = new HoldPort();
            MvsNetCore core = new MvsNetCore(port, new MvsNetCore.Config(), Clock.MONOTONIC);
            AtomicInteger received = new AtomicInteger();
            core.addUpdateListener(value -> received.incrementAndGet());
            for (int i = 0; i < 20; i++) port.updates.accept(new byte[]{(byte) i});
            core.shutdown(2000);
            check(received.get() == 20, "update tail lost: " + received.get());
            check(!core.isRunning(), "core still running");
            try { await(core.send("after-close", new byte[0], new CallScheduler.Options())); throw new AssertionError("send accepted"); }
            catch (ExecutionException expected) { }
        });

        test("failover moves to the next DC and rebuilds the payload", () -> {
            final class DcPort implements MtprotoPort {
                final List<Integer> dcs = new ArrayList<>();
                final List<Object> payloads = new ArrayList<>();
                @Override public Handle send(int dc, String method, Object tl, Callback cb) {
                    dcs.add(dc);
                    payloads.add(tl);
                    if (dc == 1) cb.onError(500, "CONNECTION_FAILED", 0);
                    else cb.onReply("reply-" + dc);
                    return () -> { };
                }
            }
            DcPort port = new DcPort();
            MvsNetCore.Config config = new MvsNetCore.Config();
            config.maxRetries = 0; // the host disables retries: failover rebuilds the payload
            MvsNetCore core = new MvsNetCore(port, config, Clock.MONOTONIC);
            try {
                CallScheduler.Options options = new CallScheduler.Options();
                options.dc = 1;
                options.timeoutMs = 2000;
                AtomicInteger built = new AtomicInteger();
                Object value = await(core.sendWithFailover("test.method",
                        () -> new Object[]{built.incrementAndGet()}, options));
                check(built.get() == 2, "payload not rebuilt per attempt: " + built.get());
                check(port.dcs.size() == 2 && port.dcs.get(0) == 1 && port.dcs.get(1) == 2,
                        "unexpected dc order: " + port.dcs);
                check(port.payloads.get(0) != port.payloads.get(1), "payload object reused");
                check("reply-2".equals(value), "success not propagated: " + value);
            } finally {
                core.shutdown(2000);
            }
        });

        test("failover stops on authoritative errors and honors the attempt limit", () -> {
            final class DcPort implements MtprotoPort {
                final List<Integer> dcs = new ArrayList<>();
                @Override public Handle send(int dc, String method, Object tl, Callback cb) {
                    dcs.add(dc);
                    if (dc == 1) cb.onError(400, "BAD_REQUEST", 0);
                    else cb.onError(500, "CONNECTION_FAILED", 0);
                    return () -> { };
                }
            }
            DcPort port = new DcPort();
            MvsNetCore.Config config = new MvsNetCore.Config();
            config.maxFailoverAttempts = 2;
            config.maxRetries = 0;
            MvsNetCore core = new MvsNetCore(port, config, Clock.MONOTONIC);
            try {
                CallScheduler.Options options = new CallScheduler.Options();
                options.dc = 1;
                options.timeoutMs = 2000;
                try {
                    await(core.sendWithFailover("test.method", () -> new Object(), options));
                    throw new AssertionError("a 400 switched data centre");
                } catch (ExecutionException expected) {
                    check(port.dcs.equals(Collections.singletonList(1)), "4xx moved to another dc: " + port.dcs);
                    check(expected.getCause() instanceof RpcException
                            && ((RpcException) expected.getCause()).code == 400, "wrong error propagated");
                }
                port.dcs.clear();
                try {
                    await(core.sendWithFailover("test.method", () -> new Object(), options));
                    throw new AssertionError("5xx accepted after every attempt");
                } catch (ExecutionException expected) {
                    check(port.dcs.equals(Arrays.asList(1, 2)), "attempt limit not honored: " + port.dcs);
                }
            } finally {
                core.shutdown(2000);
            }
        });

        test("requests preserve native default DC selection", () -> {
            check(new CallScheduler.Options().dc == Integer.MAX_VALUE,
                    "default request must not pin every account to Telegram DC 1");
        });

        test("config rejects unsafe zero limits", () -> {
            MvsNetCore.Config config = new MvsNetCore.Config();
            config.updateQueueCap = 0;
            try { new MvsNetCore((dc, method, tl, callback) -> () -> { }, config, Clock.MONOTONIC);
                throw new AssertionError("invalid config accepted"); }
            catch (IllegalArgumentException expected) { }
        });

        System.out.println("REGRESSION RESULT pass=" + passed + " fail=" + failed);
        if (failed != 0) System.exit(1);
    }

    private static final class RandomAccessFileAccess implements AutoCloseable {
        private final java.io.RandomAccessFile file;
        RandomAccessFileAccess(File target) throws IOException { file = new java.io.RandomAccessFile(target, "rw"); }
        void flip(long offset) throws IOException { file.seek(offset); int value = file.read(); file.seek(offset); file.write(value ^ 0x40); }
        @Override public void close() throws IOException { file.close(); }
    }

    private RegressionTest() { }
}
