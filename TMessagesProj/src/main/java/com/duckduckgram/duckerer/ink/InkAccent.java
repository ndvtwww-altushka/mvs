package com.duckduckgram.duckerer.ink;

import com.duckduckgram.duckerer.config.DdgConfig;

import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/**
 * mvs Ink Studio: «чернила» — пресеты единственного акцента приложения.
 *
 * <p>Дизайн-система mvs держит один акцент на всё приложение. Здесь он остаётся
 * одним, но его можно выбрать: терракота (по умолчанию, как в спеке), индиго, хвоя,
 * слива и графит. Выбор читает {@link org.telegram.ui.ActionBar.ThemeColors#applyInkPalette},
 * поэтому перекрашивается всё, что уже красится акцентом: счётчики, переключатели,
 * ссылки, курсор, кнопка отправки, исходящие пузыри.
 *
 * <p>Чего выбор не трогает: опасные действия (удалить, ошибка) остаются терракотовыми
 * при любых чернилах, статусы — шалфейными, аватарки — своей палитрой.
 *
 * <p>Таблица ниже — единственный источник цветов. Контраст каждой пары проверяет
 * {@code Tools/ink_contrast.py}: он читает эту таблицу прямо из файла.
 */
public final class InkAccent {

    public static final int TERRACOTTA = 0;
    public static final int INDIGO = 1;
    public static final int PINE = 2;
    public static final int PLUM = 3;
    public static final int GRAPHITE = 4;
    public static final int COUNT = 5;

    // Роли в строке таблицы. Тёмная тема — вторая половина строки.
    private static final int ACCENT = 0;
    private static final int SOFT = 1;
    private static final int INK = 2;
    private static final int ON_ACCENT = 3;
    private static final int DARK_OFFSET = 4;

    private static final int[] NAMES = {
            R.string.InkAccentTerracotta,
            R.string.InkAccentIndigo,
            R.string.InkAccentPine,
            R.string.InkAccentPlum,
            R.string.InkAccentGraphite,
    };

    // Строка: светлая тема {акцент, мягкий фон, акцент-для-текста, текст на акценте},
    //         тёмная тема  {акцент, мягкий фон, акцент-для-текста, текст на акценте}.
    // BEGIN-TABLE (читает Tools/ink_contrast.py)
    private static final int[][] TABLE = {
            /* terracotta */ {0xFFD9552F, 0xFFF4DACF, 0xFFB8431F, 0xFF151412, 0xFFFF7450, 0xFF3A2119, 0xFFFF9A7A, 0xFF1A0F0B},
            /* indigo     */ {0xFF4150C8, 0xFFDEE1F4, 0xFF3340B0, 0xFFF5F1E8, 0xFF8E9BFF, 0xFF20244A, 0xFFB3BCFF, 0xFF10122E},
            /* pine       */ {0xFF2C7655, 0xFFD8E8DE, 0xFF25684A, 0xFFF5F1E8, 0xFF6FCB9F, 0xFF1B3329, 0xFF93DDB8, 0xFF0E1F17},
            /* plum       */ {0xFF9A3F7E, 0xFFF0DBE8, 0xFF82336A, 0xFFF5F1E8, 0xFFDE8FC4, 0xFF3A2133, 0xFFEDAED6, 0xFF2A1224},
            /* graphite   */ {0xFF151412, 0xFFE9E3D6, 0xFF4A443B, 0xFFF5F1E8, 0xFFF0EBE1, 0xFF2B2A27, 0xFFD5CEC0, 0xFF131312},
    };
    // END-TABLE

    private InkAccent() {
    }

    /** Выбранные чернила. Всё, что не лежит в диапазоне, читается как терракота. */
    public static int index() {
        // Идемпотентно: к моменту первой покраски темы настройки могут быть ещё не подняты.
        DdgConfig.loadConfig(false);
        int value = DdgConfig.inkAccent.Int();
        return value >= 0 && value < COUNT ? value : TERRACOTTA;
    }

    public static int nameRes(int index) {
        return NAMES[clamp(index)];
    }

    private static int clamp(int index) {
        return index >= 0 && index < COUNT ? index : TERRACOTTA;
    }

    private static int color(int index, int role, boolean dark) {
        return TABLE[clamp(index)][role + (dark ? DARK_OFFSET : 0)];
    }

    // ---- Роли выбранных чернил: их читает ThemeColors.applyInkPalette ----

    public static int accent(boolean dark) {
        return color(index(), ACCENT, dark);
    }

    public static int soft(boolean dark) {
        return color(index(), SOFT, dark);
    }

    /** Акцент для текста и ссылок: темнее (светлая тема) или светлее (тёмная) самой заливки. */
    public static int ink(boolean dark) {
        return color(index(), INK, dark);
    }

    /** Цвет текста и значков поверх заливки акцентом. */
    public static int onAccent(boolean dark) {
        return color(index(), ON_ACCENT, dark);
    }

    /** Выделение текста: акцент с прозрачностью 20 %. */
    public static int accentAlpha(boolean dark) {
        return (accent(dark) & 0x00FFFFFF) | 0x33000000;
    }

    /**
     * Акцент на исходящем пузыре. Пузырь «перевёрнут» относительно темы (графитовый
     * в светлой, кремовый в тёмной), поэтому берётся текстовый акцент противоположной.
     */
    public static int outAccent(boolean dark) {
        return color(index(), INK, !dark);
    }

    // ---- Для экранов настройки ----

    /** Заливка кружка пресета в образце. */
    public static int swatch(int index, boolean dark) {
        return color(index, ACCENT, dark);
    }

    /** Цвет галочки на кружке пресета. */
    public static int swatchCheck(int index, boolean dark) {
        return color(index, ON_ACCENT, dark);
    }

    /** Терракота бренда: точка в знаке и словесном знаке не зависит от выбранных чернил. */
    public static int brand(boolean dark) {
        return color(TERRACOTTA, ACCENT, dark);
    }

    /** Тёмная ли сейчас тема. */
    public static boolean isDark() {
        return Theme.isCurrentThemeDark();
    }

    /** Выбрать чернила и сразу перекрасить приложение. */
    public static void select(int index) {
        if (index < 0 || index >= COUNT || index == index()) {
            return;
        }
        DdgConfig.inkAccent.setConfigInt(index);
        reapply();
    }

    /**
     * Перекрасить приложение прямо сейчас — тем же путём, которым Monet меняет акцент:
     * заново применить активную тему и сообщить окну, чтобы оно прогнало цвета по
     * открытым экранам.
     */
    public static void reapply() {
        Theme.ThemeInfo active = Theme.getActiveTheme();
        if (active == null) {
            return;
        }
        boolean night = Theme.isCurrentThemeNight();
        Theme.applyTheme(active, night);
        NotificationCenter.getGlobalInstance().postNotificationName(
                NotificationCenter.needSetDayNightTheme, active, night, null, -1);
    }
}
