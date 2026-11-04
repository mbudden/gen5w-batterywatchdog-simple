package com.nemesis.batterywatchdog;

/** Screen colors. DARK follows the head unit's night display, LIGHT its day display. */
final class Palette {
    final boolean dark;
    /** Window, raised panels, cards inside panels, and hairlines. */
    final int background, surface, card, line;
    /** Primary text and secondary text. */
    final int ink, muted;
    /** Accent fill and the text on it. */
    final int accent, onAccent;
    /** Gauge track, warning color, and destructive text. */
    final int track, warn, danger;
    /** A switch that is off. */
    final int off;

    private Palette(boolean dark, int background, int surface, int card, int line, int ink, int muted,
                    int accent, int onAccent, int track, int warn, int danger, int off) {
        this.dark = dark; this.background = background; this.surface = surface; this.card = card;
        this.line = line; this.ink = ink; this.muted = muted;
        this.accent = accent; this.onAccent = onAccent;
        this.track = track; this.warn = warn; this.danger = danger; this.off = off;
    }

    static final Palette LIGHT = new Palette(false,
        0xffffffff, 0xfff0f2f6, 0xffffffff, 0xffdfe3ea,
        0xff101522, 0xff677388,
        0xff007aff, 0xffffffff,
        0xffd5e6ff, 0xffa84b07, 0xffc9343a, 0xffa0a7b2);
    static final Palette DARK = new Palette(true,
        0xff0b0f17, 0xff161c27, 0xff1f2733, 0xff2a3242,
        0xffe8ecf3, 0xff97a1b3,
        0xff2678eb, 0xffffffff,
        0xff1d3352, 0xfff59e0b, 0xffff7a7f, 0xff2a3242);

    static Palette of(boolean dark) { return dark ? DARK : LIGHT; }
}
