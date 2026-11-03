#!/usr/bin/env bash
# Undo provision.sh: put back every app it removed and release the kiosk.
#   android/scripts/restore.sh              everything (apps + remove Nexus Home's device owner)
#   android/scripts/restore.sh --apps-only  only reinstall the removed apps
set -euo pipefail
PKG=com.nexuslights.panel
ADMIN="$PKG/.kiosk.AdminReceiver"
HERE="$(cd "$(dirname "$0")" && pwd)"
sh_() { adb shell "$@" | tr -d '\r'; }
APPS_ONLY=0; [ "${1:-}" = "--apps-only" ] && APPS_ONLY=1

SERIAL="$(adb get-serialno | tr -d '\r')"
LIST="$HERE/state/removed-packages-$SERIAL.txt"
if [ -f "$LIST" ]; then
  while read -r line; do
    [ -z "$line" ] && continue
    case "$line" in
      disabled:*) p="${line#disabled:}"; sh_ pm enable "$p" >/dev/null 2>&1 && echo "enabled $p" ;;
      *) sh_ cmd package install-existing "$line" >/dev/null 2>&1 && echo "restored $line" ;;
    esac
  done < "$LIST"
  mv "$LIST" "$LIST.restored-$(date +%Y%m%d%H%M%S)"
else
  echo "No removed-packages list for $SERIAL; nothing to put back."
fi
[ "$APPS_ONLY" -eq 1 ] && exit 0

# Possible only because the APK is testOnly.
sh_ am force-stop "$PKG" || true
sh_ dpm remove-active-admin "$ADMIN" && echo "Nexus Home is no longer device owner."
sh_ settings put secure location_mode 3 || true
echo "Done. The tablet is a normal Android tablet again (Nexus Home is still installed; 'adb uninstall $PKG' removes it)."
