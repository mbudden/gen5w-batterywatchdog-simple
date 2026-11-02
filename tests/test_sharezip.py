#!/usr/bin/env python3
"""The app's "Export for sharing, dates hidden" zip (ShareZip.java) against
tools/analyze.py: same layout, every date moved by whole weeks, no real date
left in the zip's entry times, and the same analysis result."""
import os, subprocess, sys, tempfile, zipfile
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import test_analyze as T

def main():
    with tempfile.TemporaryDirectory() as d:
        cls = os.path.join(d, "cls"); os.makedirs(cls)
        subprocess.run(["javac", "-nowarn", "-d", cls,
                        os.path.join(ROOT, "app/src/com/nemesis/batterywatchdog/ShareZip.java"),
                        os.path.join(HERE, "sharezip/ShareZipMain.java")], check=True, capture_output=True)
        T.simulate(d)
        raw = os.path.join(d, "raw")
        with open(os.path.join(raw, "sessions"), "w") as f: f.write("1780000000 40 820 900\n")
        with open(os.path.join(raw, "warnings"), "w") as f: f.write("1780003600 12V battery low (58%)\n")
        with open(os.path.join(raw, "warn_pct"), "w") as f: f.write("60\n")
        with open(os.path.join(raw, "status"), "w") as f: f.write("private=1\n")   # must not be included
        z = os.path.join(d, "share.zip")
        rid = subprocess.run(["java", "-cp", cls, "com.nemesis.batterywatchdog.ShareZipMain", raw, z, "random"],
                             check=True, capture_output=True, text=True).stdout.strip()
        ok = True
        def check(cond, what):
            nonlocal ok
            ok &= bool(cond)
            print(f"  {'ok' if cond else 'FAIL':4} {what}")
        with zipfile.ZipFile(z) as zf:
            names = sorted(zf.namelist())
            check(names == ["info.txt", "raw/log.csv", "raw/sessions", "raw/warn_pct", "raw/warnings"], f"only the shared files: {names}")
            check(all(i.date_time[:3] == (1980, 1, 2) for i in zf.infolist()), "entry times hold no real date")
            log = zf.read("raw/log.csv").decode(); ses = zf.read("raw/sessions").decode(); war = zf.read("raw/warnings").decode()
            ex = os.path.join(d, "x"); zf.extractall(ex)
        orig = open(os.path.join(raw, "log.csv")).read().splitlines()
        new = log.splitlines()
        check(len(orig) == len(new), "every log line kept")
        delta = int(orig[0].split(",")[0]) - int(new[0].split(",")[0])
        check(delta > 0 and delta % (7 * 86400) == 0, f"dates moved back by whole weeks ({delta // 86400} days)")
        check(all(int(o.split(",")[0]) - int(n.split(",")[0]) == delta and o.split(",")[1:] == n.split(",")[1:]
                  for o, n in zip(orig, new)), "same shift on every line, values untouched")
        check(ses.startswith(str(1780000000 - delta)) and war.startswith(str(1780003600 - delta)), "sessions and warnings shifted too")
        check(len(rid) == 4 and all(c in "0123456789abcdef" for c in rid), f"file-name id has no date ({rid})")
        got, _ = T.run(ex)
        check(got == "Normal", f"analyze.py reads it: {got}")
    sys.exit(0 if ok else 1)

if __name__ == "__main__":
    main()
