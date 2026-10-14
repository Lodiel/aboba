#!/usr/bin/env bash
# End-to-end test on an Android emulator (API 30+, which has the virtual "AndroidWifi" network).
#
# 1. Starts a fake Starlink dish (tools/e2e/fake_dish.py) on the host.
# 2. Installs the debug APK, grants location, points the app at SSID "AndroidWifi" and at the
#    fake dish (10.0.2.2 is the host as seen from the emulator).
# 3. Checks that launching the app while ALREADY connected does NOT reboot.
# 4. Turns Wi-Fi off and on and checks that the app reboots the dish when the network reappears.
# 5. Kills and relaunches the app while connected and checks that it does NOT reboot again.
#
# Usage: tools/e2e/run-e2e.sh <app-debug.apk> <output-dir>
set -uo pipefail

APK=$1
OUT=$2
PKG=com.aboba.starlinkreconnect
SSID=AndroidWifi
PORT=9200
HERE=$(cd "$(dirname "$0")" && pwd)

mkdir -p "$OUT"
LOG="$OUT/dish.jsonl"
: > "$LOG"

step() { echo; echo "=== $*"; }

dump() {
  adb shell cmd wifi status > "$OUT/wifi-status.txt" 2>&1 || true
  adb shell dumpsys connectivity > "$OUT/connectivity.txt" 2>&1 || true
  adb shell run-as "$PKG" cat shared_prefs/starlink_reconnect.xml > "$OUT/prefs.xml" 2>&1 || true
  adb logcat -d > "$OUT/logcat-full.txt" 2>&1 || true
  grep -E "ReconnectService|StarlinkCommands|WifiReconnector|AndroidRuntime|FATAL" "$OUT/logcat-full.txt" \
    > "$OUT/logcat-app.txt" || true
  adb exec-out screencap -p > "$OUT/screen.png" 2>/dev/null || true
}

fail() {
  echo "E2E FAILED: $*"
  dump
  echo "--- app log (last 80 lines):"
  tail -n 80 "$OUT/logcat-app.txt" || true
  echo "--- fake dish:"
  cat "$OUT/fake_dish.out" || true
  exit 1
}

reboots() { grep -c '"request": "reboot"' "$LOG" || true; }

# wait_for <description> <seconds> <command...>
wait_for() {
  local what=$1 secs=$2; shift 2
  local end=$((SECONDS + secs))
  until "$@" >/dev/null 2>&1; do
    if [ $SECONDS -ge $end ]; then return 1; fi
    sleep 2
  done
  echo "ok: $what"
}

wifi_connected() { adb shell cmd wifi status | grep -q "connected to \"$SSID\""; }
reboots_at_least() { [ "$(reboots)" -ge "$1" ]; }

step "fake dish"
python3 "$HERE/fake_dish.py" --port "$PORT" --log "$LOG" > "$OUT/fake_dish.out" 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true' EXIT
wait_for "fake dish listening" 30 grep -q listening "$OUT/fake_dish.out" || fail "fake dish did not start"

step "device"
adb wait-for-device
wait_for "boot completed" 300 sh -c '[ "$(adb shell getprop sys.boot_completed | tr -d "\r")" = 1 ]' \
  || fail "emulator did not boot"
adb shell getprop ro.build.version.release
adb logcat -c || true

step "install"
adb install -r -g "$APK" || fail "install failed"
adb shell dumpsys package "$PKG" | grep -q "android.permission.INTERNET: granted=true" \
  || fail "INTERNET permission missing: the app cannot open any socket"
adb shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION || true
adb shell pm grant "$PKG" android.permission.ACCESS_BACKGROUND_LOCATION || true
adb shell settings put secure location_mode 3 || true
adb shell cmd location set-location-enabled true || true
adb shell dumpsys deviceidle whitelist +"$PKG" || true

step "wifi"
adb shell svc wifi enable
wait_for "connected to $SSID" 180 wifi_connected || fail "emulator Wi-Fi never connected to $SSID"
adb shell cmd wifi status | head -5

step "configure app"
cat > "$OUT/prefs-in.xml" <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="ssid">$SSID</string>
    <boolean name="enabled" value="true" />
    <boolean name="force_switch" value="true" />
    <boolean name="notify_on_connect" value="true" />
    <boolean name="reboot_enabled" value="true" />
    <string name="reboot_target">dish</string>
    <int name="reboot_delay_sec" value="2" />
    <int name="reboot_cooldown_min" value="0" />
    <string name="dish_addr">10.0.2.2:$PORT</string>
    <string name="router_addr">10.0.2.2:$PORT</string>
</map>
XML
adb push "$OUT/prefs-in.xml" /data/local/tmp/sr_prefs.xml >/dev/null
adb shell chmod 644 /data/local/tmp/sr_prefs.xml
adb shell run-as "$PKG" mkdir -p shared_prefs
adb shell run-as "$PKG" cp /data/local/tmp/sr_prefs.xml shared_prefs/starlink_reconnect.xml \
  || adb shell "run-as $PKG sh -c 'cat > shared_prefs/starlink_reconnect.xml'" < "$OUT/prefs-in.xml"
adb shell run-as "$PKG" cat shared_prefs/starlink_reconnect.xml | grep -q "10.0.2.2:$PORT" \
  || fail "could not write app preferences"

step "1/3: launch while already connected -> must NOT reboot"
adb shell am start -W -n "$PKG/.MainActivity"
wait_for "service seeded its state" 60 sh -c "adb logcat -d -s ReconnectService | grep -q seeded" \
  || fail "service did not start / seed"
sleep 15
[ "$(reboots)" -eq 0 ] || fail "dish was rebooted on app start while already connected"
echo "ok: no reboot on start"

step "2/3: network disappears and comes back -> must reboot"
adb shell svc wifi disable
sleep 6
adb shell svc wifi enable
wait_for "Wi-Fi back on $SSID" 180 wifi_connected || fail "Wi-Fi did not come back"
wait_for "dish received reboot" 120 reboots_at_least 1 || fail "no reboot reached the dish after reconnect"
wait_for "app recorded success" 60 sh -c "adb logcat -d -s ReconnectService | grep -q 'reboot finished: anyOk=true'" \
  || fail "app did not report a successful reboot"
adb logcat -d -s ReconnectService StarlinkCommands | tail -n 15

step "3/3: app killed and relaunched while connected -> must NOT reboot again"
before=$(reboots)
adb shell am force-stop "$PKG"
sleep 2
adb shell am start -W -n "$PKG/.MainActivity"
sleep 25
[ "$(reboots)" -eq "$before" ] || fail "dish was rebooted again after app restart while connected"
echo "ok: no reboot after restart"

dump
echo
echo "E2E PASSED: reboots received by the fake dish: $(reboots)"
