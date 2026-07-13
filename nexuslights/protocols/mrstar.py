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
    # set_rgb() only sends Hue/Saturation (see module docstring) -- it
    # never touches the device's brightness, which is independent state.
    color_command_includes_brightness = False

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
        # The device's actual brightness scale is 0-1000, not 0-100 --
        # confirmed from a real HCI capture (the app's SeekBar has a max
        # of 1000, not the usual Android default of 100, which is what
        # the decompiled source alone made it easy to assume). Sending a
        # 0-100 value directly on the wire was silently interpreted as
        # out of 1000, so "full brightness" was actually landing at 10%
        # -- keep this method's public contract at 0-100 for consistency
        # with every other protocol here, and scale internally.
        #
        # The stock app's UI clamps its slider to a minimum of 3 (i.e.
        # 0.3%, not 3% as previously assumed) -- an app-layer choice, not
        # a hardware limit: the device accepts a true 0 directly and goes
        # fully dark while staying connected (confirmed on real hardware)
        # -- useful since a real power_off() on this chip cuts its BLE
        # radio instead of just the LEDs.
        value = round(self._clamp(percent, 0, 100) * 10)
        return bytes([0xBC, 0x05, 0x06, value // 256, value % 256, 0x00, 0x00, 0x00, 0x00, 0x55])
