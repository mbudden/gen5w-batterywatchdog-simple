package com.nemesis.batterywatchdog;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives as {start_epoch, end_epoch, start_pct10, end_pct10}, oldest first.
 * No Android imports, so tests can run this on a computer.
 */
final class Drives {
    /** The service flags a drive that ends lower only when it lasted this long. */
    static final int MIN_RECOVERY_MINUTES = 20, MAX_RECOVERY_MINUTES = 1440;

    private Drives() { }

    /**
     * Finished drives from the 'sessions' file. Drives separated by a stop shorter
     * than minOff seconds are merged, which also tidies history recorded before
     * short stops were ignored.
     */
    static List<long[]> finished(String sessions, long minOff) {
        List<long[]> out = new ArrayList<long[]>();
        for (String line : sessions.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 4) continue;
            long st = Fmt.parseL(f[0], 0); int min = Fmt.parse(f[1], -1), sp = Fmt.parse(f[2], -1), ep = Fmt.parse(f[3], -1);
            if (st <= 0 || min < 0 || sp < 0 || ep < 0) continue;
            add(out, new long[] { st, st + min * 60L, sp, ep }, minOff);
        }
        return out;
    }

    /** The finished drives plus the current one from the status file, as a new list. */
    static List<long[]> withCurrent(List<long[]> finished, String status, long minOff) {
        List<long[]> out = new ArrayList<long[]>();
        for (long[] d : finished) out.add(d.clone());
        String[] ss = Fmt.kv(status, "session_start").split(" ");
        long cst = ss.length > 0 ? Fmt.parseL(ss[0], 0) : 0;
        int csp = ss.length > 1 ? Fmt.parse(ss[1], -1) : -1;
        int cur = Fmt.parse(Fmt.kv(status, "pct10"), -1);
        long upd = Fmt.parseL(Fmt.kv(status, "updated"), 0);
        if (cst > 0 && csp >= 0 && cur >= 0 && upd >= cst
                && (out.isEmpty() || cst > out.get(out.size() - 1)[0])) {
            add(out, new long[] { cst, upd, csp, cur }, minOff);
        }
        return out;
    }

    static void add(List<long[]> l, long[] d, long minOff) {
        if (!l.isEmpty()) {
            long[] p = l.get(l.size() - 1);
            if (d[0] - p[1] < minOff && d[0] >= p[0]) { p[1] = Math.max(p[1], d[1]); p[3] = d[3]; return; }
        }
        l.add(d);
    }

    /** The newest finished drive long enough to judge recovery, or null. */
    static long[] lastLong(List<long[]> finished) {
        for (int i = finished.size() - 1; i >= 0; i--) {
            long[] d = finished.get(i);
            long min = (d[1] - d[0]) / 60;
            if (min >= MIN_RECOVERY_MINUTES && min <= MAX_RECOVERY_MINUTES) return d;
        }
        return null;
    }

    /** Last-7-days summary: drives, lowest start, parked drain per day, parked recharges. */
    static final class Summary {
        int count, recharges, lowest10 = Integer.MAX_VALUE;
        boolean hasRate;
        /** Tenths of a point per day; positive means it fell while parked. */
        int rate10;
    }

    static Summary summary(List<long[]> drives, long now) {
        Summary s = new Summary();
        long from = now - 7 * 86400L;
        long parkedMin = 0, parkedDrop = 0;
        for (int i = 0; i < drives.size(); i++) {
            long[] d = drives.get(i);
            if (d[0] < from) continue;
            s.count++;
            s.lowest10 = (int) Math.min(s.lowest10, d[2]);
            if (i == 0) continue;
            long[] p = drives.get(i - 1);
            long gap = (d[0] - p[1]) / 60;
            if (gap < 60) continue;                         // ignore short parks for the rate
            long drop = p[3] - d[2];                        // positive = fell while parked
            if (drop <= -10) s.recharges++;                 // rose 1+ pt: the car topped it up
            parkedMin += gap; parkedDrop += drop;
        }
        if (parkedMin >= 60) { s.hasRate = true; s.rate10 = (int) (parkedDrop * 1440 / parkedMin); }
        return s;
    }

    /**
     * Whole-percent chart bounds on 5-point steps: they always include the warning
     * line, leave a little room around the readings, and span at least 20 points.
     */
    static int[] chartRange(List<long[]> drives, int warn10) {
        int lo10 = warn10, hi10 = warn10;
        for (long[] d : drives) {
            lo10 = (int) Math.min(lo10, Math.min(d[2], d[3]));
            hi10 = (int) Math.max(hi10, Math.max(d[2], d[3]));
        }
        int lo = Math.max(0, (lo10 - 20) / 50 * 5);
        int hi = Math.min(100, ((hi10 + 20) + 49) / 50 * 5);
        if (hi - lo < 20) {
            hi = Math.min(100, lo + 20);
            lo = Math.max(0, hi - 20);
        }
        return new int[] { lo, hi };
    }
}
