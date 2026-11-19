package com.duckduckgram.duckerer.ink;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.text.TextPaint;
import android.view.View;
import android.view.animation.PathInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.Theme;

/**
 * mvs Ink Studio: датчик с насечками.
 *
 * <p>Дуга в 270° разбита на сегменты — по одному на проверку. Закрашенный сегмент
 * значит «проверка пройдена», пустой — «нет». Непрерывной шкалы нет намеренно:
 * процент из ничего был бы тем самым «щитом ради щита», от которого отказывается
 * README. Вокруг дуги — насечки, как на циферблате; при появлении они и сегменты
 * загораются по ходу стрелки за 1,4 с на кривой из дизайн-системы.
 */
public final class InkGaugeView extends View implements NotificationCenter.NotificationCenterDelegate {

    private static final float START = 135f;
    private static final float SWEEP = 270f;
    private static final float GAP = 12f;
    private static final float TICK_STEP = 5f;
    private static final int MAJOR_EVERY = 6;
    private static final long DURATION = 1400L;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint numberPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint captionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();

    private boolean[] segments = new boolean[0];
    private String caption = "";
    private float progress = 1f;
    private ValueAnimator animator;

    public InkGaugeView(Context context) {
        super(context);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStrokeCap(Paint.Cap.ROUND);
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        numberPaint.setTextAlign(Paint.Align.CENTER);
        captionPaint.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * @param ok      по значению на проверку: пройдена или нет
     * @param caption подпись под числом, например «из 3»
     * @param animate показать появление; иначе датчик сразу полный
     */
    public void setSegments(boolean[] ok, String caption, boolean animate) {
        this.segments = ok.clone();
        this.caption = caption == null ? "" : caption;
        cancelAnimation();
        if (animate && animationsEnabled()) {
            progress = 0f;
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(DURATION);
            animator.setInterpolator(new PathInterpolator(0.2f, 0.7f, 0.2f, 1f));
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        } else {
            progress = 1f;
        }
        invalidate();
    }

    private int passedCount() {
        int count = 0;
        for (boolean value : segments) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    /** Система просит не анимировать — уважаем: датчик просто появляется готовым. */
    @SuppressLint("NewApi")
    private static boolean animationsEnabled() {
        return Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled();
    }

    private void cancelAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(dp(180), dp(180));
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
        cancelAnimation();
        progress = 1f;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme) {
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - dp(20);
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius);

        int accent = Theme.getColor(Theme.key_chats_unreadCounter);
        int track = Theme.getColor(Theme.key_divider);
        int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        int muted = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2);
        int tick = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3);

        // До какого угла уже «дошла стрелка».
        float reveal = START + SWEEP * progress;

        // Насечки снаружи дуги: освещённые стрелкой плотнее, остальные еле видны.
        tickPaint.setColor(tick);
        for (float a = 0f; a <= SWEEP + 0.01f; a += TICK_STEP) {
            boolean major = Math.round(a / TICK_STEP) % MAJOR_EVERY == 0;
            float angle = START + a;
            double rad = Math.toRadians(angle);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            float inner = radius + dp(9);
            float outer = inner + dp(major ? 6f : 3.5f);
            tickPaint.setAlpha(angle <= reveal ? (major ? 210 : 120) : 46);
            tickPaint.setStrokeWidth(dp(major ? 1.5f : 1f));
            canvas.drawLine(cx + cos * inner, cy + sin * inner, cx + cos * outer, cy + sin * outer, tickPaint);
        }

        // Сегменты: дорожка всегда, заливка — у пройденных проверок.
        trackPaint.setColor(track);
        trackPaint.setStrokeWidth(dp(4));
        fillPaint.setColor(accent);
        fillPaint.setStrokeWidth(dp(4));
        int n = segments.length;
        if (n > 0) {
            float gap = n > 1 ? GAP : 0f;
            float sweep = (SWEEP - gap * (n - 1)) / n;
            for (int i = 0; i < n; i++) {
                float start = START + i * (sweep + gap);
                canvas.drawArc(arc, start, sweep, false, trackPaint);
                if (segments[i]) {
                    float lit = Math.min(sweep, Math.max(0f, reveal - start));
                    if (lit > 0.5f) {
                        canvas.drawArc(arc, start, lit, false, fillPaint);
                    }
                }
            }
        } else {
            canvas.drawArc(arc, START, SWEEP, false, trackPaint);
        }

        // Число в центре — серифом, подпись под ним — интерфейсным шрифтом.
        numberPaint.setTypeface(AndroidUtilities.serif());
        numberPaint.setTextSize(dp(46));
        numberPaint.setColor(ink);
        canvas.drawText(String.valueOf(passedCount()), cx, cy + dp(10), numberPaint);

        captionPaint.setTypeface(AndroidUtilities.regular());
        captionPaint.setTextSize(dp(13));
        captionPaint.setColor(muted);
        canvas.drawText(caption, cx, cy + dp(34), captionPaint);
    }
}
