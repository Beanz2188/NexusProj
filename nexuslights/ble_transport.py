"""The only module that talks to Bluetooth directly.

Uses ``bleak`` (cross-platform: BlueZ on Linux, Core Bluetooth on macOS,
WinRT on Windows) so the same code works whether nexuslights runs on a
Raspberry Pi, a Mac, or a Linux box acting as your smart-home hub.

``bleak`` is imported lazily so the rest of the package (protocol byte
packing, config handling) can be imported and unit-tested on a machine
that doesn't have it installed or doesn't have a Bluetooth radio.
"""

from __future__ import annotations

from dataclasses import dataclass

from .protocols import LightProtocol, get_protocol, guess_protocol


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


class BleLight:
    """A single BLE RGB light: an address + protocol, connected on demand.

    Usage:
        light = BleLight("AA:BB:CC:DD:EE:FF", "triones")
        async with light:
            await light.set_rgb(255, 0, 128)
    """

    def __init__(self, address: str, protocol: str | LightProtocol):
        self.address = address
        self.protocol: LightProtocol = (
            protocol if isinstance(protocol, LightProtocol) else get_protocol(protocol)
        )
        self._client = None

    async def connect(self) -> None:
        bleak = _require_bleak()
        self._client = bleak.BleakClient(self.address)
        await self._client.connect()

    async def disconnect(self) -> None:
        if self._client is not None:
            await self._client.disconnect()
            self._client = None

    async def __aenter__(self) -> "BleLight":
        await self.connect()
        return self

    async def __aexit__(self, *exc_info) -> None:
        await self.disconnect()

    async def _write(self, payload: bytes) -> None:
        if self._client is None:
            raise RuntimeError("Light is not connected; use 'async with light:' first")
        await self._client.write_gatt_char(self.protocol.write_characteristic, payload, response=False)

    async def power_on(self) -> None:
        await self._write(self.protocol.power_on())

    async def power_off(self) -> None:
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
