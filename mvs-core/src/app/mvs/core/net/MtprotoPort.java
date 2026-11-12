package app.mvs.core.net;

import java.util.function.Consumer;

/** Port to the existing MTProto engine. The core never implements Telegram crypto. */
public interface MtprotoPort {
    Handle send(int dc, String method, Object tlRequest, Callback cb);

    default void setUpdatesListener(Consumer<Object> sink) { }

    interface Callback {
        void onReply(Object tlResponse);
        void onError(int code, String message, long retryAfterMs);
    }

    interface Handle { void cancel(); }
}
