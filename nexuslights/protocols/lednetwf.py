"""LEDnetWF protocol (Zengge/Magic Hue chipset with GATT service 0xffff).

A newer, unrelated chipset that some LEDnetWF/MELK-branded strips ship
with instead of the classic Triones hardware -- despite the overlapping
brand names, it uses a completely different GATT layout (service ffff,
write char ff01, notify char ff02) and packet format. Reverse engineered
from HCI packet captures by the community; see
https://github.com/8none1/zengge_lednetwf for the original writeup.

Quirks that make this different from the other protocols here:
  - The device ignores every command until notifications are enabled on
    ``notify_characteristic`` -- ``ble_transport.BleLight`` handles that
    automatically when a protocol sets it.
  - The packet counter and checksum are both ignored by the device in
    practice, but the checksum is filled in anyway (sum of the payload
    bytes, AND 0xFF) since that's what real captured traffic does.
"""

from __future__ import annotations

import colorsys

from .base import LightProtocol

_HEADER = bytes([0x00, 0x00, 0x80, 0x00, 0x00, 0x0D, 0x0E, 0x0B])


def _frame(payload: bytes) -> bytes:
    checksum = sum(payload) & 0xFF
    return _HEADER + payload + bytes([checksum])


class LedNetWFProtocol(LightProtocol):
    name = "lednetwf"
    write_characteristic = "0000ff01-0000-1000-8000-00805f9b34fb"
    notify_characteristic = "0000ff02-0000-1000-8000-00805f9b34fb"
    name_hints = (
        "lednetwf",
    )

    def power_on(self) -> bytes:
        return _frame(bytes([0x3B, 0x23, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x32, 0x00, 0x00]))

    def power_off(self) -> bytes:
        return _frame(bytes([0x3B, 0x24, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x32, 0x00, 0x00]))

    def set_rgb(self, r: int, g: int, b: int) -> bytes:
        h, s, v = colorsys.rgb_to_hsv(self._clamp(r) / 255, self._clamp(g) / 255, self._clamp(b) / 255)
        hue_byte = round(h * 360 / 2)
        sat_byte = round(s * 100)
        val_byte = round(v * 100)
        return _frame(
            bytes([0x3B, 0xA1, hue_byte, sat_byte, val_byte, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00])
        )

    def set_effect(self, effect_id: int, speed: int = 50) -> bytes:
        # Different frame shape than power/color: shorter payload, no
        # checksum byte, per community-documented real captured traffic
        # (see EFFECTS_RESEARCH.md) -- not yet confirmed against this
        # project's own hardware.
        return bytes(
            [0x00, 0x00, 0x80, 0x00, 0x00, 0x04, 0x05, 0x0B, 0x38, self._clamp(effect_id, 1, 255), self._clamp(speed, 0, 100), 0x64]
        )
