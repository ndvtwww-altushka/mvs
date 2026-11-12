package org.telegram.tgnet;

import java.nio.ByteBuffer;

/** COMPILE-ONLY: поля и методы, которые трогает адаптер. */
public class NativeByteBuffer implements OutputSerializedData {
    public ByteBuffer buffer = ByteBuffer.allocate(0);
    public NativeByteBuffer(int size) throws Exception {}
    public int position() { return buffer.position(); }
    public void reuse() {}
    public void writeInt32(int value) {}
    public void writeInt64(long value) {}
    public void writeBool(boolean value) {}
    public void writeBytes(byte[] b) {}
    public void writeBytes(byte[] b, int offset, int count) {}
    public void writeByte(int i) {}
    public void writeByte(byte b) {}
    public void writeString(String s) {}
    public void writeByteArray(byte[] b, int offset, int count) {}
    public void writeByteArray(byte[] b) {}
    public void writeFloat(float f) {}
    public void writeDouble(double d) {}
    public void writeByteBuffer(NativeByteBuffer buffer) {}
    public void skip(int count) {}
    public int getPosition() { return buffer.position(); }
}
