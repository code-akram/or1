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

echo "== back to non-root adb"
adb unroot >/dev/null; sleep 3; adb wait-for-device

adb shell am start -n $PKG/.MainActivity >/dev/null
sleep 3
echo "== check"
adb shell "settings get secure enabled_accessibility_services; dumpsys notification | grep -A1 \"All notification listeners\" | tail -1; \
  dumpsys role | grep -A1 "android.app.role.HOME" | tail -1; dumpsys window | grep mCurrentFocus | head -1"
