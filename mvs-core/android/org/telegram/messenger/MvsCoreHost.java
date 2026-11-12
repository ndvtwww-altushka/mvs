package org.telegram.messenger;

import app.mvs.core.net.MvsNetCore;
import app.mvs.core.util.Clock;
import org.telegram.tgnet.MtprotoAdapter;

/**
 * Ядро mvs на аккаунт. Ленивая инициализация.
 * Перевод существующего трафика приложения на ядро — отдельный шаг, здесь его нет.
 */
public final class MvsCoreHost {

    /* Не подключён к сборке приложения: точка входа для будущей интеграции. */

    private static final MvsNetCore[] CORES = new MvsNetCore[UserConfig.MAX_ACCOUNT_COUNT];

    public static synchronized MvsNetCore get(int account) {
        if (CORES[account] == null) {
            CORES[account] = new MvsNetCore(new MtprotoAdapter(account), new MvsNetCore.Config(), Clock.SYSTEM);
        }
        return CORES[account];
    }

    private MvsCoreHost() {}
}
