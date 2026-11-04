package com.nemesis.batterywatchdog;

import android.content.Context;
import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Firmware verification. Transaction numbers can shift between firmware builds,
 * so they're looked up BY NAME in this firmware's own ITM_HSCANService definition
 * and saved (with the firmware id) for the service. Same rules as verify() in
 * svc/watchdog.sh; hand-verified builds are listed in known_firmware.txt.
 */
final class Firmware {
    static final String STATE = RootShell.STATE;
    static final String IFACE = "com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService";
    static final String KNOWN_FILE = "/data/nemesis/apps/battery.watchdog/known_firmware.txt";
    static final String[] SEARCH_DIRS = { "/system/framework", "/system/app", "/system/priv-app",
            "/vendor/framework", "/vendor/app", "/system/vendor/app", "/system/vendor/framework" };
    static final byte[] NEEDLE = "tm_interface/ITM_HSCANService$Stub;".getBytes();
    /**
     * Read-only getters logged alongside the 12V reading, raw, until their values
     * are understood: the 12V battery sensor's state, invalid and error flags, the
     * Auxiliary Battery Saver option, and HV charging status. Only ever taken from
     * a lookup by name on this firmware (never from known_firmware.txt or an
     * override), so a number from another build can't be called by mistake.
     */
    static final String[][] EXTRAS = {
        { "sns_state", "getBATSNSRState" }, { "sns_invalid", "getBATSNSRInvalid" },
        { "sns_error", "getBATSNSRError" }, { "aux_opt", "get_BAT_AuxBattOpt" },
        { "chg_status", "getChargingStatus" } };

    private final Context context;
    private final RootShell sh;
    String fw = "", verifyState = "checking", verifySource = "";
    int txSoc = -1, txHv = -1, txSoh = -1;
    final Map<String, Integer> txExtra = new HashMap<String, Integer>();

    Firmware(Context context, RootShell sh) { this.context = context; this.sh = sh; }

    boolean verified() { return txSoc > 0; }

    /** This build's version name (stamped into the manifest at build time). */
    String appVersion() {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    /** Returns resolved | saved | known_firmware | override | interface_mismatch | unverified. */
    String verify() {
        txSoc = txHv = txSoh = -1; txExtra.clear();
        fw = sh.runRoot("getprop ro.build.version.incremental").trim();
        String svcList = sh.runRoot("service list 2>/dev/null");
        if (!svcList.contains("tm_hscan_service: [" + IFACE + "]")) return "interface_mismatch";

        // 1. numbers already resolved (or overridden) for exactly this firmware
        String saved = sh.runRoot("cat " + STATE + "/txn 2>/dev/null");
        if (fw.length() > 0 && fw.equals(Fmt.kv(saved, "fw")) && Fmt.parse(Fmt.kv(saved, "soc"), -1) > 0) {
            txSoc = Fmt.parse(Fmt.kv(saved, "soc"), -1); txHv = Fmt.parse(Fmt.kv(saved, "hv"), -1); txSoh = Fmt.parse(Fmt.kv(saved, "soh"), -1);
            verifySource = Fmt.kv(saved, "source");
            if ("manual-override".equals(verifySource)) return "override";
            for (String[] x : EXTRAS) { int n = Fmt.parse(Fmt.kv(saved, x[0]), -1); if (n > 0) txExtra.put(x[0], n); }
            // saved by a version that didn't look up the extra getters: look them up once
            if (Fmt.kv(saved, "ext").length() == 0 && verifySource.startsWith("/")) {
                int soc = txSoc, hv = txHv, soh = txSoh;
                String r = resolveByName(new StringBuilder());
                if (r != null && txSoc == soc) { verifySource = r; saveTxn(r); }
                else { txSoc = soc; txHv = hv; txSoh = soh; saveTxn(verifySource); }   // keep the old numbers, mark as checked
            }
            return "resolved (saved earlier)";
        }

        // 2. resolve by name from the firmware's own interface definition.
        //    Skipped if this app version already searched this firmware and found nothing.
        String missKey = fw + " " + appVersion();
        if (!missKey.equals(sh.runRoot("cat " + STATE + "/resolve_miss 2>/dev/null").trim())) {
            StringBuilder lg = new StringBuilder();
            String r = resolveByName(lg);
            sh.runRoot("mkdir -p " + STATE);
            sh.writeStateFile("resolve.log", lg.toString());
            if (r != null) { verifySource = r; saveTxn(r); return "resolved"; }
            sh.writeStateFile("resolve_miss", missKey + "\n");
        }

        // 3. hand-verified firmware
        // one line per build: <fw> <soc> <hv> <soh>  # comment ("-" = not available)
        for (String line : sh.runRoot("cat " + KNOWN_FILE + " 2>/dev/null").split("\n")) {
            String l = line.trim();
            if (l.length() == 0 || l.startsWith("#")) continue;
            String[] f = l.split("\\s+");
            if (f.length < 2 || !f[0].equals(fw) || Fmt.parse(f[1], -1) <= 0) continue;
            txSoc = Fmt.parse(f[1], -1);
            txHv = f.length > 2 ? Fmt.parse(f[2], -1) : -1;
            txSoh = f.length > 3 ? Fmt.parse(f[3], -1) : -1;
            verifySource = "known_firmware.txt";
            return "known_firmware";
        }
        return "unverified";
    }

    /**
     * Finds ITM_HSCANService$Stub in the firmware and reads its transaction codes.
     * Pass 1: .odex/.dex files, searched in 1 MiB chunks, parsed only on a hit.
     * Pass 2: classes*.dex inside .jar/.apk (builds that aren't odexed).
     * Returns the source path, or null. Everything it tries goes into {@code lg}.
     */
    private String resolveByName(StringBuilder lg) {
        java.util.List<File> plain = new java.util.ArrayList<File>(), zips = new java.util.ArrayList<File>();
        for (String dname : SEARCH_DIRS) collect(new File(dname), plain, zips, 2);
        lg.append("searched ").append(plain.size()).append(" odex/dex and ").append(zips.size()).append(" jar/apk files\n");
        for (File f : plain) {
            try {
                boolean hit;
                try { hit = TxnResolver.fileContains(f, NEEDLE); }
                catch (java.io.IOException e) { File c = viaRoot(f); hit = c != null && TxnResolver.fileContains(c, NEEDLE); if (!hit && c != null) c.delete(); }
                if (!hit) continue;
                lg.append("found Stub in ").append(f).append("\n");
                Map<String, Integer> m;
                try { m = TxnResolver.transactions(f, TxnResolver.STUB); }
                catch (java.io.IOException e) { File c = viaRoot(f); m = TxnResolver.transactions(c, TxnResolver.STUB); c.delete(); }
                lg.append("  ").append(m.size()).append(" transactions; getBatSoc=").append(m.get("getBatSoc")).append("\n");
                if (useMap(m)) return f.getPath();
            } catch (Throwable t) {
                lg.append("  ").append(f).append(": ").append(t).append("\n");
            }
        }
        for (File f : zips) {
            try {
                Map<String, Integer> m = TxnResolver.transactionsInZip(f, TxnResolver.STUB, NEEDLE);
                if (m.isEmpty()) continue;
                lg.append("found Stub in ").append(f).append(": ").append(m.size()).append(" transactions; getBatSoc=").append(m.get("getBatSoc")).append("\n");
                if (useMap(m)) return f.getPath();
            } catch (Throwable t) {
                lg.append("  ").append(f).append(": ").append(t).append("\n");
            }
        }
        lg.append("not found\n");
        return null;
    }

    private boolean useMap(Map<String, Integer> m) {
        Integer soc = m.get("getBatSoc");
        if (soc == null) return false;
        Integer hv = m.get("remainBatteryPercentage"), soh = m.get("getXEV_Bms_GDS_SOH");
        txSoc = soc; txHv = hv == null ? -1 : hv; txSoh = soh == null ? -1 : soh;
        txExtra.clear();
        for (String[] x : EXTRAS) { Integer n = m.get(x[1]); if (n != null && n > 0) txExtra.put(x[0], n); }
        return true;
    }

    private static void collect(File dir, java.util.List<File> plain, java.util.List<File> zips, int depth) {
        File[] l = dir.listFiles();
        if (l == null) return;
        for (File f : l) {
            String n = f.getName().toLowerCase(Locale.US);
            if (f.isDirectory()) { if (depth > 1) collect(f, plain, zips, depth - 1); }
            else if (n.endsWith(".odex") || n.endsWith(".dex")) plain.add(f);
            else if (n.endsWith(".jar") || n.endsWith(".apk")) zips.add(f);
        }
    }

    /** Copy a file this app can't read into its cache via root; null on failure. */
    private File viaRoot(File f) {
        File tmp = new File(context.getCacheDir(), "resolve.bin");
        sh.runRoot("cat '" + f.getPath() + "' > '" + tmp.getAbsolutePath() + "' && chmod 644 '" + tmp.getAbsolutePath() + "'");
        return tmp.length() > 0 ? tmp : null;
    }

    void saveTxn(String source) {
        // plain echo: KitKat has no printf binary and mksh R43 has no printf builtin
        String f = STATE + "/txn";
        sh.runRoot("mkdir -p " + STATE
            + "; echo 'fw=" + fw + "' > " + f
            + "; echo 'soc=" + txSoc + "' >> " + f
            + "; echo 'hv=" + (txHv > 0 ? txHv : "") + "' >> " + f
            + "; echo 'soh=" + (txSoh > 0 ? txSoh : "") + "' >> " + f
            + extrasEcho(f, source)
            + "; echo 'source=" + source + "' >> " + f);
    }

    /** the extra getters' lines for the txn file; none for an override (and ext=1: looked up) */
    private String extrasEcho(String f, String source) {
        if ("manual-override".equals(source)) return "";
        StringBuilder b = new StringBuilder();
        for (String[] x : EXTRAS) {
            Integer n = txExtra.get(x[0]);
            b.append("; echo '").append(x[0]).append('=').append(n == null ? "" : String.valueOf(n)).append("' >> ").append(f);
        }
        return b.append("; echo 'ext=1' >> ").append(f).toString();
    }

    /** Use the built-in numbers after the owner has confirmed them on this car. */
    void applyOverride() {
        txSoc = 137; txHv = 74; txSoh = 214; saveTxn("manual-override");
        verifyState = "override"; verifySource = "manual-override";
    }

    // SOH: tm_hscan_service 214 (0xd6) returns one raw byte, a health index (100 = new).
    int readSoh() {
        if (txSoh <= 0) return -1;                       // not verified on this firmware: don't call
        String r = sh.runRoot("service call tm_hscan_service " + txSoh).trim();
        int i = r.indexOf("Parcel(");
        if (i < 0) return -1;
        String[] w = r.substring(i + 7).trim().split("\\s+");
        if (w.length < 2 || !"00000000".equals(w[0])) return -1;
        try { long v = Long.parseLong(w[1], 16); return v > 0 && v <= 100 ? (int) v : -1; } catch (Exception e) { return -1; }
    }

    /**
     * Battery-related function names in the telematics service's own interface
     * definition (the file the lookup found), with their transaction numbers.
     * Read from the firmware file only: nothing is called. This is how to find
     * out whether the firmware exposes more 12V data than getBatSoc.
     */
    String batteryFunctions() {
        String src = Fmt.kv(sh.runRoot("cat " + STATE + "/txn 2>/dev/null"), "source");
        if (!src.startsWith("/")) return "(no interface file recorded yet: open the app on a verified firmware first)";
        try {
            File f = new File(src);
            Map<String, Integer> m;
            String n = src.toLowerCase(Locale.US);
            if (n.endsWith(".apk") || n.endsWith(".jar")) m = TxnResolver.transactionsInZip(f, TxnResolver.STUB, NEEDLE);
            else {
                try { m = TxnResolver.transactions(f, TxnResolver.STUB); }
                catch (java.io.IOException e) { File c = viaRoot(f); m = TxnResolver.transactions(c, TxnResolver.STUB); c.delete(); }
            }
            java.util.TreeMap<Integer, String> hits = new java.util.TreeMap<Integer, String>();
            for (Map.Entry<String, Integer> e : m.entrySet()) {
                if (e.getKey().matches("(?i).*(bat|ibs|volt|soh|soc|12v|aux|ldc|charg).*")) hits.put(e.getValue(), e.getKey());
            }
            StringBuilder b = new StringBuilder(m.size() + " functions in " + src + "; matching:\n");
            for (Map.Entry<Integer, String> e : hits.entrySet()) b.append("  ").append(e.getKey()).append("  ").append(e.getValue()).append("\n");
            if (hits.isEmpty()) b.append("  (none)\n");
            return b.toString();
        } catch (Throwable t) {
            return "couldn't read " + src + ": " + t;
        }
    }
}
