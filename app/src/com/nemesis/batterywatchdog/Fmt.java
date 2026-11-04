package com.nemesis.batterywatchdog;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small parsing and display helpers. No Android imports, so tests can run them on a computer. */
final class Fmt {
    static final String MINUS = "−", NONE = "—";

    static String kv(String text, String key) {
        for (String l : text.split("\n")) if (l.startsWith(key + "=")) return l.substring(key.length() + 1).trim();
        return "";
    }
    static int parse(String s, int d) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; } }
    static long parseL(String s, long d) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return d; } }
    static String fmt10(int v) { return (v / 10) + "." + (v % 10); }
    static String fmtSigned10(int v) { return (v > 0 ? "+" : v < 0 ? "-" : "") + fmt10(Math.abs(v)); }
    static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
    static String shq(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    /** Whole percent, rounded down so a reading below the warning level never shows as at it. */
    static String percent(int v10) { return (v10 / 10) + "%"; }

    /** Tenths of a point as "3 pts", "1 pt" or "2.5 pts". */
    static String points(int v10) {
        int a = Math.abs(v10);
        String n = a % 10 == 0 ? String.valueOf(a / 10) : fmt10(a);
        return n + (a == 10 ? " pt" : " pts");
    }

    /** "45 min", "14 h" or "3 d". */
    static String span(int min) {
        if (min < 60) return min + " min";
        if (min < 48 * 60) return (min / 60) + " h";
        return (min / 1440) + " d";
    }

    /** How long ago a reading was taken. */
    static String age(long now, long then) {
        long sec = now - then;
        if (sec < 60) return "just now";
        if (sec < 3600) return (sec / 60) + " min ago";
        if (sec < 48 * 3600) return (sec / 3600) + " h ago";
        return new SimpleDateFormat("MMM d", Locale.US).format(new Date(then * 1000));
    }

    private Fmt() { }
}
