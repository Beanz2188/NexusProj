# Nexus Home: tablet wall panel

An Android app that turns the OTEETO K7 tablet (MT6737M, Android 8.1; its
About screen says 13, but it isn't) into a dedicated light panel. It replaces
`nexus-lights bridge` on the MacBook Air:

- controls every strip directly over Bluetooth (all seven nexuslights
  protocols, byte-for-byte from the `air-bridge` branch) plus the flux_led
  Wi-Fi dimmer
- is the Apple Home bridge itself (HomeKit), and also exposes the panel's
  motion sensor and light sensor to Apple Home
- runs as a locked-down kiosk: no other apps, no status bar, boots straight
  into the panel
- wakes when someone walks up (front camera), knocks on it or waves at it;
  dims to a clock and then turns the screen off when idle

The prebuilt APK is `dist/nexus-panel.apk`, so the Mac doesn't need the
Android SDK.

## Setting up the tablet (run on the Mac the tablet is plugged into)

Requirements: `adb` (`brew install android-platform-tools`), USB debugging on,
and the tablet showing as `device` in `adb devices`.

```bash
git fetch origin claude/blissful-hypatia-b2c2d4
git checkout claude/blissful-hypatia-b2c2d4
```

1. **Preview what will be removed** (changes nothing):

   ```bash
   android/scripts/provision.sh --dry-run --from-air you@MacBook-Air.local
   ```

2. **Provision.** This installs the app, makes it device owner, imports the
   lights from the Air's `~/.config/nexuslights/lights.yaml`, strips the other
   apps, applies battery settings and starts the panel:

   ```bash
   android/scripts/provision.sh --from-air you@MacBook-Air.local --stop-air-bridge --wifi-adb
   ```

   - `--from-air` copies the light list from the Air over SSH (the Air needs
     System Settings > General > Sharing > Remote Login). Without SSH, copy
     `lights.yaml` to this Mac and pass `--lights path/to/lights.yaml`.
   - `--stop-air-bridge` stops the Air's bridge, because the strips accept only
     one Bluetooth connection at a time.
   - `--wifi-adb` lets later updates go over Wi-Fi (until the tablet reboots).
   - If the health check at the end fails after stripping, the script puts
     the removed apps back on its own.

3. **Pair with Apple Home.** Remove the old "NexusLights" bridge from the Home
   app, then Home > + > Add Accessory and scan the code on the tablet
   (press and hold the home name at the top > Settings > Apple Home). The
   setup code is also printed at the end of `provision.sh`.

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

Settings covers adding/renaming lights (Bluetooth scan, or a Wi-Fi light by IP),
motion sensitivity and mode, dim/off timing, auto brightness, "connect per
command" for flaky radios, Apple Home pairing/reset, and **Exit kiosk mode** for
maintenance (opens Android Settings; Home brings the panel back).

## Getting the tablet back

`android/scripts/restore.sh` reinstalls every app `provision.sh` removed (the
list is kept in `android/scripts/state/`) and removes the app's device-owner
status. That works because the APK is built `testOnly`. After that it's a normal
tablet again; `adb uninstall com.nexuslights.panel` removes the app.

## Building

```bash
cd android && ./gradlew assembleRelease testReleaseUnitTest
cp app/build/outputs/apk/release/app-release.apk dist/nexus-panel.apk
```

Signing: a key at `android/signing/nexuspanel.jks` is used if present (it is
**not** committed), otherwise the debug key. A build signed with a different key
can't update the installed app in place. `update.sh` handles that by
reinstalling, and the app restores its Apple Home pairing and lights from
`/sdcard/NexusPanel/backup`, so nothing needs re-pairing.

## Not ported from the Mac (yet)

Ambilight, music artwork sync, rainbow mode, the relay server and the Meross
light stay on the Mac side. The Meross light is already in Apple Home on its own.
