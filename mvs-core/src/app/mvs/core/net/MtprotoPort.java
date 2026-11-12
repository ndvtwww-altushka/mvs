package app.mvs.core.net;

import java.util.function.Consumer;

/** Порт к движку MTProto (адаптер -> ConnectionsManager). Payload непрозрачен для ядра:
 *  на JVM - byte[], на Android - TLObject. Криптография остаётся в нативном движке. */
public interface MtprotoPort {
    Handle send(int dc, String method, Object tlRequest, Callback cb);
    default void setUpdatesListener(Consumer<Object> sink) {}
    interface Callback {
        void onReply(Object tlResponse);
        void onError(int code, String message, long retryAfterMs);
    }
    interface Handle { void cancel(); }
}
