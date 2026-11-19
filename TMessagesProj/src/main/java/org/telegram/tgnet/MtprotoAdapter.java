package org.telegram.tgnet;

import app.mvs.core.net.MtprotoPort;
import org.telegram.messenger.FileLog;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Bridge to the existing Telegram MTProto engine; cryptography remains in ConnectionsManager. */
public final class MtprotoAdapter implements MtprotoPort {
    private final int account;
    private final ConnectionsManager connections;
    private final boolean copyReplies;
    private volatile Consumer<Object> updatesSink;

    public MtprotoAdapter(int account) {
        this(account, false);
    }

    /**
     * @param copyReplies serialize every reply into a byte array inside the native callback.
     *     The TLObject handed to that callback is freed the moment it returns, so any consumer
     *     that finishes on another thread (the core completes futures) needs a copy first.
     */
    public MtprotoAdapter(int account, boolean copyReplies) {
        if (account < 0 || account >= org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT)
            throw new IllegalArgumentException("invalid account index: " + account);
        this.account = account;
        this.connections = ConnectionsManager.getInstance(account);
        this.copyReplies = copyReplies;
    }

    @Override public Handle send(int dc, String method, Object tlRequest, Callback callback) {
        if (callback == null) throw new IllegalArgumentException("callback == null");
        if (!(tlRequest instanceof TLObject)) {
            callback.onError(-1, "MtprotoAdapter: payload is not TLObject", 0);
            return () -> { };
        }
        TLObject request = (TLObject) tlRequest;
        int datacenterId = dc > 0 ? dc : ConnectionsManager.DEFAULT_DATACENTER_ID;
        int flags = ConnectionsManager.RequestFlagFailOnServerErrors
                | ConnectionsManager.RequestFlagDoNotWaitFloodWait;
        AtomicBoolean cancelled = new AtomicBoolean();
        int requestToken = connections.sendRequest(request, (response, error) -> {
            if (cancelled.get()) return;
            if (error != null) callback.onError(error.code, error.text, floodWaitMs(error.text));
            else if (copyReplies) callback.onReply(toBytes(response)); // null means an empty reply
            else callback.onReply(response); // Response TLObjects are valid only during this callback.
        }, null, null, null, flags, datacenterId, ConnectionsManager.ConnectionTypeGeneric, true);
        return () -> {
            if (cancelled.compareAndSet(false, true)) connections.cancelRequest(requestToken, true);
        };
    }

    @Override public void setUpdatesListener(Consumer<Object> sink) {
        updatesSink = sink;
        if (sink == null) {
            ConnectionsManager.mvsSetUpdatesObserver(account, null);
            return;
        }
        ConnectionsManager.mvsSetUpdatesObserver(account, updates -> {
            Consumer<Object> current = updatesSink;
            if (current == null || updates == null) return;
            byte[] copy = toBytes(updates); // Must copy before ConnectionsManager frees the TLObject.
            if (copy != null) current.accept(copy);
        });
    }

    private static byte[] toBytes(TLObject object) {
        NativeByteBuffer buffer = null;
        try {
            buffer = new NativeByteBuffer(object.getObjectSize());
            object.serializeToStream(buffer);
            byte[] output = new byte[buffer.position()];
            buffer.buffer.position(0);
            buffer.buffer.get(output);
            return output;
        } catch (Throwable failure) {
            if (failure instanceof ThreadDeath) throw (ThreadDeath) failure;
            if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
            FileLog.e(failure);
            return null;
        } finally {
            if (buffer != null) buffer.reuse();
        }
    }

    private static long floodWaitMs(String message) {
        if (message == null || !message.startsWith("FLOOD_WAIT_")) return 0;
        try {
            long seconds = Long.parseLong(message.substring("FLOOD_WAIT_".length()));
            if (seconds <= 0) return 0;
            return seconds > Long.MAX_VALUE / 1000L ? Long.MAX_VALUE : seconds * 1000L;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
