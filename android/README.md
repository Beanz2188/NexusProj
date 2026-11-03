# Nexus Home: tablet wall panel

An Android app that turns the OTEETO K7 tablet (MT6737M, Android 8.1; its
About screen says 13, but it isn't) into a wall-mounted touchscreen **remote for
the nexuslights bridge on the MacBook Air**.

The Air's bridge stays in charge: it talks to the strips over Bluetooth and is
what Apple Home pairs with. The tablet never touches the lights (its Bluetooth
is turned off). It sends commands to the bridge's panel API over Wi-Fi, so a tap
on the panel shows up in the Home app instantly, and changes made from Home,
Siri or automations show up on the panel.

```
 tablet  --Wi-Fi, HTTP + token-->  MacBook Air: nexus-lights bridge  --BLE-->  strips
                                              |
                                              +--HomeKit-->  Apple Home
```

The panel:

- Apple Home-style tiles for every light; tap to toggle, hold for a brightness
  slider and color wheel. Also scenes, plus mode tiles for the bridge's
  TV Ambilight / Rainbow / Music Art Sync switches.
- Locked-down kiosk: no other apps, no status bar, boots straight into the panel.
- Wakes when someone walks up (front camera), knocks or waves; dims to a clock,
  then turns the screen off when idle; auto brightness from the light sensor.
- Optional: reports its motion and light level to the bridge, which can show
  them in Apple Home as "Wall Panel Motion" / "Wall Panel Light Level".

The prebuilt APK is `dist/nexus-panel.apk`, so the Mac doesn't need the
Android SDK.

## 1. Update the bridge on the Air

The panel API (`nexuslights/panel_api.py`) is new, so the Air needs this
branch's nexuslights. On the Air, in its NexusProj checkout:

```bash
git fetch origin claude/blissful-hypatia-b2c2d4
git merge origin/claude/blissful-hypatia-b2c2d4
```

Then restart `nexus-lights bridge` with the same flags as before. To also get
the panel's motion and light sensors in Apple Home, add `--panel-sensors`.

On start the bridge creates `~/.config/nexuslights/panel_token`; the panel needs
it. Check that the API answers:

```bash
curl -H "Authorization: Bearer $(cat ~/.config/nexuslights/panel_token)" http://localhost:8080/api/lights
```

## 2. Set up the tablet (on the Mac it's plugged into)

Requirements: `adb` (`brew install android-platform-tools`), USB debugging on,
and the tablet showing as `device` in `adb devices`.

1. **Preview** (changes nothing):

   ```bash
   android/scripts/provision.sh --dry-run --from-air you@192.168.70.104
   ```

   `--from-air` reads the token from the Air over SSH (the Air needs System
   Settings > General > Sharing > Remote Login). Without SSH use
   `--bridge 192.168.70.104 --token <the token>`.

2. **Install without stripping first**, and check the panel works:

   ```bash
   android/scripts/provision.sh --no-strip --from-air you@192.168.70.104
   ```

3. **Then the full lock-down**, which removes every other app (reversible):

   ```bash
   android/scripts/provision.sh --from-air you@192.168.70.104 --wifi-adb
   ```

   If the health check at the end fails after stripping, the script puts the
   removed apps back on its own.

Give the Air a fixed IP (a DHCP reservation on the router) if you can. If its
address changes anyway, the panel finds it again through the bridge's HomeKit
mDNS advert.

## Day to day

| | |
|---|---|
| Hidden settings | press and hold the home name at the top of the screen |
| Toggle a light | tap its tile |
| Brightness / color | press and hold its tile |
| Wake the screen | walk up, knock, wave, or touch (the first touch only wakes) |
| Live logs | `android/scripts/logs.sh` |
| Update the app | `android/scripts/update.sh` |
| Undo everything | `android/scripts/restore.sh` |

In Settings: bridge address and token (with a connection test), each light's
room and icon, motion mode and sensitivity, dim/off timing, auto brightness,
and **Exit kiosk mode** for maintenance (opens Android Settings; Home brings
the panel back). Lights themselves are added and removed on the Mac with
`nexus-lights add` / `remove`.

## Getting the tablet back

`android/scripts/restore.sh` reinstalls every app `provision.sh` removed (the
list is kept in `android/scripts/state/`) and removes the app's device-owner
status. That works because the APK is built `testOnly`. After that it's a normal
tablet again; `adb uninstall com.nexuslights.panel` removes the app.

## Building and tests

```bash
cd android && ./gradlew assembleRelease testReleaseUnitTest
cp app/build/outputs/apk/release/app-release.apk dist/nexus-panel.apk
```

`BridgeContractTest` starts `tests/panel_contract_server.py`, which runs the
real Python panel API with fake lights, and drives it with the tablet's
`BridgeClient`. That keeps the two sides from drifting apart. It's skipped when
python3 with aiohttp and HAP-python isn't available. The Python side has its
own tests in `tests/test_panel_api.py`.

Signing: a key at `android/signing/nexuspanel.jks` is used if present (it is
**not** committed), otherwise the debug key. A build signed with a different key
can't update the installed app in place. `update.sh` then reinstalls it, after
which `provision.sh --no-strip` reconnects it to the bridge.
