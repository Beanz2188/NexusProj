"""Wall-panel API (nexuslights/panel_api.py) against the real HomeKit
accessory classes, with a fake light standing in for Bluetooth."""

from __future__ import annotations

import asyncio
import stat
from unittest.mock import MagicMock

import pytest

pytest.importorskip("pyhap")
aiohttp = pytest.importorskip("aiohttp")
from aiohttp.test_utils import TestClient, TestServer  # noqa: E402
from pyhap.loader import get_loader  # noqa: E402

from nexuslights import dashboard, panel_api  # noqa: E402
from nexuslights.homekit_bridge import (  # noqa: E402
    DimmableLightAccessory,
    PanelLightSensor,
    PanelMotionSensor,
    RgbLightAccessory,
)

TOKEN = "test-token"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


class FakeProtocol:
    color_command_includes_brightness = True


class FakeLight:
    def __init__(self):
        self.calls: list = []
        self.protocol = FakeProtocol()
        self.is_connected = True

    async def power_on(self):
        self.calls.append("on")

    async def power_off(self):
        self.calls.append("off")

    async def set_rgb(self, r, g, b):
        self.calls.append(("rgb", r, g, b))

    async def set_brightness(self, percent):
        self.calls.append(("brightness", percent))


class FakeLoop:
    """Stands in for _BleLoop: runs the coroutine right away in the calling thread."""

    def run(self, coro, timeout=20.0):
        return asyncio.run(coro)


class FakeSwitch:
    def __init__(self, aid, name):
        self.aid = aid
        self.display_name = name
        self.char_on = MagicMock(value=False)
        self.char_on.set_value.side_effect = lambda v: setattr(self.char_on, "value", v)
        self.calls = []

    def _set_on(self, value):
        self.calls.append(value)


def _driver():
    d = MagicMock()
    d.loader = get_loader()
    return d


@pytest.fixture
def setup():
    driver = _driver()
    strip_light, dimmer_light = FakeLight(), FakeLight()
    strip = RgbLightAccessory(driver, "Desk Strip", strip_light, FakeLoop())
    strip.aid = 11
    dimmer = DimmableLightAccessory(driver, "Hallway", dimmer_light, FakeLoop())
    dimmer.aid = 12
    state = dashboard.DashboardState(api_token=TOKEN)
    state.lights = [
        dashboard.LightStatus("Desk Strip", "triones", "AA", True, strip_light, accessory=strip),
        dashboard.LightStatus("Hallway", "fluxled", "10.0.0.5", False, dimmer_light, accessory=dimmer),
        dashboard.LightStatus("Meross MSL430", "meross", "x", False, FakeLight()),  # not controllable
    ]
    state.ambilight_switch = FakeSwitch(20, "TV Ambilight")
    return state, strip, strip_light, dimmer, dimmer_light, driver


def _call(state, method, path, json=None, headers=AUTH):
    async def go():
        app = dashboard._build_app(state)
        async with TestClient(TestServer(app)) as client:
            resp = await client.request(method, path, json=json, headers=headers)
            return resp.status, await resp.json()

    return asyncio.run(go())


def test_requires_token(setup):
    state, *_ = setup
    assert _call(state, "GET", "/api/lights", headers={})[0] == 401
    assert _call(state, "GET", "/api/lights", headers={"Authorization": "Bearer nope"})[0] == 401
    state.api_token = None
    assert _call(state, "GET", "/api/lights")[0] == 403


def test_status_page_still_open(setup):
    state, *_ = setup
    status, body = _call(state, "GET", "/status", headers={})
    assert status == 200 and len(body["lights"]) == 3


def test_lists_controllable_lights_and_switches(setup):
    state, *_ = setup
    status, body = _call(state, "GET", "/api/lights")
    assert status == 200
    assert [l["name"] for l in body["lights"]] == ["Desk Strip", "Hallway"]
    desk, hall = body["lights"]
    assert desk["id"] == 11 and desk["color"] is True and desk["on"] is False
    assert hall["color"] is False and hall["hue"] is None
    assert body["switches"] == [{"id": 20, "name": "TV Ambilight", "on": False}]
    assert body["panel_sensors"] is False


def test_turn_on_with_color_drives_light_and_updates_home(setup):
    state, strip, light, _, _, driver = setup
    status, body = _call(state, "POST", "/api/lights/11", {"on": True, "brightness": 50, "hue": 0, "saturation": 100})
    assert status == 200
    assert body["on"] is True and body["brightness"] == 50 and body["hue"] == 0 and body["saturation"] == 100
    assert light.calls == ["on", ("rgb", 128, 0, 0)]  # red at 50%
    assert strip.char_on.value is True and strip.char_brightness.value == 50
    assert driver.publish.called  # Home apps get told about the change


def test_color_change_while_off_only_updates_state(setup):
    state, strip, light, *_ = setup
    status, _ = _call(state, "POST", "/api/lights/11", {"hue": 120, "saturation": 100})
    assert status == 200
    assert light.calls == []
    assert strip.char_hue.value == 120


def test_turn_off(setup):
    state, _, light, *_ = setup
    _call(state, "POST", "/api/lights/11", {"on": True})
    light.calls.clear()
    _call(state, "POST", "/api/lights/11", {"on": False})
    assert light.calls == ["off"]


def test_dimmer_ignores_color(setup):
    state, _, _, dimmer, dimmer_light, _ = setup
    status, body = _call(state, "POST", "/api/lights/12", {"on": True, "brightness": 30, "hue": 200})
    assert status == 200 and body["brightness"] == 30 and body["color"] is False
    assert dimmer_light.calls == ["on", ("brightness", 30)]


def test_validation(setup):
    state, *_ = setup
    assert _call(state, "POST", "/api/lights/11", {"brightness": 150})[0] == 400
    assert _call(state, "POST", "/api/lights/11", {"on": "yes"})[0] == 400
    assert _call(state, "POST", "/api/lights/11", {})[0] == 400
    assert _call(state, "POST", "/api/lights/999", {"on": True})[0] == 404
    assert _call(state, "POST", "/api/lights/abc", {"on": True})[0] == 404


def test_switch_toggle(setup):
    state, *_ = setup
    status, body = _call(state, "POST", "/api/switches/20", {"on": True})
    assert status == 200 and body["on"] is True
    assert state.ambilight_switch.calls == [True]
    _call(state, "POST", "/api/switches/20", {"on": True})  # no-op when already on
    assert state.ambilight_switch.calls == [True]


def test_panel_sensors(setup):
    state, *_ = setup
    assert _call(state, "POST", "/api/panel", {"motion": True})[1] == {"panel_sensors": False}
    driver = _driver()
    state.panel_motion = PanelMotionSensor(driver)
    state.panel_lux = PanelLightSensor(driver)
    status, body = _call(state, "POST", "/api/panel", {"motion": True, "lux": 0})
    assert status == 200 and body == {"panel_sensors": True}
    assert state.panel_motion.char_detected.value is True
    assert state.panel_lux.char_lux.value == 0.0001  # clamped to HomeKit's minimum


def test_token_file_created_private(tmp_path, monkeypatch):
    monkeypatch.delenv("NEXUS_PANEL_TOKEN", raising=False)
    path = tmp_path / "nexuslights" / "panel_token"
    token = panel_api.load_or_create_token(path)
    assert len(token) >= 24
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert panel_api.load_or_create_token(path) == token  # stable across restarts
