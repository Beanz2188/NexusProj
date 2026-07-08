from __future__ import annotations

from .base import LightProtocol
from .elkbledom import ElkBledomProtocol
from .triones import TrionesProtocol

#: All protocols the toolkit ships with, keyed by their ``name``.
REGISTRY: dict[str, type[LightProtocol]] = {
    TrionesProtocol.name: TrionesProtocol,
    ElkBledomProtocol.name: ElkBledomProtocol,
}


def get_protocol(name: str) -> LightProtocol:
    try:
        return REGISTRY[name]()
    except KeyError as exc:
        known = ", ".join(sorted(REGISTRY))
        raise ValueError(f"Unknown protocol {name!r}. Known protocols: {known}") from exc


def guess_protocol(device_name: str | None) -> str | None:
    """Best-effort guess of which protocol a discovered BLE device speaks,
    based on its advertised name. Returns None if nothing matches — the
    user will need to set the protocol explicitly in the config file.
    """
    if not device_name:
        return None
    lowered = device_name.lower()
    for proto_name, cls in REGISTRY.items():
        if any(hint in lowered for hint in cls.name_hints):
            return proto_name
    return None


__all__ = ["LightProtocol", "REGISTRY", "get_protocol", "guess_protocol"]
