#!/usr/bin/env bash
# Install a new build of Nexus Home on the already-provisioned tablet.
#   android/scripts/update.sh [path/to/apk]     (default: android/dist/nexus-panel.apk)
#
# Same signing key as the installed copy: a normal in-place update.
# Different key (built on another machine): Android refuses the update, so
# this removes the old copy and installs fresh. That loses the panel's own
# settings (bridge address, token, rooms/icons): re-run provision.sh after.
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
    echo "Different signing key: reinstalling. Re-run provision.sh --no-strip afterwards to reconnect it to the bridge."
    sh_ dpm remove-active-admin "$ADMIN" || true
    adb uninstall "$PKG" >/dev/null || true
    adb install -t -g "$APK"
    sh_ dpm set-device-owner "$ADMIN"
    sh_ dumpsys deviceidle whitelist +"$PKG" >/dev/null || true
  else
    exit 1
  fi
fi
sh_ am start -n "$PKG/.ui.MainActivity" >/dev/null
echo "Nexus Home restarted."
