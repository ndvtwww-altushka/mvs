package com.duckduckgram.duckerer.ink;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import app.exteraless.appearance.AppearanceConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * mvs Ink Studio: фон карточки для нестандартных ячеек.
 *
 * <p>Список настроек рисует карточку-секцию только за обычными строками; у нестандартных
 * ячеек (датчик, образцы) карточки нет, и без этого помощника они выглядели бы
 * полосой на всю ширину среди скруглённых соседей. Поля и радиус берутся из тех же
 * настроек внешнего вида, что и у списка, поэтому карточка выглядит как соседние.
 * При нулевом радиусе рисуется плоская полоса, как в стоке.
 */
final class InkCard {

    private InkCard() {
    }

    /**
     * @param top верхняя граница карточки внутри ячейки, px
     */
    static void draw(Canvas canvas, Paint paint, RectF rect, int width, int height, float top) {
        final int radius = AppearanceConfig.sectionRadius();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        if (radius <= 0) {
            rect.set(0, top, width, height);
            canvas.drawRect(rect, paint);
            return;
        }
        final float corner = AndroidUtilities.dp(radius);
        final float inset = AndroidUtilities.dp(12);
        rect.set(inset, top, width - inset, height);
        canvas.drawRoundRect(rect, corner, corner, paint);

        // Волосяная кромка вместо тени: так карточки выглядят в Ink.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(AndroidUtilities.dp(1));
        paint.setColor(Theme.getColor(Theme.key_divider));
        canvas.drawRoundRect(rect, corner, corner, paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
