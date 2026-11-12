package org.telegram.tgnet;

import java.util.function.Consumer;

/** COMPILE-ONLY: сигнатуры сверены с tgnet/ConnectionsManager.java (9-аргументный sendRequest). */
public class ConnectionsManager {
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
