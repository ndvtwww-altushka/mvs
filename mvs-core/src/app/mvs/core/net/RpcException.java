package app.mvs.core.net;

public final class RpcException extends Exception {
    private static final long serialVersionUID = 1L;
    public final int code;
    public final long retryAfterMs;

    public RpcException(int code, String message, long retryAfterMs) {
        super(message == null ? "RPC error " + code : message);
        this.code = code;
        this.retryAfterMs = Math.max(0, retryAfterMs);
    }
}
