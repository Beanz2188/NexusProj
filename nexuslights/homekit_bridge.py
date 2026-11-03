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
import time

from pyhap.accessory import Accessory, Bridge
from pyhap.accessory_driver import AccessoryDriver
from pyhap.const import CATEGORY_LIGHTBULB, CATEGORY_SWITCH

from . import ambilight
from . import ble_transport
from . import dashboard
from . import music_art_sync
from . import rainbow
from .ble_transport import BleLight
from .config import load as load_config
from .meross_light import load_configured as load_meross_light
from .remote_light import RemoteLight
from .wifi_light import WifiLight

logger = logging.getLogger(__name__)


def _stable_aid(bridge: Bridge, identifier: str) -> int:
    """Derive a HomeKit accessory ID that depends only on `identifier`
    (e.g. a light's name), never on what other accessories exist or the
    order they're added in. Sorting config.lights by address before
    assigning AIDs (the previous approach) is only stable for a *fixed*
    set of lights -- adding a new light with an alphabetically earlier
    address shifts every existing light's position in that sort, and
    therefore its AID, desyncing Home's already-learned accessory mapping
    for lights that were never touched. A hash of the identifier itself
    can't shift like that.

    Callers must pass each light's *name*, not its BLE address, despite
    the address being the more obviously "permanent" identifier -- on
    macOS, CoreBluetooth hands out a different address for the exact same
    physical device on every host that scans for it (confirmed on real
    hardware migrating this bridge to a second Mac: every single light's
    address came back completely different, even though the light itself
    never changed). Keying AIDs off the address meant Apple Home no
    longer recognized any accessory after the move, breaking room
    assignments -- the bridge's own pairing survived, but every light
    underneath it looked new. A light's configured name is the one thing
    that's actually meant to stay constant across a migration."""
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
        #
        # The timeout has to be enforced *inside* the loop thread via
        # asyncio.wait_for(), not by just giving up on the calling
        # thread's future.result(timeout=...): that only stops this
        # thread from waiting, it doesn't cancel the coroutine still
        # running on self._loop. Concretely, that meant an unreachable
        # light's connect() (an in-flight BleakScanner scan) kept running
        # after we'd already "timed out" and moved on to the next light --
        # two concurrent CoreBluetooth scans on macOS then wedged the
        # whole shared BLE loop thread forever, silently hanging every
        # connection attempt after it (observed: the bridge would hang
        # indefinitely right after the first light that failed to
        # connect). wait_for() cancels the coroutine in-place, so its
        # cleanup (stopping the scan) actually runs before this returns.
        async def _run_with_timeout():
            return await asyncio.wait_for(coro, timeout=timeout)

        future = asyncio.run_coroutine_threadsafe(_run_with_timeout(), self._loop)
        return future.result(timeout=timeout + 5.0)

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

    def __init__(self, driver: AccessoryDriver, display_name: str, ble_light: BleLight | RemoteLight, ble_loop: _BleLoop):
        super().__init__(driver, display_name)
        self.ble_light = ble_light
        self.ble_loop = ble_loop
        self._hue = 0.0
        self._saturation = 0.0
        self._brightness = 100
        self._rgb_generation = 0
        self._rgb_was_dark = False

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
            r, g, b = round(r * 255), round(g * 255), round(b * 255)
            is_dark = r == 0 and g == 0 and b == 0
            if self._rgb_was_dark and not is_dark:
                # Brightness hitting 0 sends set_rgb(0, 0, 0) below -- pure
                # black, not a real "off". Some ELK-BLEDOM clones treat that
                # as equivalent to power_off() internally and then ignore
                # further color writes until they see an explicit power_on()
                # frame again, so raising brightness back up from 0 alone
                # doesn't relight them (confirmed on real hardware: "wall
                # light" stayed dark until power_on() was resent).
                await self.ble_light.power_on()
            self._rgb_was_dark = is_dark
            await self.ble_light.set_rgb(r, g, b)
            if not self.ble_light.protocol.color_command_includes_brightness:
                # This protocol (e.g. mrstar) keeps brightness as
                # independent device state, separate from the color
                # command -- moving only the color wheel would otherwise
                # leave the light at whatever brightness it was last set
                # to, decoupled from what Home is showing. Note this is
                # deliberately not a blanket "does set_brightness() exist"
                # check: elkbledom implements it too, but for a separate
                # white/CW channel, not as an alternate brightness scaler.
                await self.ble_light.set_brightness(round(brightness))

        # 20s (the default) assumes a write to an already-open connection,
        # or at worst a reconnect after an occasional drop. With
        # disconnect_after_write on, every single command pays the full
        # connect cost (scan + connect + bleak's one-time BlueZ version
        # check, which spawns a subprocess) -- confirmed on real hardware
        # that a Raspberry Pi Zero W's slow CPU/SD card can blow through
        # 20s on that alone, not because anything's actually stuck.
        timeout = 30.0 if getattr(self.ble_light, "disconnect_after_write", False) else 20.0
        self.ble_loop.run(_send(), timeout=timeout)

    def _set_on(self, value: bool) -> None:
        timeout = 30.0 if getattr(self.ble_light, "disconnect_after_write", False) else 20.0
        if value:
            self.ble_loop.run(self.ble_light.power_on(), timeout=timeout)
            self._push_rgb()
        else:
            self.ble_loop.run(self.ble_light.power_off(), timeout=timeout)

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


class DimmableLightAccessory(Accessory):
    """A single-color light exposed to HomeKit as On + Brightness only (no
    color wheel) -- the honest representation of a dimmable COB/white
    controller. Drives any light object exposing power_on()/power_off()/
    set_brightness() (e.g. a flux_led WifiLight)."""

    category = CATEGORY_LIGHTBULB

    def __init__(self, driver: AccessoryDriver, display_name: str, light, ble_loop: _BleLoop):
        super().__init__(driver, display_name)
        self.light = light
        self.ble_loop = ble_loop
        self._brightness = 100

        service = self.add_preload_service("Lightbulb", chars=["Brightness"])
        self.char_on = service.configure_char("On", setter_callback=self._set_on)
        self.char_brightness = service.configure_char("Brightness", setter_callback=self._set_brightness)

    def _set_on(self, value: bool) -> None:
        if value:
            self.ble_loop.run(self.light.power_on(), timeout=20.0)
            # Restore the last brightness: turning the channel fully on
            # doesn't necessarily reflect the level HomeKit is showing.
            self.ble_loop.run(self.light.set_brightness(self._brightness), timeout=20.0)
        else:
            self.ble_loop.run(self.light.power_off(), timeout=20.0)

    def _set_brightness(self, value: int) -> None:
        self._brightness = value
        if value == 0:
            self.ble_loop.run(self.light.power_off(), timeout=20.0)
        else:
            self.ble_loop.run(self.light.set_brightness(value), timeout=20.0)

    async def stop(self) -> None:
        await self.light.disconnect()


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
        lights: list,
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


class RainbowSwitch(Accessory):
    """A HomeKit Switch that cycles every light through the hue spectrum
    together, forever, until switched off. No external input (camera,
    now-playing metadata) -- same connection-sharing rationale as
    AmbilightSwitch, just a self-contained color cycle."""

    category = CATEGORY_SWITCH

    def __init__(
        self,
        driver: AccessoryDriver,
        lights: list,
        ble_loop: _BleLoop,
        step_degrees: float = 3.0,
        interval: float = 0.03,
        saturation: float = 1.0,
    ):
        super().__init__(driver, "Rainbow")
        self.lights = lights
        self.ble_loop = ble_loop
        self.step_degrees = step_degrees
        self.interval = interval
        self.saturation = saturation
        self._task = None

        service = self.add_preload_service("Switch")
        self.char_on = service.configure_char("On", setter_callback=self._set_on)

    def _set_on(self, value: bool) -> None:
        if value:
            if self._task is not None and not self._task.done():
                return
            self._task = self.ble_loop.create_task(
                rainbow.loop(
                    self.lights,
                    step_degrees=self.step_degrees,
                    interval=self.interval,
                    saturation=self.saturation,
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
            logger.error("Rainbow loop stopped unexpectedly: %s", exc)
            self.char_on.set_value(False)

    async def stop(self) -> None:
        if self._task is not None:
            self.ble_loop.cancel_task(self._task)


class MusicArtSyncSwitch(Accessory):
    """A HomeKit Switch that matches every light's color to the album art
    of whatever's currently playing on a paired Apple TV. Same
    connection-sharing rationale as AmbilightSwitch: runs in this process
    against the bridge's already-open BLE connections."""

    category = CATEGORY_SWITCH

    def __init__(
        self,
        driver: AccessoryDriver,
        lights: list,
        ble_loop: _BleLoop,
        atv_config,
        poll_interval: float = 0.75,
        saturation_boost: float = 1.8,
    ):
        super().__init__(driver, "Music Art Sync")
        self.lights = lights
        self.ble_loop = ble_loop
        self.atv_config = atv_config
        self.poll_interval = poll_interval
        self.saturation_boost = saturation_boost
        self._task = None

        service = self.add_preload_service("Switch")
        self.char_on = service.configure_char("On", setter_callback=self._set_on)

    def _set_on(self, value: bool) -> None:
        if value:
            if self._task is not None and not self._task.done():
                return
            self._task = self.ble_loop.create_task(
                music_art_sync.loop(
                    self.lights,
                    self.atv_config,
                    poll_interval=self.poll_interval,
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
            logger.error("Music art sync loop stopped unexpectedly: %s", exc)
            self.char_on.set_value(False)

    async def stop(self) -> None:
        if self._task is not None:
            self.ble_loop.cancel_task(self._task)


def build_bridge(
    driver: AccessoryDriver,
    ambilight_camera: int = 0,
    ambilight_fps: float = 5.0,
    ambilight_saturation_boost: float = 1.6,
    music_art_sync_poll_interval: float = 0.75,
    music_art_sync_saturation_boost: float = 1.8,
    disconnect_after_write: bool = False,
    relay_url: str | None = None,
    relay_rssi_threshold: float = -75.0,
) -> tuple[Bridge, dashboard.DashboardState, "_BleLoop"]:
    config = load_config()
    if not config.lights:
        raise RuntimeError("No lights configured. Run 'nexus-lights scan' and 'nexus-lights add' first.")

    ble_loop = _BleLoop()
    bridge = Bridge(driver, "NexusLights")
    ble_lights: list[BleLight | RemoteLight] = []
    dashboard_state = dashboard.DashboardState()
    for light_cfg in sorted(config.lights, key=lambda l: l.address):
        # WiFi (flux_led) lights: driven over the LAN, not BLE. `address`
        # holds the device IP. Exposed as a brightness-only accessory and
        # kept out of ble_lights so the ambilight/music color sync (which
        # pushes RGB) doesn't drive a single-color lamp.
        if light_cfg.protocol == "fluxled":
            wifi_light = WifiLight(light_cfg.address)
            try:
                ble_loop.run(wifi_light.connect(), timeout=15.0)
            except Exception:
                logger.warning("Could not reach WiFi light %r at %s at startup; will retry on first command",
                               light_cfg.name, light_cfg.address)
            accessory = DimmableLightAccessory(driver, light_cfg.name, wifi_light, ble_loop)
            accessory.aid = _stable_aid(bridge, light_cfg.name)
            bridge.add_accessory(accessory)
            dashboard_state.lights.append(
                dashboard.LightStatus(
                    name=light_cfg.name,
                    protocol=light_cfg.protocol,
                    address=light_cfg.address,
                    is_ble=False,
                    light=wifi_light,
                )
            )
            continue

        # A light can opt into connect-on-demand per-config (on_demand),
        # independently of the bridge-wide disconnect_after_write flag --
        # needed for "WiFi-first" controllers that flash when a persistent
        # link is held/reconnected (see LightConfig.on_demand).
        light_on_demand = disconnect_after_write or getattr(light_cfg, "on_demand", False)
        light: BleLight | RemoteLight = BleLight(
            light_cfg.address, light_cfg.protocol, disconnect_after_write=light_on_demand
        )
        via_relay = False

        # If a relay is configured (a second host, e.g. a Raspberry Pi,
        # running `nexus-lights relay-server` on lights this machine's own
        # Bluetooth can't reach well), check this light's signal strength
        # from here first. Weak or absent -> hand it off to the relay
        # instead of fighting a bad direct connection for this whole
        # session. Confirmed on real hardware that moving the bridge host
        # can put some lights (e.g. a kitchen light) out of reliable range
        # while others stay fine, so this is a per-light decision, not
        # all-or-nothing.
        if relay_url is not None:
            rssi = ble_loop.run(ble_transport.get_rssi(light_cfg.address, timeout=8.0), timeout=15.0)
            if rssi is None or rssi < relay_rssi_threshold:
                logger.info(
                    "%r: signal too weak from this host (rssi=%s), routing through relay at %s",
                    light_cfg.name,
                    rssi,
                    relay_url,
                )
                light = RemoteLight(relay_url, light_cfg.name)
                via_relay = True

        # Skip the eager startup connect for a direct BLE light when
        # disconnect_after_write is on: every light would still try to
        # connect at once here regardless of the fact that each is about
        # to be released after its first real write, recreating the exact
        # simultaneous-connection ceiling that mode exists to avoid.
        # Connecting lazily on first command keeps at most one link open
        # at a time from the very start. A relayed light's "connect" is
        # just a cheap status check against the relay host, not a BLE
        # connection at all, so it's always fine to do eagerly.
        if via_relay or not light_on_demand:
            try:
                ble_loop.run(light.connect(), timeout=15.0)
            except Exception:
                # Don't let one unreachable light (off, out of range, relay
                # host down) take the whole bridge down -- both BleLight
                # and RemoteLight connect on demand, so this light will
                # just reconnect lazily on its first real command.
                logger.warning("Could not connect to %r at startup; will retry on first command", light_cfg.name)

        ble_lights.append(light)
        accessory = RgbLightAccessory(driver, light_cfg.name, light, ble_loop)
        accessory.aid = _stable_aid(bridge, light_cfg.name)
        bridge.add_accessory(accessory)
        dashboard_state.lights.append(
            dashboard.LightStatus(
                name=light_cfg.name,
                protocol=light_cfg.protocol,
                address=light_cfg.address,
                is_ble=True,
                light=light,
            )
        )

    # Lights driven by ambilight/music-art-sync only, not exposed as their
    # own bridge accessories -- e.g. a Meross light that's already paired
    # to Apple Home directly (HomeKit has no way for this bridge to take a
    # second, independent pairing on it, so it's controlled over Meross's
    # own cloud API instead; see meross_light.py). Home already shows it,
    # this bridge just also nudges its color.
    sync_lights: list = list(ble_lights)
    meross_light = load_meross_light()
    if meross_light is not None:
        try:
            ble_loop.run(meross_light.connect(), timeout=15.0)
        except Exception:
            logger.warning("Could not connect to the Meross light at startup; will retry on first command")
        sync_lights.append(meross_light)
        dashboard_state.lights.append(
            dashboard.LightStatus(
                name="Meross MSL430",
                protocol="meross",
                address=meross_light.device_uuid or "",
                is_ble=False,
                light=meross_light,
            )
        )

    ambilight_switch = AmbilightSwitch(
        driver,
        sync_lights,
        ble_loop,
        camera_index=ambilight_camera,
        fps=ambilight_fps,
        saturation_boost=ambilight_saturation_boost,
    )
    ambilight_switch.aid = _stable_aid(bridge, "__ambilight_switch__")
    bridge.add_accessory(ambilight_switch)
    dashboard_state.ambilight_switch = ambilight_switch

    rainbow_switch = RainbowSwitch(driver, sync_lights, ble_loop)
    rainbow_switch.aid = _stable_aid(bridge, "__rainbow_switch__")
    bridge.add_accessory(rainbow_switch)
    dashboard_state.rainbow_switch = rainbow_switch

    if config.apple_tv is not None:
        music_art_sync_switch = MusicArtSyncSwitch(
            driver,
            sync_lights,
            ble_loop,
            config.apple_tv,
            poll_interval=music_art_sync_poll_interval,
            saturation_boost=music_art_sync_saturation_boost,
        )
        music_art_sync_switch.aid = _stable_aid(bridge, "__music_art_sync_switch__")
        bridge.add_accessory(music_art_sync_switch)
        dashboard_state.music_art_sync_switch = music_art_sync_switch
    else:
        logger.info("No Apple TV paired; skipping Music Art Sync switch (run 'nexus-lights pair-appletv' to add it)")

    return bridge, dashboard_state, ble_loop


async def _reconnect_watchdog(dashboard_state: dashboard.DashboardState, interval: float = 60.0) -> None:
    """Runs forever in the background: every `interval` seconds, tries to
    reconnect any light that's currently disconnected. Handles the common
    case (a BLE link dropped on its own, or a light was briefly out of
    range) without waiting for a HomeKit command to trigger the existing
    reconnect-on-demand path in ble_transport.py's _write() -- confirmed
    on real hardware that all 6 lights can end up disconnected
    simultaneously with nothing to prompt a reconnect until the next
    manual command. Best-effort and silent on failure (logged, not
    raised): one light that's genuinely gone (off, out of range) shouldn't
    spam retries loudly forever, and the external health-watchdog (see
    health_watchdog.py) is the backstop if this alone isn't enough.

    Each light that keeps failing to reconnect is retried on an
    exponential backoff (``interval``, then doubling up to ``BACKOFF_CAP``)
    rather than every tick. Every ``connect()`` attempt blocks the shared
    BLE event loop with a scan, so a genuinely-gone light retried every
    60s starves real HomeKit commands and makes the whole system laggy
    (confirmed on real hardware: 'Desk Strip' offline for weeks drove
    ~24k reconnect scans and timed out unrelated On/Hue/Brightness
    writes). Backoff resets the moment a light reconnects."""
    BACKOFF_CAP = 600.0  # never wait more than 10 min between attempts
    # name -> [consecutive_failures, next_attempt (time.monotonic)]
    backoff: dict[str, list[float]] = {}
    while True:
        await asyncio.sleep(interval)
        now = time.monotonic()
        for ls in dashboard_state.lights:
            if getattr(ls.light, "disconnect_after_write", False):
                # On-demand lights are *meant* to sit disconnected when
                # idle and connect only for a command. Reconnecting them
                # here would wake the device every cycle and make it flash.
                continue
            if ls.light.is_connected:
                backoff.pop(ls.name, None)
                continue
            state = backoff.get(ls.name)
            if state is not None and now < state[1]:
                continue  # still in this light's backoff window
            try:
                await ls.light.connect()
                logger.info("Reconnect watchdog: reconnected %r", ls.name)
                backoff.pop(ls.name, None)
            except Exception as exc:
                fails = int(state[0]) + 1 if state is not None else 1
                delay = min(interval * (2 ** (fails - 1)), BACKOFF_CAP)
                backoff[ls.name] = [fails, now + delay]
                logger.warning(
                    "Reconnect watchdog: could not reconnect %r "
                    "(failure #%d, next retry in %ds): %s",
                    ls.name, fails, int(delay), exc)


def run_bridge(
    port: int = 51826,
    persist_file: str = "nexuslights_homekit.state",
    ambilight_camera: int = 0,
    ambilight_fps: float = 5.0,
    ambilight_saturation_boost: float = 1.6,
    music_art_sync_poll_interval: float = 0.75,
    music_art_sync_saturation_boost: float = 1.8,
    dashboard_port: int | None = 8080,
    disconnect_after_write: bool = False,
    relay_url: str | None = None,
    relay_rssi_threshold: float = -75.0,
) -> None:
    driver = AccessoryDriver(port=port, persist_file=persist_file)
    bridge, dashboard_state, ble_loop = build_bridge(
        driver,
        ambilight_camera=ambilight_camera,
        ambilight_fps=ambilight_fps,
        ambilight_saturation_boost=ambilight_saturation_boost,
        music_art_sync_poll_interval=music_art_sync_poll_interval,
        music_art_sync_saturation_boost=music_art_sync_saturation_boost,
        disconnect_after_write=disconnect_after_write,
        relay_url=relay_url,
        relay_rssi_threshold=relay_rssi_threshold,
    )
    driver.add_accessory(accessory=bridge)

    if dashboard_port is not None:
        try:
            ble_loop.create_task(dashboard.start(dashboard_state, port=dashboard_port))
            logger.info("Status dashboard running at http://localhost:%s", dashboard_port)
        except Exception as exc:
            logger.warning("Could not start the status dashboard: %s", exc)

    ble_loop.create_task(_reconnect_watchdog(dashboard_state))

    logger.info("Starting HomeKit bridge on port %s (state file: %s)", port, persist_file)
    driver.start()
