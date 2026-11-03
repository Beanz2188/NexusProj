"""Music Art Sync mode: watch what's playing on an Apple TV (via Apple
Music) and push the dominant color of the current track's album art to
every configured light. Unlike ambilight.py, this doesn't need a camera
or microphone -- it reads now-playing metadata straight from tvOS over
the network, so it works even when the TV itself is off-screen.

Requires the Apple TV to have both its Companion protocol (for
metadata/now-playing) and AirPlay protocol (for artwork -- tvOS 15+
doesn't expose artwork over Companion alone) paired once via
`nexus-lights pair-appletv`, with credentials stored in config.py's
AppleTVConfig.

``loop()`` is the reusable core, same split as ambilight.py: it takes
already-connected lights and runs until cancelled, so both the
standalone CLI command and the HomeKit bridge's "Music Art Sync" switch
can drive it without fighting over BLE connections.
"""

from __future__ import annotations

import asyncio
import colorsys
import logging
import time

from .ble_transport import BleLight
from .config import AppleTVConfig, load as load_config
from .meross_light import load_configured as load_meross_light

logger = logging.getLogger(__name__)


def _require_pyatv():
    try:
        import pyatv
    except ModuleNotFoundError as exc:
        raise RuntimeError(
            "pyatv is required for music art sync mode. Install it with:\n"
            "    pip install pyatv"
        ) from exc
    return pyatv


def _require_cv2():
    try:
        import cv2
        import numpy as np
    except ModuleNotFoundError as exc:
        raise RuntimeError(
            "opencv-python and numpy are required for music art sync mode. Install them with:\n"
            "    pip install opencv-python numpy"
        ) from exc
    return cv2, np


def _dominant_color(jpeg_bytes: bytes, saturation_boost: float) -> tuple[int, int, int] | None:
    # Unlike ambilight.py's _average_color() (which is fine for a
    # constantly-changing camera feed), a single blended average washes
    # out busy album art into a muddy grey -- k-means clustering finds the
    # art's most prominent color instead: whichever cluster the most
    # pixels fall into, not a blend of all of them.
    cv2, np = _require_cv2()
    array = np.frombuffer(jpeg_bytes, dtype=np.uint8)
    frame = cv2.imdecode(array, cv2.IMREAD_COLOR)
    if frame is None:
        return None

    # Downscale before clustering -- k-means cost scales with pixel
    # count, and a few thousand samples is plenty to find the dominant
    # cluster without noticeable per-track delay.
    small = cv2.resize(frame, (50, 50), interpolation=cv2.INTER_AREA)
    pixels = small.reshape(-1, 3).astype(np.float32)

    k = 5
    criteria = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 20, 0.5)
    _, labels, centers = cv2.kmeans(pixels, k, None, criteria, 3, cv2.KMEANS_PP_CENTERS)
    counts = np.bincount(labels.flatten(), minlength=k)

    # Walk clusters most-prominent first and take the first one that's an
    # actual color rather than a shade of white/grey/black -- low
    # saturation is what all three of those have in common regardless of
    # how bright they are, so it's the only test needed to skip them.
    # Album art backgrounds are neutral often enough that skipping this
    # would make the sync just go white/grey a lot.
    _NEUTRAL_SATURATION = 0.2
    h = s = None
    for idx in np.argsort(-counts):
        b, g, r = (int(c) for c in centers[idx])
        cluster_h, cluster_s, _v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        if cluster_s >= _NEUTRAL_SATURATION:
            h, s = cluster_h, cluster_s
            break
    if h is None:
        # Every cluster was neutral (e.g. genuinely black-and-white art)
        # -- fall back to the most prominent one instead of giving up.
        b, g, r = (int(c) for c in centers[np.argmax(counts)])
        h, s, _v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)

    s = min(1.0, s * saturation_boost)
    # Always drive the light at full brightness regardless of how bright
    # the dominant color actually is in the art -- a dark dominant color
    # (e.g. a deep blue background) would otherwise make the sync look
    # like it's barely reacting.
    r, g, b = colorsys.hsv_to_rgb(h, s, 1.0)
    return round(r * 255), round(g * 255), round(b * 255)


async def _connect_atv(atv_config: AppleTVConfig):
    pyatv = _require_pyatv()
    from pyatv.const import Protocol

    loop = asyncio.get_event_loop()
    # This switch's "On" state is persisted by HAP-python and auto-restored
    # on bridge startup, which is also when the mDNS scan below is most
    # likely to get crowded out -- BLE connects and the Meross cloud login
    # are all competing for it at the same moment. A single 5s scan lost
    # that race in practice (confirmed the TV was reachable seconds later
    # with a fresh 10s scan), so give it more headroom and one retry
    # before giving up and resetting the switch back off.
    found = await pyatv.scan(loop, identifier=atv_config.identifier, timeout=10)
    if not found:
        await asyncio.sleep(2.0)
        found = await pyatv.scan(loop, identifier=atv_config.identifier, timeout=10)
    if not found:
        raise RuntimeError(
            f"Could not find Apple TV {atv_config.identifier!r} on the network "
            "(is it on the same Wi-Fi / powered on?)."
        )
    conf = found[0]
    conf.set_credentials(Protocol.Companion, atv_config.companion_credentials)
    conf.set_credentials(Protocol.AirPlay, atv_config.airplay_credentials)
    return await pyatv.connect(conf, loop)


async def _push(light, r: int, g: int, b: int) -> None:
    try:
        if not light.is_connected:
            await light.connect()
        await light.set_rgb(r, g, b)
    except Exception as exc:
        # One light dropping shouldn't stop the show for the others, but
        # silently swallowing every failure here meant "pushing color to
        # N light(s)" in the log couldn't be trusted -- it logged success
        # before any individual write was confirmed, so a BLE write that
        # failed for every light looked identical to one that worked.
        logger.warning("Music art sync: failed to push color to %s: %s", getattr(light, "address", light), exc)


async def loop(
    lights: list,
    atv_config: AppleTVConfig,
    poll_interval: float = 0.75,
    saturation_boost: float = 1.8,
) -> None:
    """Poll the Apple TV's now-playing state and push each new track's
    album-art color to ``lights`` until cancelled."""
    pyatv_exceptions = _require_pyatv().exceptions
    atv = await _connect_atv(atv_config)
    last_identifier: object = None
    try:
        while True:
            loop_start = time.monotonic()
            try:
                playing = await atv.metadata.playing()
                # Deliberately not playing.hash: confirmed against real
                # hardware that it doesn't change between tracks in the
                # same continuous stream (it appears tied to the
                # underlying AirPlay/RAOP session, not the track), while
                # title/artist/album do update correctly each poll --
                # using hash meant a new song's colors only ever showed up
                # after manually re-toggling the switch, never on its own.
                identifier = (
                    (playing.title, playing.artist, playing.album)
                    if playing.device_state.name == "Playing"
                    else None
                )
                if identifier is not None and identifier != last_identifier:
                    logger.info("Music art sync: track changed to %r, fetching artwork", identifier)
                    # Mark this track as handled *before* fetching artwork,
                    # not after: a track whose artwork fetch hangs or fails
                    # would otherwise get retried every single poll (as
                    # often as every 0.75s) instead of just being skipped
                    # once, and a stuck fetch previously had no way to be
                    # distinguished from the switch just not working --
                    # confirmed on real hardware that a slow/failing
                    # artwork response for one track was silently eating
                    # every retry attempt until the switch was toggled.
                    last_identifier = identifier
                    try:
                        art = await asyncio.wait_for(atv.metadata.artwork(), timeout=6.0)
                    except asyncio.TimeoutError:
                        logger.warning("Music art sync: artwork fetch timed out for %r, skipping", identifier)
                        art = None
                    if art is not None:
                        color = _dominant_color(art.bytes, saturation_boost)
                        if color is not None:
                            r, g, b = color
                            logger.info("Music art sync: pushing color %s to %d light(s)", (r, g, b), len(lights))
                            await asyncio.gather(*(_push(light, r, g, b) for light in lights))
                        else:
                            logger.info("Music art sync: could not extract a color for %r", identifier)
                    else:
                        logger.info("Music art sync: no artwork returned for %r", identifier)
                elif identifier is None:
                    last_identifier = None
            except (pyatv_exceptions.ProtocolError, ConnectionError, OSError) as exc:
                # The TV went to sleep, dropped off Wi-Fi, or the
                # connection otherwise died mid-loop -- reconnect and keep
                # going rather than letting the whole switch die.
                logger.warning("Music art sync: lost connection to Apple TV (%s), reconnecting", exc)
                try:
                    atv.close()
                except Exception:
                    pass
                atv = await _connect_atv(atv_config)
                last_identifier = None

            elapsed = time.monotonic() - loop_start
            await asyncio.sleep(max(0.0, poll_interval - elapsed))
    finally:
        atv.close()


async def run(poll_interval: float = 0.75, saturation_boost: float = 1.8) -> None:
    """Standalone entry point: connects its own lights and Apple TV, then runs ``loop()``."""
    config = load_config()
    if not config.lights:
        raise RuntimeError("No lights configured. Run 'nexus-lights scan' and 'nexus-lights add' first.")
    if config.apple_tv is None:
        raise RuntimeError("No Apple TV paired. Run 'nexus-lights pair-appletv' first.")

    lights: list = [BleLight(light_cfg.address, light_cfg.protocol) for light_cfg in config.lights]
    names = [light_cfg.name for light_cfg in config.lights]
    meross_light = load_meross_light()
    if meross_light is not None:
        lights.append(meross_light)
        names.append("Meross MSL430")

    print(f"Connecting to {len(lights)} light(s)...")
    for light, name in zip(lights, names):
        try:
            await light.connect()
            print(f"  connected: {name}")
        except Exception as exc:
            print(f"  could not connect to {name!r} ({exc}); will keep retrying in the loop")

    print(f"Connecting to Apple TV {config.apple_tv.identifier}...")
    print("Running -- Ctrl+C to stop.")
    try:
        await loop(lights, config.apple_tv, poll_interval=poll_interval, saturation_boost=saturation_boost)
    finally:
        for light in lights:
            await light.disconnect()
