package com.duckduckgram.duckerer.ink;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.RadioButton;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/**
 * mvs Ink Studio: карточка «Чернила» с рядом образцов цвета.
 *
 * <p>Карточку и заголовок рисует сама ячейка: у нестандартных ячеек список не рисует
 * карточку-секцию, а заголовок отдельной строкой оказался бы своей карточкой впритык
 * к образцам. Радиус и поля берутся из «карточек-секций» внешнего вида, поэтому
 * карточка выглядит как соседние; при нулевом радиусе рисуется плоская полоса, как в стоке.
 *
 * <p>Каждый образец — кружок цвета акцента с подписью; выбранный обведён и отмечен
 * галочкой. Выбор сразу перекрашивает приложение. Для экранного диктора образцы —
 * переключатели: у каждого есть название, роль «радиокнопка» и состояние «выбрано».
 */
public final class InkSwatchRow extends LinearLayout implements NotificationCenter.NotificationCenterDelegate {

    public interface Listener {
        void onSelected(int index);
    }

    private static final int CARD_TOP_DP = 4;
    private static final int HEADER_DP = 40;

    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint headerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cardRect = new RectF();
    private final Swatch[] swatches = new Swatch[InkAccent.COUNT];
    private Listener listener;

    public InkSwatchRow(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setWillNotDraw(false);
        // Сверху оставлено место под заголовок, который рисуется в onDraw.
        setPadding(0, dp(CARD_TOP_DP + HEADER_DP), 0, dp(8));

        LinearLayout strip = new LinearLayout(context);
        strip.setOrientation(HORIZONTAL);
        strip.setPadding(dp(22), 0, dp(22), 0);
        for (int i = 0; i < InkAccent.COUNT; i++) {
            swatches[i] = new Swatch(context, i);
            strip.addView(swatches[i], new LinearLayout.LayoutParams(0, dp(92), 1f));
        }
        addView(strip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Перерисовать карточку и все образцы: после выбора и после смены темы. */
    public void refresh() {
        invalidate();
        // Дочерние виды перерисовываются только по собственному invalidate().
        for (Swatch swatch : swatches) {
            swatch.invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme) {
            refresh();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float top = dp(CARD_TOP_DP);
        final float width = getWidth();

        InkCard.draw(canvas, cardPaint, cardRect, getWidth(), getHeight(), top);

        // Заголовок — тем же стилем, что у заголовков секций списка.
        headerPaint.setTypeface(AndroidUtilities.bold());
        headerPaint.setTextSize(dp(15));
        headerPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        String title = getString(R.string.InkStudioInkHeader);
        float baseline = top + dp(27);
        if (LocaleController.isRTL) {
            headerPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(title, width - dp(21), baseline, headerPaint);
        } else {
            headerPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(title, dp(21), baseline, headerPaint);
        }
    }

    private final class Swatch extends View {

        private final int index;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path check = new Path();

        Swatch(Context context, int index) {
            super(context);
            this.index = index;
            setContentDescription(getString(InkAccent.nameRes(index)));
            setOnClickListener(v -> {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                InkAccent.select(index);
                refresh();
                if (listener != null) {
                    listener.onSelected(index);
                }
            });
        }

        private boolean selected() {
            return InkAccent.index() == index;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(RadioButton.class.getName());
            info.setCheckable(true);
            info.setChecked(selected());
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final boolean dark = InkAccent.isDark();
            final boolean selected = selected();
            float cx = getWidth() / 2f;
            float cy = dp(32);

            // Кружок цвета акцента; у графита на тёмном фоне заливка кремовая.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(InkAccent.swatch(index, dark));
            canvas.drawCircle(cx, cy, dp(19), paint);

            // Тонкая кромка, чтобы кремовый и графитовый кружки не сливались с фоном.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(Theme.getColor(Theme.key_divider));
            canvas.drawCircle(cx, cy, dp(19), paint);

            if (selected) {
                paint.setStrokeWidth(dp(2));
                paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                canvas.drawCircle(cx, cy, dp(25), paint);

                paint.setStrokeWidth(dp(2.2f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(InkAccent.swatchCheck(index, dark));
                check.reset();
                check.moveTo(cx - dp(7), cy + dp(0.5f));
                check.lineTo(cx - dp(2), cy + dp(5.5f));
                check.lineTo(cx + dp(7.5f), cy - dp(5));
                canvas.drawPath(check, paint);
                paint.setStrokeCap(Paint.Cap.BUTT);
            }

            label.setTypeface(selected ? AndroidUtilities.bold() : AndroidUtilities.regular());
            label.setTextSize(dp(12));
            label.setTextAlign(Paint.Align.CENTER);
            label.setColor(Theme.getColor(selected
                    ? Theme.key_windowBackgroundWhiteBlackText
                    : Theme.key_windowBackgroundWhiteGrayText2));
            CharSequence name = TextUtils.ellipsize(getString(InkAccent.nameRes(index)), label,
                    getWidth() - dp(4), TextUtils.TruncateAt.END);
            canvas.drawText(name, 0, name.length(), cx, dp(76), label);
        }
    }
}
