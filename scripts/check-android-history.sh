#!/usr/bin/env bash
set -euo pipefail

# Runs on a disposable Android emulator. It never touches a real device's data.
pkg=com.opentune.spotifystats
apk=$(find android-apk -name '*.apk' -print -quit)
test -n "$apk"
work=${RUNNER_TEMP:-/tmp}/opentune-history-smoke
mkdir -p "$work"

python3 - "$work/history.json" <<'PY'
import json, sys, time
now = int(time.time() * 1000)
with open(sys.argv[1], 'w', encoding='utf-8') as f:
    json.dump([dict(videoId=f'spotify:track-{i % 2000}', title=f'Track {i % 2000}',
                    artist=f'Artist {i % 100}', playedAt=now-i*60000, listenedMs=180000,
                    fromAccount=True) for i in range(100000)], f)
PY

adb install -r "$apk"
adb push "$work/history.json" /data/local/tmp/opentune-history-smoke.json >/dev/null
adb shell run-as "$pkg" mkdir -p files
adb shell "run-as $pkg sh -c 'cat /data/local/tmp/opentune-history-smoke.json > files/history.json'"
adb logcat -c
adb shell am start -W "$pkg/com.opentune.MainActivity"

ready=0
for _ in $(seq 1 90); do
    if ! adb shell pidof "$pkg" | tr -d '\r' | grep -q '[0-9]'; then
        echo 'App process exited while loading the archive.'
        adb logcat -d -v brief | tail -250
        exit 1
    fi
    if adb exec-out run-as "$pkg" cat databases/listening_history_v2.db > "$work/history.db" 2>/dev/null &&
        python3 - "$work/history.db" <<'PY'
import sqlite3, sys
try:
    db = sqlite3.connect(f'file:{sys.argv[1]}?mode=ro', uri=True)
    assert db.execute("select value from metadata where key='initialized'").fetchone() == ('1',)
    assert db.execute('select count(*) from listens').fetchone()[0] == 100000
except Exception:
    sys.exit(1)
PY
    then ready=1; break; fi
    sleep 2
done
test "$ready" = 1 || { echo '100,000-listen migration did not finish.'; exit 1; }

adb shell input swipe 500 1500 500 500 350
adb shell input swipe 500 1500 500 500 350
adb shell dumpsys gfxinfo "$pkg" > "$work/gfxinfo.txt"
adb shell screencap -p /sdcard/opentune-history-smoke.png
adb pull /sdcard/opentune-history-smoke.png "$work/screen.png" >/dev/null
adb shell am force-stop "$pkg"
adb shell am start -W "$pkg/com.opentune.MainActivity"
sleep 5
adb shell pidof "$pkg" | tr -d '\r' | grep -q '[0-9]'
adb logcat -d -v brief > "$work/logcat.txt"
python3 - "$work/logcat.txt" "$pkg" <<'PY'
import sys
log = open(sys.argv[1], encoding='utf-8', errors='replace').read()
package = sys.argv[2]
if f'ANR in {package}' in log:
    raise SystemExit('Android reported an app ANR')
for section in log.split('FATAL EXCEPTION:')[1:]:
    if package in section[:1000]:
        raise SystemExit('Android reported a fatal app crash: ' + section[:1000])
print('100,000-record migration, scrolling and restart completed without a detected crash or ANR.')
PY
