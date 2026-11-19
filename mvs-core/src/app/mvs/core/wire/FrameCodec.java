package app.mvs.core.wire;

import app.mvs.core.util.Clock;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.CRC32;

/** MVSP framing, bounded streaming decoder and bounded fragment reassembly. */
public final class FrameCodec {
    public static final int HEADER = 17;
    public static final int MAX_PAYLOAD = 256 * 1024;
    public static final int FRAG_OVERHEAD = 11;
    public static final int MAX_FEED_BYTES = 4 * MAX_PAYLOAD;
    public static final int MAX_REASSEMBLED = 16 * MAX_PAYLOAD;
    private static final int MAX_BUFFERED = MAX_FEED_BYTES + HEADER;

    public static final byte REQ = 1, RES = 2, EVT = 3, ERR = 4, PING = 5, PONG = 6, FRAG = 7;

    public static final class Frame {
        public final byte type;
        public final byte flags;
        public final int seq;
        public final byte[] payload;

        public Frame(byte type, byte flags, int seq, byte[] payload) {
            if (payload == null) throw new IllegalArgumentException("payload == null");
            this.type = type;
            this.flags = flags;
            this.seq = seq;
            this.payload = payload;
        }
    }

    public static final class ProtocolException extends Exception {
        private static final long serialVersionUID = 1L;
        public ProtocolException(String message) { super(message); }
    }

    public static byte[] encode(Frame frame) {
        if (frame == null || frame.payload == null) throw new IllegalArgumentException("frame/payload == null");
        if (frame.payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("payload too big");
        ByteBuffer buffer = ByteBuffer.allocate(HEADER + frame.payload.length);
        buffer.put((byte) 'M').put((byte) 'V').put((byte) 1).put(frame.type).put(frame.flags);
        buffer.putInt(frame.seq).putInt(frame.payload.length);
        CRC32 crc = new CRC32();
        crc.update(buffer.array(), 0, 13);
        crc.update(frame.payload);
        buffer.putInt((int) crc.getValue()).put(frame.payload);
        return buffer.array();
    }

    public static final class Decoder {
        private byte[] buffer = new byte[4096];
        private int size;

        public synchronized int bufferedBytes() { return size; }

        /** Invalid input resets the decoder so callers can safely start a new stream. */
        public synchronized List<Frame> feed(byte[] data, int offset, int length) throws ProtocolException {
            if (data == null) throw new ProtocolException("data is null");
            if (offset < 0 || length < 0 || offset > data.length - length)
                throw new ProtocolException("invalid input range");
            if (length > MAX_FEED_BYTES) throw new ProtocolException("feed too big");
            if ((long) size + length > MAX_BUFFERED) throw new ProtocolException("decoder buffer limit");

            ensure(size + length);
            System.arraycopy(data, offset, buffer, size, length);
            size += length;
            List<Frame> frames = new ArrayList<>();
            int position = 0;
            try {
                while (size - position >= HEADER) {
                    if (buffer[position] != 'M' || buffer[position + 1] != 'V')
                        throw new ProtocolException("magic");
                    if (buffer[position + 2] != 1) throw new ProtocolException("version");
                    int payloadLength = readInt(buffer, position + 9);
                    if (payloadLength < 0 || payloadLength > MAX_PAYLOAD)
                        throw new ProtocolException("length");
                    int frameLength = HEADER + payloadLength;
                    if (size - position < frameLength) break;

                    CRC32 crc = new CRC32();
                    crc.update(buffer, position, 13);
                    crc.update(buffer, position + HEADER, payloadLength);
                    if ((int) crc.getValue() != readInt(buffer, position + 13))
                        throw new ProtocolException("crc");

                    frames.add(new Frame(buffer[position + 3], buffer[position + 4],
                            readInt(buffer, position + 5),
                            Arrays.copyOfRange(buffer, position + HEADER, position + frameLength)));
                    position += frameLength;
                }
            } catch (ProtocolException malformed) {
                size = 0;
                throw malformed;
            }

            if (position > 0) {
                System.arraycopy(buffer, position, buffer, 0, size - position);
                size -= position;
            }
            return frames;
        }

        public synchronized void reset() { size = 0; }

        private void ensure(int required) throws ProtocolException {
            if (required > MAX_BUFFERED) throw new ProtocolException("decoder buffer limit");
            if (required <= buffer.length) return;
            int capacity = buffer.length;
            while (capacity < required) {
                int doubled = capacity > MAX_BUFFERED / 2 ? MAX_BUFFERED : capacity * 2;
                if (doubled <= capacity) throw new ProtocolException("decoder buffer overflow");
                capacity = doubled;
            }
            buffer = Arrays.copyOf(buffer, capacity);
        }
    }

    /** chunk is user payload bytes; the fragment header consumes another 11 bytes. */
    public static List<Frame> fragment(byte type, int sequence, byte[] payload, int chunk) {
        if (payload == null) throw new IllegalArgumentException("payload == null");
        if (type == FRAG) throw new IllegalArgumentException("nested fragments are not supported");
        if (payload.length > MAX_REASSEMBLED) throw new IllegalArgumentException("message too big to fragment");
        if (chunk <= 0 || chunk > MAX_PAYLOAD - FRAG_OVERHEAD)
            throw new IllegalArgumentException("chunk");
        int total = Math.max(1, (int) (((long) payload.length + chunk - 1L) / chunk));
        if (total > 4096) throw new IllegalArgumentException("too many fragments");

        List<Frame> frames = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            int from = index * chunk;
            int to = Math.min(payload.length, from + chunk);
            ByteBuffer part = ByteBuffer.allocate(FRAG_OVERHEAD + (to - from));
            part.put(type).putInt(sequence).putShort((short) index).putShort((short) total).putShort((short) 0);
            part.put(payload, from, to - from);
            frames.add(new Frame(FRAG, (byte) 0, sequence, part.array()));
        }
        return frames;
    }

    public static final class Reassembler {
        private static final class Part {
            byte[][] parts;
            int received;
            byte type;
            long bytes;
            long lastTouch;
        }

        private final Map<Integer, Part> pending = new HashMap<>();
        private final Clock clock;
        private final long ttlMs, perAssemblyMax, globalMax;
        private final int maxPending;
        private long globalBytes;

        public Reassembler() {
            this(Clock.MONOTONIC, 30_000, 16, 4L * MAX_PAYLOAD, 16L * MAX_PAYLOAD);
        }

        public Reassembler(Clock clock, long ttlMs, int maxPending,
                           long perAssemblyMax, long globalMax) {
            if (clock == null || ttlMs < 1 || maxPending < 1 || perAssemblyMax < 1
                    || globalMax < perAssemblyMax || perAssemblyMax > Integer.MAX_VALUE || maxPending > 1024)
                throw new IllegalArgumentException("invalid reassembly limits");
            this.clock = clock;
            this.ttlMs = ttlMs;
            this.maxPending = maxPending;
            this.perAssemblyMax = perAssemblyMax;
            this.globalMax = globalMax;
        }

        public synchronized int pendingCount() { return pending.size(); }
        public synchronized long pendingBytes() { return globalBytes; }

        public synchronized Frame feed(Frame frame) throws ProtocolException {
            if (frame == null || frame.payload == null || frame.type != FRAG
                    || frame.payload.length < FRAG_OVERHEAD) throw new ProtocolException("frag");
            long now = clock.nowMs();
            evictExpired(now);

            ByteBuffer input = ByteBuffer.wrap(frame.payload);
            byte type = input.get();
            int sequence = input.getInt();
            int index = input.getShort() & 0xffff;
            int total = input.getShort() & 0xffff;
            int reserved = input.getShort() & 0xffff;
            if (reserved != 0) throw new ProtocolException("frag reserved bits");
            if (frame.seq != sequence) throw new ProtocolException("frag seq mismatch");
            if (type == FRAG || total == 0 || index >= total || total > 4096)
                throw new ProtocolException("frag range/type");

            int chunkLength = input.remaining();
            Part part = pending.get(sequence);
            if (part == null) {
                if (pending.size() >= maxPending) throw new ProtocolException("too many pending");
                part = new Part();
                part.parts = new byte[total][];
                part.type = type;
                part.lastTouch = now;
                pending.put(sequence, part);
            } else if (part.parts.length != total || part.type != type) {
                discard(sequence, part);
                throw new ProtocolException("frag mismatch");
            }

            part.lastTouch = now;
            byte[] previous = part.parts[index];
            if (previous != null) {
                if (previous.length != chunkLength || !matches(input, previous)) {
                    discard(sequence, part);
                    throw new ProtocolException("conflicting duplicate fragment");
                }
                return null;
            }
            if (chunkLength > perAssemblyMax - part.bytes)
                throw new ProtocolException("assembly too big");
            if (chunkLength > globalMax - globalBytes)
                throw new ProtocolException("global reassembly limit");

            byte[] chunk = new byte[chunkLength];
            input.get(chunk);
            part.parts[index] = chunk;
            part.bytes += chunkLength;
            globalBytes += chunkLength;
            part.received++;
            if (part.received < total) return null;

            pending.remove(sequence);
            globalBytes -= part.bytes;
            byte[] full = new byte[(int) part.bytes];
            int offset = 0;
            for (byte[] fragment : part.parts) {
                if (fragment == null) throw new ProtocolException("incomplete assembly invariant");
                System.arraycopy(fragment, 0, full, offset, fragment.length);
                offset += fragment.length;
            }
            return new Frame(type, (byte) 0, sequence, full);
        }

        private void evictExpired(long now) {
            Iterator<Map.Entry<Integer, Part>> iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                Part part = iterator.next().getValue();
                if (now >= part.lastTouch && now - part.lastTouch > ttlMs) {
                    globalBytes -= part.bytes;
                    iterator.remove();
                }
            }
        }

        private void discard(int sequence, Part part) {
            if (pending.remove(sequence, part)) globalBytes -= part.bytes;
        }

        private static boolean matches(ByteBuffer input, byte[] expected) {
            ByteBuffer duplicate = input.asReadOnlyBuffer();
            for (byte value : expected) if (duplicate.get() != value) return false;
            return true;
        }
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 255) << 24) | ((bytes[offset + 1] & 255) << 16)
                | ((bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
    }

    private FrameCodec() { }
}
