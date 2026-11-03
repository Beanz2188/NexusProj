"""LEDnetWF "IOTWF" variant -- newer Zengge/Surplife firmware.

Same GATT layout as :mod:`lednetwf` (service ffff, write ff01, notify
ff02) but a newer firmware (advertised names like "IOTWF06B", sold under
Surplife/Zengge and paired via the Magic Home / Zengge app) that differs
in two ways the classic ``lednetwf`` protocol gets wrong:

  - Power moved to a 0x71 command: ``0x71 0x23 0x0F`` (on) / ``0x71 0x24
    0x0F`` (off), carried in a 4-byte inner block -- NOT the classic
    ``0x3B 0x23`` / ``0x3B 0x24`` frames. On real hardware the old frames
    are ACKed at the GATT layer but silently ignored; the device only
    flips its reported power byte (0x23<->0x24 in the JSON-wrapped status
    it pushes on ff02) in response to the 0x71 frames.
  - Header byte 1 is a per-command sequence counter the stock app
    increments on every command; mirrored here (wraps at 0xFF).

Color is unchanged from classic lednetwf (0x3B 0xA1 + HSV), so brightness
is still folded into the color write. Reference:
https://github.com/ArthurPierce23/surplife-ble (and 8none1/lednetwf_ble).
"""

from __future__ import annotations

import colorsys
import itertools

from .base import LightProtocol


class LedNetWFIotwfProtocol(LightProtocol):
    name = "lednetwf-iotwf"
    write_characteristic = "0000ff01-0000-1000-8000-00805f9b34fb"
    notify_characteristic = "0000ff02-0000-1000-8000-00805f9b34fb"
    name_hints = ("iotwf",)

    def __init__(self) -> None:
        # Header byte 1: per-command sequence counter. The device uses it
        # only to group multi-fragment commands, but the stock firmware
        # always increments it, so do the same. Starts at 1, wraps at 0xFF.
        self._seq = itertools.cycle(range(1, 256))

    def _frame(self, inner: list[int]) -> bytes:
        checksum = sum(inner) & 0xFF
        body = bytes(inner) + bytes([checksum])
        length = len(body)
        return bytes([0x00, next(self._seq), 0x80, 0x00, 0x00, length, length + 1, 0x0B]) + body

    def power_on(self) -> bytes:
        return self._frame([0x71, 0x23, 0x0F])

    def power_off(self) -> bytes:
        return self._frame([0x71, 0x24, 0x0F])

    def set_rgb(self, r: int, g: int, b: int) -> bytes:
        h, s, v = colorsys.rgb_to_hsv(self._clamp(r) / 255, self._clamp(g) / 255, self._clamp(b) / 255)
        hue_byte = round(h * 360 / 2)
        sat_byte = round(s * 100)
        val_byte = round(v * 100)
        return self._frame([0x3B, 0xA1, hue_byte, sat_byte, val_byte, 0, 0, 0, 0, 0, 0, 0])
