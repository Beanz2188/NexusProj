# nexuslights

One tool to control your BLE RGB light strips instead of three different
manufacturer apps, with an optional bridge into Apple HomeKit so they show
up in the Home app / Siri / automations without a dedicated hub.

## Why this exists

Most cheap BLE RGB(W) strips (sold under names like Triones, LEDBLE, MELK,
Happy Lighting, ELK-BLEDOM, Longra...) are the same handful of OEM
designs re-skinned with different apps. That's why you end up with three
apps for lights that are all doing basically the same thing over
Bluetooth Low Energy. This project talks to the strips directly over BLE
(no manufacturer app needed) and currently ships two protocol
implementations covering a large share of these devices:

- **triones** — Triones / LEDBLE / MELK / LEDnetWF / Happy Lighting / QHM
- **elkbledom** — ELK-BLEDOM / ELK-BULB / Longra

If your strip uses a third protocol, see [Adding a new
protocol](#adding-a-new-protocol) below — the pattern is small and
self-contained.

## Install

```bash
pip install -e .
# add HomeKit support:
pip install -e ".[homekit]"
```

Requires Python 3.10+ and a Bluetooth radio. Tested conceptually against
BlueZ (Linux) via `bleak`; `bleak` also supports macOS (Core Bluetooth)
and Windows (WinRT), so the same code should run on a Mac or PC too.

## Quick start

```bash
# 1. Find your strip
nexus-lights scan

# 2. Save it with a friendly name (protocol_guess from scan, or try both)
nexus-lights add AA:BB:CC:DD:EE:FF "Desk Strip" --protocol triones

# 3. Control it
nexus-lights on "Desk Strip"
nexus-lights color "Desk Strip" 255 0 128
nexus-lights brightness "Desk Strip" 60
nexus-lights off "Desk Strip"

# or all of them at once
nexus-lights all-on
nexus-lights all-off
```

Config lives at `~/.config/nexuslights/lights.yaml`:

```yaml
lights:
  - address: AA:BB:CC:DD:EE:FF
    name: Desk Strip
    protocol: triones
  - address: 11:22:33:44:55:66
    name: Bed Strip
    protocol: elkbledom
```

## Bridging to Apple HomeKit

```bash
pip install -e ".[homekit]"
nexus-lights bridge
```

This starts a small HomeKit accessory server (via
[HAP-python](https://github.com/ikalchev/HAP-python)) that exposes every
light in your config as a HomeKit lightbulb — on/off, brightness, hue,
and saturation. On first run it prints an 8-digit setup code in the
terminal. In the Home app: **Add Accessory → More options... → enter
code**. Once paired, the lights work with Siri, Home automations, and
anyone else on your home's HomeKit.

Run `nexus-lights bridge` on whatever machine stays on and near the
strips (a Raspberry Pi, an always-on Mac mini, etc.) — that machine *is*
your hub, the same role a Hue bridge would normally play.

Pairing state is written to `nexuslights_homekit.state` in the working
directory; don't commit or share that file, it's this bridge's HomeKit
identity.

## Wall panel (tablet)

`android/` is a wall-mounted touchscreen remote for this bridge: an Android
kiosk app that controls the lights through the bridge's panel API
(`nexuslights/panel_api.py`, on the dashboard port, token in
`~/.config/nexuslights/panel_token`). The bridge stays the only thing talking
to the strips and to Apple Home. See [android/README.md](android/README.md).

## Tuning a protocol against real hardware

BLE RGB strip protocols vary slightly between clones even within the
same family. If `on`/`off`/`color` don't do what you expect, use the
scanner and a raw command to iterate:

```bash
nexus-lights scan   # confirm the device address and check the protocol_guess
nexus-lights raw AA:BB:CC:DD:EE:FF triones 56ff0000000ff0aa
```

`raw` writes arbitrary hex bytes to the protocol's write characteristic,
so you can compare against captures from the manufacturer app (e.g. via
Android's Bluetooth HCI snoop log, or the nRF Connect app) and adjust the
frame layout in `nexuslights/protocols/*.py` accordingly.

## Adding a new protocol

Each protocol is a small class in `nexuslights/protocols/` implementing
`LightProtocol` (see `base.py`): it only has to turn a command into raw
bytes plus the write characteristic UUID. Register it in
`nexuslights/protocols/__init__.py`'s `REGISTRY`, and add `name_hints`
so `nexus-lights scan` can guess it automatically. No Bluetooth code
needed in the protocol module itself — `ble_transport.py` handles the
actual GATT write.

## Project layout

```
nexuslights/
  protocols/          # pure byte-packing, no BLE/asyncio dependency
    base.py
    triones.py
    elkbledom.py
  ble_transport.py     # the only module that imports bleak
  config.py            # ~/.config/nexuslights/lights.yaml
  manager.py           # unifies multiple lights/protocols
  homekit_bridge.py     # HAP-python bridge -> Apple Home
  cli.py               # nexus-lights entry point
tests/
  test_protocols.py    # byte-packing tests, no hardware/bleak needed
  test_config.py
```

## Running tests

```bash
pip install -e ".[dev]"
pytest
```

Protocol and config tests run without any Bluetooth hardware or radio.
Actually exercising `ble_transport.py` / `homekit_bridge.py` requires a
real strip and hasn't been hardware-tested yet — try the `scan` and
`raw` commands first against your specific strips and adjust the
protocol byte layouts if needed.
