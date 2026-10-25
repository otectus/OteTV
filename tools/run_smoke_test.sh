#!/usr/bin/env bash
# Runs the client smoke test (runClient -PsmokeTest): a dev client creates a flat world, places a plasma TV, plays the
# bundled demo through the real media worker, pauses, seeks, opens every TV screen, saves
# run/screenshots/otetv_smoke_*.png and run/smoketest/smoketest-result.json, then quits. Under Hyprland the tiling layout
# would choose the window size, so the test client's own window (found by its PID) is floated and sized; no other
# window is touched. Never compile the project while it runs: the client loads classes from build/.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
QUIET=/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh

float_client() {
    command -v hyprctl > /dev/null || return 0
    for _ in $(seq 1 300); do
        local pid
        pid="$(pgrep -f 'java.*otetv\.smoketest\.output' | head -n 1)"
        if [ -n "$pid" ] && hyprctl clients -j | python3 -c "import json,sys; sys.exit(0 if any(c['pid'] == $pid for c in json.load(sys.stdin)) else 1)"; then
            # Hyprland 0.55+ Lua dispatchers; the exact pid selector touches no other window.
            hyprctl dispatch "hl.dsp.window.float({ action = \"enable\", window = \"pid:$pid\" })" > /dev/null
            hyprctl dispatch "hl.dsp.window.resize({ x = 1280, y = 720, relative = false, window = \"pid:$pid\" })" > /dev/null
            return 0
        fi
        sleep 1
    done
}

mkdir -p "$ROOT/run"
rm -rf "$ROOT/run/smoketest"
rm -f "$ROOT"/run/screenshots/otetv_smoke_*.png
"$QUIET" "$ROOT" runClient -PsmokeTest > "$ROOT/run/smoketest-run.txt" 2>&1 &
pid=$!
float_client
wait "$pid"
cat "$ROOT/run/smoketest-run.txt" | tail -n 3
if [ -f "$ROOT/run/smoketest/smoketest-result.json" ]; then
    python3 -c "import json; d = json.load(open('$ROOT/run/smoketest/smoketest-result.json')); print('pass', d['pass'], '-', d['reason'])"
else
    echo "no result file"
fi
