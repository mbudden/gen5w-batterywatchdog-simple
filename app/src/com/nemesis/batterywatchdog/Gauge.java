package com.nemesis.batterywatchdog;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** The 12V charge on a 0-100 % bar, with the warning level marked. */
final class Gauge extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private final float dp;
    private final Palette palette;
    private int value10 = -1, warnPct = 60;

    Gauge(Context context, Palette palette) {
        super(context); this.palette = palette;
        dp = context.getResources().getDisplayMetrics().density;
        paint.setTextSize(14 * dp); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    void setValue(int value10, int warnPct) {
        if (value10 == this.value10 && warnPct == this.warnPct) return;
        this.value10 = value10; this.warnPct = warnPct; invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float width = getWidth(), top = 24 * dp, bottom = top + 12 * dp, radius = 6 * dp;
        boolean low = value10 >= 0 && value10 < warnPct * 10;
        int color = low ? palette.warn : palette.accent;
        float warnX = width * warnPct / 100f;
        track.set(0, top, width, bottom);
        paint.setStyle(Paint.Style.FILL); paint.setColor(palette.track);
        canvas.drawRoundRect(track, radius, radius, paint);
        float x = width * Math.max(0, Math.min(1000, value10)) / 1000f;
        if (value10 >= 0) {
            canvas.save(); canvas.clipRect(0, top, x, bottom);
            paint.setColor(color); canvas.drawRoundRect(track, radius, radius, paint); canvas.restore();
        }
        paint.setColor(palette.ink); paint.setStrokeWidth(2 * dp);
        canvas.drawLine(warnX, top - 4 * dp, warnX, bottom + 4 * dp, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("Warn " + warnPct + "%", warnX, top - 9 * dp, paint);
        paint.setColor(palette.muted);
        float labels = bottom + 20 * dp;
        paint.setTextAlign(Paint.Align.LEFT); canvas.drawText("0%", 0, labels, paint);
        paint.setTextAlign(Paint.Align.RIGHT); canvas.drawText("100%", width, labels, paint);
        if (value10 >= 0) {
            float cx = Math.max(10 * dp, Math.min(width - 10 * dp, x)), cy = (top + bottom) / 2;
            paint.setColor(palette.surface); canvas.drawCircle(cx, cy, 10 * dp, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4 * dp); paint.setColor(color);
            canvas.drawCircle(cx, cy, 8 * dp, paint);
        }
    }
}
