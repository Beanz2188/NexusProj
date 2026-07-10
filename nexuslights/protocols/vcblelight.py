"""VC-BLELIGHT protocol (AMill-branded lamps, GATT service 0xae30).

Controlled by the "Raingel" app. Byte layout confirmed from a real HCI
snoop capture of that app (see https://github.com/AndrianBdn/open-vc-blelight
for the original -- but partially incorrect -- reverse engineering; the
capture showed two corrections: color frames are 7 bytes with 3 trailing
zero bytes, not 4, and brightness is a 0-100 percentage, not 0-255).

Known issue: writes are accepted with no GATT error but the device does
not react, on every host tried so far. The real app's connection may be
bonded/encrypted in a way a plain unauthenticated connect() doesn't
satisfy. Unconfirmed working end-to-end -- revisit once this can be
tested from a Linux/BlueZ host, which handles BLE bonding differently
than macOS Core Bluetooth.
"""

from __future__ import annotations

from .base import LightProtocol


class VcBleLightProtocol(LightProtocol):
    name = "vcblelight"
    write_characteristic = "0000ae10-0000-1000-8000-00805f9b34fb"
    write_with_response = True
    name_hints = (
        "vc-blelight",
    )

    def power_on(self) -> bytes:
        return bytes([0x01, 0x01])

    def power_off(self) -> bytes:
        return bytes([0x01, 0x00])

    def set_rgb(self, r: int, g: int, b: int) -> bytes:
        return bytes([0x03, self._clamp(r), self._clamp(g), self._clamp(b), 0x00, 0x00, 0x00])

    def set_brightness(self, percent: int) -> bytes:
        return bytes([0x02, self._clamp(percent, 0, 100)])
