"""Network relay for lights that a HomeKit bridge on a different host
can't reach directly over Bluetooth.

Exposes a small HTTP API (aiohttp) over whichever lights *this* host can
reach via BLE, using the exact same LightManager/config every other entry
point uses. A bridge running elsewhere (e.g. a Mac whose Bluetooth range
doesn't cover every room) can point specific lights at this instead of
talking to them directly over its own Bluetooth -- see remote_light.py for
the client side that does that substitution.
"""

from __future__ import annotations

import asyncio

from .manager import LightManager


def _require_aiohttp():
    try:
        from aiohttp import web
    except ModuleNotFoundError as exc:
        raise RuntimeError(
            "aiohttp is required for the relay server. Install it with:\n"
            "    pip install aiohttp"
        ) from exc
    return web


def _build_app(manager: LightManager):
    web = _require_aiohttp()

    async def status_handler(request):
        name = request.match_info["name"]
        try:
            light = manager._resolve(name)
        except KeyError:
            return web.json_response({"error": "unknown light"}, status=404)
        return web.json_response({"connected": light.is_connected})

    async def power_on_handler(request):
        name = request.match_info["name"]
        try:
            await manager.power_on(name)
        except KeyError:
            return web.json_response({"error": "unknown light"}, status=404)
        except Exception as exc:
            return web.json_response({"error": str(exc)}, status=502)
        return web.json_response({"ok": True})

    async def power_off_handler(request):
        name = request.match_info["name"]
        try:
            await manager.power_off(name)
        except KeyError:
            return web.json_response({"error": "unknown light"}, status=404)
        except Exception as exc:
            return web.json_response({"error": str(exc)}, status=502)
        return web.json_response({"ok": True})

    async def rgb_handler(request):
        name = request.match_info["name"]
        body = await request.json()
        try:
            await manager.set_rgb(name, int(body["r"]), int(body["g"]), int(body["b"]))
        except KeyError as exc:
            return web.json_response({"error": f"unknown light or missing field {exc}"}, status=404)
        except Exception as exc:
            return web.json_response({"error": str(exc)}, status=502)
        return web.json_response({"ok": True})

    async def brightness_handler(request):
        name = request.match_info["name"]
        body = await request.json()
        try:
            await manager.set_brightness(name, int(body["percent"]))
        except KeyError as exc:
            return web.json_response({"error": f"unknown light or missing field {exc}"}, status=404)
        except Exception as exc:
            return web.json_response({"error": str(exc)}, status=502)
        return web.json_response({"ok": True})

    app = web.Application()
    app.router.add_get("/lights/{name}/status", status_handler)
    app.router.add_post("/lights/{name}/power_on", power_on_handler)
    app.router.add_post("/lights/{name}/power_off", power_off_handler)
    app.router.add_post("/lights/{name}/rgb", rgb_handler)
    app.router.add_post("/lights/{name}/brightness", brightness_handler)
    return app


async def run(port: int = 9090) -> None:
    web = _require_aiohttp()
    manager = LightManager()
    app = _build_app(manager)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", port)
    await site.start()
    print(f"Relay server listening on 0.0.0.0:{port}, lights: {', '.join(manager.light_names())}")
    await asyncio.Event().wait()
