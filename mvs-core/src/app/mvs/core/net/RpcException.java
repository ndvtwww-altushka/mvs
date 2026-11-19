package app.mvs.core.net;

public final class RpcException extends Exception {
    public final int code; public final long retryAfterMs;
    public RpcException(int code, String message, long retryAfterMs) {
        super(message); this.code = code; this.retryAfterMs = retryAfterMs;
    }
}
