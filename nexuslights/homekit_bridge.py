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
import hashlib
import logging
import threading

from pyhap.accessory import Accessory, Bridge
from pyhap.accessory_driver import AccessoryDriver
from pyhap.const import CATEGORY_LIGHTBULB, CATEGORY_SWITCH

from . import ambilight
from .ble_transport import BleLight
from .config import load as load_config

logger = logging.getLogger(__name__)


def _stable_aid(bridge: Bridge, identifier: str) -> int:
    """Derive a HomeKit accessory ID that depends only on `identifier`
    (e.g. a light's BLE address), never on what other accessories exist
    or the order they're added in. Sorting config.lights by address
    before assigning AIDs (the previous approach) is only stable for a
    *fixed* set of lights -- adding a new light with an alphabetically
    earlier address shifts every existing light's position in that sort,
    and therefore its AID, desyncing Home's already-learned accessory
    mapping for lights that were never touched. A hash of the identifier
    itself can't shift like that."""
    digest = int(hashlib.sha256(identifier.encode()).hexdigest(), 16)
    aid = digest % 1_000_000 + 2  # 0 is invalid, 1 is the bridge itself
    while aid == 7 or aid in bridge.accessories:  # pyhap: "AID=7 gets unsupported. See issue #61"
        aid += 1
    return aid


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

    def create_task(self, coro):
        """Schedule a long-running coroutine without waiting for it to
        finish. Returns the real asyncio.Task (not a wrapping
        concurrent.futures.Future) so it can actually be cancelled later:
        Future.cancel() from run_coroutine_threadsafe only works while the
        coroutine hasn't started running yet, which for a loop that starts
        immediately is never true by the time anyone tries to stop it."""

        async def _spawn():
            return self._loop.create_task(coro)

        future = asyncio.run_coroutine_threadsafe(_spawn(), self._loop)
        return future.result(timeout=5.0)

    def cancel_task(self, task) -> None:
        self._loop.call_soon_threadsafe(task.cancel)


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


class AmbilightSwitch(Accessory):
    """A HomeKit Switch that toggles ambilight mode for every light on the
    bridge. Runs in the same process (and reuses the same already-open BLE
    connections) as the Lightbulb accessories, rather than as a separate
    nexus-lights process, since two processes both holding a connection to
    the same light isn't possible over BLE."""

    category = CATEGORY_SWITCH

    def __init__(
        self,
        driver: AccessoryDriver,
        lights: list[BleLight],
        ble_loop: _BleLoop,
        camera_index: int = 0,
        fps: float = 5.0,
        saturation_boost: float = 1.6,
    ):
        super().__init__(driver, "TV Ambilight")
        self.lights = lights
        self.ble_loop = ble_loop
        self.camera_index = camera_index
        self.fps = fps
        self.saturation_boost = saturation_boost
        self._task = None

        service = self.add_preload_service("Switch")
        self.char_on = service.configure_char("On", setter_callback=self._set_on)

    def _set_on(self, value: bool) -> None:
        if value:
            if self._task is not None and not self._task.done():
                return
            self._task = self.ble_loop.create_task(
                ambilight.loop(
                    self.lights,
                    camera_index=self.camera_index,
                    fps=self.fps,
                    saturation_boost=self.saturation_boost,
                )
            )
            self._task.add_done_callback(self._on_task_done)
        else:
            if self._task is not None:
                self.ble_loop.cancel_task(self._task)
                self._task = None

    def _on_task_done(self, task) -> None:
        if task.cancelled():
            return
        exc = task.exception()
        if exc is not None:
            logger.error("Ambilight loop stopped unexpectedly: %s", exc)
            self.char_on.set_value(False)

    async def stop(self) -> None:
        if self._task is not None:
            self.ble_loop.cancel_task(self._task)


def build_bridge(
    driver: AccessoryDriver,
    ambilight_camera: int = 0,
    ambilight_fps: float = 5.0,
    ambilight_saturation_boost: float = 1.6,
) -> Bridge:
    config = load_config()
    if not config.lights:
        raise RuntimeError("No lights configured. Run 'nexus-lights scan' and 'nexus-lights add' first.")

    ble_loop = _BleLoop()
    bridge = Bridge(driver, "NexusLights")
    ble_lights: list[BleLight] = []
    for light_cfg in sorted(config.lights, key=lambda l: l.address):
        ble_light = BleLight(light_cfg.address, light_cfg.protocol)
        try:
            ble_loop.run(ble_light.connect(), timeout=15.0)
        except Exception:
            # Don't let one unreachable light (off, out of range) take the
            # whole bridge down. _write() connects on demand, so this light
            # will just reconnect lazily on its first real command.
            logger.warning("Could not connect to %r at startup; will retry on first command", light_cfg.name)
        ble_lights.append(ble_light)
        accessory = RgbLightAccessory(driver, light_cfg.name, ble_light, ble_loop)
        accessory.aid = _stable_aid(bridge, light_cfg.address)
        bridge.add_accessory(accessory)

    ambilight_switch = AmbilightSwitch(
        driver,
        ble_lights,
        ble_loop,
        camera_index=ambilight_camera,
        fps=ambilight_fps,
        saturation_boost=ambilight_saturation_boost,
    )
    ambilight_switch.aid = _stable_aid(bridge, "__ambilight_switch__")
    bridge.add_accessory(ambilight_switch)
    return bridge


def run_bridge(
    port: int = 51826,
    persist_file: str = "nexuslights_homekit.state",
    ambilight_camera: int = 0,
    ambilight_fps: float = 5.0,
    ambilight_saturation_boost: float = 1.6,
) -> None:
    driver = AccessoryDriver(port=port, persist_file=persist_file)
    driver.add_accessory(
        accessory=build_bridge(
            driver,
            ambilight_camera=ambilight_camera,
            ambilight_fps=ambilight_fps,
            ambilight_saturation_boost=ambilight_saturation_boost,
        )
    )
    logger.info("Starting HomeKit bridge on port %s (state file: %s)", port, persist_file)
    driver.start()
