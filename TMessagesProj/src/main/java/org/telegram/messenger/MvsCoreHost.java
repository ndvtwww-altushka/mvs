package org.telegram.messenger;

import app.mvs.core.net.MvsNetCore;
import app.mvs.core.util.Clock;
import org.telegram.tgnet.MtprotoAdapter;

/** Optional future integration entry point: one lazily-created core per Telegram account. */
public final class MvsCoreHost {
    private static final MvsNetCore[] CORES = new MvsNetCore[UserConfig.MAX_ACCOUNT_COUNT];

    public static synchronized MvsNetCore get(int account) {
        checkAccount(account);
        if (CORES[account] == null) {
            CORES[account] = new MvsNetCore(new MtprotoAdapter(account), new MvsNetCore.Config(), Clock.MONOTONIC);
        }
        return CORES[account];
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
