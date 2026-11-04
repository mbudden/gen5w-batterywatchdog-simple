package com.nemesis.batterywatchdog;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Each drive as a bar from its starting 12V to its ending 12V, joined by the parked time. */
final class DriveChart extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF frame = new RectF();
    private final float dp;
    private final Palette palette;
    private List<long[]> drives = new ArrayList<long[]>();
    private int warn10 = 600, drop10 = 150;
    private boolean endsNow;

    DriveChart(Context context, Palette palette) {
        super(context); this.palette = palette;
        dp = context.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE); line.setStrokeCap(Paint.Cap.ROUND);
        dashed.setStyle(Paint.Style.STROKE); dashed.setStrokeWidth(2 * dp); dashed.setColor(palette.warn);
        dashed.setPathEffect(new DashPathEffect(new float[]{8 * dp, 6 * dp}, 0));
        label.setTextSize(13 * dp); label.setColor(palette.muted);
        setContentDescription("12V charge at the start and end of recent drives");
    }

    /** endsNow: the newest entry is the drive happening now. */
    void setData(List<long[]> drives, int warn10, int drop10, boolean endsNow) {
        this.drives = drives; this.warn10 = warn10; this.drop10 = drop10; this.endsNow = endsNow; invalidate();
    }

    private static float y(float pct10, int lo, int hi, float top, float height) {
        return top + height * (1f - (pct10 / 10f - lo) / (hi - lo));
    }

    @Override protected void onDraw(Canvas c) {
        frame.set(0, 0, getWidth(), getHeight());
        fill.setStyle(Paint.Style.FILL); fill.setColor(palette.card);
        c.drawRoundRect(frame, 14 * dp, 14 * dp, fill);
        if (drives.isEmpty()) {
            label.setTextAlign(Paint.Align.CENTER); label.setTextSize(17 * dp);
            c.drawText("No drives yet", getWidth() / 2f, getHeight() / 2f + 6 * dp, label);
            label.setTextSize(13 * dp); return;
        }
        float left = 48 * dp, top = 32 * dp, width = getWidth() - left - 14 * dp, height = getHeight() - top - 28 * dp;
        int[] range = Drives.chartRange(drives, warn10);
        int lo = range[0], hi = range[1];

        drawLegend(c);

        // Hairlines at both ends. The warning line's label wins when one would sit beside it.
        float warnY = y(warn10, lo, hi, top, height);
        line.setStrokeWidth(dp); line.setColor(palette.line); line.setAlpha(255);
        label.setTextAlign(Paint.Align.RIGHT); label.setColor(palette.muted);
        for (int pct : range) {
            float yy = y(pct * 10, lo, hi, top, height);
            c.drawLine(left, yy, left + width, yy, line);
            if (Math.abs(yy - warnY) >= 16 * dp) c.drawText(pct + "%", left - 8 * dp, yy + 5 * dp, label);
        }
        path.reset(); path.moveTo(left, warnY); path.lineTo(left + width, warnY);
        c.drawPath(path, dashed);
        // In the axis gutter, where the drives can't cross it.
        label.setColor(palette.warn);
        c.drawText((warn10 / 10) + "%", left - 8 * dp, warnY + 5 * dp, label);
        label.setColor(palette.muted);

        int n = drives.size();
        float slot = width / Math.max(n, 6);

        // Parked time: from each drive's end to the next drive's start.
        for (int i = 1; i < n; i++) {
            long[] prev = drives.get(i - 1), d = drives.get(i);
            boolean bad = d[2] - prev[3] <= -drop10;
            line.setColor(bad ? palette.warn : palette.muted);
            line.setAlpha(bad ? 255 : 178);
            line.setStrokeWidth((bad ? 2f : 1.3f) * dp);
            c.drawLine(left + slot * (i - 0.5f), y(prev[3], lo, hi, top, height),
                       left + slot * (i + 0.5f), y(d[2], lo, hi, top, height), line);
        }
        line.setAlpha(255);

        // The drives, then their dots on top.
        line.setColor(palette.accent); line.setStrokeWidth(4 * dp);
        for (int i = 0; i < n; i++) {
            long[] d = drives.get(i);
            float x = left + slot * (i + 0.5f);
            c.drawLine(x, y(d[2], lo, hi, top, height), x, y(d[3], lo, hi, top, height), line);
        }
        for (int i = 0; i < n; i++) {
            long[] d = drives.get(i);
            float x = left + slot * (i + 0.5f);
            boolean now = endsNow && i == n - 1;
            fill.setColor(palette.ink);
            c.drawCircle(x, y(d[2], lo, hi, top, height), 4.7f * dp, fill);
            fill.setColor(now && d[3] < warn10 ? palette.warn : palette.accent);
            c.drawCircle(x, y(d[3], lo, hi, top, height), (now ? 6.7f : 4.7f) * dp, fill);
        }

        drawDays(c, left, width, slot, top + height + 20 * dp);
    }

    private void drawLegend(Canvas c) {
        float y = 20 * dp, cy = y - 4.5f * dp;
        float x = getWidth() - 14 * dp;
        label.setTextAlign(Paint.Align.RIGHT); label.setColor(palette.muted);
        c.drawText("Parked", x, y, label);
        x -= label.measureText("Parked") + 8 * dp;
        line.setColor(palette.muted); line.setAlpha(178); line.setStrokeWidth(1.3f * dp);
        c.drawLine(x - 20 * dp, cy, x, cy, line);
        line.setAlpha(255);
        x -= 20 * dp + 18 * dp;
        c.drawText("End", x, y, label);
        x -= label.measureText("End") + 8 * dp + 4.7f * dp;
        fill.setColor(palette.accent); c.drawCircle(x, cy, 4.7f * dp, fill);
        x -= 4.7f * dp + 18 * dp;
        c.drawText("Start", x, y, label);
        x -= label.measureText("Start") + 8 * dp + 4.7f * dp;
        fill.setColor(palette.ink); c.drawCircle(x, cy, 4.7f * dp, fill);
    }

    private void drawDays(Canvas c, float left, float width, float slot, float y) {
        SimpleDateFormat day = new SimpleDateFormat("MMM d", Locale.US);
        int n = drives.size();
        float nowX = left + slot * (n - 0.5f);
        label.setTextAlign(Paint.Align.CENTER);
        String lastLabel = "";
        float lastX = -1000;
        for (int i = 0; i < n; i++) {
            float x = left + slot * (i + 0.5f);
            String text = day.format(new Date(drives.get(i)[0] * 1000));
            if (text.equals(lastLabel) || x - lastX < 60 * dp) continue;
            if (endsNow && nowX - x < 48 * dp) continue;     // leave room for "Now"
            c.drawText(text, Math.max(left + 22 * dp, x), y, label);
            lastLabel = text; lastX = x;
        }
        if (endsNow) {
            label.setColor(palette.ink); label.setFakeBoldText(true);
            c.drawText("Now", Math.min(left + width - 14 * dp, nowX), y, label);
            label.setColor(palette.muted); label.setFakeBoldText(false);
        }
    }
}
