package com.nemesis.batterywatchdog;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** The Settings icon: two lines with a knob on each. Drawn, so it doesn't depend on fonts. */
final class SlidersIcon extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    SlidersIcon(Context context, int color) {
        super(context);
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas c) {
        float u = Math.min(getWidth(), getHeight()) / 30f;
        paint.setStrokeWidth(2.5f * u);
        c.drawLine(4 * u, 9 * u, 16 * u, 9 * u, paint); c.drawLine(22 * u, 9 * u, 26 * u, 9 * u, paint);
        c.drawLine(4 * u, 21 * u, 8 * u, 21 * u, paint); c.drawLine(14 * u, 21 * u, 26 * u, 21 * u, paint);
        c.drawCircle(19 * u, 9 * u, 3 * u, paint); c.drawCircle(11 * u, 21 * u, 3 * u, paint);
    }
}
