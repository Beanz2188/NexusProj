"""Control API for the wall-panel tablet (see android/), served by the
status dashboard on the same port.

The bridge stays the only thing talking to the lights; the tablet is a
remote. Every command goes through the same HomeKit accessory objects Home
uses, so a tap on the tablet updates the Home app live (and vice versa: the
tablet polls and sees changes made from Home, Siri or automations).

Endpoints (all need ``Authorization: Bearer <token>``):

  GET  /api/lights           lights + mode switches + their current state
  POST /api/lights/{id}      {"on": bool, "brightness": 0-100,
                              "hue": 0-360, "saturation": 0-100}  (any subset)
  POST /api/switches/{id}    {"on": bool}   (TV Ambilight, Rainbow, ...)
  POST /api/panel            {"motion": bool, "lux": float}   (panel sensors)

The token is created on first start at ~/.config/nexuslights/panel_token
(mode 600); android/scripts/provision.sh copies it onto the tablet. Without
it, anyone on the Wi-Fi could drive the lights, which HomeKit itself never
allows.

Threading: the dashboard runs on the bridge's BLE event loop, and the
accessory setters block on that same loop (``_BleLoop.run``), so every
command runs in a worker thread. One lock per accessory keeps two panel
commands for the same light from interleaving.
"""

from __future__ import annotations

import asyncio
import hmac
import logging
import os
import secrets
import threading
from pathlib import Path

logger = logging.getLogger(__name__)

TOKEN_PATH = Path.home() / ".config" / "nexuslights" / "panel_token"

_locks: dict[int, threading.Lock] = {}
_locks_guard = threading.Lock()


def load_or_create_token(path: Path = TOKEN_PATH) -> str:
    """Read the panel token, creating a random one (mode 600) if missing."""
    env = os.environ.get("NEXUS_PANEL_TOKEN")
    if env:
        return env.strip()
    if path.exists():
        token = path.read_text().strip()
        if token:
            return token
    path.parent.mkdir(parents=True, exist_ok=True)
    token = secrets.token_urlsafe(24)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(token + "\n")
    logger.info("Created wall-panel API token at %s", path)
    return token


def _char(acc, name: str):
    c = getattr(acc, name, None)
    return None if c is None else c.value


def light_dict(ls) -> dict | None:
    """JSON for one dashboard LightStatus, or None if the panel can't control it
    (e.g. the Meross light, which Home controls directly)."""
    acc = getattr(ls, "accessory", None)
    if acc is None or not hasattr(acc, "panel_set"):
        return None
    color = bool(getattr(acc, "supports_color", True))
    light = ls.light
    return {
        "id": acc.aid,
        "name": ls.name,
        "protocol": ls.protocol,
        "connected": bool(getattr(light, "is_connected", False)),
        "on_demand": bool(getattr(light, "disconnect_after_write", False)),
        "color": color,
        "on": bool(_char(acc, "char_on")),
        "brightness": int(_char(acc, "char_brightness") or 0),
        "hue": float(_char(acc, "char_hue") or 0.0) if color else None,
        "saturation": float(_char(acc, "char_saturation") or 0.0) if color else None,
    }


def _switches(state) -> list:
    return [
        s for s in (state.ambilight_switch, state.rainbow_switch, state.music_art_sync_switch)
        if s is not None and hasattr(s, "char_on")
    ]


def switch_dict(sw) -> dict:
    return {"id": sw.aid, "name": sw.display_name, "on": bool(sw.char_on.value)}


def snapshot(state) -> dict:
    lights = [d for d in (light_dict(ls) for ls in state.lights) if d is not None]
    return {
        "lights": lights,
        "switches": [switch_dict(s) for s in _switches(state)],
        "panel_sensors": state.panel_motion is not None or state.panel_lux is not None,
    }


def _lock_for(aid: int) -> threading.Lock:
    with _locks_guard:
        return _locks.setdefault(aid, threading.Lock())


def _parse_light_body(body: dict) -> dict:
    """Validate a light command; raises ValueError with a readable message."""
    out: dict = {}
    if "on" in body:
        if not isinstance(body["on"], bool):
            raise ValueError("'on' must be true or false")
        out["on"] = body["on"]
    for key, lo, hi, kind in (("brightness", 0, 100, int), ("hue", 0, 360, float), ("saturation", 0, 100, float)):
        if key in body and body[key] is not None:
            v = body[key]
            if isinstance(v, bool) or not isinstance(v, (int, float)):
                raise ValueError(f"'{key}' must be a number")
            if not lo <= v <= hi:
                raise ValueError(f"'{key}' must be between {lo} and {hi}")
            out[key] = kind(round(v)) if kind is int else kind(v)
    if not out:
        raise ValueError("nothing to change: send on, brightness, hue and/or saturation")
    return out


def register(app, state, web) -> None:
    """Add the /api routes and their auth check to the dashboard's aiohttp app."""

    @web.middleware
    async def auth(request, handler):
        if request.path.startswith("/api/"):
            token = state.api_token
            if not token:
                return web.json_response({"error": "wall-panel API is disabled"}, status=403)
            given = request.headers.get("Authorization", "")
            if not hmac.compare_digest(given.encode(), f"Bearer {token}".encode()):
                return web.json_response({"error": "bad or missing token"}, status=401)
        return await handler(request)

    app.middlewares.append(auth)

    def _find(items, raw_id):
        try:
            wanted = int(raw_id)
        except ValueError:
            return None
        return next((x for x in items if x[0] == wanted), None)

    async def _run(aid: int, fn):
        loop = asyncio.get_running_loop()

        def locked():
            with _lock_for(aid):
                fn()

        await loop.run_in_executor(None, locked)

    async def get_lights(request):
        return web.json_response(snapshot(state))

    async def post_light(request):
        lights = [(ls.accessory.aid, ls) for ls in state.lights
                  if getattr(ls, "accessory", None) is not None and hasattr(ls.accessory, "panel_set")]
        hit = _find(lights, request.match_info["id"])
        if hit is None:
            return web.json_response({"error": "no such light"}, status=404)
        ls = hit[1]
        try:
            changes = _parse_light_body(await request.json())
        except (ValueError, TypeError) as exc:
            return web.json_response({"error": str(exc)}, status=400)
        try:
            await _run(ls.accessory.aid, lambda: ls.accessory.panel_set(**changes))
        except Exception as exc:  # BLE timeout etc.: state is updated, the light didn't answer
            logger.warning("Panel command for %r failed: %s", ls.name, exc)
            return web.json_response({"error": f"light didn't respond: {exc}", "light": light_dict(ls)}, status=502)
        return web.json_response(light_dict(ls))

    async def post_switch(request):
        hit = _find([(s.aid, s) for s in _switches(state)], request.match_info["id"])
        if hit is None:
            return web.json_response({"error": "no such switch"}, status=404)
        sw = hit[1]
        try:
            body = await request.json()
            on = body["on"]
            if not isinstance(on, bool):
                raise ValueError
        except Exception:
            return web.json_response({"error": "send {\"on\": true|false}"}, status=400)

        def apply():
            if bool(sw.char_on.value) != on:
                sw.char_on.set_value(on)
                sw._set_on(on)

        await _run(sw.aid, apply)
        return web.json_response(switch_dict(sw))

    async def post_panel(request):
        try:
            body = await request.json()
        except Exception:
            return web.json_response({"error": "expected JSON"}, status=400)
        if state.panel_motion is not None and isinstance(body.get("motion"), bool):
            state.panel_motion.set_motion(body["motion"])
        lux = body.get("lux")
        if state.panel_lux is not None and isinstance(lux, (int, float)) and not isinstance(lux, bool):
            state.panel_lux.set_lux(float(lux))
        return web.json_response({"panel_sensors": state.panel_motion is not None or state.panel_lux is not None})

    app.router.add_get("/api/lights", get_lights)
    app.router.add_post("/api/lights/{id}", post_light)
    app.router.add_post("/api/switches/{id}", post_switch)
    app.router.add_post("/api/panel", post_panel)
