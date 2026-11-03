"""Magic Home / Zengge WiFi light controller (flux_led, LAN TCP port 5577).

Some controllers (e.g. Zengge "IOTWF" / Surplife, paired via the Magic
Home app) treat Bluetooth as a short-lived setup channel and are really
meant to be driven over WiFi once joined to the network. Their LAN
protocol is the well-established flux_led/Magic Home one, which is far
more stable than reverse-engineering their quirky newer BLE framing.

Exposes the same async interface the bridge expects from a light
(``connect()``, ``is_connected``, ``power_on()``, ``power_off()``,
``set_brightness()``, ``set_rgb()``) so it drops into the bridge
alongside the BLE lights. flux_led's ``WifiLedBulb`` is synchronous, so
every call runs on this light's own single-thread executor -- never the
shared BLE event loop -- so a slow network round-trip can only ever delay
this one light, the same isolation MerossLight uses for its cloud calls.
"""

from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor


class WifiLight:
    #: flux_led carries brightness on a dedicated channel, independent of
    #: any color write, so the bridge must call set_brightness() in
    #: addition to set_rgb() -- same contract as a protocol whose color
    #: command does NOT already include brightness. (For the single-color
    #: "DIM" controllers this is the only meaningful control.)
    color_command_includes_brightness = False

    def __init__(self, ip: str):
        self.ip = ip
        self._bulb = None
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="wifi-" + ip)

    @property
    def protocol(self) -> "WifiLight":
        # The bridge reads light.protocol.color_command_includes_brightness;
        # a WiFi light has no byte-packing protocol object, so it stands in
        # as its own, exposing just that flag.
        return self

    @property
    def is_connected(self) -> bool:
        return self._bulb is not None

    async def _run(self, fn):
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(self._executor, fn)

    async def connect(self) -> None:
        def _connect():
            from flux_led import WifiLedBulb

            bulb = WifiLedBulb(self.ip)
            bulb.update_state()
            return bulb

        self._bulb = await self._run(_connect)

    async def disconnect(self) -> None:
        bulb = self._bulb
        self._bulb = None
        if bulb is not None:
            def _close():
                try:
                    bulb.close()
                except Exception:
                    pass
            await self._run(_close)

    async def _ensure(self) -> None:
        if self._bulb is None:
            await self.connect()

    async def power_on(self) -> None:
        await self._ensure()
        await self._run(lambda: self._bulb.turnOn())

    async def power_off(self) -> None:
        await self._ensure()
        await self._run(lambda: self._bulb.turnOff())

    async def set_brightness(self, percent: int) -> None:
        await self._ensure()
        level = max(0, min(255, round(percent * 255 / 100)))
        await self._run(lambda: self._bulb.setWarmWhite255(level))

    async def set_rgb(self, r: int, g: int, b: int) -> None:
        # Single-color (DIM) controllers have no color; brightness is driven
        # via set_brightness() instead. For an RGB-capable flux_led device
        # this would set color, but the bridge only pairs these with the
        # brightness-only accessory today, so color writes are a safe no-op.
        await self._ensure()
        if getattr(self._bulb, "rgbwcapable", False) or "DIM" not in getattr(self._bulb, "color_modes", {"DIM"}):
            await self._run(lambda: self._bulb.setRgb(r, g, b))
