"""The only module that talks to Bluetooth directly.

Uses ``bleak`` (cross-platform: BlueZ on Linux, Core Bluetooth on macOS,
WinRT on Windows) so the same code works whether nexuslights runs on a
Raspberry Pi, a Mac, or a Linux box acting as your smart-home hub.

``bleak`` is imported lazily so the rest of the package (protocol byte
packing, config handling) can be imported and unit-tested on a machine
that doesn't have it installed or doesn't have a Bluetooth radio.
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass

from .protocols import LightProtocol, get_protocol, guess_protocol

logger = logging.getLogger(__name__)

_WRITE_TIMEOUT = 5.0


def _require_bleak():
    try:
        import bleak
    except ModuleNotFoundError as exc:
        raise RuntimeError(
            "bleak is required for BLE operations. Install it with:\n"
            "    pip install bleak"
        ) from exc
    return bleak


@dataclass
class DiscoveredDevice:
    address: str
    name: str | None
    rssi: int | None
    guessed_protocol: str | None


async def scan(timeout: float = 8.0) -> list[DiscoveredDevice]:
    """Scan for nearby BLE devices and guess which ones are RGB strips."""
    bleak = _require_bleak()
    devices = await bleak.BleakScanner.discover(timeout=timeout, return_adv=True)

    found: list[DiscoveredDevice] = []
    for device, adv in devices.values():
        name = device.name or adv.local_name
        found.append(
            DiscoveredDevice(
                address=device.address,
                name=name,
                rssi=adv.rssi,
                guessed_protocol=guess_protocol(name),
            )
        )
    found.sort(key=lambda d: (d.guessed_protocol is None, -(d.rssi or -999)))
    return found


async def get_rssi(address: str, timeout: float = 8.0) -> int | None:
    """One-shot scan to check a device's current signal strength from
    this host, without connecting to it. Used at bridge startup to decide
    whether a light is reachable strongly enough from here, or should
    instead be routed through a network relay running on a different,
    better-placed host (see remote_light.py). Returns None if the device
    isn't seen at all within the scan window."""
    bleak = _require_bleak()
    devices = await bleak.BleakScanner.discover(timeout=timeout, return_adv=True)
    for device, adv in devices.values():
        if device.address.lower() == address.lower():
            return adv.rssi
    return None


class BleLight:
    """A single BLE RGB light: an address + protocol, connected on demand.

    Usage:
        light = BleLight("AA:BB:CC:DD:EE:FF", "triones")
        async with light:
            await light.set_rgb(255, 0, 128)
    """

    def __init__(
        self,
        address: str,
        protocol: str | LightProtocol,
        disconnect_after_write: bool = False,
    ):
        self.address = address
        self.protocol: LightProtocol = (
            protocol if isinstance(protocol, LightProtocol) else get_protocol(protocol)
        )
        self._client = None
        # Off by default: holding one persistent connection per light is
        # faster and works fine on capable hardware (confirmed reliable on
        # macOS/CoreBluetooth for 6+ simultaneous lights). Some BLE chips
        # can't sustain that many links at once, though -- confirmed on
        # real hardware that a Raspberry Pi Zero W's onboard Broadcom
        # radio started failing to connect more and more lights the longer
        # it tried to keep all of them connected simultaneously, eventually
        # reaching 0/6 connected. Enabling this trades per-command latency
        # (a fresh connect for every write) for never holding more than one
        # connection open at a time, keeping well under whatever ceiling
        # the radio has regardless of how many lights are configured.
        self.disconnect_after_write = disconnect_after_write

    async def connect(self) -> None:
        bleak = _require_bleak()
        # Resolve to a BLEDevice via an explicit scan first rather than
        # handing bleak a bare address string. Constructed from a string,
        # BleakClient.connect() does its own internal scan-by-address before
        # connecting; in practice that path has been noticeably less
        # reliable on macOS (writes silently not taking effect) than
        # scanning first and connecting to the resulting device object.
        device = await bleak.BleakScanner.find_device_by_address(self.address, timeout=10.0)
        self._client = bleak.BleakClient(
            device if device is not None else self.address,
            disconnected_callback=lambda _client: logger.info(
                "BLE disconnected: %s (protocol=%s)", self.address, self.protocol.name
            ),
        )
        try:
            await self._client.connect()
        except Exception:
            # On BlueZ, a connect() that fails partway (confirmed on real
            # hardware: a Pi's onboard Broadcom chip hitting its limit on
            # simultaneous BLE links) can still leave BlueZ's own device
            # object thinking it's connected, even though this client
            # object never finished connecting -- every later connect
            # attempt for that address then fails too ("device not found"
            # during scan, since a BlueZ-connected peripheral stops
            # advertising), compounding across restarts until every light
            # is stuck. Explicitly disconnecting after a failed connect
            # clears that phantom state so the next attempt isn't blocked
            # by this one's failure.
            try:
                await self._client.disconnect()
            except Exception:
                pass
            raise
        if self.protocol.notify_characteristic is not None:
            # Some chipsets (e.g. LEDnetWF) silently ignore every write
            # until a client has subscribed to notifications.
            await self._client.start_notify(self.protocol.notify_characteristic, lambda *_: None)

    async def disconnect(self) -> None:
        if self._client is not None:
            await self._client.disconnect()
            self._client = None

    @property
    def is_connected(self) -> bool:
        return self._client is not None and self._client.is_connected

    async def __aenter__(self) -> "BleLight":
        await self.connect()
        return self

    async def __aexit__(self, *exc_info) -> None:
        await self.disconnect()

    async def _write(self, payload: bytes) -> None:
        if self._client is None or not self._client.is_connected:
            # self._client is None whenever the *first ever* connect() for
            # this light was cancelled/failed before it got far enough to
            # construct a client at all (confirmed on real hardware: every
            # light can fail its startup connect at once on a machine
            # that's never bonded with any of them before) -- previously
            # this raised instead of reconnecting, silently breaking that
            # light for every future command despite the bridge's own
            # startup log promising "will retry on first command". The BLE
            # link can also drop on its own after a successful connect
            # (range, interference, the peripheral's own timeout) between
            # long-lived connections such as the HomeKit bridge's --
            # either way, reconnect on demand rather than failing every
            # write from here on.
            await self.connect()
        try:
            await self._write_once(payload)
        except asyncio.TimeoutError:
            # bleak/CoreBluetooth can keep reporting is_connected=True for a
            # link that's actually gone dead (silently dropped rather than
            # cleanly disconnected) -- confirmed on real hardware that a
            # write to a light in this state just hangs, forever reporting
            # "connected", never timing out on its own. Left unbounded, that
            # stalled the whole HomeKit bridge for 20+ seconds (every light
            # shares one HAP session, so one stuck write blocks all of
            # them), long enough for Apple Home to consider the bridge
            # unresponsive -- most visible after activating a Scene that
            # writes to several lights at once. Force a fresh connection and
            # retry once so the failure resolves in seconds instead of
            # hanging until the caller's own much longer timeout gives up.
            logger.warning("BLE write to %s timed out on a stale connection; reconnecting and retrying", self.address)
            try:
                await self._client.disconnect()
            except Exception:
                pass
            self._client = None
            await self.connect()
            await self._write_once(payload)
        if self.disconnect_after_write:
            await self.disconnect()

    async def _write_once(self, payload: bytes) -> None:
        await asyncio.wait_for(
            self._client.write_gatt_char(
                self.protocol.write_characteristic, payload, response=self.protocol.write_with_response
            ),
            timeout=_WRITE_TIMEOUT,
        )

    async def power_on(self) -> None:
        # Only power_off is special-cased below: on real hardware, sending
        # this chip's actual power_on byte never disconnects it, only
        # power_off does -- so there's no reason to avoid it here. But the
        # "off" workaround leaves brightness at 0, which power_on's own
        # byte doesn't reset (brightness is separate device state), so
        # restore it explicitly for protocols using that workaround.
        await self._write(self.protocol.power_on())
        if self.protocol.power_off_disconnects:
            await self._write(self.protocol.set_brightness(100))

    async def power_off(self) -> None:
        if self.protocol.power_off_disconnects:
            await self._write(self.protocol.set_brightness(0))
        else:
            await self._write(self.protocol.power_off())

    async def set_rgb(self, r: int, g: int, b: int) -> None:
        await self._write(self.protocol.set_rgb(r, g, b))

    async def set_brightness(self, percent: int) -> None:
        await self._write(self.protocol.set_brightness(percent))

    async def set_white(self, percent: int) -> None:
        await self._write(self.protocol.set_white(percent))

    async def set_effect(self, effect_id: int, speed: int = 50) -> None:
        await self._write(self.protocol.set_effect(effect_id, speed))

    async def send_raw(self, payload: bytes) -> None:
        """Escape hatch for tuning a protocol against real hardware: send
        arbitrary bytes to the write characteristic and see what happens."""
        await self._write(payload)
