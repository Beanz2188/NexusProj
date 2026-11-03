"""Rainbow mode: cycles every light through the full hue spectrum in sync,
forever, until cancelled. Unlike ambilight.py (driven by a camera feed) or
music_art_sync.py (driven by now-playing metadata), this has no external
input -- it's just a continuous color cycle for a party/ambient effect.
"""

from __future__ import annotations

import asyncio
import colorsys
import logging

logger = logging.getLogger(__name__)


async def _push(light, r: int, g: int, b: int) -> None:
    try:
        if not light.is_connected:
            await light.connect()
        await light.set_rgb(r, g, b)
    except Exception as exc:
        logger.warning("Rainbow: failed to push color to %s: %s", getattr(light, "address", light), exc)


async def loop(
    lights: list,
    step_degrees: float = 3.0,
    interval: float = 0.03,
    saturation: float = 1.0,
) -> None:
    """Every light shows the same color at any given moment, sweeping
    together through the hue wheel -- not each light offset to a
    different point in the cycle. step_degrees controls how gradual each
    transition looks (smaller = smoother fade, larger = more visible
    jumps between colors) -- since every step is still a real BLE write,
    smaller steps mean more of them per full cycle, i.e. a somewhat
    longer cycle, not just a smoother-looking one. interval sets the
    nominal pause between steps, but actual speed is capped by how fast
    every light's BLE write completes each round, since the
    loop waits for that gather() before sleeping, so it won't ever go
    faster than the slowest light can actually respond)."""
    hue = 0.0
    while True:
        r, g, b = colorsys.hsv_to_rgb(hue / 360.0, saturation, 1.0)
        rgb = (round(r * 255), round(g * 255), round(b * 255))
        await asyncio.gather(*(_push(light, *rgb) for light in lights))
        hue = (hue + step_degrees) % 360.0
        await asyncio.sleep(interval)
