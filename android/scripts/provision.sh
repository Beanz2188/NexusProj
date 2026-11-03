#!/usr/bin/env bash
# Turn the OTEETO K7 tablet into a Nexus Home wall panel: a touchscreen
# remote for the nexuslights bridge on the MacBook Air. The tablet never
# talks to the lights itself (no Bluetooth); the Air's bridge stays in charge
# of the lights and Apple Home.
#
# Runs on the Mac the tablet is plugged into (USB debugging on). No taps
# needed on the tablet unless it asks to "Allow USB debugging" once.
#
#   android/scripts/provision.sh --bridge 192.168.70.104 --from-air USER@HOST [options]
#
#   --bridge URL         the Air bridge's dashboard address (host or host:port,
#                        port defaults to 8080). Defaults to the --from-air host.
#   --from-air USER@HOST read the panel token from the Air over SSH
#                        (~/.config/nexuslights/panel_token)
#   --token TOKEN        ...or give the token directly
#   --no-strip           don't remove the tablet's other apps
#   --dry-run            show what would be removed, change nothing
#   --wifi-adb           afterwards, enable adb over Wi-Fi (port 5555)
#   --force              skip the "is this the K7?" check
#
# The Air must run a bridge with the panel API (this branch's nexuslights;
# see android/README.md). Everything is reversible with restore.sh.
set -euo pipefail

PKG=com.nexuslights.panel
ADMIN="$PKG/.kiosk.AdminReceiver"
HERE="$(cd "$(dirname "$0")" && pwd)"
APK="$HERE/../dist/nexus-panel.apk"
STATE_DIR="$HERE/state"

BRIDGE=""
TOKEN=""
FROM_AIR=""
STRIP=1
DRY=0
WIFI_ADB=0
FORCE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --bridge) BRIDGE="$2"; shift 2 ;;
    --token) TOKEN="$2"; shift 2 ;;
    --from-air) FROM_AIR="$2"; shift 2 ;;
    --no-strip) STRIP=0; shift ;;
    --dry-run) DRY=1; shift ;;
    --wifi-adb) WIFI_ADB=1; shift ;;
    --force) FORCE=1; shift ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

say()  { printf '\n\033[1;33m==> %s\033[0m\n' "$*"; }
ok()   { printf '    \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '    \033[31m!\033[0m %s\n' "$*"; }
sh_()  { adb shell "$@" | tr -d '\r'; }

command -v adb >/dev/null || { echo "adb not found. Install it: brew install android-platform-tools" >&2; exit 1; }
[ -f "$APK" ] || { echo "APK not found at $APK" >&2; exit 1; }
mkdir -p "$STATE_DIR"

# ---------------------------------------------------------------------------
say "Checking the bridge on the Air"
if [ -z "$BRIDGE" ] && [ -n "$FROM_AIR" ]; then BRIDGE="${FROM_AIR#*@}"; fi
[ -n "$BRIDGE" ] || { echo "Tell me where the bridge is: --bridge 192.168.70.104" >&2; exit 2; }
case "$BRIDGE" in *://*) ;; *) BRIDGE="http://$BRIDGE" ;; esac
BRIDGE="${BRIDGE%/}"
case "${BRIDGE#*://}" in *:*) ;; *) BRIDGE="$BRIDGE:8080" ;; esac
if [ -z "$TOKEN" ] && [ -n "$FROM_AIR" ]; then
  TOKEN="$(ssh -o ConnectTimeout=8 "$FROM_AIR" 'cat ~/.config/nexuslights/panel_token' 2>/dev/null | tr -d '[:space:]' || true)"
  [ -n "$TOKEN" ] && ok "read the panel token from $FROM_AIR" || warn "couldn't read ~/.config/nexuslights/panel_token on $FROM_AIR"
fi
[ -n "$TOKEN" ] || { echo "Need the panel token: --from-air USER@HOST, or --token (cat ~/.config/nexuslights/panel_token on the Air)" >&2; exit 2; }
CODE="$(curl -s -o "$STATE_DIR/bridge.json" -w '%{http_code}' -m 8 -H "Authorization: Bearer $TOKEN" "$BRIDGE/api/lights" || true)"
case "$CODE" in
  200) ok "$BRIDGE answers: $(grep -o '"name"' "$STATE_DIR/bridge.json" | wc -l | tr -d ' ') light/switch name(s)" ;;
  401) echo "The bridge rejected the token." >&2; exit 1 ;;
  404) echo "The bridge at $BRIDGE has no panel API yet: update nexuslights on the Air (android/README.md) and restart it." >&2; exit 1 ;;
  000) echo "Can't reach $BRIDGE from this Mac. Is 'nexus-lights bridge' running on the Air?" >&2; exit 1 ;;
  *) echo "Unexpected answer from the bridge: HTTP $CODE" >&2; exit 1 ;;
esac
rm -f "$STATE_DIR/bridge.json"

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
  # Out of Doze so motion still wakes the screen after hours of idle.
  sh_ dumpsys deviceidle whitelist +"$PKG" >/dev/null || warn "couldn't whitelist from Doze"
  ok "battery exemption"
fi

# ---------------------------------------------------------------------------
say "Pointing the panel at the bridge"
if [ "$DRY" -eq 1 ]; then
  ok "(dry run) would configure $BRIDGE"
else
  DEST="/sdcard/Android/data/$PKG/files"
  TMP="$STATE_DIR/panel.json"
  printf '{"bridge_url": "%s", "token": "%s"}
' "$BRIDGE" "$TOKEN" > "$TMP"
  sh_ mkdir -p "$DEST"
  adb push "$TMP" "$DEST/panel.json" >/dev/null
  rm -f "$TMP"
  ok "$BRIDGE (read on next app start, then deleted from the tablet's storage)"
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
  # one space-separated line, so every entry (including ones at line ends) matches below
  KEEP_EXACT=" $(echo $KEEP_EXACT) "
  # Kept by pattern: overlays/resources, input methods, radios, MediaTek/vendor services...
  KEEP_RE='inputmethod|overlay|resources|wifi|bluetooth|telephony|telecom|\.ims|^com\.mediatek\.|^com\.mtk\.|^vendor\.|^com\.android\.providers\.'
  # ...except these known battery drainers / plain apps, which go anyway.
  # (DuraSpeed in particular kills background apps, i.e. this panel.)
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
  sh_ settings put global wifi_sleep_policy 2 || true          # keep Wi-Fi on with screen off (motion reports, fast wake)
  sh_ settings put global stay_on_while_plugged_in 0 || true   # the app manages the screen itself
  sh_ settings put system screen_off_timeout 600000 || true    # fallback only; the app dims/offs sooner
  sh_ settings put secure location_mode 0 || true              # nothing needs location
  sh_ cmd bluetooth_manager disable >/dev/null 2>&1 || true    # the Air talks to the lights, not the tablet
  sh_ settings put global auto_time 1 || true
  sh_ svc data disable >/dev/null 2>&1 || true                 # no mobile data on a wall panel
  ok "done"
fi

# ---------------------------------------------------------------------------
say "Starting the panel"
if [ "$DRY" -eq 0 ]; then
  sh_ am force-stop "$PKG" || true   # so it reads panel.json on start
  sh_ am start -n "$PKG/.ui.MainActivity" >/dev/null
  sleep 8
fi

# ---------------------------------------------------------------------------
say "Health check"
FAIL=0
if [ "$DRY" -eq 0 ]; then
  if [ -n "$(sh_ pidof "$PKG")" ]; then ok "app running"; else warn "app is NOT running"; FAIL=1; fi
  if sh_ dumpsys wifi | grep -q "Wi-Fi is enabled"; then ok "Wi-Fi on"; else warn "Wi-Fi is off"; FAIL=1; fi
  CRASH="$(adb logcat -d -b crash 2>/dev/null | grep -A3 "$PKG" | head -20 || true)"
  if [ -n "$CRASH" ]; then warn "crash log:"; echo "$CRASH"; FAIL=1; fi
  LINE="$(adb logcat -d -s Bridge 2>/dev/null | grep -E "connected to bridge|can't reach bridge" | tail -1 || true)"
  case "$LINE" in
    *"connected to bridge"*) ok "panel $(echo "$LINE" | sed 's/.*connected to bridge/connected to bridge/')" ;;
    *) warn "panel hasn't connected to the bridge yet: ${LINE:-no log yet} (check android/scripts/logs.sh)" ;;
  esac
  adb logcat -d 2>/dev/null | grep -E " (PanelApp|PanelSettings|Bridge|Kiosk|Presence|CamMotion)" | tail -40 > "$STATE_DIR/last-provision-log.txt" || true
  ok "recent app log saved to $STATE_DIR/last-provision-log.txt"
fi

if [ "$FAIL" -ne 0 ] && [ "$STRIP" -eq 1 ] && [ "$DRY" -eq 0 ]; then
  warn "Something's wrong after stripping apps; putting them back to be safe."
  "$HERE/restore.sh" --apps-only || true
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
    The panel controls your lights through the Air's bridge; Apple Home keeps
    working exactly as before. Settings on the tablet: press and hold the home
    name at the top of the screen.

    Update later:   android/scripts/update.sh
    Logs:           android/scripts/logs.sh
    Undo it all:    android/scripts/restore.sh
EOF
