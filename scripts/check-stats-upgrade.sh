#!/usr/bin/env bash
# Run only on the disposable CI emulator supplied by the preview workflow.
set -euo pipefail
before=$1
after=$2
output=$3
mkdir -p "$output"
pkg=com.opentune.spotifystats
activity="$pkg/com.opentune.MainActivity"
# Bound individual device commands even when Activity Manager stops replying.
adb() { timeout 45s "$adb_bin" "$@"; }
adb_bin=$(type -P adb)
adb shell svc power stayon true
adb shell wm dismiss-keyguard
adb install -g "$before"

measure() {
  local label=$1
  # Warm the initial setup and library migration outside the cold-launch samples.
  adb shell am start -n "$activity" > /dev/null
  sleep 8
  for sample in 1 2 3 4 5; do
    echo "Measuring $label launch $sample"
    adb shell am force-stop "$pkg"
    adb logcat -b events -c
    adb shell am start -n "$activity"
    sleep 8
    adb logcat -b events -d -s wm_activity_launch_time > "$output/$label-$sample-launch.txt"
    adb shell pidof "$pkg" > /dev/null
    adb shell dumpsys meminfo "$pkg" > "$output/$label-$sample-memory.txt"
    adb shell dumpsys gfxinfo "$pkg" > "$output/$label-$sample-frames.txt"
  done
}
measure before
first_install() {
  adb shell dumpsys package "$pkg" | sed -n 's/.*firstInstallTime=//p' | head -1
}
initial=$(first_install)
[[ -n "$initial" ]]
adb install -r -g "$after"
[[ "$(first_install)" == "$initial" ]]
printf 'Updated in place. First installation timestamp: %s\n' "$initial" | tee "$output/upgrade.txt"
measure after
python3 - "$output" <<'PY'
import json
from pathlib import Path
import re
from statistics import median
import sys
root = Path(sys.argv[1])
summary = {}
for label in ["before", "after"]:
    series = {}
    fields = [
        ("cold_start_ms", "launch", r"com\.opentune\.spotifystats/[^,]+,\s*(\d+)\]"),
        ("total_pss_kib", "memory", r"TOTAL PSS:\s*([\d,]+)"),
        ("rendered_frames", "frames", r"Total frames rendered:\s*(\d+)"),
        ("janky_frames", "frames", r"Janky frames:\s*(\d+)"),
    ]
    for key, suffix, pattern in fields:
        values = []
        for filename in sorted(root.glob(f"{label}-*-{suffix}.txt")):
            match = re.search(pattern, filename.read_text())
            if match:
                values.append(int(match[1].replace(",", "")))
        series[key] = {"samples": values, "median": median(values) if values else None}
    summary[label] = series
(root / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps(summary))
PY
