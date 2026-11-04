package com.nemesis.batterywatchdog;

/** Diagnostics text, shown in the dialog and saved by the USB export. */
final class Diagnostics {
    private Diagnostics() { }

    static String build(RootShell sh, Firmware firmware) {
        String STATE = RootShell.STATE, NEM = RootShell.NEM;
        String SVC_ID = RootShell.SVC_ID, SVC_LOG = RootShell.SVC_LOG, SVC_SCRIPT = RootShell.SVC_SCRIPT;
        StringBuilder d = new StringBuilder();
        d.append("service: ").append(sh.runRoot(NEM + " service status " + SVC_ID + " 2>&1").trim()).append("\n");
        d.append("app: ").append(firmware.appVersion()).append("   firmware: ").append(firmware.fw).append("\n");
        d.append("verification: ").append(firmware.verifyState).append(firmware.verifySource.length() > 0 ? " (" + firmware.verifySource + ")" : "")
            .append("  soc=").append(firmware.txSoc).append(" hv=").append(firmware.txHv).append(" soh=").append(firmware.txSoh).append("\n");
        d.append("logged too (by name): ").append(firmware.txExtra.isEmpty() ? "none" : firmware.txExtra.toString()).append("\n");
        d.append("file writes: ").append(sh.lastWriteMethod.length() > 0 ? sh.lastWriteMethod : "(none yet)").append("\n");
        d.append("telematics battery-related functions (names only, nothing is called):\n")
            .append(firstLines(firmware.batteryFunctions(), 40)).append("\n");
        d.append("lookup by name:\n").append(firstLines(sh.runRoot("cat " + STATE + "/resolve.log 2>/dev/null"), 8)).append("\n");
        // newest log lines first, so the latest error is at the top
        String log = sh.runRoot("cat " + SVC_LOG + " 2>&1");
        if (log.trim().length() == 0 || log.contains("No such file"))
            log = sh.runRoot(NEM + " log service " + SVC_ID + " 2>&1");
        String[] lines = log.trim().split("\n");
        d.append("service log, newest first:\n");
        if (log.trim().length() == 0) d.append("(empty)\n");
        for (int i = lines.length - 1, n = 0; i >= 0 && n < 15; i--, n++) d.append(lines[i]).append("\n");
        d.append("\n");
        // NEMESIS's own log says why it marked the service failed
        String nl = sh.runRoot("cat /data/nemesis/nemesis.log 2>&1");
        String[] nlines = nl.split("\n");
        d.append("nemesis.log, battery.watchdog lines, newest first:\n");
        int shown = 0;
        for (int i = nlines.length - 1; i >= 0 && shown < 12; i--) {
            if (nlines[i].contains("battery.watchdog") || nlines[i].contains("watchdog.sh")) { d.append(nlines[i]).append("\n"); shown++; }
        }
        if (shown == 0) d.append(nl.contains("No such file") ? "(no /data/nemesis/nemesis.log)\n" : "(no matching lines)\n");
        d.append("\n");
        d.append("shell: ").append(sh.runRoot("echo $KSH_VERSION").trim()).append("\n");
        d.append("script file: ").append(sh.runRoot("ls -l " + SVC_SCRIPT + " 2>&1").trim()).append("\n");
        String chk = sh.runRoot("/system/bin/sh -n " + SVC_SCRIPT + " 2>&1 && echo OK").trim();
        d.append("script syntax check: ").append(chk.length() == 0 ? "(no output)" : chk).append("\n");
        d.append("date: ").append(sh.runRoot("date; date +%s").trim().replace("\n", "  |  ")).append("\n");
        d.append("display: ").append(sh.runRoot("dumpsys power | grep -E 'mWakefulness=|mScreenOn=|policy=' 2>/dev/null").trim()).append("\n\n");
        d.append("status file:\n").append(sh.runRoot("cat " + STATE + "/status 2>&1").trim()).append("\n\n");
        d.append("state dir:\n").append(sh.runRoot("ls -l " + STATE + " 2>&1").trim()).append("\n\n");
        // outside temperature: which source the monitor used, and what each source shows now
        String st = sh.runRoot("cat " + STATE + "/status 2>/dev/null");
        d.append("temperature: ").append(Fmt.kv(st, "temp_c").length() > 0 ? Fmt.kv(st, "temp_c") + " C via " + Fmt.kv(st, "temp_src") : "none found").append("\n");
        d.append("climate dump, OutTemp lines:\n").append(firstLines(sh.runRoot("dumpsys com.hkmc.micom.datc 2>&1 | grep -i outtemp"), 8)).append("\n");
        d.append("logcat temperature lines, newest:\n")
            .append(lastLines(sh.runRoot("logcat -d 2>/dev/null | grep -E 'outTemperature|setOutsideTempUIAdvanced'"), 3)).append("\n");
        return d.toString();
    }

    static String firstLines(String text, int n) {
        String t = text.trim();
        if (t.length() == 0) return "(none)\n";
        String[] l = t.split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.length && i < n; i++) b.append(l[i]).append("\n");
        if (l.length > n) b.append("... (" + (l.length - n) + " more)\n");
        return b.toString();
    }

    static String lastLines(String text, int n) {
        String t = text.trim();
        if (t.length() == 0) return "(none)\n";
        String[] l = t.split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, l.length - n); i < l.length; i++) b.append(l[i]).append("\n");
        return b.toString();
    }
}
