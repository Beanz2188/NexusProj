"""External health check for the HomeKit bridge.

Deliberately a separate, short-lived process invoked periodically (via
its own LaunchAgent using StartInterval, not run continuously inside the
bridge) rather than a background task in homekit_bridge.py: the in-process
reconnect watchdog there already retries individual disconnected lights,
but if the bridge's own event loop ever truly wedges, nothing running
inside that same process could reliably notice or act on it. This one
only reads the dashboard's read-only /status endpoint from the outside
and, if the bridge stays unhealthy for too long, restarts it via
launchctl -- the one action that works even if the bridge process itself
is completely stuck.
"""

from __future__ import annotations

import json
import subprocess
import time
import urllib.request
from pathlib import Path

DEFAULT_STATE_FILE = Path.home() / ".config" / "nexuslights" / "watchdog_state.json"


def _load_state(state_file: Path) -> dict:
    if not state_file.exists():
        return {}
    try:
        return json.loads(state_file.read_text())
    except Exception:
        return {}


def _save_state(state_file: Path, state: dict) -> None:
    state_file.parent.mkdir(parents=True, exist_ok=True)
    state_file.write_text(json.dumps(state))


def _escalate(state_file: Path, state: dict, now: float, restart_after_seconds: float, launchd_label: str, reason: str) -> str:
    unhealthy_since = state.get("unhealthy_since")
    if unhealthy_since is None:
        _save_state(state_file, {"unhealthy_since": now})
        return f"unhealthy ({reason}); starting grace period before considering a restart"

    elapsed = now - unhealthy_since
    if elapsed < restart_after_seconds:
        return f"unhealthy ({reason}) for {elapsed:.0f}s; waiting until {restart_after_seconds:.0f}s before restarting"

    subprocess.run(["launchctl", "kickstart", "-k", launchd_label], check=False)
    _save_state(state_file, {})
    return f"unhealthy ({reason}) for {elapsed:.0f}s; restarted the bridge via launchctl kickstart"


def check(
    dashboard_url: str = "http://localhost:8080",
    launchd_label: str = "gui/501/com.nexuslights.bridge",
    unhealthy_fraction: float = 0.5,
    restart_after_seconds: float = 300.0,
    state_file: Path = DEFAULT_STATE_FILE,
) -> str:
    """Runs a single check-and-maybe-restart cycle. Returns a short status
    string for the caller to log; doesn't loop or sleep itself -- that's
    the job of whatever invokes this periodically."""
    state = _load_state(state_file)
    now = time.time()

    try:
        with urllib.request.urlopen(f"{dashboard_url}/status", timeout=10) as resp:
            data = json.loads(resp.read())
    except Exception as exc:
        return _escalate(state_file, state, now, restart_after_seconds, launchd_label, f"dashboard unreachable: {exc}")

    # Meross is controlled over its own cloud API, not this host's
    # Bluetooth -- its connectivity says nothing about whether the
    # bridge's BLE side is healthy, so it's excluded from the fraction.
    ble_lights = [light for light in data.get("lights", []) if light.get("protocol") != "meross"]
    if not ble_lights:
        _save_state(state_file, {})
        return "no BLE lights configured; nothing to check"

    disconnected = sum(1 for light in ble_lights if not light.get("connected"))
    fraction = disconnected / len(ble_lights)

    if fraction <= unhealthy_fraction:
        if state.get("unhealthy_since") is not None:
            _save_state(state_file, {})
        return f"healthy: {disconnected}/{len(ble_lights)} lights disconnected"

    return _escalate(
        state_file, state, now, restart_after_seconds, launchd_label,
        f"{disconnected}/{len(ble_lights)} lights disconnected",
    )
