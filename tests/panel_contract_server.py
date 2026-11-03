"""Runs the real wall-panel API (panel_api.py on the dashboard app) with fake
lights, for the Android client's contract test
(android/app/src/test/.../BridgeContractTest.kt).

    python3 tests/panel_contract_server.py PORT

Prints READY once listening. GET /_calls shows what the fake lights were told.
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path
from unittest.mock import MagicMock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from aiohttp import web  # noqa: E402
from pyhap.loader import get_loader  # noqa: E402

from nexuslights import dashboard  # noqa: E402
from nexuslights.homekit_bridge import DimmableLightAccessory, RgbLightAccessory  # noqa: E402

TOKEN = "contract-token"


class FakeProtocol:
    color_command_includes_brightness = True


class FakeLight:
    def __init__(self, name, calls):
        self.name, self.calls = name, calls
        self.protocol = FakeProtocol()
        self.is_connected = True

    async def power_on(self):
        self.calls.append(f"{self.name} on")

    async def power_off(self):
        self.calls.append(f"{self.name} off")

    async def set_rgb(self, r, g, b):
        self.calls.append(f"{self.name} rgb {r} {g} {b}")

    async def set_brightness(self, p):
        self.calls.append(f"{self.name} brightness {p}")


class FakeLoop:
    def run(self, coro, timeout=20.0):
        return asyncio.run(coro)


class FakeSwitch:
    def __init__(self, aid, name):
        self.aid, self.display_name = aid, name
        self.char_on = MagicMock(value=False)
        self.char_on.set_value.side_effect = lambda v: setattr(self.char_on, "value", v)

    def _set_on(self, value):
        pass


def main(port: int) -> None:
    driver = MagicMock()
    driver.loader = get_loader()
    calls: list[str] = []
    desk_light, hall_light = FakeLight("desk", calls), FakeLight("hall", calls)
    desk = RgbLightAccessory(driver, "Desk Strip", desk_light, FakeLoop())
    desk.aid = 11
    hall = DimmableLightAccessory(driver, "Hallway", hall_light, FakeLoop())
    hall.aid = 12
    state = dashboard.DashboardState(api_token=TOKEN)
    state.lights = [
        dashboard.LightStatus("Desk Strip", "triones", "AA", True, desk_light, accessory=desk),
        dashboard.LightStatus("Hallway", "fluxled", "10.0.0.5", False, hall_light, accessory=hall),
    ]
    state.ambilight_switch = FakeSwitch(20, "TV Ambilight")
    app = dashboard._build_app(state)

    async def get_calls(request):
        return web.json_response(calls)

    app.router.add_get("/_calls", get_calls)

    async def serve():
        runner = web.AppRunner(app)
        await runner.setup()
        await web.TCPSite(runner, "127.0.0.1", port).start()
        print("READY", flush=True)
        await asyncio.Event().wait()

    asyncio.run(serve())


if __name__ == "__main__":
    main(int(sys.argv[1]))
