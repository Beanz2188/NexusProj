"""Expose configured lights to Apple Home via HomeKit — no separate hub
needed. This process *is* the "smart home hub" for these lights: it runs
a HAP-python accessory server that Apple Home pairs with directly using
a setup code, the same way it would pair with a Philips Hue bridge.

HAP-python's accessory driver calls characteristic setters synchronously
from its own event loop. BLE writes are async (bleak), so each accessory
hands its writes to a dedicated background event loop running in its own
thread, and blocks briefly waiting for the result -- simplest thing that
works correctly for a handful of lights; not designed for hundreds.

Run with: nexus-lights bridge
Then in the Home app: Add Accessory -> More options... -> enter the
8-digit setup code HAP-python prints on first run.
"""

from __future__ import annotations

import asyncio
import colorsys
import logging
import threading

from pyhap.accessory import Accessory, Bridge
from pyhap.accessory_driver import AccessoryDriver
from pyhap.const import CATEGORY_LIGHTBULB

from .ble_transport import BleLight
from .config import load as load_config

logger = logging.getLogger(__name__)


class _BleLoop:
    """Runs a dedicated asyncio loop on a background thread so HAP-python's
    synchronous characteristic-setter callbacks can drive bleak writes."""

    def __init__(self) -> None:
        self._loop = asyncio.new_event_loop()
        self._thread = threading.Thread(target=self._loop.run_forever, daemon=True)
        self._thread.start()

    def run(self, coro, timeout: float = 20.0):
        # BleLight.connect() scans for the device (up to 10s) before
        # connecting, so a mid-session reconnect after the BLE link drops
        # can legitimately take longer than a plain write. 5s wasn't
        # enough headroom for that and caused spurious TimeoutErrors on
        # otherwise-successful reconnects.
        future = asyncio.run_coroutine_threadsafe(coro, self._loop)
        return future.result(timeout=timeout)


class RgbLightAccessory(Accessory):
    category = CATEGORY_LIGHTBULB

    def __init__(self, driver: AccessoryDriver, display_name: str, ble_light: BleLight, ble_loop: _BleLoop):
        super().__init__(driver, display_name)
        self.ble_light = ble_light
        self.ble_loop = ble_loop
        self._hue = 0.0
        self._saturation = 0.0
        self._brightness = 100
        self._rgb_generation = 0

        service = self.add_preload_service("Lightbulb", chars=["Brightness", "Hue", "Saturation"])
        self.char_on = service.configure_char("On", setter_callback=self._set_on)
        self.char_brightness = service.configure_char("Brightness", setter_callback=self._set_brightness)
        self.char_hue = service.configure_char("Hue", setter_callback=self._set_hue)
        self.char_saturation = service.configure_char("Saturation", setter_callback=self._set_saturation)

    def _push_rgb(self) -> None:
        # HomeKit fires Hue/Saturation/Brightness as separate rapid-fire
        # characteristic writes while dragging the color picker. Each BLE
        # write is a slow round trip, so a burst of drag events queues up
        # behind a single connection. Tag every push with a generation
        # number and have queued sends bail out if a newer one has already
        # been requested, so only the final color in a burst reaches the
        # light instead of it visibly working through stale intermediate
        # colors.
        self._rgb_generation += 1
        generation = self._rgb_generation
        hue, saturation, brightness = self._hue, self._saturation, self._brightness

        async def _send() -> None:
            if generation != self._rgb_generation:
                return
            r, g, b = colorsys.hsv_to_rgb(hue / 360.0, saturation / 100.0, brightness / 100.0)
            await self.ble_light.set_rgb(round(r * 255), round(g * 255), round(b * 255))

        self.ble_loop.run(_send())

    def _set_on(self, value: bool) -> None:
        if value:
            self.ble_loop.run(self.ble_light.power_on())
            self._push_rgb()
        else:
            self.ble_loop.run(self.ble_light.power_off())

    def _set_brightness(self, value: int) -> None:
        self._brightness = value
        self._push_rgb()

    def _set_hue(self, value: float) -> None:
        self._hue = value
        self._push_rgb()

    def _set_saturation(self, value: float) -> None:
        self._saturation = value
        self._push_rgb()

    async def stop(self) -> None:
        await self.ble_light.disconnect()


def build_bridge(driver: AccessoryDriver) -> Bridge:
    config = load_config()
    if not config.lights:
        raise RuntimeError("No lights configured. Run 'nexus-lights scan' and 'nexus-lights add' first.")

    ble_loop = _BleLoop()
    bridge = Bridge(driver, "NexusLights")
    # HAP-python auto-assigns each accessory's AID by the order add_accessory()
    # is called, and HomeKit pairings cache AID per accessory. Config.upsert()
    # re-appends an entry when it's updated, which reorders config.lights and
    # would otherwise silently reassign AIDs -- breaking existing HomeKit
    # pairings for lights whose config hasn't even changed. Iterating in a
    # stable order (by address, which never changes for a given light) keeps
    # AID assignment consistent across config edits.
    for light_cfg in sorted(config.lights, key=lambda l: l.address):
        ble_light = BleLight(light_cfg.address, light_cfg.protocol)
        try:
            ble_loop.run(ble_light.connect(), timeout=15.0)
        except Exception:
            # Don't let one unreachable light (off, out of range) take the
            # whole bridge down. _write() connects on demand, so this light
            # will just reconnect lazily on its first real command.
            logger.warning("Could not connect to %r at startup; will retry on first command", light_cfg.name)
        bridge.add_accessory(RgbLightAccessory(driver, light_cfg.name, ble_light, ble_loop))
    return bridge


def run_bridge(port: int = 51826, persist_file: str = "nexuslights_homekit.state") -> None:
    driver = AccessoryDriver(port=port, persist_file=persist_file)
    driver.add_accessory(accessory=build_bridge(driver))
    logger.info("Starting HomeKit bridge on port %s (state file: %s)", port, persist_file)
    driver.start()
