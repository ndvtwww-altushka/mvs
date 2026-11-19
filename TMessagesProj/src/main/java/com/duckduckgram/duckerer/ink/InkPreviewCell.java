package com.duckduckgram.duckerer.ink;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.view.View;

import app.exteraless.appearance.AppearanceConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/**
 * mvs Ink Studio: живой образец. Строка списка чатов, два пузыря и поле ввода,
 * нарисованные теми же цветовыми ключами, которыми рисует само приложение, — поэтому
 * образец перекрашивается вместе с выбором чернил и показывает форму аватарок из
 * настроек. Ничего не обращается к сети и к аккаунту.
 */
public final class InkPreviewCell extends View implements NotificationCenter.NotificationCenterDelegate {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint inPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint outPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    private StaticLayout inLayout;
    private StaticLayout outLayout;
    private int inWidth;
    private int inHeight;
    private int outWidth;
    private int outHeight;
    private int totalHeight = dp(300);
    private int builtForWidth = -1;
    private boolean dirty = true;

    public InkPreviewCell(Context context) {
        super(context);
        setWillNotDraw(false);
        // Образец — картинка: экранный диктор его пропускает.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Перерисовать после смены чернил или формы аватарок. */
    public void refresh() {
        dirty = true;
        requestLayout();
        invalidate();
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
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (dirty || width != builtForWidth) {
            buildLayouts(width);
        }
        setMeasuredDimension(width, totalHeight);
    }

    private void buildLayouts(int width) {
        int innerWidth = width - 2 * dp(12) - 2 * dp(14);
        int bubbleMax = (int) (innerWidth * 0.78f);
        int padH = dp(12);
        int padV = dp(9);

        inPaint.setTypeface(AndroidUtilities.regular());
        inPaint.setTextSize(dp(14.5f));
        inPaint.setColor(Theme.getColor(Theme.key_chat_messageTextIn));
        outPaint.setTypeface(AndroidUtilities.regular());
        outPaint.setTextSize(dp(14.5f));
        outPaint.setColor(Theme.getColor(Theme.key_chat_messageTextOut));

        // Входящий: текст и ссылка — ссылка берёт цвет текстового акцента.
        SpannableStringBuilder incoming = new SpannableStringBuilder(getString(R.string.InkPreviewIn));
        int linkStart = incoming.length() + 1;
        incoming.append('\n').append("t.me/mvs");
        incoming.setSpan(new ForegroundColorSpan(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText)),
                linkStart, incoming.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        incoming.setSpan(new UnderlineSpan(), linkStart, incoming.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        int textWidth = Math.max(dp(80), bubbleMax - 2 * padH);
        inLayout = new StaticLayout(incoming, inPaint, textWidth,
                Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
        outLayout = new StaticLayout(getString(R.string.InkPreviewOut), outPaint, textWidth,
                Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);

        inWidth = widest(inLayout) + 2 * padH;
        inHeight = inLayout.getHeight() + 2 * padV;
        // Исходящий несёт ещё строку со временем и галочками.
        outWidth = Math.max(widest(outLayout), dp(56)) + 2 * padH;
        outHeight = outLayout.getHeight() + dp(15) + 2 * padV;

        totalHeight = dp(8) + dp(14) + dp(34) + dp(56) + dp(10) + inHeight + dp(6)
                + outHeight + dp(12) + dp(40) + dp(14) + dp(8);
        builtForWidth = width;
        dirty = false;
    }

    private static int widest(StaticLayout layout) {
        float widest = 0f;
        for (int i = 0; i < layout.getLineCount(); i++) {
            widest = Math.max(widest, layout.getLineWidth(i));
        }
        return (int) Math.ceil(widest);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (inLayout == null || outLayout == null) {
            return;
        }
        final boolean dark = InkAccent.isDark();
        final int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int hair = Theme.getColor(Theme.key_divider);

        // Поля 12 dp — как у карточек-секций списка настроек.
        float left = dp(12);
        float right = getWidth() - dp(12);
        float top = dp(8);
        float bottom = getHeight() - dp(8);

        // Карточка-«экран».
        rect.set(left, top, right, bottom);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        canvas.drawRoundRect(rect, dp(20), dp(20), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(hair);
        canvas.drawRoundRect(rect, dp(20), dp(20), paint);
        paint.setStyle(Paint.Style.FILL);

        float x0 = left + dp(14);
        float x1 = right - dp(14);
        float y = top + dp(14);

        // Шапка: словесный знак слева, название чернил справа.
        text.setTypeface(AndroidUtilities.serif());
        text.setTextSize(dp(24));
        text.setColor(ink);
        text.setTextAlign(Paint.Align.LEFT);
        float baseline = y + dp(24);
        canvas.drawText("mvs", x0, baseline, text);
        float markWidth = text.measureText("mvs");
        paint.setColor(InkAccent.brand(dark));
        canvas.drawCircle(x0 + markWidth + dp(4), baseline - dp(2.5f), dp(3), paint);

        text.setTypeface(AndroidUtilities.regular());
        text.setTextSize(dp(13));
        text.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(getString(InkAccent.nameRes(InkAccent.index())), x1, baseline - dp(2), text);
        y += dp(34);

        // Строка списка чатов.
        String name = getString(R.string.InkPreviewName);
        float avatar = dp(44);
        float avatarTop = y + (dp(56) - avatar) / 2f;
        float corners = AppearanceConfig.getAvatarCorners(avatar);
        rect.set(x0, avatarTop, x0 + avatar, avatarTop + avatar);
        paint.setColor(Theme.getColor(Theme.key_avatar_backgroundRed));
        canvas.drawRoundRect(rect, corners, corners, paint);

        text.setTypeface(AndroidUtilities.serif());
        text.setTextSize(dp(20));
        text.setColor(Theme.getColor(Theme.key_avatar_nameInMessageRed));
        text.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(name.substring(0, 1), rect.centerX(), rect.centerY() + dp(7), text);

        float textX = x0 + avatar + dp(12);
        text.setTypeface(AndroidUtilities.bold());
        text.setTextSize(dp(15));
        text.setColor(Theme.getColor(Theme.key_chats_name));
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(name, textX, y + dp(24), text);

        text.setTypeface(AndroidUtilities.regular());
        text.setTextSize(dp(13.5f));
        text.setColor(Theme.getColor(Theme.key_chats_message));
        CharSequence last = TextUtils.ellipsize(getString(R.string.InkPreviewLast), text,
                x1 - dp(32) - textX, TextUtils.TruncateAt.END);
        canvas.drawText(last, 0, last.length(), textX, y + dp(44), text);

        text.setTextSize(dp(12));
        text.setColor(Theme.getColor(Theme.key_chats_date));
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("12:08", x1, y + dp(22), text);

        // Бейдж непрочитанного — заливка выбранным акцентом.
        text.setTypeface(AndroidUtilities.bold());
        text.setTextSize(dp(11.5f));
        float badgeHeight = dp(20);
        float badgeWidth = Math.max(badgeHeight, text.measureText("2") + dp(12));
        rect.set(x1 - badgeWidth, y + dp(30), x1, y + dp(30) + badgeHeight);
        paint.setColor(Theme.getColor(Theme.key_chats_unreadCounter));
        canvas.drawRoundRect(rect, badgeHeight / 2f, badgeHeight / 2f, paint);
        text.setColor(Theme.getColor(Theme.key_chats_unreadCounterText));
        text.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("2", rect.centerX(), rect.centerY() + dp(4), text);
        y += dp(56) + dp(10);

        // Входящий пузырь: хвост слева снизу.
        rect.set(x0, y, x0 + inWidth, y + inHeight);
        drawBubble(canvas, Theme.getColor(Theme.key_chat_inBubble), hair, false);
        canvas.save();
        canvas.translate(x0 + dp(12), y + dp(9));
        inLayout.draw(canvas);
        canvas.restore();
        y += inHeight + dp(6);

        // Исходящий пузырь: хвост справа снизу, под текстом время и галочки.
        rect.set(x1 - outWidth, y, x1, y + outHeight);
        drawBubble(canvas, Theme.getColor(Theme.key_chat_outBubble), 0, true);
        canvas.save();
        canvas.translate(x1 - outWidth + dp(12), y + dp(9));
        outLayout.draw(canvas);
        canvas.restore();

        float metaBaseline = y + outHeight - dp(9) - dp(2);
        text.setTypeface(AndroidUtilities.regular());
        text.setTextSize(dp(11));
        text.setColor(Theme.getColor(Theme.key_chat_outTimeText));
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("12:09", x1 - dp(12) - dp(17), metaBaseline, text);
        drawCheck(canvas, x1 - dp(12) - dp(11), metaBaseline - dp(3.5f),
                Theme.getColor(Theme.key_chat_outSentCheck));
        drawCheck(canvas, x1 - dp(12) - dp(6.5f), metaBaseline - dp(3.5f),
                Theme.getColor(Theme.key_chat_outSentCheck));
        y += outHeight + dp(12);

        // Поле ввода и кнопка отправки.
        float send = dp(40);
        rect.set(x0, y, x1 - send - dp(8), y + send);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Theme.getColor(Theme.key_chat_messagePanelBackground));
        canvas.drawRoundRect(rect, send / 2f, send / 2f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(hair);
        canvas.drawRoundRect(rect, send / 2f, send / 2f, paint);

        text.setTextSize(dp(14.5f));
        text.setColor(Theme.getColor(Theme.key_chat_messagePanelHint));
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(getString(R.string.InkPreviewHint), x0 + dp(16), y + dp(25), text);

        float sx = x1 - send / 2f;
        float sy = y + send / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Theme.getColor(Theme.key_chat_messagePanelSend));
        canvas.drawCircle(sx, sy, send / 2f, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(Theme.getColor(Theme.key_chats_unreadCounterText));
        canvas.drawLine(sx, sy + dp(6), sx, sy - dp(6), paint);
        path.reset();
        path.moveTo(sx - dp(5), sy - dp(1));
        path.lineTo(sx, sy - dp(6));
        path.lineTo(sx + dp(5), sy - dp(1));
        canvas.drawPath(path, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    /** Пузырь с малым радиусом у «хвоста»: слева снизу у входящего, справа у исходящего. */
    private void drawBubble(Canvas canvas, int fill, int stroke, boolean outgoing) {
        float r = dp(16);
        float t = dp(5);
        float[] radii = outgoing
                ? new float[]{r, r, r, r, t, t, r, r}
                : new float[]{r, r, r, r, r, r, t, t};
        path.reset();
        path.addRoundRect(rect, radii, Path.Direction.CW);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fill);
        canvas.drawPath(path, paint);
        if (stroke != 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(stroke);
            canvas.drawPath(path, paint);
        }
    }

    private void drawCheck(Canvas canvas, float x, float y, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.4f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
        path.reset();
        path.moveTo(x - dp(3), y);
        path.lineTo(x - dp(0.8f), y + dp(2.4f));
        path.lineTo(x + dp(3.4f), y - dp(2.6f));
        canvas.drawPath(path, paint);
    }
}
