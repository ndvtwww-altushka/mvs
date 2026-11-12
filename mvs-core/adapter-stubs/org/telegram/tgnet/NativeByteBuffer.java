package org.telegram.tgnet;
import java.nio.ByteBuffer;
public class NativeByteBuffer implements OutputSerializedData {
    public ByteBuffer buffer = ByteBuffer.allocate(0);
    public NativeByteBuffer(int size) throws Exception { buffer = ByteBuffer.allocate(Math.max(0, size)); }
    public int position() { return buffer.position(); }
    public void reuse() { }
    public void writeInt32(int value) { buffer.putInt(value); }
    public void writeInt64(long value) { buffer.putLong(value); }
    public void writeBool(boolean value) { buffer.put((byte) (value ? 1 : 0)); }
    public void writeBytes(byte[] bytes) { buffer.put(bytes); }
    public void writeBytes(byte[] bytes, int offset, int count) { buffer.put(bytes, offset, count); }
    public void writeByte(int value) { buffer.put((byte) value); }
    public void writeByte(byte value) { buffer.put(value); }
    public void writeString(String value) { }
    public void writeByteArray(byte[] bytes, int offset, int count) { buffer.put(bytes, offset, count); }
    public void writeByteArray(byte[] bytes) { buffer.put(bytes); }
    public void writeFloat(float value) { buffer.putFloat(value); }
    public void writeDouble(double value) { buffer.putDouble(value); }
    public void writeByteBuffer(NativeByteBuffer value) { }
    public void skip(int count) { buffer.position(buffer.position() + count); }
    public int getPosition() { return buffer.position(); }
}
