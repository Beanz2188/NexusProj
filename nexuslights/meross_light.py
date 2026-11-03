"""Control for Meross cloud-connected lights (e.g. the MSL430 ambient
light) that are already paired to Apple Home directly.

HomeKit only allows a *second* controller pairing to be added by an
already-paired controller (an "Add Pairing" request sent through an
authenticated session) -- it has no path for an independent tool like
this one to create its own separate pairing against an accessory Apple
Home already owns, short of removing it from Home and re-adding it
afterward. Meross's own cloud API sidesteps that limitation entirely:
it's a completely separate control path from HomeKit, so it doesn't
touch the accessory's existing Apple Home pairing at all.

Exposes the same async interface (``connect()``, ``is_connected``,
``set_rgb()``) as ``ble_transport.BleLight``, so it can be dropped into
ambilight.py's and music_art_sync.py's light lists alongside the BLE
lights without those modules needing to know the difference.
"""

from __future__ import annotations

import asyncio
import json
import threading
from pathlib import Path

CREDENTIALS_PATH = Path.home() / ".config" / "nexuslights" / "meross_credentials.json"


def load_configured() -> "MerossLight | None":
    """Returns a MerossLight for the account's saved device, or None if
    'nexus-lights pair-meross' hasn't been run yet."""
    if not CREDENTIALS_PATH.exists():
        return None
    creds = json.loads(CREDENTIALS_PATH.read_text())
    device_uuid = creds.get("device_uuid")
    if not device_uuid:
        return None
    return MerossLight(device_uuid=device_uuid)


class MerossLight:
    def __init__(self, device_uuid: str | None = None, credentials_path: Path = CREDENTIALS_PATH):
        self.device_uuid = device_uuid
        self.credentials_path = credentials_path
        self._http_client = None
        self._manager = None
        self._device = None

        # meross-iot's manager keeps a persistent MQTT client alive in the
        # background for as long as it's connected (reconnects, keepalives,
        # retries). Every other light on the bridge shares one event-loop
        # thread for BLE writes (_BleLoop in homekit_bridge.py); if Meross's
        # background work ran on that same thread, a slow cloud
        # reconnect/timeout could starve out and delay unrelated BLE
        # writes queued behind it -- confirmed on real hardware: a Meross
        # MQTT timeout was immediately followed by a BLE light's brightness
        # write blowing through its own 20s timeout and getting reported
        # back to HomeKit as a failure mid-scene. Giving Meross its own
        # dedicated loop/thread means a cloud hiccup can only ever slow
        # Meross's own calls, never anyone else's.
        self._loop = asyncio.new_event_loop()
        self._thread = threading.Thread(target=self._loop.run_forever, daemon=True)
        self._thread.start()

    async def _run(self, coro):
        future = asyncio.run_coroutine_threadsafe(coro, self._loop)
        return await asyncio.wrap_future(future)

    @property
    def is_connected(self) -> bool:
        return self._device is not None

    async def connect(self) -> None:
        await self._run(self._connect())

    async def _connect(self) -> None:
        from meross_iot.http_api import MerossHttpClient
        from meross_iot.manager import MerossManager

        if not self.credentials_path.exists():
            raise RuntimeError(
                f"No Meross credentials found at {self.credentials_path}. "
                "Run 'nexus-lights pair-meross' first."
            )
        creds = json.loads(self.credentials_path.read_text())
        self._http_client = await MerossHttpClient.async_from_user_password(
            api_base_url="https://iot.meross.com", email=creds["email"], password=creds["password"]
        )
        self._manager = MerossManager(http_client=self._http_client)
        await self._manager.async_init()
        await self._manager.async_device_discovery()
        uuids = (self.device_uuid,) if self.device_uuid else None
        devices = self._manager.find_devices(device_uuids=uuids, device_type="msl430")
        if not devices:
            raise RuntimeError(f"Meross device {self.device_uuid or '(msl430)'} not found on the account.")
        self._device = devices[0]
        await self._device.async_update()

    async def disconnect(self) -> None:
        await self._run(self._disconnect())

    async def _disconnect(self) -> None:
        if self._manager is not None:
            self._manager.close()
        if self._http_client is not None:
            await self._http_client.async_logout()
        self._manager = None
        self._http_client = None
        self._device = None

    async def set_rgb(self, r: int, g: int, b: int) -> None:
        await self._run(self._set_rgb(r, g, b))

    async def _set_rgb(self, r: int, g: int, b: int) -> None:
        if not self.is_connected:
            await self._connect()
        await self._device.async_set_light_color(rgb=(r, g, b))

    async def power_on(self) -> None:
        await self._run(self._power_on())

    async def _power_on(self) -> None:
        if not self.is_connected:
            await self._connect()
        await self._device.async_toggle(True)

    async def power_off(self) -> None:
        await self._run(self._power_off())

    async def _power_off(self) -> None:
        if not self.is_connected:
            await self._connect()
        await self._device.async_toggle(False)
