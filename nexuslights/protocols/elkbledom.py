"""ELK-BLEDOM protocol (also sold as ELK-BULB, LED-BLE, Longra, etc.).

The second most common BLE RGB strip protocol found on AliExpress/Amazon
generic strips. Every frame is 9 bytes: 0x7e header, payload, 0x00, 0xef
trailer. Reference matches the reverse engineering behind several
open-source Home Assistant custom components for this device family.
"""

from __future__ import annotations

from .base import LightProtocol


class ElkBledomProtocol(LightProtocol):
    name = "elkbledom"
    write_characteristic = "0000fff3-0000-1000-8000-00805f9b34fb"
    name_hints = (
        "elk-ble",
        "elk-bulb",
        "elk-lamp",
        "longra",
        "led-ble",
    )

    def power_on(self) -> bytes:
        return bytes([0x7E, 0x00, 0x04, 0xF0, 0x00, 0x01, 0xFF, 0x00, 0xEF])

    def power_off(self) -> bytes:
        return bytes([0x7E, 0x00, 0x04, 0x00, 0x00, 0x00, 0xFF, 0x00, 0xEF])

    def set_rgb(self, r: int, g: int, b: int) -> bytes:
        return bytes(
            [0x7E, 0x00, 0x05, 0x03, self._clamp(r), self._clamp(g), self._clamp(b), 0x00, 0xEF]
        )

    def set_brightness(self, percent: int) -> bytes:
        level = self._clamp(percent, 0, 100)
        return bytes([0x7E, 0x00, 0x01, level, 0xFF, 0xFF, 0xFF, 0x00, 0xEF])

    def set_effect(self, effect_id: int, speed: int = 50) -> bytes:
        return bytes(
            [0x7E, 0x00, 0x03, self._clamp(effect_id, 0, 0xFF), self._clamp(speed, 0, 0xFF), 0xFF, 0xFF, 0x00, 0xEF]
        )
