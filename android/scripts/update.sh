#!/usr/bin/env bash
# Install a new build of Nexus Home on the already-provisioned tablet.
#   android/scripts/update.sh [path/to/apk]     (default: android/dist/nexus-panel.apk)
#
# Same signing key as the installed copy: a normal in-place update.
# Different key (built on another machine): Android refuses the update, so
# this removes the old copy and installs fresh. The app restores its Apple
# Home pairing and lights from /sdcard/NexusPanel/backup, so nothing needs re-pairing.
set -euo pipefail
PKG=com.nexuslights.panel
ADMIN="$PKG/.kiosk.AdminReceiver"
HERE="$(cd "$(dirname "$0")" && pwd)"
APK="${1:-$HERE/../dist/nexus-panel.apk}"
sh_() { adb shell "$@" | tr -d '\r'; }

[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 1; }
[ "$(adb get-state 2>/dev/null)" = "device" ] || { echo "No tablet connected (adb devices)." >&2; exit 1; }

if OUT="$(adb install -r -t -g "$APK" 2>&1)"; then
  echo "Updated in place."
else
  echo "$OUT"
  if echo "$OUT" | grep -q -E "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match"; then
    echo "Different signing key: reinstalling (pairing + lights come back from the on-device backup)."
    if [ -z "$(sh_ ls /sdcard/NexusPanel/backup/homekit.json 2>/dev/null)" ]; then
      echo "WARNING: no /sdcard/NexusPanel/backup/homekit.json on the tablet; Apple Home will need re-pairing." >&2
    fi
    sh_ dpm remove-active-admin "$ADMIN" || true
    adb uninstall "$PKG" >/dev/null || true
    adb install -t -g "$APK"
    sh_ dpm set-device-owner "$ADMIN"
    sh_ pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS || true
    sh_ dumpsys deviceidle whitelist +"$PKG" >/dev/null || true
  else
    exit 1
  fi
fi
sh_ am start -n "$PKG/.ui.MainActivity" >/dev/null
echo "Nexus Home restarted."
