package app.mvs.core.store;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.zip.CRC32;

public final class SegmentLog {
    public static final int MAX_DATA = 1024 * 1024;
    public static final class Rec {
        public final int type; public final byte[] data;
        public Rec(int type, byte[] data) { this.type = type; this.data = data; }
    }
    public interface Visitor { void visit(int type, byte[] data) throws Exception; }
    private final File file; private RandomAccessFile raf;
    public long recoveredRecords, truncatedBytes;

    public SegmentLog(File file) throws IOException {
        this.file = file;
        File tmp = new File(file.getPath() + ".tmp"), old = new File(file.getPath() + ".old");
        if (tmp.exists()) tmp.delete();
        if (old.exists()) { if (!file.exists()) old.renameTo(file); else old.delete(); }
        raf = new RandomAccessFile(file, "rw");
        recover();
    }

    private void recover() throws IOException {
        long len = raf.length(), pos = 0;
        byte[] hdr = new byte[8];
        while (pos < len) {
            if (len - pos < 8) break;
            raf.seek(pos); raf.readFully(hdr);
            int reclen = be(hdr, 0), crc = be(hdr, 4);
            if (reclen < 1 || reclen > MAX_DATA + 1 || len - pos < 8 + reclen) break;
            byte[] body = new byte[reclen];
            raf.readFully(body);
            CRC32 c = new CRC32(); c.update(body);
            if ((int)c.getValue() != crc) break;
            pos += 8 + reclen; recoveredRecords++;
        }
        if (pos < len) { truncatedBytes = len - pos; raf.setLength(pos); raf.getFD().sync(); }
    }

    public synchronized void append(List<Rec> recs, boolean force) throws IOException {
        int total = 0;
        for (Rec r : recs) {
            if (r.data.length > MAX_DATA) throw new IOException("record too big");
            total += 9 + r.data.length;
        }
        ByteBuffer b = ByteBuffer.allocate(total);
        for (Rec r : recs) {
            CRC32 c = new CRC32(); c.update((byte)r.type); c.update(r.data);
            b.putInt(1 + r.data.length).putInt((int)c.getValue()).put((byte)r.type).put(r.data);
        }
        b.flip();
        java.nio.channels.FileChannel ch = raf.getChannel();
        ch.position(ch.size());
        while (b.hasRemaining()) ch.write(b);
        if (force) ch.force(false);
    }

    public synchronized void scan(Visitor v) throws Exception {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            while (true) {
                int reclen;
                try { reclen = in.readInt(); } catch (EOFException e) { return; }
                int crc = in.readInt();
                byte[] body = new byte[reclen];
                in.readFully(body);
                CRC32 c = new CRC32(); c.update(body);
                if ((int)c.getValue() != crc) throw new IOException("crc mismatch in sealed log");
                v.visit(body[0] & 255, java.util.Arrays.copyOfRange(body, 1, body.length));
            }
        }
    }

    public synchronized void rewrite(List<Rec> live) throws IOException {
        for (Rec r : live) if (r.data.length > MAX_DATA) throw new IOException("record too big"); // 8c: до записи
        File tmp = new File(file.getPath() + ".tmp"), old = new File(file.getPath() + ".old");
        try (RandomAccessFile out = new RandomAccessFile(tmp, "rw")) {
            for (Rec r : live) {
                CRC32 c = new CRC32(); c.update((byte)r.type); c.update(r.data);
                ByteBuffer b = ByteBuffer.allocate(9 + r.data.length);
                b.putInt(1 + r.data.length).putInt((int)c.getValue()).put((byte)r.type).put(r.data);
                out.write(b.array());
            }
            out.getFD().sync();
        }
        raf.close();
        if (!file.renameTo(old)) { raf = new RandomAccessFile(file, "rw"); throw new IOException("rename old"); }
        if (!tmp.renameTo(file)) { old.renameTo(file); raf = new RandomAccessFile(file, "rw"); throw new IOException("rename tmp"); }
        raf = new RandomAccessFile(file, "rw");
        old.delete();
    }

    public synchronized void close() throws IOException { raf.close(); }
    private static int be(byte[] b, int o) { return ((b[o]&255)<<24)|((b[o+1]&255)<<16)|((b[o+2]&255)<<8)|(b[o+3]&255); }
}
