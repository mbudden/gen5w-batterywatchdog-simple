package com.nemesis.batterywatchdog;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Native view styling, shared by both screens. */
final class Ui {
    static final int FILL = LinearLayout.LayoutParams.MATCH_PARENT, WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    final Context context;
    final Palette p;

    Ui(Context context, Palette palette) { this.context = context; this.p = palette; }

    int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    LinearLayout column() { return linear(LinearLayout.VERTICAL); }
    LinearLayout row() {
        LinearLayout v = linear(LinearLayout.HORIZONTAL);
        v.setBaselineAligned(false);   // children line up by gravity unless a row asks otherwise
        return v;
    }
    private LinearLayout linear(int orientation) {
        LinearLayout v = new LinearLayout(context); v.setOrientation(orientation); return v;
    }
    TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(context);
        v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        v.setIncludeFontPadding(false);
        return v;
    }
    TextView caption(String value) { return text(value, 12, p.muted, true); }
    GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(radius));
        return shape;
    }
    /** A rounded fill that dims while pressed. */
    Drawable pill(int fill, int radius) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(fill & 0xccffffff, radius));
        states.addState(new int[]{}, rounded(fill, radius));
        return states;
    }
    @SuppressWarnings("deprecation")
    void background(View view, Drawable drawable) { view.setBackgroundDrawable(drawable); }

    /** A raised card with its share of the row's width. */
    LinearLayout panel(LinearLayout parent, float weight, int gap) {
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(18), dp(20), dp(18));
        background(panel, rounded(p.surface, 20));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, FILL, weight); params.leftMargin = dp(gap);
        parent.addView(panel, params); return panel;
    }
    /** Takes up a share of the spare height, so a card's contents stay evenly spaced. */
    View spacer() {
        View v = new View(context);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 1f));
        return v;
    }
    View hairline() {
        View v = new View(context); v.setBackgroundColor(p.line);
        v.setLayoutParams(new LinearLayout.LayoutParams(FILL, Math.max(1, dp(1))));
        return v;
    }
    LinearLayout.LayoutParams below(int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(WRAP, WRAP); params.topMargin = dp(margin); return params;
    }
}
