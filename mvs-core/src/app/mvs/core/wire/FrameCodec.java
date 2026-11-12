package app.mvs.core.wire;

import app.mvs.core.util.Clock;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.CRC32;

public final class FrameCodec {
    public static final int HEADER = 17, MAX_PAYLOAD = 256 * 1024, FRAG_OVERHEAD = 11;
    public static final byte REQ = 1, RES = 2, EVT = 3, ERR = 4, PING = 5, PONG = 6, FRAG = 7;

    public static final class Frame {
        public final byte type, flags; public final int seq; public final byte[] payload;
        public Frame(byte type, byte flags, int seq, byte[] payload) {
            this.type = type; this.flags = flags; this.seq = seq; this.payload = payload;
        }
    }

    public static final class ProtocolException extends Exception { public ProtocolException(String m){super(m);} }

    public static byte[] encode(Frame f) {
        if (f.payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("payload too big");
        ByteBuffer b = ByteBuffer.allocate(HEADER + f.payload.length);
        b.put((byte)'M').put((byte)'V').put((byte)1).put(f.type).put(f.flags);
        b.putInt(f.seq).putInt(f.payload.length);
        CRC32 c = new CRC32(); c.update(b.array(), 0, 13); c.update(f.payload);
        b.putInt((int)c.getValue()).put(f.payload);
        return b.array();
    }

    public static final class Decoder {
        private byte[] buf = new byte[4096]; private int size;

        public List<Frame> feed(byte[] data, int off, int len) throws ProtocolException {
            if (len > 4 * MAX_PAYLOAD) throw new ProtocolException("feed too big");
            ensure(size + len);
            System.arraycopy(data, off, buf, size, len); size += len;
            List<Frame> out = new ArrayList<>(); int pos = 0;
            while (size - pos >= HEADER) {
                if (buf[pos] != 'M' || buf[pos+1] != 'V') throw new ProtocolException("magic");
                if (buf[pos+2] != 1) throw new ProtocolException("version");
                int plen = readInt(buf, pos + 9);
                if (plen < 0 || plen > MAX_PAYLOAD) throw new ProtocolException("length");
                if (size - pos < HEADER + plen) break;
                CRC32 c = new CRC32(); c.update(buf, pos, 13); c.update(buf, pos + HEADER, plen);
                if ((int)c.getValue() != readInt(buf, pos + 13)) throw new ProtocolException("crc");
                out.add(new Frame(buf[pos+3], buf[pos+4], readInt(buf, pos+5),
                        Arrays.copyOfRange(buf, pos + HEADER, pos + HEADER + plen)));
                pos += HEADER + plen;
            }
            if (pos > 0) { System.arraycopy(buf, pos, buf, 0, size - pos); size -= pos; }
            return out;
        }

        private void ensure(int n) {
            if (n <= buf.length) return;
            int cap = buf.length; while (cap < n) cap *= 2;
            buf = Arrays.copyOf(buf, cap);
        }
    }

    /** chunk — байты данных на фрагмент; кадр несёт chunk+11, поэтому chunk <= MAX_PAYLOAD-11. */
    public static List<Frame> fragment(byte type, int seq, byte[] payload, int chunk) {
        if (chunk <= 0 || chunk > MAX_PAYLOAD - FRAG_OVERHEAD) throw new IllegalArgumentException("chunk");
        int total = Math.max(1, (payload.length + chunk - 1) / chunk);
        if (total > 4096) throw new IllegalArgumentException("too many fragments");
        List<Frame> out = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int from = i * chunk, to = Math.min(payload.length, from + chunk);
            ByteBuffer p = ByteBuffer.allocate(FRAG_OVERHEAD + (to - from));
            p.put(type).putInt(seq).putShort((short)i).putShort((short)total).put(payload, from, to - from);
            out.add(new Frame(FRAG, (byte)0, seq, p.array()));
        }
        return out;
    }

    public static final class Reassembler {
        private static final class Part {
            byte[][] parts; int got; byte type; long bytes; long lastTouch;
        }

        private final Map<Integer, Part> pending = new HashMap<>();
        private final Clock clock; private final long ttlMs, perAssemblyMax, globalMax;
        private final int maxPending; private long globalBytes;

        public Reassembler() { this(Clock.SYSTEM, 30000, 16, 16L * MAX_PAYLOAD, 64L * MAX_PAYLOAD); }

        public Reassembler(Clock clock, long ttlMs, int maxPending, long perAssemblyMax, long globalMax) {
            this.clock = clock; this.ttlMs = ttlMs; this.maxPending = maxPending;
            this.perAssemblyMax = perAssemblyMax; this.globalMax = globalMax;
        }

        public synchronized int pendingCount() { return pending.size(); }
        public synchronized long pendingBytes() { return globalBytes; }

        public synchronized Frame feed(Frame f) throws ProtocolException {
            long now = clock.nowMs();
            Iterator<Map.Entry<Integer, Part>> ev = pending.entrySet().iterator();
            while (ev.hasNext()) {
                Part p = ev.next().getValue();
                if (now - p.lastTouch > ttlMs) { globalBytes -= p.bytes; ev.remove(); }
            }
            if (f.type != FRAG || f.payload.length < FRAG_OVERHEAD) throw new ProtocolException("frag");
            ByteBuffer p = ByteBuffer.wrap(f.payload);
            byte type = p.get(); int seq = p.getInt();
            if (f.seq != seq) throw new ProtocolException("frag seq mismatch");
            int idx = p.getShort() & 0xffff, total = p.getShort() & 0xffff;
            if (total == 0 || idx >= total || total > 4096) throw new ProtocolException("frag range");
            int chunkLen = p.remaining();
            Part part = pending.get(seq);
            if (part == null) {
                if (pending.size() >= maxPending) throw new ProtocolException("too many pending");
                part = new Part(); part.parts = new byte[total][]; part.type = type;
                pending.put(seq, part);
            }
            if (part.parts.length != total || part.type != type) throw new ProtocolException("frag mismatch");
            part.lastTouch = now;
            if (part.parts[idx] == null) {
                if (part.bytes + chunkLen > perAssemblyMax) throw new ProtocolException("assembly too big");
                if (globalBytes + chunkLen > globalMax) throw new ProtocolException("global reassembly limit");
                part.parts[idx] = new byte[chunkLen]; p.get(part.parts[idx]);
                part.bytes += chunkLen; globalBytes += chunkLen; part.got++;
            }
            if (part.got < total) return null;
            pending.remove(seq); globalBytes -= part.bytes;
            byte[] full = new byte[(int) part.bytes]; int o = 0;
            for (byte[] b : part.parts) { System.arraycopy(b, 0, full, o, b.length); o += b.length; }
            return new Frame(type, (byte)0, seq, full);
        }
    }

    private static int readInt(byte[] b, int o) {
        return ((b[o]&255)<<24)|((b[o+1]&255)<<16)|((b[o+2]&255)<<8)|(b[o+3]&255);
    }

    private FrameCodec() {}
}
