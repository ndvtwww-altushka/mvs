package org.telegram.tgnet;
import java.util.function.Consumer;
/** Compile-only signature stub, not a replacement for Telegram's ConnectionsManager. */
public class ConnectionsManager {
    public static final int DEFAULT_DATACENTER_ID = Integer.MAX_VALUE;
    public static final int ConnectionTypeGeneric = 1;
    public static final int RequestFlagFailOnServerErrors = 2;
    public static final int RequestFlagDoNotWaitFloodWait = 1024;
    private static final ConnectionsManager[] INSTANCES = new ConnectionsManager[10];
    public static synchronized ConnectionsManager getInstance(int account) {
        if (account < 0 || account >= INSTANCES.length) throw new IllegalArgumentException("account");
        if (INSTANCES[account] == null) INSTANCES[account] = new ConnectionsManager();
        return INSTANCES[account];
    }
    public int sendRequest(TLObject object, RequestDelegate onComplete,
            RequestDelegateTimestamp onCompleteTimestamp, QuickAckDelegate onQuickAck,
            WriteToSocketDelegate onWriteToSocket, int flags, int datacenterId,
            int connectionType, boolean immediate) { return 0; }
    public void cancelRequest(int token, boolean notifyServer) { }
    public static void mvsSetUpdatesObserver(int account, Consumer<TLRPC.Updates> observer) { }
}
