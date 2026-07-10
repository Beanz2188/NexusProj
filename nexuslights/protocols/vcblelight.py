"""VC-BLELIGHT protocol (AMill-branded lamps, GATT service 0xae30).

Controlled by the "Raingel" app. Byte layout confirmed from a real HCI
snoop capture of that app (see https://github.com/AndrianBdn/open-vc-blelight
for the original -- but partially incorrect -- reverse engineering; the
capture showed two corrections: color frames are 7 bytes with 3 trailing
zero bytes, not 4, and brightness is a 0-100 percentage, not 0-255).

The write characteristic and write type were wrong in an earlier version
of this file (guessed from an ATT opcode in the capture without a
confirmed handle-to-UUID mapping). Decompiling the real Raingel app
(am.doit.dohome.strip.service.StripBleManager) showed it actually writes
to AE01 with WRITE_TYPE_NO_RESPONSE -- confirmed working end-to-end
against real hardware once corrected.
"""

from __future__ import annotations

from .base import LightProtocol


class VcBleLightProtocol(LightProtocol):
    name = "vcblelight"
    write_characteristic = "0000ae01-0000-1000-8000-00805f9b34fb"
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
