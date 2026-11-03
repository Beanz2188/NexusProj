"""Client-side stand-in for a light that's actually reached over the
network via relay_server.py running on a different host, not directly
over this process's own Bluetooth.

Duck-types the same interface as BleLight/MerossLight (is_connected,
connect, disconnect, power_on, power_off, set_rgb, set_brightness) so it
drops into homekit_bridge.py's, ambilight.py's, and music_art_sync.py's
light lists exactly like any other light -- callers don't need to know a
given light is actually being controlled by a different machine.
"""

from __future__ import annotations


def _require_aiohttp():
    try:
        import aiohttp
    except ModuleNotFoundError as exc:
        raise RuntimeError(
            "aiohttp is required to reach a relayed light. Install it with:\n"
            "    pip install aiohttp"
        ) from exc
    return aiohttp


def _new_session(aiohttp, timeout_seconds: float):
    # aiohttp resolves hostnames with aiodns (a C-ares based resolver) by
    # default whenever it's installed, which meross-iot already pulls in
    # as a dependency here -- confirmed on real hardware that c-ares can't
    # resolve mDNS/Bonjour ".local" hostnames at all (it only speaks
    # regular unicast DNS), even though the exact same hostname resolves
    # fine via ping/dscacheutil, which go through macOS's own resolver.
    # ThreadedResolver instead calls the standard socket.getaddrinfo(),
    # which *does* go through the OS resolver and therefore handles
    # ".local" names correctly -- needed since the relay host is
    # identified by its mDNS name specifically so it keeps working across
    # DHCP lease changes.
    connector = aiohttp.TCPConnector(resolver=aiohttp.resolver.ThreadedResolver())
    return aiohttp.ClientSession(connector=connector, timeout=aiohttp.ClientTimeout(total=timeout_seconds))


class RemoteLight:
    def __init__(self, relay_url: str, name: str):
        self.relay_url = relay_url.rstrip("/")
        self.name = name
        self._connected = False

    @property
    def is_connected(self) -> bool:
        return self._connected

    async def connect(self) -> None:
        aiohttp = _require_aiohttp()
        async with _new_session(aiohttp, 10) as session:
            async with session.get(f"{self.relay_url}/lights/{self.name}/status") as resp:
                data = await resp.json()
                self._connected = bool(data.get("connected"))

    async def disconnect(self) -> None:
        self._connected = False

    async def _post(self, path: str, json_body: dict | None = None) -> None:
        aiohttp = _require_aiohttp()
        async with _new_session(aiohttp, 15) as session:
            async with session.post(f"{self.relay_url}/lights/{self.name}/{path}", json=json_body) as resp:
                if resp.status != 200:
                    body = await resp.text()
                    self._connected = False
                    raise RuntimeError(f"relay error for {self.name}/{path}: {resp.status} {body}")
                self._connected = True

    async def power_on(self) -> None:
        await self._post("power_on")

    async def power_off(self) -> None:
        await self._post("power_off")

    async def set_rgb(self, r: int, g: int, b: int) -> None:
        await self._post("rgb", {"r": r, "g": g, "b": b})

    async def set_brightness(self, percent: int) -> None:
        await self._post("brightness", {"percent": percent})
