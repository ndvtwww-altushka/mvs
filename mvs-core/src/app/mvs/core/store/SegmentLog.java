package app.mvs.core.store;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.zip.CRC32;

/** Append-only CRC-protected journal with conservative tail recovery and safe compaction. */
public final class SegmentLog {
    public static final int MAX_DATA = 1024 * 1024;
    private static final long MAX_BATCH_BYTES = 64L * 1024 * 1024;

    public static final class Rec {
        public final int type;
        public final byte[] data;
        public Rec(int type, byte[] data) {
            if (type < 0 || type > 255 || data == null) throw new IllegalArgumentException("invalid record");
            this.type = type;
            this.data = data;
        }
    }

    public interface Visitor { void visit(int type, byte[] data) throws Exception; }

    private final File file;
    private RandomAccessFile raf;
    private boolean closed;
    public long recoveredRecords;
    public long truncatedBytes;

    public SegmentLog(File file) throws IOException {
        if (file == null) throw new IllegalArgumentException("file == null");
        this.file = file;
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())
            throw new IOException("cannot create log directory: " + parent);
        recoverRewriteArtifacts();
        this.raf = new RandomAccessFile(file, "rw");
        recoverTail();
    }

    private void recoverRewriteArtifacts() throws IOException {
        File temporary = new File(file.getPath() + ".tmp");
        File backup = new File(file.getPath() + ".old");
        if (!file.exists() && backup.exists()) move(backup, file);
        if (file.exists() && backup.exists() && !backup.delete())
            throw new IOException("cannot remove stale backup: " + backup);
        if (temporary.exists() && !temporary.delete())
            throw new IOException("cannot remove incomplete rewrite: " + temporary);
    }

    private void recoverTail() throws IOException {
        long length = raf.length();
        long position = 0;
        byte[] header = new byte[8];
        while (position < length) {
            if (length - position < header.length) break;
            raf.seek(position);
            raf.readFully(header);
            int recordLength = readInt(header, 0);
            int expectedCrc = readInt(header, 4);
            if (recordLength < 1 || recordLength > MAX_DATA + 1
                    || length - position < 8L + recordLength) break;
            byte[] body = new byte[recordLength];
            raf.readFully(body);
            if (crc(body) != expectedCrc) break;
            position += 8L + recordLength;
            recoveredRecords++;
        }
        if (position < length) {
            truncatedBytes = length - position;
            raf.setLength(position);
            raf.getFD().sync();
        }
    }

    public synchronized void append(List<Rec> records, boolean force) throws IOException {
        ensureOpen();
        if (records == null) throw new IllegalArgumentException("records == null");
        long totalBytes = 0;
        for (Rec record : records) {
            validate(record);
            totalBytes += 9L + record.data.length;
            if (totalBytes > MAX_BATCH_BYTES) throw new IOException("batch too large");
        }
        if (totalBytes == 0) return;
        ByteBuffer buffer = ByteBuffer.allocate((int) totalBytes);
        for (Rec record : records) {
            byte[] body = new byte[record.data.length + 1];
            body[0] = (byte) record.type;
            System.arraycopy(record.data, 0, body, 1, record.data.length);
            buffer.putInt(body.length).putInt(crc(body)).put(body);
        }
        buffer.flip();
        FileChannel channel = raf.getChannel();
        channel.position(channel.size());
        while (buffer.hasRemaining()) channel.write(buffer);
        if (force) channel.force(false);
    }

    public synchronized void scan(Visitor visitor) throws Exception {
        ensureOpen();
        if (visitor == null) throw new IllegalArgumentException("visitor == null");
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            while (true) {
                int recordLength;
                try { recordLength = input.readInt(); }
                catch (EOFException end) { return; }
                int expectedCrc = input.readInt();
                if (recordLength < 1 || recordLength > MAX_DATA + 1)
                    throw new IOException("invalid record length in sealed log");
                byte[] body = new byte[recordLength];
                input.readFully(body);
                if (crc(body) != expectedCrc) throw new IOException("crc mismatch in sealed log");
                visitor.visit(body[0] & 255, java.util.Arrays.copyOfRange(body, 1, body.length));
            }
        }
    }

    public synchronized void rewrite(List<Rec> liveRecords) throws IOException {
        ensureOpen();
        if (liveRecords == null) throw new IllegalArgumentException("records == null");
        long totalBytes = 0;
        for (Rec record : liveRecords) {
            validate(record);
            totalBytes += 9L + record.data.length;
            if (totalBytes > MAX_BATCH_BYTES) throw new IOException("rewrite too large");
        }

        File temporary = new File(file.getPath() + ".tmp");
        File backup = new File(file.getPath() + ".old");
        try (RandomAccessFile output = new RandomAccessFile(temporary, "rw")) {
            output.setLength(0);
            for (Rec record : liveRecords) {
                byte[] body = new byte[record.data.length + 1];
                body[0] = (byte) record.type;
                System.arraycopy(record.data, 0, body, 1, record.data.length);
                ByteBuffer entry = ByteBuffer.allocate(9 + record.data.length);
                entry.putInt(body.length).putInt(crc(body)).put(body);
                output.write(entry.array());
            }
            output.getFD().sync();
        }

        raf.close();
        IOException rewriteFailure = null;
        try {
            if (backup.exists() && !backup.delete()) throw new IOException("cannot remove old backup");
            move(file, backup);
            move(temporary, file);
            // The new journal is installed. A leftover .old is harmless and is
            // removed/recovered on the next open; do not report a committed rewrite as failed.
            if (backup.exists()) backup.delete();
        } catch (IOException failure) {
            rewriteFailure = failure;
        }

        // If installation failed after the original was renamed, restore it before reopening.
        if (!file.exists() && backup.exists()) {
            try { move(backup, file); }
            catch (IOException restoreFailure) {
                if (rewriteFailure != null) rewriteFailure.addSuppressed(restoreFailure);
                else rewriteFailure = restoreFailure;
            }
        }
        if (!file.exists()) {
            raf = null;
            IOException lost = new IOException("journal missing after compaction; backup is " + backup);
            if (rewriteFailure != null) lost.addSuppressed(rewriteFailure);
            throw lost;
        }
        raf = new RandomAccessFile(file, "rw");
        if (rewriteFailure != null) throw rewriteFailure;
    }

    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        raf.close();
    }

    private void ensureOpen() throws IOException {
        if (closed || raf == null) throw new IOException("segment log is closed");
    }

    private static void validate(Rec record) throws IOException {
        if (record == null || record.data == null) throw new IOException("null record");
        if (record.type < 0 || record.type > 255 || record.data.length > MAX_DATA)
            throw new IOException("invalid or oversized record");
    }

    private static int crc(byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(body);
        return (int) crc.getValue();
    }

    /** Same-directory rename keeps the journal swap atomic on the app's filesystem. */
    private static void move(File source, File target) throws IOException {
        if (target.exists() && !target.delete()) throw new IOException("cannot replace " + target);
        if (!source.renameTo(target)) throw new IOException("rename failed: " + source + " -> " + target);
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 255) << 24) | ((bytes[offset + 1] & 255) << 16)
                | ((bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
    }
}
