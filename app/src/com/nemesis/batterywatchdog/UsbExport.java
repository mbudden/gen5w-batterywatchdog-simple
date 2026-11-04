package com.nemesis.batterywatchdog;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/** Writes readings, drives and warnings to a USB drive. Runs off the main thread. */
final class UsbExport {
    static final String STATE = RootShell.STATE;
    static final String[] USB_ROOTS = { "/storage/usb0", "/storage/usb1", "/storage/usb2",
            "/mnt/media_rw/usb0", "/mnt/media_rw/usb1" };
    static final String[] STATE_FILES = { "log.csv", "log.old.csv", "sessions", "warnings", "status", "txn",
            "enabled", "warn_pct", "drop_pct", "interval_s", "toast_start", "decode", "scale_x10" };
    static final String NO_USB = "No USB drive found. Insert a FAT32 or exFAT drive and try again.";

    private final RootShell sh;
    private final Firmware firmware;

    UsbExport(RootShell sh, Firmware firmware) { this.sh = sh; this.firmware = firmware; }

    /** the first writable USB root, or null */
    private String usbRoot() {
        StringBuilder probe = new StringBuilder();
        for (String r : USB_ROOTS) {
            probe.append("[ -d " + r + " ] && touch " + r + "/.bwd_w 2>/dev/null && rm " + r + "/.bwd_w && echo " + r + " && exit 0; ");
        }
        String usb = sh.runRoot("( " + probe + " ) 2>/dev/null").trim();
        if (usb.length() == 0 || !usb.startsWith("/")) return null;
        return usb.split("\n")[0].trim();
    }

    /** state files read through root (the app can't read the state dir itself) */
    private Map<String, String> readState(String[] names) {
        Map<String, String> m = new java.util.LinkedHashMap<String, String>();
        for (String f : names) {
            String q = Fmt.shq(STATE + "/" + f);
            if (sh.runRoot("[ -f " + q + " ] && echo y").trim().equals("y")) m.put(f, sh.runRoot("cat " + q + " 2>/dev/null"));
        }
        return m;
    }

    /** Export for sharing: only a zip, dates shifted, no file name or entry time that shows the date. */
    String doShareExport() {
        String usb = usbRoot();
        if (usb == null) return NO_USB;
        Map<String, String> raw = readState(ShareZip.SHARE_FILES);
        if (raw.get("log.csv") == null && raw.get("log.old.csv") == null) return "There are no readings to export yet.";
        String info = "app: " + firmware.appVersion() + "\nfirmware: " + firmware.fw + "\nverification: " + firmware.verifyState + "\n";
        String name = "BatteryWatchdog-share-" + ShareZip.randomId() + ".zip";
        String err;
        try { err = sh.writeRootBytes(usb + "/" + name, ShareZip.share(raw, info, ShareZip.randomShift())); }
        catch (Exception e) { err = e.toString(); }
        sh.runRoot("sync");
        if (err.length() > 0) return "Couldn't write " + name + " to " + usb + ":\n" + err;
        return "Wrote " + name + " to " + usb + ".\n\nIt has the battery log, drives and warnings, with every date moved back "
            + "by the same random number of weeks. Attach it to a \"Share battery data\" issue on GitHub. Thank you!";
    }

    String doExport() {
        String usb = usbRoot();
        if (usb == null) return NO_USB;

        String stamp = new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date());
        String dir = usb + "/BatteryWatchdog-export-" + stamp;
        sh.runRoot("mkdir -p '" + dir + "/raw'");

        // raw copies (cat: KitKat's toolbox has no cp)
        StringBuilder cp = new StringBuilder();
        for (String f : STATE_FILES) cp.append("[ -f " + STATE + "/" + f + " ] && cat " + STATE + "/" + f + " > '" + dir + "/raw/" + f + "'; ");
        sh.runRoot(cp.toString());

        // readable files built here, then copied over by root
        SimpleDateFormat t = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        int rows = 0;
        StringBuilder rd = new StringBuilder("time,event,v12_pct,main_battery_pct,outside_f,hv_soh,raw,sensor_state,sensor_invalid,sensor_error,aux_saver_opt,charging_status\n");
        String log = sh.runRoot("cat " + STATE + "/log.old.csv " + STATE + "/log.csv 2>/dev/null");
        for (String line : log.split("\n")) {
            String[] f = line.split(",", -1);
            if (f.length < 4) continue;
            long ts = Fmt.parseL(f[0], 0);
            if (ts <= 0) continue;
            int p = Fmt.parse(f[3], -1);
            String hv = f.length > 5 && Fmt.parse(f[5], -1) >= 0 ? Fmt.fmt10(Fmt.parse(f[5], -1)) : "";
            String tf = f.length > 6 && f[6].trim().length() > 0 ? String.valueOf(Math.round(Fmt.parse(f[6], 0) * 9 / 5f + 32)) : "";
            rd.append(t.format(new Date(ts * 1000))).append(',').append(f[1]).append(',')
              .append(p >= 0 ? Fmt.fmt10(p) : "").append(',').append(hv).append(',').append(tf).append(',')
              .append(f.length > 4 ? f[4] : "").append(',').append(f[2]);
            for (int k = 7; k <= 11; k++) rd.append(',').append(f.length > k ? f[k] : "");
            rd.append('\n');
            rows++;
        }

        // one row per finished drive, with the parked time and change before it
        StringBuilder dv = new StringBuilder("drive_start,drive_minutes,v12_start_pct,v12_end_pct,change_during_drive_pts,parked_before_minutes,change_while_parked_pts\n");
        long prevEnd = -1; int prevEndPct = -1;
        int drives = 0;
        for (String line : sh.runRoot("cat " + STATE + "/sessions 2>/dev/null").split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 4) continue;
            long st = Fmt.parseL(f[0], 0); int min = Fmt.parse(f[1], -1), sp = Fmt.parse(f[2], -1), ep = Fmt.parse(f[3], -1);
            if (st <= 0 || min < 0 || sp < 0 || ep < 0) continue;
            String parked = prevEnd > 0 && st > prevEnd ? String.valueOf((st - prevEnd) / 60) : "";
            String pchg = prevEnd > 0 && st > prevEnd ? Fmt.fmtSigned10(sp - prevEndPct) : "";
            dv.append(t.format(new Date(st * 1000))).append(',').append(min).append(',').append(Fmt.fmt10(sp)).append(',')
              .append(Fmt.fmt10(ep)).append(',').append(Fmt.fmtSigned10(ep - sp)).append(',').append(parked).append(',').append(pchg).append('\n');
            prevEnd = st + min * 60L; prevEndPct = ep; drives++;
        }

        StringBuilder wr = new StringBuilder();
        for (String line : sh.runRoot("cat " + STATE + "/warnings 2>/dev/null").split("\n")) {
            String l = line.trim();
            int sp = l.indexOf(' ');
            if (sp <= 0) continue;
            long ts = Fmt.parseL(l.substring(0, sp), 0);
            wr.append(ts > 0 ? t.format(new Date(ts * 1000)) : l.substring(0, sp)).append("  ").append(l.substring(sp + 1)).append('\n');
        }
        if (wr.length() == 0) wr.append("No warnings recorded.\n");

        String err = "";
        err += writeToUsb("readings.csv", rd.toString(), dir);
        err += writeToUsb("drives.csv", dv.toString(), dir);
        err += writeToUsb("warnings.txt", wr.toString(), dir);
        String diag = "Battery Watchdog export " + stamp + "\n\n" + Diagnostics.build(sh, firmware);
        err += writeToUsb("diagnostics.txt", diag, dir);
        // the same files as one zip, ready to attach to an issue
        try {
            Map<String, String> z = new java.util.LinkedHashMap<String, String>();
            z.put("readings.csv", rd.toString()); z.put("drives.csv", dv.toString());
            z.put("warnings.txt", wr.toString()); z.put("diagnostics.txt", diag);
            for (Map.Entry<String, String> e : readState(STATE_FILES).entrySet()) z.put("raw/" + e.getKey(), e.getValue());
            String zr = sh.writeRootBytes(dir + ".zip", ShareZip.plain(z));
            if (zr.length() > 0) err += "zip: " + zr + "\n";
        } catch (Exception e) { err += "zip: " + e + "\n"; }
        sh.runRoot("sync");
        String rawList = sh.runRoot("ls " + Fmt.shq(dir + "/raw") + " 2>&1").trim().replace("\n", " ");
        if (err.length() > 0) return "Some files couldn't be written to " + dir + ":\n" + err
            + "\nraw/ on the drive: " + (rawList.length() > 0 ? rawList : "(empty)");
        return "Exported " + rows + " readings and " + drives + " drives (written via " + sh.lastWriteMethod + ") to\n" + dir
            + "\n\nreadings.csv and drives.csv open in Excel or Google Sheets. raw/ has the original files. "
            + "The .zip beside the folder has the same files, ready to attach to a \"Share battery data\" issue.";
    }

    private String writeToUsb(String name, String content, String dir) {
        String r = sh.writeRoot(dir + "/" + name, content);
        return r.length() == 0 ? "" : name + ": " + r + "\n";
    }
}
