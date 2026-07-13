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
"""

from __future__ import annotations

import asyncio

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


@main.command()
@click.option("--port", default=51826, help="HomeKit accessory server port.")
@click.option("--persist-file", default="nexuslights_homekit.state", help="HAP-python pairing state file.")
@click.option("--ambilight-camera", default=0, help="OpenCV camera index for the 'TV Ambilight' switch.")
@click.option("--ambilight-fps", default=5.0, help="Target updates per second for ambilight mode.")
@click.option(
    "--ambilight-saturation-boost", default=1.6, help="Saturation multiplier applied in ambilight mode."
)
def bridge(
    port: int,
    persist_file: str,
    ambilight_camera: int,
    ambilight_fps: float,
    ambilight_saturation_boost: float,
) -> None:
    """Start a HomeKit bridge exposing every configured light (plus a 'TV
    Ambilight' switch) to Apple Home."""
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
    )


if __name__ == "__main__":
    main()
