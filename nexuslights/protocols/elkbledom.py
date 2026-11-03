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


class ElkBledomNotifyProtocol(ElkBledomProtocol):
    """Same byte format and write characteristic as ElkBledomProtocol, but
    for chip variants (confirmed on real hardware: one branded
    "ELK-BLEDDM", one letter off from "ELK-BLEDOM") that silently ignore
    every write until a client has subscribed to the fff4 notify
    characteristic first -- same quirk LedNetWFProtocol already handles,
    just a different chip family that needs it too. Kept as a separate
    protocol name rather than added to ElkBledomProtocol directly: other
    already-working elkbledom lights may not expose this characteristic at
    all, so turning it on unconditionally for every elkbledom light could
    break their connect() instead of fixing anything. No name_hints -- the
    two variants are indistinguishable by advertised name alone, so this
    one is only reachable by setting --protocol explicitly.
    """

    name = "elkbledom-notify"
    notify_characteristic = "0000fff4-0000-1000-8000-00805f9b34fb"
    # This chip's fff3 characteristic advertises both "write" and
    # "write-without-response" -- confirmed on real hardware that on a
    # weak/borderline connection, fire-and-forget writes sometimes just
    # don't make it over the air with no error on either end (bleak and
    # CoreBluetooth both reported the connection as fine throughout, and
    # the disconnected_callback never fired), so the light looked randomly
    # unresponsive with nothing in the logs to show why -- only repeatedly
    # toggling until one attempt got lucky actually worked. Requesting a
    # response turns a silently dropped command into an actual timeout
    # ble_transport.py's _write() already knows how to catch and retry.
    write_with_response = True
