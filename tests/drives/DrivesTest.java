package com.nemesis.batterywatchdog;

import java.util.ArrayList;
import java.util.List;

/** Host test for the home screen's numbers. Run by tests/test_drives.sh. */
public class DrivesTest {
    static int failures;

    static void eq(String what, Object want, Object got) {
        if (!String.valueOf(want).equals(String.valueOf(got))) {
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
            failures++;
        }
    }

    static String range(int warn10, int... pct10) {
        List<long[]> l = new ArrayList<long[]>();
        for (int p : pct10) l.add(new long[] { 1, 2, p, p });
        int[] r = Drives.chartRange(l, warn10);
        return r[0] + "-" + r[1];
    }

    public static void main(String[] a) {
        long minOff = 600, t = 1_800_000_000L;

        // short stops merge; bad lines are skipped
        String sessions = t + " 30 800 810\n" + (t + 30 * 60 + 300) + " 10 810 820\nnot a line\n" + (t + 7200) + " 25 830 820\n";
        List<long[]> done = Drives.finished(sessions, minOff);
        eq("merged count", 2, done.size());
        eq("merged end pct", 820L, done.get(0)[3]);
        eq("merged end time", t + 45 * 60, done.get(0)[1]);

        // the current drive is added without changing the finished list
        String status = "session_start=" + (t + 20000) + " 815\npct10=825\nupdated=" + (t + 20600) + "\n";
        List<long[]> all = Drives.withCurrent(done, status, minOff);
        eq("with current", 3, all.size());
        eq("finished untouched", 2, done.size());
        eq("current end", 825L, all.get(2)[3]);

        // a current drive just after a short stop merges into a copy
        List<long[]> merged = Drives.withCurrent(done, "session_start=" + (t + 7200 + 25 * 60 + 60) + " 815\npct10=900\nupdated=" + (t + 20000) + "\n", minOff);
        eq("merged current", 2, merged.size());
        eq("merged copy", 900L, merged.get(1)[3]);
        eq("original kept", 820L, done.get(1)[3]);

        // no session yet
        eq("no current", 2, Drives.withCurrent(done, "pct10=800\n", minOff).size());

        // the newest drive long enough to judge recovery
        eq("last long", t + 7200, Drives.lastLong(done)[0]);
        eq("too short", null, Drives.lastLong(Drives.finished(t + " 19 800 700\n", minOff)));
        eq("too long", null, Drives.lastLong(Drives.finished(t + " 1441 800 700\n", minOff)));
        eq("skips short", t, Drives.lastLong(Drives.finished(t + " 30 800 810\n" + (t + 7200) + " 5 800 700\n", minOff))[0]);

        // the last 7 days: 2 pts lost over a 2 h park is 24 pts a day; a rise counts as a recharge
        long now = t + 86400;
        List<long[]> week = new ArrayList<long[]>();
        week.add(new long[] { t - 30 * 86400, t - 30 * 86400 + 600, 900, 900 });   // too old to count
        week.add(new long[] { t, t + 1800, 800, 820 });
        week.add(new long[] { t + 1800 + 7200, t + 1800 + 9000, 800, 810 });
        week.add(new long[] { t + 1800 + 9000 + 7200, t + 1800 + 9000 + 9000, 830, 840 });
        Drives.Summary s = Drives.summary(week, now);
        eq("drives", 3, s.count);
        eq("lowest", 800, s.lowest10);
        eq("recharges", 1, s.recharges);
        eq("has rate", true, s.hasRate);
        eq("no drives", 0, Drives.summary(new ArrayList<long[]>(), now).count);
        eq("no rate", false, Drives.summary(new ArrayList<long[]>(), now).hasRate);

        // chart bounds: match the mockup, include the warning line, stay in 0-100, never collapse
        eq("normal range", "55-90", range(600, 800, 880));
        eq("low range", "55-90", range(600, 570, 880));
        eq("warn below readings", "25-90", range(300, 800, 880));
        eq("warn above readings", "75-95", range(900, 800, 850));
        eq("no readings", "55-75", range(600));
        eq("full", "90-100", range(900, 1000).split("-")[1].equals("100") ? "90-100" : "bad");
        eq("empty battery", "0-20", range(300, 0).startsWith("0-") ? "0-20" : "bad");

        // display
        eq("percent floors", "59%", Fmt.percent(599));
        eq("points", "3 pts", Fmt.points(-30));
        eq("one point", "1 pt", Fmt.points(10));
        eq("tenths", "2.5 pts", Fmt.points(25));
        eq("minutes", "45 min", Fmt.span(45));
        eq("hours", "14 h", Fmt.span(14 * 60 + 20));
        eq("days", "3 d", Fmt.span(3 * 1440));
        eq("just now", "just now", Fmt.age(t, t - 30));
        eq("min ago", "2 min ago", Fmt.age(t, t - 120));
        eq("clock behind", "just now", Fmt.age(t, t + 500));

        // steppers land on multiples of 5 and stay in range
        eq("up", 65, Config.step(60, 1, 30, 90));
        eq("down", 55, Config.step(60, -1, 30, 90));
        eq("up from between", 65, Config.step(62, 1, 30, 90));
        eq("down from between", 60, Config.step(62, -1, 30, 90));
        eq("top", 90, Config.step(90, 1, 30, 90));
        eq("bottom", 5, Config.step(5, -1, 5, 40));

        if (failures > 0) { System.out.println(failures + " failed"); System.exit(1); }
        System.out.println("drives ok");
    }
}
