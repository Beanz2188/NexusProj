"""Triones / "LED BLE" / Happy Lighting protocol.

This is the most common protocol among cheap BLE RGB(W) strips sold under
many different brand names (Triones, LEDBLE, MELK, LEDnetWF, Happy
Lighting...) — they're almost all the same OEM hardware re-skinned, which
is why so many unrelated-looking apps can control the same strip.

Reference: write characteristic FFD9 under service FFD5. Frame formats
below match the widely-documented reverse engineering of this OEM
protocol (used by several open-source Home Assistant integrations for
this device family). Some clones shift a byte or two — if your strip
doesn't respond, use `nexus-lights raw` to experiment and adjust this
file; the frame formats are simple enough to patch.
"""

from __future__ import annotations

from .base import LightProtocol


class TrionesProtocol(LightProtocol):
    name = "triones"
    write_characteristic = "0000ffd9-0000-1000-8000-00805f9b34fb"
    name_hints = (
        "triones",
        "ledble",
        "led-ble",
        "melk",
        "happy lighting",
        "qhm",
    )

    def power_on(self) -> bytes:
        return bytes([0xCC, 0x23, 0x33])

    def power_off(self) -> bytes:
        return bytes([0xCC, 0x24, 0x33])

    def set_rgb(self, r: int, g: int, b: int) -> bytes:
        return bytes(
            [0x56, self._clamp(r), self._clamp(g), self._clamp(b), 0x00, 0xF0, 0xAA]
        )

    def set_white(self, percent: int) -> bytes:
        level = self._clamp(round(percent * 255 / 100))
        return bytes([0x56, 0x00, 0x00, 0x00, level, 0x0F, 0xAA])

    def set_effect(self, effect_id: int, speed: int = 50) -> bytes:
        return bytes([0xBB, self._clamp(effect_id, 0, 0xFF), self._clamp(speed, 0, 0xFF), 0x44])
