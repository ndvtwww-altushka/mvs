package org.telegram.tgnet;
public interface OutputSerializedData {
    void writeInt32(int value); void writeInt64(long value); void writeBool(boolean value);
    void writeBytes(byte[] bytes); void writeBytes(byte[] bytes, int offset, int count);
    void writeByte(int value); void writeByte(byte value); void writeString(String value);
    void writeByteArray(byte[] bytes, int offset, int count); void writeByteArray(byte[] bytes);
    void writeFloat(float value); void writeDouble(double value);
    void writeByteBuffer(NativeByteBuffer buffer); void skip(int count); int getPosition();
}
