"""nexus-lights: one CLI to replace three manufacturer apps.

    nexus-lights scan                          # find nearby BLE strips
    nexus-lights add AA:BB:CC:DD:EE:FF "Desk" --protocol triones
    nexus-lights list
    nexus-lights on "Desk"
    nexus-lights off "Desk"
    nexus-lights color "Desk" 255 0 128
    nexus-lights brightness "Desk" 60
    nexus-lights all-on / all-off
    nexus-lights raw AA:BB:CC:DD:EE:FF triones 56ff0000000ff0aa   # protocol tuning
    nexus-lights bridge                        # expose lights to Apple HomeKit
    nexus-lights ambilight                     # match lights to a camera feed of the TV
    nexus-lights pair-appletv                  # pair an Apple TV for music art sync
    nexus-lights music-art-sync                # match lights to Apple Music album art
    nexus-lights pair-meross                   # add a Meross cloud light (already in Apple Home) to the mix
"""

from __future__ import annotations

import asyncio
import json

import click

from .ble_transport import BleLight, scan as ble_scan
from .manager import LightManager
from .protocols import REGISTRY


def _run(coro):
    return asyncio.run(coro)


@click.group()
def main() -> None:
    """Control BLE RGB light strips from one place, and bridge them to HomeKit."""


@main.command()
@click.option("--timeout", default=8.0, help="Scan duration in seconds.")
def scan(timeout: float) -> None:
    """Scan for nearby BLE devices and guess which are RGB strips."""
    devices = _run(ble_scan(timeout=timeout))
    if not devices:
        click.echo("No BLE devices found. Make sure the strip is powered on and in range.")
        return
    for d in devices:
        guess = d.guessed_protocol or "unknown (use --protocol when adding, or try both)"
        click.echo(f"{d.address}  rssi={d.rssi:<5}  name={d.name or '?':<20}  protocol_guess={guess}")


@main.command()
@click.argument("address")
@click.argument("name")
@click.option("--protocol", required=True, type=click.Choice(sorted(REGISTRY)))
def add(address: str, name: str, protocol: str) -> None:
    """Save a light to the config so it can be controlled by NAME later."""
    manager = LightManager()
    manager.add_light(address, name, protocol)
    click.echo(f"Added {name!r} ({address}, {protocol}).")


@main.command(name="list")
def list_lights() -> None:
    """List configured lights."""
    manager = LightManager()
    if not manager.config.lights:
        click.echo("No lights configured yet. Run 'nexus-lights scan' then 'nexus-lights add'.")
        return
    for light in manager.config.lights:
        click.echo(f"{light.name:<20} {light.address}  ({light.protocol})")


@main.command()
@click.argument("name")
def on(name: str) -> None:
    """Turn a light on."""
    _run(LightManager().power_on(name))


@main.command()
@click.argument("name")
def off(name: str) -> None:
    """Turn a light off."""
    _run(LightManager().power_off(name))


@main.command()
def all_on() -> None:
    """Turn every configured light on."""
    _run(LightManager().all_on())


@main.command()
def all_off() -> None:
    """Turn every configured light off."""
    _run(LightManager().all_off())


@main.command()
@click.argument("name")
@click.argument("r", type=click.IntRange(0, 255))
@click.argument("g", type=click.IntRange(0, 255))
@click.argument("b", type=click.IntRange(0, 255))
def color(name: str, r: int, g: int, b: int) -> None:
    """Set a light's RGB color, e.g. 'nexus-lights color Desk 255 0 128'."""
    _run(LightManager().set_rgb(name, r, g, b))


@main.command()
@click.argument("name")
@click.argument("percent", type=click.IntRange(0, 100))
def brightness(name: str, percent: int) -> None:
    """Set a light's brightness (0-100), if its protocol supports it."""
    _run(LightManager().set_brightness(name, percent))


@main.command()
@click.argument("address")
@click.argument("protocol", type=click.Choice(sorted(REGISTRY)))
@click.argument("hex_payload")
def raw(address: str, protocol: str, hex_payload: str) -> None:
    """Send raw hex bytes to a light. For tuning a protocol against real
    hardware when the shipped byte layout doesn't quite match your strip."""
    payload = bytes.fromhex(hex_payload.replace(" ", ""))

    async def _send() -> None:
        light = BleLight(address, protocol)
        async with light:
            await light.send_raw(payload)

    _run(_send())
    click.echo(f"Sent {payload.hex()} to {address}.")


@main.command()
@click.option("--camera", default=0, help="OpenCV camera index (0 is usually the built-in webcam).")
@click.option("--fps", default=5.0, help="Target updates per second.")
@click.option("--saturation-boost", default=1.6, help="Multiplier applied to the averaged frame's saturation.")
def ambilight(camera: int, fps: float, saturation_boost: float) -> None:
    """Match every configured light's color to a camera feed pointed at the TV."""
    try:
        from .ambilight import run as run_ambilight
    except ModuleNotFoundError as exc:
        raise click.ClickException(
            "opencv-python is required for ambilight mode. Install it with:\n"
            "    pip install opencv-python numpy"
        ) from exc

    _run(run_ambilight(camera_index=camera, fps=fps, saturation_boost=saturation_boost))


@main.command(name="pair-appletv")
@click.argument("identifier")
def pair_appletv(identifier: str) -> None:
    """Pair an Apple TV (by ID/MAC, as shown by 'atvremote scan') for music
    art sync. Pairs both Companion (now-playing metadata) and AirPlay
    (album artwork) -- tvOS 15+ doesn't expose artwork over Companion
    alone. Prompts for a PIN shown on the TV screen for each protocol."""
    try:
        import pyatv
        from pyatv.const import Protocol
    except ModuleNotFoundError as exc:
        raise click.ClickException(
            "pyatv is required for Apple TV pairing. Install it with:\n"
            "    pip install pyatv"
        ) from exc

    from .config import AppleTVConfig, load as load_config, save as save_config

    async def _pair_protocol(conf, protocol: Protocol, loop) -> str:
        handler = await pyatv.pair(conf, protocol, loop)
        try:
            await handler.begin()
            if handler.device_provides_pin:
                pin = click.prompt(f"Enter the PIN shown on the TV for {protocol.name}", type=str)
                handler.pin(pin)
            else:
                pin = click.prompt(f"Enter a 4-digit PIN to display for {protocol.name}", type=str)
                handler.pin(pin)
                click.echo("Enter that PIN on the Apple TV now.")
            await handler.finish()
            if not handler.has_paired:
                raise click.ClickException(f"{protocol.name} pairing failed.")
            return handler.service.credentials
        finally:
            await handler.close()

    async def _run_pairing() -> None:
        loop = asyncio.get_event_loop()
        found = await pyatv.scan(loop, identifier=identifier, timeout=5)
        if not found:
            raise click.ClickException(f"No Apple TV found with identifier {identifier!r}.")
        conf = found[0]
        click.echo(f"Found {conf.name} at {conf.address}.")

        companion_creds = await _pair_protocol(conf, Protocol.Companion, loop)
        click.echo("Companion paired.")
        airplay_creds = await _pair_protocol(conf, Protocol.AirPlay, loop)
        click.echo("AirPlay paired.")

        config = load_config()
        config.apple_tv = AppleTVConfig(
            identifier=identifier,
            companion_credentials=companion_creds,
            airplay_credentials=airplay_creds,
        )
        save_config(config)
        click.echo(f"Saved. {conf.name} is ready for 'nexus-lights music-art-sync'.")

    _run(_run_pairing())


@main.command(name="music-art-sync")
@click.option("--poll-interval", default=0.75, help="Seconds between now-playing checks.")
@click.option("--saturation-boost", default=1.8, help="Multiplier applied to each album art's averaged saturation.")
def music_art_sync(poll_interval: float, saturation_boost: float) -> None:
    """Match every configured light's color to the currently-playing Apple
    Music track's album art on the paired Apple TV."""
    try:
        from .music_art_sync import run as run_music_art_sync
    except ModuleNotFoundError as exc:
        raise click.ClickException(
            "pyatv, opencv-python and numpy are required for music art sync. Install them with:\n"
            "    pip install pyatv opencv-python numpy"
        ) from exc

    _run(run_music_art_sync(poll_interval=poll_interval, saturation_boost=saturation_boost))


@main.command(name="pair-meross")
def pair_meross() -> None:
    """Save Meross account credentials and find its MSL430 light, so
    ambilight/music-art-sync can drive its color too -- alongside, not
    instead of, its existing Apple Home pairing (see meross_light.py for
    why a direct second HomeKit pairing isn't possible here)."""
    try:
        from meross_iot.http_api import MerossHttpClient
        from meross_iot.manager import MerossManager
    except ModuleNotFoundError as exc:
        raise click.ClickException(
            "meross-iot is required for Meross support. Install it with:\n"
            "    pip install meross-iot"
        ) from exc

    from .meross_light import CREDENTIALS_PATH

    email = click.prompt("Meross account email")
    password = click.prompt("Meross account password", hide_input=True)

    async def _discover():
        http_client = await MerossHttpClient.async_from_user_password(
            api_base_url="https://iot.meross.com", email=email, password=password
        )
        manager = MerossManager(http_client=http_client)
        try:
            await manager.async_init()
            await manager.async_device_discovery()
            return manager.find_devices(device_type="msl430")
        finally:
            manager.close()
            await http_client.async_logout()

    devices = _run(_discover())
    if not devices:
        raise click.ClickException("No Meross MSL430 found on that account.")
    device = devices[0]

    CREDENTIALS_PATH.parent.mkdir(parents=True, exist_ok=True)
    CREDENTIALS_PATH.write_text(json.dumps({"email": email, "password": password, "device_uuid": device.uuid}))
    CREDENTIALS_PATH.chmod(0o600)
    click.echo(f"Saved. {device.name!r} ({device.uuid}) is ready for ambilight/music-art-sync.")


@main.command()
@click.option("--port", default=51826, help="HomeKit accessory server port.")
@click.option("--persist-file", default="nexuslights_homekit.state", help="HAP-python pairing state file.")
@click.option("--ambilight-camera", default=0, help="OpenCV camera index for the 'TV Ambilight' switch.")
@click.option("--ambilight-fps", default=5.0, help="Target updates per second for ambilight mode.")
@click.option(
    "--ambilight-saturation-boost", default=1.6, help="Saturation multiplier applied in ambilight mode."
)
@click.option(
    "--music-art-sync-poll-interval",
    default=2.0,
    help="Seconds between now-playing checks for the 'Music Art Sync' switch.",
)
@click.option(
    "--music-art-sync-saturation-boost",
    default=1.8,
    help="Saturation multiplier applied to album art colors.",
)
@click.option(
    "--dashboard-port",
    default=8080,
    type=int,
    help="Port for the local status dashboard (light connection state + signal strength). Set to 0 to disable it.",
)
@click.option(
    "--disconnect-after-write",
    is_flag=True,
    default=False,
    help=(
        "Disconnect each BLE light right after sending it a command, instead of holding a persistent "
        "connection. Slower per-command (a fresh connect each time), but never holds more than one BLE "
        "connection open at once -- for BLE chips that can't sustain many simultaneous links (e.g. a "
        "Raspberry Pi Zero W's onboard radio)."
    ),
)
@click.option(
    "--relay-url",
    default=None,
    help=(
        "Base URL of a 'nexus-lights relay-server' running on another host (e.g. http://raspberrypi.local:9090). "
        "At startup, any configured light whose signal is too weak from this machine gets routed through that "
        "host instead, over the network -- for lights that are out of this bridge's own Bluetooth range."
    ),
)
@click.option(
    "--relay-rssi-threshold",
    default=-75.0,
    type=float,
    help="Signal strength (dBm) below which a light is routed through --relay-url instead of controlled directly.",
)
@click.option(
    "--panel-sensors",
    is_flag=True,
    default=False,
    help="Add 'Wall Panel Motion' and 'Wall Panel Light Level' sensors to Apple Home, fed by the wall-panel tablet.",
)
@click.option(
    "--no-panel-api",
    is_flag=True,
    default=False,
    help="Turn off the wall-panel control API (/api on the dashboard port).",
)
def bridge(
    port: int,
    persist_file: str,
    ambilight_camera: int,
    ambilight_fps: float,
    ambilight_saturation_boost: float,
    music_art_sync_poll_interval: float,
    music_art_sync_saturation_boost: float,
    dashboard_port: int,
    disconnect_after_write: bool,
    relay_url: str | None,
    relay_rssi_threshold: float,
    panel_sensors: bool,
    no_panel_api: bool,
) -> None:
    """Start a HomeKit bridge exposing every configured light (plus a 'TV
    Ambilight' switch, and a 'Music Art Sync' switch if an Apple TV is
    paired) to Apple Home."""
    try:
        from .homekit_bridge import run_bridge
    except ModuleNotFoundError as exc:
        raise click.ClickException(
            "HAP-python is required for the HomeKit bridge. Install it with:\n"
            "    pip install HAP-python"
        ) from exc

    run_bridge(
        port=port,
        persist_file=persist_file,
        ambilight_camera=ambilight_camera,
        ambilight_fps=ambilight_fps,
        ambilight_saturation_boost=ambilight_saturation_boost,
        music_art_sync_poll_interval=music_art_sync_poll_interval,
        music_art_sync_saturation_boost=music_art_sync_saturation_boost,
        dashboard_port=dashboard_port or None,
        disconnect_after_write=disconnect_after_write,
        relay_url=relay_url,
        relay_rssi_threshold=relay_rssi_threshold,
        panel_api_enabled=not no_panel_api,
        panel_sensors=panel_sensors,
    )


@main.command(name="relay-server")
@click.option("--port", default=9090, help="Port to listen on for relayed light commands.")
def relay_server(port: int) -> None:
    """Run a lightweight network relay for this machine's configured
    lights, so a HomeKit bridge running on a different host (whose own
    Bluetooth can't reach these lights well) can control them over the
    network instead. Point that bridge's 'nexus-lights bridge --relay-url'
    at this host."""
    try:
        from .relay_server import run as run_relay_server
    except ModuleNotFoundError as exc:
        raise click.ClickException(
            "aiohttp is required for the relay server. Install it with:\n"
            "    pip install aiohttp"
        ) from exc

    _run(run_relay_server(port=port))


@main.command(name="watchdog-check")
@click.option("--dashboard-url", default="http://localhost:8080", help="Base URL of the bridge's status dashboard.")
@click.option(
    "--launchd-label",
    default="gui/501/com.nexuslights.bridge",
    help="launchctl target to restart if the bridge stays unhealthy (see 'launchctl print gui/<uid>' for the exact label).",
)
@click.option(
    "--unhealthy-fraction",
    default=0.5,
    type=float,
    help="Fraction of BLE lights that must be disconnected at once to count as unhealthy.",
)
@click.option(
    "--restart-after",
    default=300.0,
    type=float,
    help="Seconds the bridge must stay unhealthy before this restarts it (gives the in-process reconnect watchdog time to fix it first).",
)
def watchdog_check(dashboard_url: str, launchd_label: str, unhealthy_fraction: float, restart_after: float) -> None:
    """Run one health check against a running bridge's dashboard, restarting
    it via launchctl if too many lights have stayed disconnected for too
    long. Meant to be invoked periodically (e.g. every couple of minutes)
    by its own LaunchAgent with StartInterval -- not run continuously
    itself, and not run on the same schedule as the bridge's own
    in-process reconnect attempts, which get first chance to fix things."""
    from .health_watchdog import check

    result = check(
        dashboard_url=dashboard_url,
        launchd_label=launchd_label,
        unhealthy_fraction=unhealthy_fraction,
        restart_after_seconds=restart_after,
    )
    click.echo(result)


if __name__ == "__main__":
    main()
