package com.nemesis.batterywatchdog;

/** The monitor's settings, one value per file in the state folder. */
final class Config {
    static final String[] DECODE = { "Auto", "Integer", "Float" };
    static final int[] INTERVALS = { 60, 300, 900 };

    boolean enabled = true, toastStart = false;
    int warnPct = 60, dropPct = 15, decode = 1, scaleX10 = 10, interval = 300;

    void load(RootShell sh) {
        enabled    = sh.readInt("enabled", 1) == 1;
        toastStart = sh.readInt("toast_start", 0) == 1;
        warnPct    = Fmt.clamp(sh.readInt("warn_pct", 60), 30, 90);
        dropPct    = Fmt.clamp(sh.readInt("drop_pct", 15), 5, 40);
        decode     = Fmt.clamp(sh.readInt("decode", 1), 0, 2);
        scaleX10   = sh.readInt("scale_x10", 10) == 5 ? 5 : 10;
        interval   = sh.readInt("interval_s", 300);
    }

    /** Returns what the shell printed; it starts with "ERR" if the write couldn't run. */
    String save(RootShell sh) {
        String STATE = RootShell.STATE;
        String cmd = "mkdir -p " + STATE
            + "; echo " + (enabled ? 1 : 0) + " > " + STATE + "/enabled"
            + "; echo " + (toastStart ? 1 : 0) + " > " + STATE + "/toast_start"
            + "; echo " + warnPct + " > " + STATE + "/warn_pct"
            + "; echo " + dropPct + " > " + STATE + "/drop_pct"
            + "; echo " + decode + " > " + STATE + "/decode"
            + "; echo " + scaleX10 + " > " + STATE + "/scale_x10"
            + "; echo " + interval + " > " + STATE + "/interval_s";
        return sh.runRoot(cmd);
    }

    /** The next multiple of 5 above or below v, within lo..hi. */
    static int step(int v, int delta, int lo, int hi) {
        int next = delta > 0 ? (v / 5 + 1) * 5 : ((v + 4) / 5 - 1) * 5;
        return Fmt.clamp(next, lo, hi);
    }
}
