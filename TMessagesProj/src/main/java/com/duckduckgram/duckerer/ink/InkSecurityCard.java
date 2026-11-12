package com.duckduckgram.duckerer.ink;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import java.util.List;

/**
 * mvs Ink Studio: карточка «Защита» — датчик и под ним список проверок.
 *
 * <p>Датчик показывает сводку, список — что именно проверено. Пока список пуст или
 * ничего не применимо, на дуге виден только след дорожки.
 */
public final class InkSecurityCard extends LinearLayout implements NotificationCenter.NotificationCenterDelegate {

    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cardRect = new RectF();
    private final InkGaugeView gauge;
    private final LinearLayout factsList;
    private List<InkSecurityFacts.Fact> facts;

    public InkSecurityCard(Context context) {
        super(context);
        setOrientation(VERTICAL);
        // Карточку рисует onDraw: сверху 4 dp воздуха, внутри поля 12 + 16 dp.
        setWillNotDraw(false);
        setPadding(dp(28), dp(24), dp(28), dp(12));

        gauge = new InkGaugeView(context);
        LinearLayout.LayoutParams gaugeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gaugeParams.gravity = Gravity.CENTER_HORIZONTAL;
        addView(gauge, gaugeParams);

        factsList = new LinearLayout(context);
        factsList.setOrientation(VERTICAL);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        listParams.topMargin = dp(14);
        addView(factsList, listParams);
    }

    /**
     * @param list    результат {@link InkSecurityFacts#collect}
     * @param animate разыграть появление датчика; повторные обновления — без анимации
     */
    public void setFacts(List<InkSecurityFacts.Fact> list, boolean animate) {
        facts = list;

        int counted = InkSecurityFacts.counted(list);
        boolean[] segments = new boolean[counted];
        int index = 0;
        for (InkSecurityFacts.Fact fact : list) {
            if (fact.state != InkSecurityFacts.SKIPPED) {
                segments[index++] = fact.state == InkSecurityFacts.OK;
            }
        }
        String caption = formatString(R.string.InkSecurityOf, counted);
        gauge.setSegments(segments, caption, animate);
        gauge.setContentDescription(InkSecurityFacts.passed(list) + " " + caption);

        factsList.removeAllViews();
        for (InkSecurityFacts.Fact fact : list) {
            factsList.addView(buildRow(fact), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private View buildRow(InkSecurityFacts.Fact fact) {
        Context context = getContext();
        String titleText = getString(fact.titleRes);
        String valueText = getString(fact.valueRes);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(44));
        row.setFocusable(true);
        row.setContentDescription(titleText + ", " + valueText);

        row.addView(new MarkView(context, fact.state), new LinearLayout.LayoutParams(dp(20), dp(20)));

        TextView title = new TextView(context);
        title.setTypeface(AndroidUtilities.regular());
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setText(titleText);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(12);
        row.addView(title, titleParams);

        TextView value = new TextView(context);
        value.setTypeface(AndroidUtilities.regular());
        value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        value.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        value.setGravity(Gravity.END);
        value.setText(valueText);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        valueParams.leftMargin = dp(8);
        row.addView(value, valueParams);
        return row;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        InkCard.draw(canvas, cardPaint, cardRect, getWidth(), getHeight(), dp(4));
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
            invalidate();
            if (facts != null) {
                setFacts(facts, false);
            }
        }
    }

    /** Отметка в начале строки: галочка на акценте, предупреждение или «неизвестно». */
    private static final class MarkView extends View {

        private final int state;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        MarkView(Context context, int state) {
            super(context);
            this.state = state;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - dp(1);

            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            if (state == InkSecurityFacts.OK) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Theme.getColor(Theme.key_chats_unreadCounter));
                canvas.drawCircle(cx, cy, r, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.6f));
                paint.setColor(Theme.getColor(Theme.key_chats_unreadCounterText));
                path.reset();
                path.moveTo(cx - r * 0.42f, cy + r * 0.02f);
                path.lineTo(cx - r * 0.10f, cy + r * 0.36f);
                path.lineTo(cx + r * 0.46f, cy - r * 0.32f);
                canvas.drawPath(path, paint);
            } else if (state == InkSecurityFacts.WARN) {
                int color = Theme.getColor(Theme.key_text_RedRegular);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.6f));
                paint.setColor(color);
                canvas.drawCircle(cx, cy, r - dp(0.8f), paint);
                canvas.drawLine(cx, cy - r * 0.45f, cx, cy + r * 0.08f, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy + r * 0.44f, dp(1f), paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.6f));
                paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
                canvas.drawCircle(cx, cy, r - dp(0.8f), paint);
                canvas.drawLine(cx - r * 0.35f, cy, cx + r * 0.35f, cy, paint);
            }
        }
    }
}
