"""MrStar protocol (advertised device name "GATT--DEMO").

Controlled by the "MrStar" Android app (package com.findn.mrstar).
Byte layout extracted directly from the app's decompiled source
(AdjustFragment.f0/h0/i0 for color, e1.d.onStopTrackingTouch for
brightness, AdjustFragment for power) rather than guessed from traffic,
so this is a high-confidence match to what the real app sends.

Frames all share the shape [0xBC, cmd, sub, ...args, 0x55]. Unlike
Hue/Saturation/Value, this firmware only takes Hue and Saturation for
color -- Value/brightness is a separate command, and setting a color
does not change the current brightness level.
"""

from __future__ import annotations

import colorsys

from .base import LightProtocol


class MrStarProtocol(LightProtocol):
    name = "mrstar"
    write_characteristic = "0000fff3-0000-1000-8000-00805f9b34fb"
    name_hints = (
        "gatt--demo",
    )
    # The hardware power_off command (below) cuts this chip's BLE radio
    # entirely rather than just switching the LEDs off -- confirmed on
    # real hardware, the device becomes completely unreachable afterward
    # until physically power-cycled. BleLight prefers dimming to the
    # minimum brightness over calling power_off() when this is set.
    power_off_disconnects = True

    def power_on(self) -> bytes:
        return bytes([0xBC, 0x01, 0x01, 0x01, 0x55])

    def power_off(self) -> bytes:
        return bytes([0xBC, 0x01, 0x01, 0x00, 0x55])

    def set_rgb(self, r: int, g: int, b: int) -> bytes:
        h, s, _v = colorsys.rgb_to_hsv(self._clamp(r) / 255, self._clamp(g) / 255, self._clamp(b) / 255)
        hue = int(h * 360)
        sat = int(s * 1000)
        return bytes([0xBC, 0x04, 0x06, hue // 256, hue % 256, sat // 256, sat % 256, 0x00, 0x00, 0x55])

    def set_brightness(self, percent: int) -> bytes:
        # The stock app's UI clamps its slider to a minimum of 3, but
        # that's an app-layer choice, not a hardware limit: the device
        # accepts 0 directly and goes fully dark while staying connected
        # (confirmed on real hardware) -- useful since a real power_off()
        # on this chip cuts its BLE radio instead of just the LEDs.
        percent = self._clamp(percent, 0, 100)
        return bytes([0xBC, 0x05, 0x06, percent // 256, percent % 256, 0x00, 0x00, 0x00, 0x00, 0x55])
