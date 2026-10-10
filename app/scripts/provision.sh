#!/usr/bin/env bash
# Install and set up the or1 agent on the RAVOZ, through the Mac's ADB server tunnelled to port 5038.
# Every command is pinned to the RAVOZ serial; the OnePlus on the same server is never addressed.
#   app/scripts/provision.sh [path/to/agent-debug.apk]
set -euo pipefail

ADB_BIN="${OR1_ADB:-$HOME/.local/share/android/platform-tools/adb}"
SERIAL=RAV1022V1000265
PKG=com.or1.agent
KEY_FILE="$HOME/.config/or1/anthropic-api-key"
APK="${1:-$(dirname "$0")/../agent/build/outputs/apk/debug/agent-debug.apk}"
adb() { "$ADB_BIN" -P 5038 -s "$SERIAL" "$@"; }

# Without the tunnel, `adb -P 5038` would start a local server on that port and block the tunnel.
ss -ltn | grep -q '127.0.0.1:5038 ' || { echo "ADB tunnel on port 5038 is down." >&2; exit 1; }
"$ADB_BIN" -P 5038 devices | grep -q "^$SERIAL[[:space:]]device" || { echo "$SERIAL is not connected." >&2; exit 1; }
[ -s "$KEY_FILE" ] || { echo "No API key in $KEY_FILE." >&2; exit 1; }

echo "== install"
adb install -r "$APK" | tail -1

echo "== root (for the key file's SELinux label)"
adb root >/dev/null; sleep 3; adb wait-for-device

echo "== API key (over stdin, never in argv)"
# Platform apps run at a different SELinux level than files created by run-as, so write as root and
# label the file with the app data directory's own context.
adb shell "d=/data/data/$PKG; mkdir -p \$d/files; cat > \$d/files/anthropic-key; \
  owner=\$(stat -c %u:%g \$d); ctx=\$(ls -Zd \$d | awk '{print \$1}'); \
  chown \$owner \$d/files \$d/files/anthropic-key; chmod 700 \$d/files; chmod 600 \$d/files/anthropic-key; \
  chcon \"\$ctx\" \$d/files \$d/files/anthropic-key" < "$KEY_FILE"

echo "== accessibility, notification access, DND access"
adb shell "
  add() { cur=\$(settings get secure \$1); case \":\$cur:\" in *\":\$2:\"*) ;; \
    *) if [ \"\$cur\" = null ] || [ -z \"\$cur\" ]; then settings put secure \$1 \$2; else settings put secure \$1 \"\$cur:\$2\"; fi;; esac; }
  add enabled_accessibility_services $PKG/$PKG.A11yService
  settings put secure accessibility_enabled 1
  cmd notification allow_listener $PKG/$PKG.NotifListener
  cmd notification allow_dnd $PKG"

echo "== runtime permissions, battery exemption, home role"
for p in POST_NOTIFICATIONS BLUETOOTH_CONNECT ACCESS_FINE_LOCATION READ_CALENDAR WRITE_CALENDAR READ_CONTACTS WRITE_CONTACTS RECORD_AUDIO; do adb shell pm grant $PKG android.permission.$p; done
adb shell cmd deviceidle whitelist +$PKG >/dev/null
# Etar (the calendar app, from F-Droid) starts a foreground service for reminders, which Android blocks from the background.
adb shell "pm path ws.xsoh.etar >/dev/null && cmd deviceidle whitelist +ws.xsoh.etar >/dev/null" || true
# Clock asks for notification permission on first launch, which would block alarm and timer tasks.
adb shell pm grant com.android.deskclock android.permission.POST_NOTIFICATIONS
adb shell cmd role add-role-holder android.app.role.HOME $PKG
# No other way into apps: launcher3 only stayed for its Recents screen, which the lockdown (Lockdown.kt) hides anyway.
adb shell pm disable-user --user 0 com.android.launcher3 >/dev/null
# No navigation bar at all (TrebleDroid sets qemu.hw.mainkeys from this at boot): the lockdown leaves it without
# buttons, and an empty bar still holds the composer off the bottom edge. Takes effect on the next reboot.
adb shell setprop persist.sys.phh.mainkeys 1
# Wi-Fi only, cellular radio off: airplane mode limited to the cell radio (Wi-Fi and Bluetooth stay on, also after a
# reboot), mobile data off in case airplane mode is ever turned off, and no airplane icon in the status bar. The SIM
# dialog and SIM notifications are handled by the lockdown.
adb shell "settings put global airplane_mode_radios cell,nfc,wimax; cmd connectivity airplane-mode enable; \
  svc data disable; settings put secure icon_blacklist rotate,headset,airplane"
# Nothing but or1 from boot on: no lock screen (there's no PIN to lose) and a plain black wallpaper. The boot screens
# themselves (logo partition, boot animation) are in brand/README.md.
adb shell locksettings set-disabled true
adb shell am broadcast -n $PKG/.DebugReceiver --es wallpaper black >/dev/null

echo "== back to non-root adb"
adb unroot >/dev/null; sleep 3; adb wait-for-device

adb shell am start -n $PKG/.MainActivity >/dev/null
sleep 3
echo "== check"
adb shell "settings get secure enabled_accessibility_services; dumpsys notification | grep -A1 \"All notification listeners\" | tail -1; \
  dumpsys role | grep -A1 "android.app.role.HOME" | tail -1; dumpsys window | grep mCurrentFocus | head -1"
