"""Lightweight local status dashboard for the HomeKit bridge process.

Runs a small HTTP server (aiohttp) on its own port, showing every
configured light's connection state, plus whether the Ambilight/Music Art
Sync switches are currently running -- a browser view of what the
bridge's logs already say, without needing to tail them.

Live signal strength was tried and dropped: a background
BleakScanner.discover() can't see it (already-connected BLE peripherals
generally stop advertising -- confirmed on real hardware, a scan run
alongside the bridge's persistent connections matched zero of six known
lights despite finding 30 other nearby devices), and reading it straight
off the connection isn't possible either -- bleak's get_rssi() only
exists on its private per-platform backend class, not the public
BleakClient wrapper this project holds a reference to.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field


@dataclass
class LightStatus:
    name: str
    protocol: str
    address: str
    is_ble: bool
    light: object  # BleLight | MerossLight, duck-typed on .is_connected


@dataclass
class DashboardState:
    lights: list[LightStatus] = field(default_factory=list)
    ambilight_switch: object | None = None
    music_art_sync_switch: object | None = None
    rainbow_switch: object | None = None
    started_at: float = field(default_factory=time.monotonic)


def _require_aiohttp():
    try:
        from aiohttp import web
    except ModuleNotFoundError as exc:
        raise RuntimeError(
            "aiohttp is required for the status dashboard. Install it with:\n"
            "    pip install aiohttp"
        ) from exc
    return web


def _switch_running(switch) -> bool | None:
    if switch is None:
        return None
    task = getattr(switch, "_task", None)
    return task is not None and not task.done()


def _status_dict(state: DashboardState) -> dict:
    lights = [
        {
            "name": ls.name,
            "protocol": ls.protocol,
            "connected": bool(ls.light.is_connected),
        }
        for ls in state.lights
    ]
    return {
        "uptime_seconds": round(time.monotonic() - state.started_at, 1),
        "lights": lights,
        "ambilight_running": _switch_running(state.ambilight_switch),
        "music_art_sync_running": _switch_running(state.music_art_sync_switch),
        "rainbow_running": _switch_running(state.rainbow_switch),
    }


_INDEX_HTML = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>NexusLights</title>
<style>
  body { font-family: -apple-system, sans-serif; background: #111; color: #eee; padding: 2rem; }
  h1 { font-weight: 600; }
  table { border-collapse: collapse; width: 100%; max-width: 480px; }
  th, td { text-align: left; padding: 0.5rem 1rem; border-bottom: 1px solid #333; }
  .ok { color: #4caf50; }
  .bad { color: #f44336; }
  .muted { color: #888; }
  .switches { margin-top: 1.5rem; }
  #uptime { color: #888; font-size: 0.9em; }
</style>
</head>
<body>
<h1>NexusLights</h1>
<div id="uptime">loading...</div>
<table>
  <thead><tr><th>Light</th><th>Protocol</th><th>Status</th></tr></thead>
  <tbody id="lights"></tbody>
</table>
<div class="switches" id="switches"></div>
<script>
function fmtUptime(s) {
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
  return `up ${h}h ${m}m`;
}
async function refresh() {
  const res = await fetch('/status');
  const data = await res.json();
  document.getElementById('uptime').textContent = fmtUptime(data.uptime_seconds);
  const rows = data.lights.map(l => `<tr>
    <td>${l.name}</td>
    <td class="muted">${l.protocol}</td>
    <td class="${l.connected ? 'ok' : 'bad'}">${l.connected ? 'connected' : 'disconnected'}</td>
  </tr>`).join('');
  document.getElementById('lights').innerHTML = rows;
  const sw = [];
  if (data.ambilight_running !== null) {
    sw.push(`Ambilight: <span class="${data.ambilight_running ? 'ok' : 'muted'}">${data.ambilight_running ? 'running' : 'off'}</span>`);
  }
  if (data.music_art_sync_running !== null) {
    sw.push(`Music Art Sync: <span class="${data.music_art_sync_running ? 'ok' : 'muted'}">${data.music_art_sync_running ? 'running' : 'off'}</span>`);
  }
  if (data.rainbow_running !== null) {
    sw.push(`Rainbow: <span class="${data.rainbow_running ? 'ok' : 'muted'}">${data.rainbow_running ? 'running' : 'off'}</span>`);
  }
  document.getElementById('switches').innerHTML = sw.join('<br>');
}
refresh();
setInterval(refresh, 3000);
</script>
</body>
</html>
"""


def _build_app(state: DashboardState):
    web = _require_aiohttp()

    async def index_handler(request):
        return web.Response(text=_INDEX_HTML, content_type="text/html")

    async def status_handler(request):
        return web.json_response(_status_dict(state))

    app = web.Application()
    app.router.add_get("/", index_handler)
    app.router.add_get("/status", status_handler)
    return app


async def start(state: DashboardState, port: int = 8080) -> None:
    """Starts the dashboard's HTTP server. Returns once it's listening --
    meant to be fired off as a background task alongside the bridge."""
    web = _require_aiohttp()
    app = _build_app(state)
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", port)
    await site.start()
