package org.telegram.tgnet;

import app.mvs.core.net.MtprotoPort;
import org.telegram.messenger.FileLog;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Мост mvs-core -> нативный MTProto-движок. Криптография остаётся в ConnectionsManager.
 *
 * ВРЕМЯ ЖИЗНИ: ConnectionsManager вызывает freeResources() у ответа ПОСЛЕ возврата из
 * делегата. TLObject-ответ нельзя удерживать: потребитель читает его синхронно.
 * Апдейты ядро держит в очереди, поэтому здесь они копируются в byte[] до освобождения.
 *
 * dc пробрасывается в движок (native_sendRequest его получает), но политику выбора
 * и per-DC failover по-прежнему определяет движок — см. AUDIT 7b-c.
 */
public final class MtprotoAdapter implements MtprotoPort {

    private final int account;
    private final ConnectionsManager cm;
    private volatile Consumer<Object> updatesSink;

    public MtprotoAdapter(int account) {
        this.account = account;
        this.cm = ConnectionsManager.getInstance(account);
    }

    @Override
    public Handle send(int dc, String method, Object tlRequest, Callback cb) {
        if (!(tlRequest instanceof TLObject)) {
            cb.onError(-1, "MtprotoAdapter: payload is not TLObject", 0);
            return () -> {};
        }
        TLObject req = (TLObject) tlRequest;
        int datacenterId = dc > 0 ? dc : ConnectionsManager.DEFAULT_DATACENTER_ID;
        // Ошибки и FLOOD_WAIT отдаём ядру: ретраями управляет RetryInterceptor,
        // а не движок, который иначе молча ждёт.
        int flags = ConnectionsManager.RequestFlagFailOnServerErrors
                  | ConnectionsManager.RequestFlagDoNotWaitFloodWait;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final int token = cm.sendRequest(req, (response, error) -> {
            if (cancelled.get()) return;
            if (error != null) {
                cb.onError(error.code, error.text, floodWaitMs(error.text));
            } else {
                cb.onReply(response); // читать синхронно: после возврата объект освобождается
            }
        }, null, null, null, flags, datacenterId, ConnectionsManager.ConnectionTypeGeneric, true);
        return () -> {
            if (cancelled.compareAndSet(false, true)) {
                cm.cancelRequest(token, true);   // идемпотентно за счёт CAS
            }
        };
    }

    @Override
    public void setUpdatesListener(Consumer<Object> sink) {
        this.updatesSink = sink;
        if (sink == null) {
            ConnectionsManager.mvsSetUpdatesObserver(account, null);
        } else {
            ConnectionsManager.mvsSetUpdatesObserver(account, updates -> {
                Consumer<Object> s = updatesSink;
                if (s == null) return;
                byte[] copy = toBytes(updates);   // копия до freeResources
                if (copy != null) s.accept(copy);
            });
        }
    }

    private static byte[] toBytes(TLObject obj) {
        NativeByteBuffer buf = null;
        try {
            buf = new NativeByteBuffer(obj.getObjectSize());
            obj.serializeToStream(buf);
            byte[] out = new byte[buf.position()];
            buf.buffer.position(0);
            buf.buffer.get(out);
            return out;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        } finally {
            if (buf != null) buf.reuse();
        }
    }

    private static long floodWaitMs(String text) {
        if (text != null && text.startsWith("FLOOD_WAIT_")) {
            try { return Long.parseLong(text.substring("FLOOD_WAIT_".length())) * 1000L; }
            catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}
