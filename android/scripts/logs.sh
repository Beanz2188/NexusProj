#!/usr/bin/env bash
# Live log of the Nexus Home app on the tablet (Ctrl-C to stop).
adb logcat -v time -s PanelApp HomeKit Ble BleScan WifiLight LightRepo ConfigImport Kiosk Presence CamMotion Backup AndroidRuntime
