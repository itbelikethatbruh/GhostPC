#!/usr/bin/env bash
# Launch test for the CI emulator: installs the APK, uses the app the way a player would
# (cold start, open the menu, visit every tab, open a site that asks for multithreading),
# saves screenshots and logs to $OUT, and fails if the app process crashes or dies.
#
# Usage: smoke-test.sh <apk> [output-dir]
set -uo pipefail

APK="$1"
OUT="${2:-smoke}"
PKG="com.radiant.pcbrowser"
ACTIVITY="$PKG/.MainActivity"
mkdir -p "$OUT"

shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
crashed() { adb logcat -d -b crash 2>/dev/null | grep -q "$PKG"; }
alive() { [ -n "$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')" ]; }

save_logs() {
  adb logcat -d -b crash > "$OUT/crash.txt" 2>&1 || true
  adb logcat -d > "$OUT/logcat.txt" 2>&1 || true
}

fail() {
  echo "::error::$1"
  shot "zz-failure"
  save_logs
  if [ -s "$OUT/crash.txt" ]; then
    echo "----- crash buffer -----"
    head -n 80 "$OUT/crash.txt"
  fi
  exit 1
}

# Taps the on-screen view whose content description is $1 (rail tabs, buttons).
tap_desc() {
  local coords=""
  for _ in 1 2 3 4; do
    if adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 &&
       adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1; then
      coords=$(python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    if node.get("content-desc") == sys.argv[2]:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
      if [ -n "$coords" ]; then
        adb shell input tap $coords
        return 0
      fi
    fi
    sleep 1
  done
  echo "::warning::Could not find '$1' on screen"
  return 1
}

adb shell dumpsys package com.google.android.webview 2>/dev/null | grep -m1 versionName > "$OUT/webview-version.txt" || true
echo "Emulator WebView: $(cat "$OUT/webview-version.txt" 2>/dev/null)"

adb install -r "$APK" || fail "Could not install $APK"
adb logcat -c || true
adb logcat -b crash -c || true

echo "== Cold start"
adb shell am start -W -n "$ACTIVITY" || fail "Could not start the app"
sleep 1.5
shot "01-launch"
sleep 20
crashed && fail "The app crashed during start-up"
alive || fail "The app is not running after start-up"
shot "02-home"

echo "== Menu"
adb shell input keyevent KEYCODE_BACK
sleep 2
shot "03-menu-games"
for tab in Controls Display Buttons Status; do
  if tap_desc "$tab"; then
    sleep 1.5
    shot "04-menu-$(echo "$tab" | tr '[:upper:]' '[:lower:]')"
  fi
done
crashed && fail "The app crashed while using the menu"
adb shell input keyevent KEYCODE_BACK
sleep 1

echo "== Site that asks for multithreading"
adb shell am start -W -n "$ACTIVITY" -a android.intent.action.VIEW -d "https://vel.gg/bsz/cotd" || true
sleep 30
crashed && fail "The app crashed on vel.gg"
alive || fail "The app is not running after opening vel.gg"
shot "05-velgg"
adb shell input keyevent KEYCODE_BACK
sleep 2
if tap_desc "Status"; then
  sleep 1.5
  shot "06-velgg-status"
fi

crashed && fail "The app crashed"
save_logs
grep -E "RadiantIsolation|Radiant" "$OUT/logcat.txt" | tail -n 40 || true
echo "Launch test passed"
