package org.telegram.tgnet;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * COMPILE-ONLY контракты для CI: адаптер собирается без Android SDK.
 * Сигнатуры сверены с деревом core-audit (ConnectionsManager, TLObject,
 * NativeByteBuffer, OutputSerializedData, RequestDelegate).
 * Это не замена Android-сборке: авторитетная проверка — compileDebugJavaWithJavac.
 */

interface OutputSerializedData {
    void writeInt32(int value);
    void writeInt64(long value);
    void writeBool(boolean value);
    void writeBytes(byte[] b);
    void writeBytes(byte[] b, int offset, int count);
    void writeByte(int i);
    void writeByte(byte b);
    void writeString(String s);
    void writeByteArray(byte[] b, int offset, int count);
    void writeByteArray(byte[] b);
    void writeFloat(float f);
    void writeDouble(double d);
    void writeByteBuffer(NativeByteBuffer buffer);
    void skip(int count);
    int getPosition();
}

class TLObject {
    public void serializeToStream(OutputSerializedData stream) {}
    public int getObjectSize() { return 0; }
    public void freeResources() {}
}

class TLRPC {
    public static class TL_error { public int code; public String text; }
    public static class Updates extends TLObject {}
}

interface RequestDelegate { void run(TLObject response, TLRPC.TL_error error); }
interface RequestDelegateTimestamp { void run(int timestamp); }
interface QuickAckDelegate { void run(); }
interface WriteToSocketDelegate { void run(); }

class NativeByteBuffer implements OutputSerializedData {
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

class ConnectionsManager {
    public final static int DEFAULT_DATACENTER_ID = Integer.MAX_VALUE;
    public final static int ConnectionTypeGeneric = 1;
    public final static int RequestFlagFailOnServerErrors = 2;
    public final static int RequestFlagDoNotWaitFloodWait = 1024;

    private static final ConnectionsManager[] INSTANCES = new ConnectionsManager[10];

    public static ConnectionsManager getInstance(int num) {
        if (INSTANCES[num] == null) INSTANCES[num] = new ConnectionsManager();
        return INSTANCES[num];
    }

    public int sendRequest(TLObject object, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp,
                           QuickAckDelegate onQuickAck, WriteToSocketDelegate onWriteToSocket,
                           int flags, int datacenterId, int connectionType, boolean immediate) { return 0; }

    public void cancelRequest(int token, boolean notifyServer) {}

    public static void mvsSetUpdatesObserver(int account, Consumer<TLRPC.Updates> observer) {}
}
