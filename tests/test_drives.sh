#!/bin/sh
# Checks the home screen's numbers (drive merging, the 7-day summary, chart bounds,
# display text) on a computer. Needs: javac, java.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
APP="$ROOT/app/src/com/nemesis/batterywatchdog"
W=$(mktemp -d)
# Config.java only needs RootShell's signature to compile, so give it a stub.
mkdir -p "$W/stub/com/nemesis/batterywatchdog"
cat > "$W/stub/com/nemesis/batterywatchdog/RootShell.java" <<'EOF'
package com.nemesis.batterywatchdog;
final class RootShell {
    static final String STATE = "";
    int readInt(String name, int def) { return def; }
    String runRoot(String cmd) { return ""; }
}
EOF
javac --release 8 -Xlint:-options -d "$W/out" "$APP/Fmt.java" "$APP/Drives.java" "$APP/Config.java" \
    "$W/stub/com/nemesis/batterywatchdog/RootShell.java" "$ROOT/tests/drives/DrivesTest.java"
java -cp "$W/out" com.nemesis.batterywatchdog.DrivesTest
rm -rf "$W"
