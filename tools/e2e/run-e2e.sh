#!/usr/bin/env bash
# End-to-end test on an Android emulator (API 30+, which has the virtual open network
# "AndroidWifi"). A fake Starlink dish (tools/e2e/fake_dish.py) runs on the host and is reached
# from the emulator at 10.0.2.2. Each scenario resets the app state and checks how many reboot
# commands the dish received.
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
STREAM="$OUT/logcat-stream.txt"
: > "$LOG"
: > "$STREAM"
SERVER_PID=
LOGCAT_PID=
PASSED=()

step() { echo; echo "=== $*"; }
note() { echo "    $*"; }

cleanup() {
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null
  [ -n "$LOGCAT_PID" ] && kill "$LOGCAT_PID" 2>/dev/null
  true
}
trap cleanup EXIT

dump() {
  adb shell cmd wifi status > "$OUT/wifi-status.txt" 2>&1 || true
  adb shell dumpsys wifi > "$OUT/dumpsys-wifi.txt" 2>&1 || true
  adb shell dumpsys connectivity > "$OUT/connectivity.txt" 2>&1 || true
  adb shell dumpsys activity services "$PKG" > "$OUT/services.txt" 2>&1 || true
  adb shell run-as "$PKG" cat shared_prefs/starlink_reconnect.xml > "$OUT/prefs.xml" 2>&1 || true
  adb logcat -d -v threadtime > "$OUT/logcat-dump.txt" 2>/dev/null || true
  grep -E "ReconnectService|StarlinkCommands|WifiReconnector|FATAL EXCEPTION|E2E|ANR in|$PKG" \
    "$OUT/logcat-dump.txt" | grep -v "VM exiting" > "$OUT/logcat-app.txt" 2>/dev/null || true
  adb exec-out screencap -p > "$OUT/screen.png" 2>/dev/null || true
}

fail() {
  echo
  echo "E2E FAILED: $*"
  dump
  echo "--- app log (last 60 lines):"
  app_log | tail -n 60 || true
  echo "--- crashes / ANRs:"
  grep -E "FATAL EXCEPTION|ANR in $PKG" -A 12 "$OUT/logcat-dump.txt" | head -40 || true
  echo "--- fake dish:"
  tail -n 20 "$OUT/fake_dish.out" || true
  echo "--- passed before the failure: ${PASSED[*]:-none}"
  exit 1
}

pass() { PASSED+=("$1"); echo "PASS: $1"; }

reboots() { grep -c '"request": "reboot"' "$LOG" || true; }

# wait_for <description> <seconds> <command...>
wait_for() {
  local what=$1 secs=$2; shift 2
  local end=$((SECONDS + secs))
  until "$@" >/dev/null 2>&1; do
    if [ $SECONDS -ge $end ]; then note "timeout ($secs s): $what"; return 1; fi
    sleep 2
  done
  note "ok: $what"
}

wifi_connected() {
  adb shell cmd wifi status 2>/dev/null | grep -q "connected to \"$SSID\"" && return 0
  adb shell dumpsys wifi 2>/dev/null | grep -q "mWifiInfo SSID: \"$SSID\".*Supplicant state: COMPLETED"
}
reboots_at_least() { [ "$(reboots)" -ge "$1" ]; }

# Log positions: mark() writes a unique marker line into logcat and prints its id;
# log_since <id> prints the current logcat dump from that marker on ("all" = whole buffer, e.g.
# after a phone reboot). Reading `logcat -d` avoids adb's output buffering when streaming to a file.
mark() {
  local id="mark-$SECONDS-$RANDOM"
  adb shell log -t E2E "$id" >/dev/null 2>&1
  echo "$id"
}
app_log() {
  adb logcat -d -v threadtime -s E2E:I ReconnectService:V StarlinkCommands:V WifiReconnector:V 2>/dev/null
}
log_since() {
  if [ "$1" = all ]; then
    app_log
  else
    app_log | awk -v id="$1" 'f {print} index($0, "E2E") && index($0, id) {f = 1}'
  fi
}
log_has() { log_since "$1" | grep -q "$2"; }
log_count() { log_since "$1" | grep -c "$2" || true; }

start_logcat() {
  adb logcat -v threadtime >> "$STREAM" 2>/dev/null &
  LOGCAT_PID=$!
}

start_dish() {
  python3 "$HERE/fake_dish.py" --port "$PORT" --log "$LOG" >> "$OUT/fake_dish.out" 2>&1 &
  SERVER_PID=$!
  wait_for "fake dish listening" 30 sh -c "grep -c listening '$OUT/fake_dish.out' | grep -qv '^0$'" \
    || fail "fake dish did not start"
}

stop_dish() {
  kill "$SERVER_PID" 2>/dev/null; wait "$SERVER_PID" 2>/dev/null
  SERVER_PID=
  note "fake dish stopped"
}

wait_boot() {
  adb wait-for-device
  wait_for "boot completed" 300 sh -c '[ "$(adb shell getprop sys.boot_completed | tr -d "\r")" = 1 ]' \
    || fail "emulator did not boot"
}

# write_prefs <cooldown-minutes>: fresh settings, no reboot history.
write_prefs() {
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
    <int name="reboot_cooldown_min" value="$1" />
    <string name="dish_addr">10.0.2.2:$PORT</string>
    <string name="router_addr">10.0.2.2:$PORT</string>
</map>
XML
  adb push "$OUT/prefs-in.xml" /data/local/tmp/sr_prefs.xml >/dev/null
  adb shell chmod 644 /data/local/tmp/sr_prefs.xml
  adb shell run-as "$PKG" mkdir -p shared_prefs
  adb shell run-as "$PKG" cp /data/local/tmp/sr_prefs.xml shared_prefs/starlink_reconnect.xml \
    || adb shell "run-as $PKG sh -c 'cat > shared_prefs/starlink_reconnect.xml'" < "$OUT/prefs-in.xml"
  adb shell run-as "$PKG" cat shared_prefs/starlink_reconnect.xml | grep -q "\"reboot_cooldown_min\" value=\"$1\"" \
    || fail "could not write app preferences"
}

# reset_app <cooldown-minutes>: stop the app, wipe its reboot history, start it from the launcher
# while Wi-Fi is up, and wait until the service has taken the current state as baseline.
reset_app() {
  adb shell am force-stop "$PKG"
  write_prefs "$1"
  wait_for "Wi-Fi on $SSID" 180 wifi_connected || fail "Wi-Fi not connected before reset"
  local m; m=$(mark)
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
  wait_for "service seeded" 60 log_has "$m" "seeded: ssid=$SSID linkUp=true" \
    || fail "service did not start connected after reset"
}

# flap <seconds-off>: Wi-Fi off, wait, on. The app itself turns Wi-Fi back on as well.
flap() {
  adb shell svc wifi disable
  sleep "$1"
  adb shell svc wifi enable
  wait_for "Wi-Fi back on $SSID" 180 wifi_connected || fail "Wi-Fi did not come back"
}

expect_reboots() { # <expected-count> <seconds-to-wait-for-more> <message>
  local want=$1 settle=$2
  sleep "$settle"
  local got; got=$(reboots)
  [ "$got" -eq "$want" ] || fail "$3 (dish got $got reboot(s), expected $want)"
}

# ----------------------------------------------------------------------------------------------

step "setup"
start_dish
wait_boot
note "Android $(adb shell getprop ro.build.version.release | tr -d '\r'), API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
adb logcat -c || true
start_logcat
adb install -r -g "$APK" >/dev/null || fail "install failed"
adb shell dumpsys package "$PKG" | grep -q "android.permission.INTERNET: granted=true" \
  || fail "INTERNET permission missing: the app cannot open any socket"
adb shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
adb shell pm grant "$PKG" android.permission.ACCESS_BACKGROUND_LOCATION 2>/dev/null || true
adb shell settings put secure location_mode 3 || true
adb shell cmd location set-location-enabled true 2>/dev/null || true
adb shell svc wifi enable
wait_for "connected to $SSID" 180 wifi_connected || fail "emulator Wi-Fi never connected to $SSID"

step "A. app started while already connected -> no reboot"
reset_app 0
expect_reboots 0 15 "dish was rebooted on app start while already connected"
pass A

step "B. Wi-Fi drops and returns, app on screen -> reboot"
n=$(reboots); m=$(mark)
flap 6
wait_for "dish received reboot" 120 reboots_at_least $((n + 1)) || fail "no reboot after reconnect"
wait_for "app recorded success" 60 log_has "$m" "reboot finished: anyOk=true" || fail "app did not record success"
expect_reboots $((n + 1)) 10 "more than one reboot for one reconnect"
pass B

step "C. app killed and relaunched while connected -> no reboot"
n=$(reboots); m=$(mark)
adb shell am force-stop "$PKG"
sleep 2
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
wait_for "service seeded" 60 log_has "$m" "seeded: ssid=$SSID linkUp=true" || fail "service did not restart"
expect_reboots "$n" 20 "dish was rebooted after app restart while connected"
pass C

# Light Doze is what a phone in a pocket / bag gets: screen off, not charging, but moving. Deep
# Doze needs ~30+ min lying still; in forced deep Doze Android 15/16 do not even reconnect Wi-Fi
# when told to from the shell, so it is not something an app can work around.
step "D. phone in pocket: app in background, screen off, light Doze -> reboot"
reset_app 0
n=$(reboots)
adb shell input keyevent KEYCODE_HOME
adb shell dumpsys battery unplug
adb shell input keyevent KEYCODE_SLEEP
sleep 2
adb shell dumpsys deviceidle force-idle light || true
flap 6
wait_for "dish received reboot in Doze" 150 reboots_at_least $((n + 1)) \
  || { adb shell dumpsys deviceidle unforce; adb shell dumpsys battery reset; fail "no reboot with the app in background / Doze"; }
adb shell dumpsys deviceidle unforce || true
adb shell dumpsys battery reset || true
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard 2>/dev/null || adb shell input keyevent 82 || true
pass D

step "E. network forgotten in Wi-Fi settings -> app joins it by itself, then reboots"
if adb shell cmd wifi list-networks >/dev/null 2>&1; then
  reset_app 0
  n=$(reboots); m=$(mark)
  ids=$(adb shell cmd wifi list-networks | awk -v s="$SSID" '$2 == s {print $1}' | tr -d '\r')
  [ -n "$ids" ] || fail "could not find the saved $SSID network"
  for id in $ids; do adb shell cmd wifi forget-network "$id" >/dev/null; done
  note "forgot network id(s): $ids"
  wait_for "Wi-Fi back on $SSID (joined by the app)" 180 wifi_connected \
    || fail "app did not join the forgotten network by itself"
  wait_for "dish received reboot" 120 reboots_at_least $((n + 1)) || fail "no reboot after the app joined"
  log_since "$m" | grep -E "addNetwork|enableNetwork|connect attempt" | head -5 | sed 's/^/    /'
  pass E
else
  note "skipped: 'cmd wifi list-networks' not available on this API level"
fi

step "F. loop protection: Wi-Fi drops again right after a reboot (Mini restarting) -> no 2nd reboot"
reset_app 10
n=$(reboots); m=$(mark)
flap 6
wait_for "first reboot" 120 reboots_at_least $((n + 1)) || fail "no first reboot"
wait_for "app recorded success" 60 log_has "$m" "reboot finished: anyOk=true" || fail "app did not record success"
flap 20
saw_two_connects() { [ "$(log_count "$m" "connected to $SSID (transition)")" -ge 2 ]; }
wait_for "app saw the reconnect" 60 saw_two_connects \
  || fail "app did not notice the second connection"
expect_reboots $((n + 1)) 20 "rebooted again within the cooldown (reboot loop)"
log_has "$m" "reboot skipped: cooldown" || fail "cooldown was not the reason for skipping"
pass F

step "G. dish not answering when the network appears -> app retries and reboots later"
reset_app 0
n=$(reboots); m=$(mark)
stop_dish
flap 6
wait_for "first attempt failed" 120 log_has "$m" "reboot finished: anyOk=false" || fail "app did not try while the dish was down"
log_has "$m" "retrying in" || fail "app did not schedule a retry"
start_dish
wait_for "dish received reboot on retry" 200 reboots_at_least $((n + 1)) || fail "no retry after the dish came back"
pass G

step "H. phone reboot inside the network's range -> no reboot; later drop/return -> reboot"
write_prefs 0
pid=$(adb shell pidof "$PKG" | tr -d '\r')
[ -n "$pid" ] && adb shell run-as "$PKG" kill -9 "$pid" 2>/dev/null
n=$(reboots)
kill "$LOGCAT_PID" 2>/dev/null; LOGCAT_PID=
echo "----- phone reboot -----" >> "$STREAM"
adb reboot
timeout 90 adb wait-for-disconnect 2>/dev/null || sleep 15
wait_boot
start_logcat
wait_for "Wi-Fi on $SSID after boot" 180 wifi_connected || fail "Wi-Fi did not connect after boot"
wait_for "service started by BootReceiver" 120 log_has all "ReconnectService: seeded:" \
  || fail "service was not started at boot"
sleep 30
got=$(reboots)
[ "$got" -eq "$n" ] || fail "dish was rebooted because the phone rebooted (got $got, expected $n)"
log_since all | grep -E "ReconnectService: (seeded|connected)" | head -3 | sed 's/^/    /'
m=$(mark)
flap 6
wait_for "dish received reboot after boot" 120 reboots_at_least $((n + 1)) || fail "no reboot after reconnect following boot"
pass H

dump
echo
echo "E2E PASSED: ${PASSED[*]} (reboots received by the fake dish: $(reboots))"
