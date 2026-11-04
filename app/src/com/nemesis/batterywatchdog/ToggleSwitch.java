package com.nemesis.batterywatchdog;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** An on/off switch drawn in the app's colors. The owner handles the click. */
final class ToggleSwitch extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private final Palette palette;
    private boolean on;

    ToggleSwitch(Context context, Palette palette) { super(context); this.palette = palette; }

    void setOn(boolean value) {
        on = value; setSelected(value);
        setContentDescription(value ? "On" : "Off"); invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float h = getHeight(), r = h / 2f, inset = h * 0.11f, knob = r - inset;
        track.set(0, 0, getWidth(), h);
        paint.setColor(on ? palette.accent : palette.off);
        canvas.drawRoundRect(track, r, r, paint);
        paint.setColor(0xffffffff);
        canvas.drawCircle(on ? getWidth() - r : r, r, knob, paint);
    }
}
