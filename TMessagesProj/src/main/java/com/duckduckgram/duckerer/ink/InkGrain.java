package com.duckduckgram.duckerer.ink;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

import com.duckduckgram.duckerer.config.DdgConfig;

import java.lang.ref.WeakReference;
import java.util.Random;

/**
 * mvs Ink Studio: зерно бумаги.
 *
 * <p>Дизайн-система просит 4–5 % зернистости поверх экрана — так, чтобы не мешать чтению.
 * Здесь это один прозрачный вид поверх окна, который заливает экран повторяющимся
 * шумовым тайлом. Пропускает касания, не участвует в доступности и сам отключается,
 * когда выбран уровень «Выкл.».
 *
 * <p>Шум двухтонный: тёмные и светлые крапинки в равных долях. Поэтому один и тот же
 * тайл читается как волокна бумаги в светлой теме (видны тёмные) и как тонкая пыль
 * в тёмной (видны светлые) — отдельных тайлов под темы не нужно.
 */
public final class InkGrain {

    public static final int OFF = 0;
    public static final int FINE = 1;
    public static final int VISIBLE = 2;

    /** Максимальная прозрачность крапинки на каждом уровне. */
    private static final float[] STRENGTH = {0f, 0.05f, 0.09f};

    private static final int TILE = 128;
    private static final long SEED = 0x4D5653L; // «MVS»

    private static Bitmap tile;
    private static WeakReference<GrainView> current;

    private InkGrain() {
    }

    /** Выбранный уровень зерна. Всё, что вне диапазона, читается как «тонкое». */
    public static int level() {
        DdgConfig.loadConfig(false);
        int value = DdgConfig.inkGrain.Int();
        return value >= OFF && value <= VISIBLE ? value : FINE;
    }

    public static float strength() {
        return STRENGTH[level()];
    }

    /** Создаёт оверлей и запоминает его: так настройка применяется без перезапуска. */
    public static View createView(Context context) {
        GrainView view = new GrainView(context);
        current = new WeakReference<>(view);
        return view;
    }

    /** Применить текущий уровень к живому оверлею. */
    public static void refresh() {
        GrainView view = current == null ? null : current.get();
        if (view != null) {
            view.apply();
        }
    }

    private static synchronized Bitmap tile() {
        if (tile != null) {
            return tile;
        }
        Random random = new Random(SEED);
        int[] pixels = new int[TILE * TILE];
        for (int i = 0; i < pixels.length; i++) {
            int roll = random.nextInt(100);
            if (roll < 72) {
                int alpha = 60 + random.nextInt(196);
                int tone = roll < 36 ? 0 : 255;
                pixels[i] = Color.argb(alpha, tone, tone, tone);
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, TILE, 0, 0, TILE, TILE);
        tile = bitmap;
        return bitmap;
    }

    private static final class GrainView extends View {

        private final Paint paint = new Paint();

        GrainView(Context context) {
            super(context);
            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

            BitmapShader shader = new BitmapShader(tile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            // Крапинка ≈ 0,75 dp: на плотных экранах шум остаётся зерном, а не пылью.
            float scale = Math.max(1f, context.getResources().getDisplayMetrics().density * 0.75f);
            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            shader.setLocalMatrix(matrix);
            paint.setShader(shader);
            paint.setFilterBitmap(false);
            apply();
        }

        void apply() {
            float strength = strength();
            // View.* указан явно: у внешнего класса есть свой VISIBLE (уровень зерна).
            if (strength <= 0f) {
                setVisibility(View.GONE);
                return;
            }
            paint.setAlpha(Math.round(strength * 255f));
            setVisibility(View.VISIBLE);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        }
    }
}
