"""Ambilight-style mode: sample a camera feed pointed at the TV (or its
HDMI signal, if you have a capture device that shows up as a webcam --
this only cares that OpenCV can open it as a VideoCapture index) and push
the dominant color to every configured light in real time.

Plain frame averaging tends to look washed-out/grey compared to what
dedicated Ambilight products do, since a mix of many hues cancels out
saturation. Boosting saturation on the averaged color after the fact
gets a much closer "vibrant dominant color" feel without the cost of a
real dominant-color algorithm (k-means, histograms, etc.) -- good enough
for ambient lighting, where nobody is looking for exact reproduction.

``loop()`` is the reusable core: it takes already-connected lights and
runs until cancelled, so both the standalone CLI command and the
HomeKit bridge's "TV Ambilight" switch can drive it without fighting
over BLE connections (bleak/BLE only allows one connection per light,
so whichever process holds the connection has to be the one running
the loop).
"""

from __future__ import annotations

import asyncio
import colorsys
import os
import time

from .ble_transport import BleLight
from .config import load as load_config
from .meross_light import load_configured as load_meross_light


def _require_cv2():
    # On macOS, AVFoundation's camera-authorization prompt has to run on
    # the process's main thread. The HomeKit bridge runs this loop on its
    # own dedicated BLE thread (so it doesn't block HAP-python's event
    # loop), where that prompt can't be shown -- it just fails outright.
    # Skipping the runtime re-request is safe as long as the OS has
    # already recorded a permission decision for this app (e.g. from
    # running `nexus-lights ambilight` standalone once, which runs on the
    # main thread and can show the real prompt).
    os.environ.setdefault("OPENCV_AVFOUNDATION_SKIP_AUTH", "1")
    try:
        import cv2
    except ModuleNotFoundError as exc:
        raise RuntimeError(
            "opencv-python is required for ambilight mode. Install it with:\n"
            "    pip install opencv-python numpy"
        ) from exc
    return cv2


def _average_color(frame, saturation_boost: float) -> tuple[int, int, int]:
    # Downscaling with INTER_AREA is a fast, good-enough way to get a
    # frame's average color (each output pixel is a weighted average of
    # the region it came from).
    cv2 = _require_cv2()
    small = cv2.resize(frame, (1, 1), interpolation=cv2.INTER_AREA)
    b, g, r = (int(c) for c in small[0, 0])

    h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    s = min(1.0, s * saturation_boost)
    r, g, b = colorsys.hsv_to_rgb(h, s, v)
    return round(r * 255), round(g * 255), round(b * 255)


async def _push(light, r: int, g: int, b: int) -> None:
    try:
        if not light.is_connected:
            # Covers both a light that never connected at startup and one
            # whose connection dropped mid-run -- set_rgb()'s own write
            # path only retries the latter case.
            await light.connect()
        await light.set_rgb(r, g, b)
    except Exception:
        pass  # one light dropping shouldn't stop the show for the others


async def loop(
    lights: list,
    camera_index: int = 0,
    fps: float = 5.0,
    saturation_boost: float = 1.6,
) -> None:
    """Sample the camera and push colors to ``lights`` until cancelled."""
    cv2 = _require_cv2()
    capture = cv2.VideoCapture(camera_index)
    if not capture.isOpened():
        raise RuntimeError(
            f"Could not open camera index {camera_index}. Try a different --camera index "
            "(0 is usually the built-in webcam; Continuity Camera and other devices take "
            "higher indices, depending on what's plugged in / paired)."
        )

    interval = 1.0 / fps
    try:
        while True:
            loop_start = time.monotonic()
            ok, frame = capture.read()
            if ok:
                r, g, b = _average_color(frame, saturation_boost)
                await asyncio.gather(*(_push(light, r, g, b) for light in lights))
            elapsed = time.monotonic() - loop_start
            await asyncio.sleep(max(0.0, interval - elapsed))
    finally:
        capture.release()


async def run(
    camera_index: int = 0,
    fps: float = 5.0,
    saturation_boost: float = 1.6,
) -> None:
    """Standalone entry point: connects its own lights, then runs ``loop()``."""
    config = load_config()
    if not config.lights:
        raise RuntimeError("No lights configured. Run 'nexus-lights scan' and 'nexus-lights add' first.")

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

    print("Running -- Ctrl+C to stop.")
    try:
        await loop(lights, camera_index=camera_index, fps=fps, saturation_boost=saturation_boost)
    finally:
        for light in lights:
            await light.disconnect()
