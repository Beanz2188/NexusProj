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


@dataclass
class Config:
    lights: list[LightConfig] = field(default_factory=list)

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
    return Config(lights=lights)


def save(config: Config, path: Path = DEFAULT_CONFIG_PATH) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    raw = {"lights": [asdict(light) for light in config.lights]}
    try:
        import yaml

        path.write_text(yaml.safe_dump(raw, sort_keys=False))
    except ModuleNotFoundError:
        path.write_text(json.dumps(raw, indent=2))
