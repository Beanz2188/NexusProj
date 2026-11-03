"""On-disk config: the list of lights you've told nexuslights about.

Automatic BLE name matching gets you 80% of the way, but it's a guess —
this file is the source of truth once you've confirmed a device works,
and the only way to set a friendly name / room for HomeKit.

Format (YAML):

    lights:
      - address: AA:BB:CC:DD:EE:FF
        name: Desk Strip
        protocol: triones
      - address: 11:22:33:44:55:66
        name: Bed Strip
        protocol: elkbledom
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from pathlib import Path

DEFAULT_CONFIG_PATH = Path.home() / ".config" / "nexuslights" / "lights.yaml"


@dataclass
class LightConfig:
    address: str
    name: str
    protocol: str
    #: Connect only to send a command, then disconnect, instead of holding
    #: a persistent BLE link. For "WiFi-first" controllers (e.g. Zengge
    #: "IOTWF" / lednetwf-iotwf) that treat BLE as a short-lived setup
    #: channel and drop idle connections -- the bridge reconnecting such a
    #: light every watchdog cycle makes it visibly flash (confirmed on real
    #: hardware: the kitchen light blinked continuously until set on-demand).
    on_demand: bool = False


@dataclass
class AppleTVConfig:
    identifier: str
    companion_credentials: str
    airplay_credentials: str


@dataclass
class Config:
    lights: list[LightConfig] = field(default_factory=list)
    apple_tv: AppleTVConfig | None = None

    def get(self, address: str) -> LightConfig | None:
        address = address.lower()
        for light in self.lights:
            if light.address.lower() == address:
                return light
        return None

    def upsert(self, light: LightConfig) -> None:
        existing = self.get(light.address)
        if existing is not None:
            self.lights.remove(existing)
        self.lights.append(light)


def load(path: Path = DEFAULT_CONFIG_PATH) -> Config:
    if not path.exists():
        return Config()
    try:
        import yaml

        raw = yaml.safe_load(path.read_text()) or {}
    except ModuleNotFoundError:
        raw = json.loads(path.read_text() or "{}")
    lights = [LightConfig(**entry) for entry in raw.get("lights", [])]
    apple_tv_raw = raw.get("apple_tv")
    apple_tv = AppleTVConfig(**apple_tv_raw) if apple_tv_raw else None
    return Config(lights=lights, apple_tv=apple_tv)


def save(config: Config, path: Path = DEFAULT_CONFIG_PATH) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    raw = {"lights": [asdict(light) for light in config.lights]}
    if config.apple_tv is not None:
        raw["apple_tv"] = asdict(config.apple_tv)
    try:
        import yaml

        path.write_text(yaml.safe_dump(raw, sort_keys=False))
    except ModuleNotFoundError:
        path.write_text(json.dumps(raw, indent=2))
