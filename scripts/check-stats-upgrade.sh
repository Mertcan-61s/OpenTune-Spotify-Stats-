#!/usr/bin/env bash
# Run only on the disposable CI emulator supplied by the preview workflow.
set -euo pipefail
before=$1
after=$2
output=$3
mkdir -p "$output"
pkg=com.opentune.spotifystats
activity="$pkg/com.opentune.MainActivity"
adb install -g "$before"

measure() {
  local label=$1
  # Warm the initial setup and library migration outside the cold-launch samples.
  adb shell am start -W -n "$activity" > /dev/null
  sleep 8
  for sample in 1 2 3 4 5; do
    adb shell am force-stop "$pkg"
    adb shell input keyevent KEYCODE_HOME
    adb shell am start -W -n "$activity" > "$output/$label-$sample-launch.txt"
    grep -q 'Status: ok' "$output/$label-$sample-launch.txt"
    sleep 5
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
        ("cold_start_ms", "launch", r"TotalTime:\s*(\d+)"),
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
        if len(values) != 5:
            raise RuntimeError(f"Missing {label} {key} observations")
        series[key] = {"samples": values, "median": median(values)}
    summary[label] = series
(root / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps(summary))
PY
