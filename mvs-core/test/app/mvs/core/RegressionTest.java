package app.mvs.core;
import app.mvs.core.net.*;
import app.mvs.core.store.*;
import app.mvs.core.util.*;
import app.mvs.core.wire.FrameCodec;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Ожидание: все PASS на исправленной версии. */
public final class RegressionTest {
    static int pass, fail;
    interface Body { void run() throws Exception; }
    static void t(String name, long ms, Body b) {
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread th = new Thread(() -> { try { b.run(); } catch (Throwable e) { err.set(e); } });
        th.setDaemon(true); th.start();
        try { th.join(ms); } catch (InterruptedException ignored) {}
        if (th.isAlive()) { fail++; System.out.println("TIMEOUT " + name); }
        else if (err.get() != null) { fail++; System.out.println("FAIL " + name + " : " + err.get()); }
        else { pass++; System.out.println("PASS " + name); }
    }
    static void check(boolean c, String m) { if (!c) throw new AssertionError(m); }
    static final class HoldPort implements MtprotoPort {
        final AtomicInteger sent = new AtomicInteger(); volatile Callback last; final AtomicBoolean cancelled = new AtomicBoolean();
        public Handle send(int dc, String m, Object tl, Callback cb) { sent.incrementAndGet(); last = cb; return () -> cancelled.set(true); }
    }
    static final class ManualClock implements Clock { long t = 1_000_000; public long nowMs() { return t; } void advance(long ms) { t += ms; } }

    public static void main(String[] a) throws Exception {
        ScheduledExecutorService timers = Executors.newScheduledThreadPool(2);
        AesGcmCipher aes = new AesGcmCipher(new byte[32]);

        t("1a superseded future completes", 5000, () -> {
            MessageStore s = new MessageStore(Files.createTempDirectory("r").toFile(), aes, timers);
            CompletableFuture<Void> f1 = s.put(1, 1, "a".getBytes());
            CompletableFuture<Void> f2 = s.put(1, 1, "b".getBytes());
            f1.get(3, TimeUnit.SECONDS); f2.get(3, TimeUnit.SECONDS);
            check(new String(s.get(1, 1).get()).equals("b"), "last-writer-wins");
            s.close();
        });
        t("1b compact failure keeps index", 5000, () -> {
            AtomicBoolean failSeal = new AtomicBoolean();
            CipherPort c = new CipherPort() {
                public byte[] seal(byte[] p) throws GeneralSecurityException { if (failSeal.get()) throw new GeneralSecurityException("boom"); return aes.seal(p); }
                public byte[] open(byte[] p) throws GeneralSecurityException { return aes.open(p); }
            };
            MessageStore s = new MessageStore(Files.createTempDirectory("r").toFile(), c, timers);
            s.put(1, 1, "v".getBytes()).get();
            failSeal.set(true);
            try { s.compact((d, m) -> false).get(3, TimeUnit.SECONDS); throw new AssertionError("compact should fail"); }
            catch (ExecutionException ok) {}
            check(s.get(1, 1).get() != null, "index intact after failed compact");
            s.close();
        });
        t("1c pending drained before compact", 5000, () -> {
            File dir = Files.createTempDirectory("r").toFile();
            MessageStore s = new MessageStore(dir, aes, timers);
            s.put(5, 1, "x".getBytes());                 // ещё в pending
            s.compact((d, m) -> false).get(3, TimeUnit.SECONDS);
            s.flush().get();
            check(s.get(5, 1).get() == null, "pending not resurrected");
            s.close();
            MessageStore re = new MessageStore(dir, aes, timers);
            check(re.get(5, 1).get() == null, "compaction persisted across reopen");
            re.close();
        });
        t("1d close releases writer", 15000, () -> {
            CountDownLatch block = new CountDownLatch(1);
            MessageStore s = new MessageStore(Files.createTempDirectory("r").toFile(), aes, timers);
            s.compact((d, m) -> { try { block.await(); } catch (InterruptedException ignored) {} return true; });
            Thread closer = new Thread(() -> { try { s.close(); } catch (Exception ignored) {} });
            closer.start();
            Thread.sleep(6000);                          // flush timeout прошёл
            block.countDown();
            closer.join(12000);
            check(!closer.isAlive(), "close returned");
            try { s.put(1, 1, "y".getBytes()).get(1, TimeUnit.SECONDS); throw new AssertionError("put after close accepted"); }
            catch (ExecutionException ok) {}
        });
        t("2a fragment boundary rejected", 3000, () -> {
            try { FrameCodec.fragment(FrameCodec.EVT, 1, new byte[10], FrameCodec.MAX_PAYLOAD); throw new AssertionError("accepted"); }
            catch (IllegalArgumentException ok) {}
            for (FrameCodec.Frame f : FrameCodec.fragment(FrameCodec.EVT, 1, new byte[10], FrameCodec.MAX_PAYLOAD - 11)) FrameCodec.encode(f);
        });
        t("2b per-fragment memory cap", 3000, () -> {
            ManualClock mc = new ManualClock();
            FrameCodec.Reassembler ra = new FrameCodec.Reassembler(mc, 30000, 16, 1000, 100000);
            java.util.List<FrameCodec.Frame> fr = FrameCodec.fragment(FrameCodec.EVT, 1, new byte[1800], 600);
            ra.feed(fr.get(0));
            try { ra.feed(fr.get(1)); throw new AssertionError("cap not enforced per fragment"); }
            catch (FrameCodec.ProtocolException ok) {}
        });
        t("2c TTL evicts stale assembly", 3000, () -> {
            ManualClock mc = new ManualClock();
            FrameCodec.Reassembler ra = new FrameCodec.Reassembler(mc, 1000, 16, 1 << 20, 1 << 24);
            ra.feed(FrameCodec.fragment(FrameCodec.EVT, 1, new byte[100], 50).get(0));
            check(ra.pendingCount() == 1, "pending");
            mc.advance(1001);
            ra.feed(FrameCodec.fragment(FrameCodec.EVT, 2, new byte[10], 10).get(0));
            check(ra.pendingCount() == 1, "stale evicted");
        });
        t("2d seq mismatch rejected", 3000, () -> {
            FrameCodec.Frame inner = FrameCodec.fragment(FrameCodec.EVT, 1, new byte[20], 10).get(0);
            FrameCodec.Frame bad = new FrameCodec.Frame(FrameCodec.FRAG, (byte)0, 999, inner.payload);
            try { new FrameCodec.Reassembler().feed(bad); throw new AssertionError("accepted"); }
            catch (FrameCodec.ProtocolException ok) {}
        });
        t("3a null result completes", 3000, () -> {
            HoldPort p = new HoldPort();
            CallScheduler sc = new CallScheduler(p, 4, 4, 100, timers);
            CompletableFuture<Object> f = sc.submit("m", new byte[0], new CallScheduler.Options());
            p.last.onReply(null);
            check(f.get(2, TimeUnit.SECONDS) == null, "null is success");
        });
        t("3b deadline from submit", 3000, () -> {
            HoldPort p = new HoldPort();
            CallScheduler sc = new CallScheduler(p, 1, 1, 100, timers);
            sc.submit("block", new byte[0], new CallScheduler.Options());
            CallScheduler.Options o = new CallScheduler.Options(); o.timeoutMs = 100;
            CompletableFuture<Object> f = sc.submit("queued", new byte[0], o);
            try { f.get(1, TimeUnit.SECONDS); throw new AssertionError("no timeout"); }
            catch (ExecutionException e) { check(e.getCause() instanceof TimeoutException, "timeout in queue"); }
        });
        t("3c handle cancelled on race", 3000, () -> {
            AtomicBoolean cancelled = new AtomicBoolean();
            MtprotoPort p = (dc, m, tl, cb) -> { try { Thread.sleep(150); } catch (InterruptedException ignored) {} return () -> cancelled.set(true); };
            CallScheduler sc = new CallScheduler(p, 1, 1, 10, timers);
            CallScheduler.Options o = new CallScheduler.Options(); o.timeoutMs = 30;
            try { sc.submit("m", new byte[0], o).get(1, TimeUnit.SECONDS); } catch (ExecutionException ignored) {}
            Thread.sleep(250);
            check(cancelled.get(), "handle cancelled after late assignment");
        });
        t("3d no recursion on sync port", 8000, () -> {
            MtprotoPort sync = (dc, m, tl, cb) -> { cb.onReply(new byte[]{1}); return () -> {}; };
            CallScheduler sc = new CallScheduler(sync, 4, 4, 10000, timers);
            CompletableFuture<?> lastF = null;
            for (int i = 0; i < 5000; i++) lastF = sc.submit("m", new byte[0], new CallScheduler.Options());
            lastF.get(5, TimeUnit.SECONDS);
            check(sc.completed.get() == 5000, "all complete iteratively");
        });
        t("3e shutdown cancels in-flight", 3000, () -> {
            HoldPort p = new HoldPort();
            CallScheduler sc = new CallScheduler(p, 1, 1, 100, timers);
            CompletableFuture<Object> f = sc.submit("inflight", new byte[0], new CallScheduler.Options());
            sc.shutdown();
            try { f.get(1, TimeUnit.SECONDS); throw new AssertionError("not completed"); }
            catch (ExecutionException ok) {}
            check(p.cancelled.get(), "handle cancelled");
        });
        t("4a cancel propagates to request", 3000, () -> {
            HoldPort p = new HoldPort();
            CallScheduler sc = new CallScheduler(p, 4, 4, 100, timers);
            RequestPipeline pipe = new RequestPipeline(java.util.Collections.singletonList(
                    new RetryInterceptor(3, 1, 5, 50, new Backoff.Budget(10, 10, Clock.MONOTONIC), timers)),
                    r -> sc.submit(r.method, r.tl, r.opts));
            CompletableFuture<Object> f = pipe.execute(new RequestPipeline.Request("m", new byte[0], new CallScheduler.Options()));
            f.cancel(true);
            Thread.sleep(50);
            check(p.cancelled.get(), "underlying request cancelled");
        });
        t("4b fatal not retried", 3000, () -> {
            AtomicInteger n = new AtomicInteger();
            MtprotoPort p = (dc, m, tl, cb) -> { n.incrementAndGet(); throw new IllegalStateException("fatal"); };
            CallScheduler sc = new CallScheduler(p, 4, 4, 100, timers);
            RequestPipeline pipe = new RequestPipeline(java.util.Collections.singletonList(
                    new RetryInterceptor(3, 1, 5, 50, new Backoff.Budget(10, 10, Clock.MONOTONIC), timers)),
                    r -> sc.submit(r.method, r.tl, r.opts));
            try { pipe.execute(new RequestPipeline.Request("m", new byte[0], new CallScheduler.Options())).get(2, TimeUnit.SECONDS); }
            catch (ExecutionException ignored) {}
            check(n.get() == 1, "fatal not retried");
        });
        t("4c FLOOD_WAIT not shortened", 3000, () -> {
            AtomicInteger n = new AtomicInteger();
            MtprotoPort p = (dc, m, tl, cb) -> { if (n.incrementAndGet() == 1) cb.onError(420, "FLOOD_WAIT_5", 5000); else cb.onReply(new byte[]{1}); return () -> {}; };
            CallScheduler sc = new CallScheduler(p, 4, 4, 100, timers);
            RequestPipeline pipe = new RequestPipeline(java.util.Collections.singletonList(
                    new RetryInterceptor(3, 1, 5, 50, new Backoff.Budget(10, 10, Clock.MONOTONIC), timers)),
                    r -> sc.submit(r.method, r.tl, r.opts));
            try { pipe.execute(new RequestPipeline.Request("m", new byte[0], new CallScheduler.Options())).get(2, TimeUnit.SECONDS); throw new AssertionError("retried"); }
            catch (ExecutionException ok) { check(n.get() == 1, "propagated, not retried early"); }
        });
        t("4d rejected scheduler completes", 3000, () -> {
            ScheduledExecutorService dead = Executors.newScheduledThreadPool(1); dead.shutdown();
            AtomicInteger n = new AtomicInteger();
            MtprotoPort p = (dc, m, tl, cb) -> { n.incrementAndGet(); cb.onError(-1, "net", 0); return () -> {}; };
            CallScheduler sc = new CallScheduler(p, 4, 4, 100, timers);
            RequestPipeline pipe = new RequestPipeline(java.util.Collections.singletonList(
                    new RetryInterceptor(3, 1, 5, 50, new Backoff.Budget(10, 10, Clock.MONOTONIC), dead)),
                    r -> sc.submit(r.method, r.tl, r.opts));
            try { pipe.execute(new RequestPipeline.Request("m", new byte[0], new CallScheduler.Options())).get(2, TimeUnit.SECONDS); throw new AssertionError("hung"); }
            catch (ExecutionException ok) {}
        });
        t("5a backoff no overflow", 2000, () -> {
            Backoff b = new Backoff(Long.MAX_VALUE / 2, Long.MAX_VALUE);
            boolean anyPositive = false;
            for (int i = 0; i < 50; i++) { long d = b.nextDelayMs(); check(d >= 0, "negative"); if (d > 0) anyPositive = true; }
            check(anyPositive, "delays positive");
        });
        t("5b budget robust to clock jump", 2000, () -> {
            ManualClock mc = new ManualClock();
            Backoff.Budget budget = new Backoff.Budget(3, 1, mc);
            check(budget.tryAcquire() && budget.tryAcquire() && budget.tryAcquire(), "3 tokens");
            mc.advance(-10000);                          // часы назад
            check(!budget.tryAcquire(), "no extra tokens");
            mc.advance(10000); mc.advance(1000);         // вернулись +1с
            check(budget.tryAcquire(), "refilled");
        });
        t("6 probe token semantics", 2000, () -> {
            ManualClock mc = new ManualClock();
            CircuitBreaker cb = new CircuitBreaker(mc, 32, 8, 50, 500, 30000);
            for (int i = 0; i < 8; i++) cb.record(false);
            mc.advance(500);
            long probe = cb.allow(); check(probe > 0, "probe granted");
            check(cb.allow() == -1, "no parallel probe");
            cb.record(true, 0);                          // чужой результат
            check(cb.state() == CircuitBreaker.State.HALF_OPEN, "stale ignored");
            cb.record(true, probe);
            check(cb.state() == CircuitBreaker.State.CLOSED, "real probe closes");
        });
        t("6 stuck probe reclaimed", 2000, () -> {
            ManualClock mc = new ManualClock();
            CircuitBreaker cb = new CircuitBreaker(mc, 32, 8, 50, 500, 30000, 1000);
            for (int i = 0; i < 8; i++) cb.record(false);
            mc.advance(500); long p1 = cb.allow();
            mc.advance(1001);
            check(cb.allow() == -1 && cb.state() == CircuitBreaker.State.OPEN, "stuck probe reopened");
            mc.advance(1000); long p2 = cb.allow();
            check(p2 > 0 && p2 != p1, "new probe token");
            cb.record(true, p1); check(cb.state() == CircuitBreaker.State.HALF_OPEN, "old token ignored");
        });
        t("7a updates drained on shutdown", 5000, () -> {
            java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<Object>> sink = new java.util.concurrent.atomic.AtomicReference<>();
            MtprotoPort p = new MtprotoPort() {
                public Handle send(int dc, String m, Object tl, Callback cb) { cb.onReply(new byte[]{1}); return () -> {}; }
                public void setUpdatesListener(java.util.function.Consumer<Object> s) { sink.set(s); }
            };
            MvsNetCore.Config cfg = new MvsNetCore.Config(); cfg.updateQueueCap = 64;
            MvsNetCore core = new MvsNetCore(p, cfg, Clock.SYSTEM);
            AtomicInteger got = new AtomicInteger();
            core.addUpdateListener(u -> got.incrementAndGet());
            for (int i = 0; i < 10; i++) sink.get().accept(new byte[]{1});
            core.shutdown(3000);
            check(got.get() == 10, "all updates drained on shutdown, got " + got.get());
        });
        t("8a CRC body corruption", 3000, () -> {
            File dir = Files.createTempDirectory("r").toFile();
            SegmentLog log = new SegmentLog(new File(dir, "l.log"));
            java.util.List<SegmentLog.Rec> recs = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) recs.add(new SegmentLog.Rec(1, ("record-" + i + "-xxxxxxxxxxxx").getBytes()));
            log.append(recs, true); long len = new File(dir, "l.log").length(); log.close();
            File cc = new File(dir, "crc.log"); Files.copy(new File(dir, "l.log").toPath(), cc.toPath());
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(cc, "rw")) { raf.seek(70); raf.write(raf.readByte() ^ 1); } // ТЕЛО 3-й записи
            SegmentLog cl = new SegmentLog(cc);
            check(cl.recoveredRecords == 2, "truncated at CRC of body"); cl.close();
        });
        t("8b partial header recovery", 3000, () -> {
            File dir = Files.createTempDirectory("r").toFile();
            SegmentLog log = new SegmentLog(new File(dir, "l.log"));
            log.append(java.util.Collections.singletonList(new SegmentLog.Rec(1, "abcdef".getBytes())), true);
            long len = new File(dir, "l.log").length(); log.close();
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(new File(dir, "l.log"), "rw")) { raf.setLength(len + 3); } // огрызок заголовка
            SegmentLog re = new SegmentLog(new File(dir, "l.log"));
            check(re.recoveredRecords == 1 && re.truncatedBytes == 3, "partial header truncated"); re.close();
        });
        t("8c rewrite atomic + oversize", 3000, () -> {
            File dir = Files.createTempDirectory("r").toFile();
            SegmentLog log = new SegmentLog(new File(dir, "l.log"));
            log.append(java.util.Collections.singletonList(new SegmentLog.Rec(1, "ok".getBytes())), true);
            try { log.rewrite(java.util.Collections.singletonList(new SegmentLog.Rec(1, new byte[SegmentLog.MAX_DATA + 1]))); throw new AssertionError("oversize accepted"); }
            catch (IOException ok) {}
            java.util.List<String> seen = new java.util.ArrayList<>();
            log.scan((t, b) -> seen.add(new String(b)));
            check(seen.size() == 1 && seen.get(0).equals("ok"), "original intact after failed rewrite");
            log.close();
            // имитация краха между rename: есть .old, нет основного
            File main = new File(dir, "c.log"), old = new File(dir, "c.log.old");
            Files.copy(new File(dir, "l.log").toPath(), old.toPath());
            SegmentLog rec = new SegmentLog(main);
            check(rec.recoveredRecords == 1, "recovered from .old"); rec.close();
        });
        timers.shutdownNow();
        System.out.println("REGRESSION RESULT pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
