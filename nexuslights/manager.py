"""Aggregates every configured light behind one simple API.

This is the single place both the CLI and the HomeKit bridge go through,
so "one place to control all your lights" (regardless of which of your
three apps/protocols each strip actually speaks) is enforced by the code
structure, not just a convention.
"""

from __future__ import annotations

import asyncio

from . import config as config_module
from .ble_transport import BleLight
from .config import Config, LightConfig


class LightManager:
    def __init__(self, config: Config | None = None):
        self.config = config if config is not None else config_module.load()
        self._lights: dict[str, BleLight] = {
            light.address: BleLight(light.address, light.protocol) for light in self.config.lights
        }

    def add_light(self, address: str, name: str, protocol: str) -> None:
        self.config.upsert(LightConfig(address=address, name=name, protocol=protocol))
        self._lights[address] = BleLight(address, protocol)
        config_module.save(self.config)

    def remove_light(self, address: str) -> None:
        self.config.lights = [l for l in self.config.lights if l.address.lower() != address.lower()]
        self._lights.pop(address, None)
        config_module.save(self.config)

    def light_names(self) -> list[str]:
        return [light.name for light in self.config.lights]

    def _resolve(self, name_or_address: str) -> BleLight:
        cfg = next(
            (l for l in self.config.lights if name_or_address in (l.name, l.address)),
            None,
        )
        if cfg is None:
            raise KeyError(f"No configured light matches {name_or_address!r}")
        return self._lights[cfg.address]

    async def _with_light(self, name_or_address: str, action) -> None:
        light = self._resolve(name_or_address)
        async with light:
            await action(light)

    async def power_on(self, name_or_address: str) -> None:
        await self._with_light(name_or_address, lambda l: l.power_on())

    async def power_off(self, name_or_address: str) -> None:
        await self._with_light(name_or_address, lambda l: l.power_off())

    async def set_rgb(self, name_or_address: str, r: int, g: int, b: int) -> None:
        await self._with_light(name_or_address, lambda l: l.set_rgb(r, g, b))

    async def set_brightness(self, name_or_address: str, percent: int) -> None:
        await self._with_light(name_or_address, lambda l: l.set_brightness(percent))

    async def all_on(self) -> None:
        await asyncio.gather(*(self.power_on(l.name) for l in self.config.lights))

    async def all_off(self) -> None:
        await asyncio.gather(*(self.power_off(l.name) for l in self.config.lights))
