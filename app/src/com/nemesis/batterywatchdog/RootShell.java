package com.nemesis.batterywatchdog;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Reads and writes /data/nemesis/state through the privileged `nemesis root -c`
 * channel (this app runs as an ordinary uid).
 */
final class RootShell {
    static final String STATE = "/data/nemesis/state/battery.watchdog";
    static final String NEM = "/system/xbin/nemesis";
    static final String SVC_ID = "battery.watchdog/watch";
    static final String SVC_LOG = "/data/nemesis/logs/battery.watchdog/watch.log";
    static final String SVC_SCRIPT = "/data/nemesis/apps/battery.watchdog/svc/watchdog.sh";

    private final Context context;
    String lastWriteMethod = "";

    RootShell(Context context) { this.context = context; }

    String runRoot(String cmd) {
        StringBuilder out = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[] { NEM, "root", "-c", cmd });
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) out.append(line).append("\n");
            p.waitFor();
        } catch (Exception e) {
            return "ERR " + e.getMessage();
        }
        return out.toString();
    }

    int readInt(String name, int def) {
        return Fmt.parse(runRoot("cat " + STATE + "/" + name + " 2>/dev/null").trim(), def);
    }

    void writeStateFile(String name, String content) {
        writeRoot(STATE + "/" + name, content);
    }

    /**
     * Writes {@code content} to {@code path} as root. Getting text from this app
     * to a root shell isn't straightforward on the head unit, so it tries several
     * ways in turn and checks each by reading the file back:
     *   1. stage it in the app's external files dir (shared storage, readable by
     *      root), then `cat` it into place;
     *   2. stage it in the app's cache, world-readable, then `cat`;
     *   3. pipe it to `cat > path` on the root shell's stdin;
     *   4. append it in small chunks with mksh's `print -r` (no escape handling).
     * Returns "" on success, otherwise what each attempt did. lastWriteMethod
     * records which one worked (shown in Diagnostics).
     */
    String writeRoot(String path, String content) {
        String q = Fmt.shq(path);
        StringBuilder why = new StringBuilder();
        byte[] bytes;
        try { bytes = content.getBytes("UTF-8"); } catch (Exception e) { return e.toString(); }
        String staged = writeRootStaged(path, bytes, content, why);
        if (staged != null) return staged;

        // 4: small chunks, raw print (mksh's echo would interpret backslashes)
        runRoot(": > " + q);
        StringBuilder cmd = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            cmd.append("print -r -- ").append(Fmt.shq(line)).append(" >> ").append(q).append("; ");
            if (cmd.length() > 1500) { runRoot(cmd.toString()); cmd.setLength(0); }
        }
        if (cmd.length() > 0) runRoot(cmd.toString());
        if (sameContent(path, content)) { lastWriteMethod = "chunks"; return ""; }
        why.append("chunks: mismatch");
        lastWriteMethod = "failed";
        return why.toString();
    }

    /** Write bytes to a root-only path (stage then cat, or stdin); verified by size. */
    String writeRootBytes(String path, byte[] bytes) {
        StringBuilder why = new StringBuilder();
        String r = writeRootStaged(path, bytes, null, why);
        if (r != null) return r;
        lastWriteMethod = "failed";
        return why.toString();
    }

    /**
     * "" when written, null when every binary-safe way failed (reasons in why).
     * text, when given, is the same content as a string: short files are then also
     * checked by content if the size can't be read.
     */
    private String writeRootStaged(String path, byte[] bytes, String text, StringBuilder why) {
        String q = Fmt.shq(path);

        // 1 + 2: stage a file, root copies it
        File[] stages = { stageDir(true), stageDir(false) };
        String[] names = { "external", "cache" };
        for (int i = 0; i < stages.length; i++) {
            File dir = stages[i];
            if (dir == null) { why.append(names[i]).append(": no dir; "); continue; }
            File tmp = new File(dir, "bwd-stage.tmp");
            try {
                java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                try { out.write(bytes); } finally { out.close(); }
                tmp.setReadable(true, false);
                dir.setExecutable(true, false);
                String r = runRoot("cat " + Fmt.shq(tmp.getAbsolutePath()) + " > " + q + " 2>&1").trim();
                boolean ok = written(path, bytes, text);
                tmp.delete();
                if (ok) { lastWriteMethod = names[i]; return ""; }
                why.append(names[i]).append(": ").append(r.length() > 0 ? r : "mismatch").append("; ");
            } catch (Exception e) {
                tmp.delete();
                why.append(names[i]).append(": ").append(e.getMessage()).append("; ");
            }
        }

        // 3: stdin
        try {
            Process p = Runtime.getRuntime().exec(new String[] { NEM, "root", "-c", "cat > " + q });
            java.io.OutputStream o = p.getOutputStream();
            o.write(bytes);
            o.close();
            p.waitFor();
            if (written(path, bytes, text)) { lastWriteMethod = "stdin"; return ""; }
            why.append("stdin: mismatch; ");
        } catch (Exception e) { why.append("stdin: ").append(e.getMessage()).append("; "); }
        return null;
    }

    private boolean written(String path, byte[] bytes, String text) {
        return text != null ? sameContent(path, text) : fileSize(path) == bytes.length;
    }

    private File stageDir(boolean external) {
        try {
            File d = external ? context.getExternalFilesDir(null) : context.getCacheDir();
            if (d == null) return null;
            d.mkdirs();
            return d.isDirectory() ? d : null;
        } catch (Exception e) { return null; }
    }

    /**
     * Was the file written completely? Compares its size on disk with the bytes
     * we meant to write. (Reading the whole file back through the root shell
     * wasn't reliable on the car: an export that worked was reported as failed,
     * most likely because long output gets cut off on the way back.)
     */
    private boolean sameContent(String path, String content) {
        long want;
        try { want = content.getBytes("UTF-8").length; } catch (Exception e) { return false; }
        long got = fileSize(path);
        if (got >= 0) return got == want;
        // size unknown: fall back to comparing short files by content
        if (want > 4096) return false;
        String back = runRoot("cat " + Fmt.shq(path) + " 2>/dev/null");
        return back.trim().equals(content.trim());
    }

    /** File size from `ls -l` as root, or -1. Works with toolbox and busybox formats. */
    private long fileSize(String path) {
        String l = runRoot("ls -l " + Fmt.shq(path) + " 2>/dev/null").trim();
        if (l.length() == 0 || l.startsWith("ls:")) return -1;
        String[] t = l.split("\\s+");
        // the size is the last plain number before the date (2026-10-30 or Oct)
        for (int i = 1; i < t.length; i++) {
            if (t[i].matches("\\d{4}-\\d\\d-\\d\\d") || t[i].matches("[A-Z][a-z]{2}")) {
                for (int j = i - 1; j > 0; j--) if (t[j].matches("\\d+")) return Long.parseLong(t[j]);
                return -1;
            }
        }
        return -1;
    }
}
