package org.telegram.messenger;

import app.mvs.core.net.CallScheduler;
import app.mvs.core.net.MvsNetCore;
import app.mvs.core.net.RpcException;
import app.mvs.core.util.Clock;
import org.telegram.tgnet.MtprotoAdapter;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * One lazily created core per Telegram account, and the only place where this client hands a
 * request to it. Cryptography, the physical connection and DC migration stay in
 * {@code ConnectionsManager}: the core decides which data centre to ask, not how to talk to it.
 */
public final class MvsCoreHost {

    /** Outcome of {@link #sendWithFailover}; {@code response} is null whenever the code is not 0. */
    public interface Callback {
        void onResult(TLObject response, int errorCode, String errorText);
    }

    /** Logcat tag. The core is otherwise invisible unless something goes wrong. */
    private static final String TAG = "mvs-core";

    private static final MvsNetCore[] CORES = new MvsNetCore[UserConfig.MAX_ACCOUNT_COUNT];

    public static synchronized MvsNetCore get(int account) {
        checkAccount(account);
        if (CORES[account] == null) {
            MvsNetCore.Config config = new MvsNetCore.Config();
            // The core must not retry by itself: a repeat would replay a TLObject whose native
            // memory is already gone. Every attempt gets a fresh object from the host instead.
            config.maxRetries = 0;
            MvsNetCore core = new MvsNetCore(new MtprotoAdapter(account, true), config, Clock.MONOTONIC);
            // Nothing consumes updates yet; drain them so the queue cannot grow while idle.
            core.addUpdateListener(update -> { });
            CORES[account] = core;
        }
        return CORES[account];
    }

    /**
     * Sends one request through the core: home data centre first, then the remaining centres
     * while the failure looks like a transport problem. {@code factory} is called once per
     * attempt and must return a brand new request object.
     *
     * @return false when the core refused the request before any attempt, so the caller can
     *     fall back to {@code ConnectionsManager#sendRequest}
     */
    public static boolean sendWithFailover(int account, String method,
                                           Supplier<TLObject> factory, int homeDc,
                                           long perAttemptTimeoutMs, Callback callback) {
        if (factory == null || callback == null)
            throw new IllegalArgumentException("factory and callback are required");
        final MvsNetCore core;
        try {
            core = get(account);
        } catch (RuntimeException failure) {
            FileLog.e(failure);
            return false;
        }
        if (!core.isRunning()) return false;
        CallScheduler.Options options = new CallScheduler.Options();
        options.timeoutMs = perAttemptTimeoutMs;
        options.dc = homeDc >= 1 ? homeDc : CallScheduler.Options.AUTO_DC;
        android.util.Log.d(TAG, "send " + method + " home=" + options.dc
                + " plan=" + java.util.Arrays.toString(core.failoverPlan(options.dc)));
        core.sendWithFailover(method, factory::get, options).whenComplete((value, error) -> {
            Throwable cause = unwrap(error);
            if (cause != null) {
                android.util.Log.d(TAG, "fail " + method + " code=" + errorCode(cause) + " " + describe(cause));
                callback.onResult(null, errorCode(cause), describe(cause));
                return;
            }
            TLObject response;
            try {
                response = parse(factory.get(), value);
            } catch (RuntimeException failure) {
                FileLog.e(failure);
                callback.onResult(null, -1, describe(failure));
                return;
            }
            android.util.Log.d(TAG, "reply " + method + " " + response.getClass().getSimpleName());
            callback.onResult(response, 0, null);
        });
        return true;
    }

    /** Rebuilds the reply from the copy the adapter made inside the native callback. */
    private static TLObject parse(TLObject shell, Object payload) {
        if (!(payload instanceof byte[]))
            throw new IllegalStateException("reply is not a byte array");
        if (shell == null) throw new IllegalStateException("factory returned null");
        SerializedData data = new SerializedData((byte[]) payload);
        int constructor = data.readInt32(false);
        TLObject response = shell.deserializeResponse(data, constructor, false);
        if (response == null)
            throw new IllegalStateException("unexpected response 0x" + Integer.toHexString(constructor));
        return response;
    }

    private static int errorCode(Throwable cause) {
        if (cause instanceof RpcException) return ((RpcException) cause).code;
        return -1;
    }

    private static String describe(Throwable cause) {
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message;
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error;
    }

    public static synchronized void shutdown(int account) {
        checkAccount(account);
        MvsNetCore core = CORES[account];
        CORES[account] = null;
        if (core != null) core.shutdown(2_000);
    }

    public static synchronized void shutdownAll() {
        for (int account = 0; account < CORES.length; account++) {
            MvsNetCore core = CORES[account];
            CORES[account] = null;
            if (core != null) core.shutdown(2_000);
        }
    }

    private static void checkAccount(int account) {
        if (account < 0 || account >= CORES.length)
            throw new IllegalArgumentException("invalid account index: " + account);
    }

    private MvsCoreHost() { }
}
