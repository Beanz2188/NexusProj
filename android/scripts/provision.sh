#!/usr/bin/env bash
# Turn the OTEETO K7 tablet into a dedicated Nexus Home wall panel.
# Runs on the Mac the tablet is plugged into (USB debugging on). No taps
# needed on the tablet unless it asks to "Allow USB debugging" once.
#
#   android/scripts/provision.sh [options]
#
#   --lights FILE        nexuslights lights.yaml to import (default:
#                        ~/.config/nexuslights/lights.yaml if it exists here)
#   --from-air USER@HOST copy lights.yaml from the MacBook Air over SSH
#   --stop-air-bridge    with --from-air: stop `nexus-lights bridge` on the Air
#                        (the strips accept only one BLE connection at a time)
#   --no-strip           don't remove the tablet's other apps
#   --dry-run            show what would be removed, change nothing
#   --wifi-adb           afterwards, enable adb over Wi-Fi (port 5555)
#   --force              skip the "is this the K7?" check
#
# Everything is reversible with android/scripts/restore.sh.
set -euo pipefail

PKG=com.nexuslights.panel
ADMIN="$PKG/.kiosk.AdminReceiver"
HERE="$(cd "$(dirname "$0")" && pwd)"
APK="$HERE/../dist/nexus-panel.apk"
STATE_DIR="$HERE/state"

LIGHTS=""
FROM_AIR=""
STOP_AIR=0
STRIP=1
DRY=0
WIFI_ADB=0
FORCE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --lights) LIGHTS="$2"; shift 2 ;;
    --from-air) FROM_AIR="$2"; shift 2 ;;
    --stop-air-bridge) STOP_AIR=1; shift ;;
    --no-strip) STRIP=0; shift ;;
    --dry-run) DRY=1; shift ;;
    --wifi-adb) WIFI_ADB=1; shift ;;
    --force) FORCE=1; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

say()  { printf '\n\033[1;33m==> %s\033[0m\n' "$*"; }
ok()   { printf '    \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '    \033[31m!\033[0m %s\n' "$*"; }
sh_()  { adb shell "$@" | tr -d '\r'; }

command -v adb >/dev/null || { echo "adb not found. Install it: brew install android-platform-tools" >&2; exit 1; }
[ -f "$APK" ] || { echo "APK not found at $APK" >&2; exit 1; }

# ---------------------------------------------------------------------------
say "Checking the tablet"
adb start-server >/dev/null 2>&1 || true
STATE="$(adb get-state 2>/dev/null || true)"
if [ "$STATE" != "device" ]; then
  adb devices -l
  echo "Tablet not ready (state: ${STATE:-none}). If it says 'unauthorized', unlock it and tap Allow." >&2
  exit 1
fi
SERIAL="$(adb get-serialno | tr -d '\r')"
MODEL="$(sh_ getprop ro.product.model)"
SDK="$(sh_ getprop ro.build.version.sdk)"
PLATFORM="$(sh_ getprop ro.board.platform)"
ok "$MODEL ($PLATFORM, API $SDK, serial $SERIAL)"
if [ "$FORCE" -eq 0 ] && [ "$MODEL" != "K7" ]; then
  echo "This doesn't look like the OTEETO K7 (model '$MODEL'). Re-run with --force if that's intended." >&2
  exit 1
fi
mkdir -p "$STATE_DIR"

# ---------------------------------------------------------------------------
say "Installing Nexus Home"
if [ "$DRY" -eq 1 ]; then
  ok "(dry run) would install $APK"
else
  if ! OUT="$(adb install -r -t -g "$APK" 2>&1)"; then
    echo "$OUT"
    if echo "$OUT" | grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE; then
      echo "An older build signed with a different key is installed. Run android/scripts/update.sh, which handles this." >&2
    fi
    exit 1
  fi
  ok "installed"
fi

# ---------------------------------------------------------------------------
say "Making it the device owner (kiosk lock-down)"
if sh_ dpm list-owners 2>/dev/null | grep -q "$PKG" || sh_ dumpsys device_policy | grep -q "admin=ComponentInfo{$PKG"; then
  ok "already device owner"
elif [ "$DRY" -eq 1 ]; then
  ok "(dry run) would run dpm set-device-owner $ADMIN"
else
  ACCOUNTS="$(sh_ dumpsys account | grep -c 'Account {' || true)"
  if [ "${ACCOUNTS:-0}" -gt 0 ]; then
    warn "There are $ACCOUNTS account(s) on the tablet; Android won't allow a device owner until they're removed:"
    sh_ dumpsys account | grep 'Account {' || true
    echo "Remove them in Settings > Accounts, then re-run." >&2
    exit 1
  fi
  OUT="$(sh_ dpm set-device-owner "$ADMIN" 2>&1 || true)"
  echo "    $OUT"
  echo "$OUT" | grep -qi "success" || { echo "dpm set-device-owner failed (see above)." >&2; exit 1; }
  ok "device owner set"
fi

if [ "$DRY" -eq 0 ]; then
  # Lets the app flip Location on only while scanning for strips.
  sh_ pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS || warn "couldn't grant WRITE_SECURE_SETTINGS"
  # Keep the bridge out of Doze so Apple Home stays responsive with the screen off.
  sh_ dumpsys deviceidle whitelist +"$PKG" >/dev/null || warn "couldn't whitelist from Doze"
  ok "permissions + battery exemption"
fi

# ---------------------------------------------------------------------------
say "Importing your lights"
if [ -n "$FROM_AIR" ]; then
  LIGHTS="$STATE_DIR/lights-from-air.yaml"
  if scp -q -o ConnectTimeout=8 "$FROM_AIR:.config/nexuslights/lights.yaml" "$LIGHTS"; then
    ok "copied lights.yaml from $FROM_AIR"
  else
    warn "couldn't scp from $FROM_AIR (is Remote Login on in the Air's Sharing settings?)"
    LIGHTS=""
  fi
fi
if [ -z "$LIGHTS" ] && [ -f "$HOME/.config/nexuslights/lights.yaml" ]; then
  LIGHTS="$HOME/.config/nexuslights/lights.yaml"
fi
if [ -n "$LIGHTS" ] && [ -f "$LIGHTS" ]; then
  COUNT="$(grep -c 'address:' "$LIGHTS" || true)"
  if [ "$DRY" -eq 1 ]; then
    ok "(dry run) would import $COUNT light(s) from $LIGHTS"
  else
    DEST="/sdcard/Android/data/$PKG/files"
    sh_ mkdir -p "$DEST"
    adb push "$LIGHTS" "$DEST/lights.yaml" >/dev/null
    ok "pushed $COUNT light(s) from $LIGHTS (imported on next app start)"
  fi
else
  warn "no lights.yaml given. Add lights on the tablet: hold the home name > Settings > Add Light"
  warn "or re-run with --from-air you@macbook-air.local (or --lights path/to/lights.yaml)"
fi

# ---------------------------------------------------------------------------
if [ "$STRIP" -eq 1 ]; then
  say "Removing everything else from the tablet"
  # Packages kept no matter what: the OS core, radios, telephony stack (the
  # MTK baseband crash-loops without it), keyboard, Settings (maintenance),
  # adb shell, and vendor plumbing. Everything else, including Google Play
  # Services and the launcher, is uninstalled for user 0, which is fully
  # reversible (restore.sh) and frees RAM + stops background battery drain.
  KEEP_EXACT=" android $PKG com.android.systemui com.android.shell com.android.settings
    com.android.providers.settings com.android.phone com.android.providers.telephony
    com.android.server.telecom com.android.bluetooth com.android.keychain
    com.android.location.fused com.android.inputdevices com.android.defcontainer
    com.android.packageinstaller com.google.android.packageinstaller
    com.android.providers.media com.android.providers.downloads com.android.providers.contacts
    com.android.providers.blockednumber com.android.providers.userdictionary
    com.android.externalstorage com.android.mms.service com.android.carrierconfig
    com.android.stk com.android.cellbroadcastreceiver com.android.ims com.android.se
    com.android.nfc com.android.certinstaller com.android.proxyhandler com.android.vpndialogs
    com.android.ext.services com.android.ext.shared com.google.android.ext.services
    com.google.android.ext.shared com.android.webview com.google.android.webview
    com.android.providers.drm com.android.companiondevicemanager com.android.storagemanager
    com.android.wallpaper.livepicker com.android.wallpapercropper "
  # Kept by pattern: overlays/resources, input methods, radios, MediaTek/vendor services...
  KEEP_RE='inputmethod|overlay|resources|wifi|bluetooth|telephony|telecom|\.ims|^com\.mediatek\.|^com\.mtk\.|^vendor\.|^com\.android\.providers\.'
  # ...except these known battery drainers / plain apps, which go anyway.
  # (DuraSpeed in particular kills background apps, i.e. the HomeKit bridge.)
  JUNK_RE='mtklogger|duraspeed|callrecorder|datatransfer|fmradio|\.camera$|camera2|filemanager|gallery|music|video|soundrecorder|lbs\.em|nlpservice|calendar|email|browser|launcher|providers\.calendar|ygps|gnss\.nonframework'

  REMOVED_FILE="$STATE_DIR/removed-packages-$SERIAL.txt"
  touch "$REMOVED_FILE"
  REMOVE_LIST=""
  for p in $(sh_ pm list packages | sed 's/^package://' | sort); do
    case "$KEEP_EXACT" in *" $p "*) continue ;; esac
    if echo "$p" | grep -Eq "$KEEP_RE" && ! echo "$p" | grep -Eq "$JUNK_RE"; then continue; fi
    REMOVE_LIST="$REMOVE_LIST $p"
  done
  N=0
  for p in $REMOVE_LIST; do N=$((N + 1)); done
  echo "    $N package(s) to remove:"
  for p in $REMOVE_LIST; do echo "      - $p"; done

  if [ "$DRY" -eq 0 ] && [ "$N" -gt 0 ]; then
    for p in $REMOVE_LIST; do
      if sh_ pm uninstall -k --user 0 "$p" | grep -q Success; then
        grep -qx "$p" "$REMOVED_FILE" || echo "$p" >> "$REMOVED_FILE"
      else
        # some system packages refuse uninstall-for-user; disabling is next best
        sh_ pm disable-user --user 0 "$p" >/dev/null 2>&1 && { grep -qx "disabled:$p" "$REMOVED_FILE" || echo "disabled:$p" >> "$REMOVED_FILE"; }
      fi
    done
    ok "removed $N package(s); list saved to $REMOVED_FILE (restore.sh puts them back)"
  fi
fi

# ---------------------------------------------------------------------------
say "Battery tuning"
if [ "$DRY" -eq 0 ]; then
  sh_ settings put global wifi_scan_always_enabled 0 || true   # no background Wi-Fi scans
  sh_ settings put global ble_scan_always_enabled 0 || true    # no background BLE scans by others
  sh_ settings put global wifi_sleep_policy 2 || true          # keep Wi-Fi on with screen off (HomeKit)
  sh_ settings put global stay_on_while_plugged_in 0 || true   # the app manages the screen itself
  sh_ settings put system screen_off_timeout 600000 || true    # fallback only; the app dims/offs sooner
  sh_ settings put secure location_mode 0 || true              # app turns it on only while scanning
  sh_ settings put global auto_time 1 || true
  sh_ svc data disable >/dev/null 2>&1 || true                 # no mobile data on a wall panel
  ok "done"
fi

# ---------------------------------------------------------------------------
say "Starting the panel"
if [ "$DRY" -eq 0 ]; then
  sh_ am force-stop "$PKG" || true   # so it re-reads lights.yaml on start
  sh_ am start -n "$PKG/.ui.MainActivity" >/dev/null
  sleep 8
fi

# ---------------------------------------------------------------------------
say "Health check"
FAIL=0
if [ "$DRY" -eq 0 ]; then
  if [ -n "$(sh_ pidof "$PKG")" ]; then ok "app running"; else warn "app is NOT running"; FAIL=1; fi
  if sh_ dumpsys wifi | grep -q "Wi-Fi is enabled"; then ok "Wi-Fi on"; else warn "Wi-Fi is off"; FAIL=1; fi
  if sh_ settings get global bluetooth_on | grep -q 1; then ok "Bluetooth on"; else warn "Bluetooth is off"; fi
  CRASH="$(adb logcat -d -b crash 2>/dev/null | grep -A3 "$PKG" | head -20 || true)"
  if [ -n "$CRASH" ]; then warn "crash log:"; echo "$CRASH"; FAIL=1; fi
  LINE="$(adb logcat -d -s HomeKit:I 2>/dev/null | grep 'setup code' | tail -1 || true)"
  if [ -n "$LINE" ]; then
    ok "$(echo "$LINE" | sed 's/.*HomeKit bridge/HomeKit bridge/')"
  else
    warn "HomeKit bridge hasn't reported in yet (check: android/scripts/logs.sh)"
  fi
  adb logcat -d 2>/dev/null | grep -E " (PanelApp|HomeKit|Ble|WifiLight|ConfigImport|Kiosk|Presence)" | tail -40 > "$STATE_DIR/last-provision-log.txt" || true
  ok "recent app log saved to $STATE_DIR/last-provision-log.txt"
fi

if [ "$FAIL" -ne 0 ] && [ "$STRIP" -eq 1 ] && [ "$DRY" -eq 0 ]; then
  warn "Something's wrong after stripping apps; putting them back to be safe."
  "$HERE/restore.sh" --apps-only || true
fi

# ---------------------------------------------------------------------------
if [ -n "$FROM_AIR" ] && [ "$STOP_AIR" -eq 1 ] && [ "$DRY" -eq 0 ]; then
  say "Stopping the old bridge on the Air"
  if ssh -o ConnectTimeout=8 "$FROM_AIR" "pkill -f 'nexus-lights bridge' && echo stopped || echo 'not running'"; then
    ok "the strips are free for the tablet now"
  else
    warn "couldn't reach $FROM_AIR. Stop 'nexus-lights bridge' on the Air yourself"
  fi
fi

if [ "$WIFI_ADB" -eq 1 ] && [ "$DRY" -eq 0 ]; then
  say "Enabling adb over Wi-Fi"
  IP="$(sh_ ip -f inet addr show wlan0 | awk '/inet /{print $2}' | cut -d/ -f1)"
  adb tcpip 5555 >/dev/null
  sleep 2
  adb connect "$IP:5555" && ok "next time: adb connect $IP:5555 (until the tablet reboots)"
fi

say "Done"
cat <<EOF
    Next: on your iPhone, Home app > + > Add Accessory, then scan the code shown on
    the tablet (hold the home name at the top > Settings > Apple Home).
    Remove the old "NexusLights" bridge from Home first so lights don't appear twice.

    Update later:   android/scripts/update.sh
    Logs:           android/scripts/logs.sh
    Undo it all:    android/scripts/restore.sh
EOF
