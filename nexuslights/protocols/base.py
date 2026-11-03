"""Protocol interface for BLE RGB light controllers.

Each concrete protocol only knows how to turn a command into raw bytes
(and which BLE characteristic to write them to) — it never talks to
Bluetooth itself. That's handled by ``nexuslights.ble_transport.BleLight``,
which is the only place ``bleak`` gets imported. This split lets the
byte-packing logic be unit-tested on any machine, with no Bluetooth
radio or ``bleak`` install required.
"""

from __future__ import annotations

from abc import ABC, abstractmethod


class LightProtocol(ABC):
    """Encodes light commands as raw BLE GATT writes for one device family."""

    #: Human-readable protocol identifier, used in config files and the CLI.
    name: str = "base"

    #: BLE GATT characteristic (UUID string) that commands are written to.
    write_characteristic: str

    #: Optional notify characteristic that must be subscribed to before the
    #: device will accept writes (some chipsets otherwise silently ignore
    #: every command). Most protocols don't need this.
    notify_characteristic: str | None = None

    #: Whether writes should be sent as a BLE "Write Request" (waits for a
    #: response) rather than the default "Write Command" (fire-and-forget).
    write_with_response: bool = False

    #: Whether this chipset's hardware power_off command cuts its BLE
    #: radio entirely rather than just switching the LEDs off, making it
    #: unreachable until physically power-cycled. Callers should prefer
    #: dimming to the dimmest supported brightness over calling
    #: power_off() for protocols where this is True.
    power_off_disconnects: bool = False

    #: Whether set_rgb()'s output already scales with brightness (most
    #: protocols bake brightness into the RGB values themselves), so a
    #: caller pushing hue/saturation/brightness together only needs to
    #: call set_rgb(). False means color and brightness are independent
    #: device state -- set_rgb() alone won't change brightness, so
    #: callers need to also call set_brightness() to keep them in sync.
    #: (Not the same as whether set_brightness() is *implemented* --
    #: e.g. elkbledom implements it for a separate white/CW channel, not
    #: as an alternate way to scale the RGB color.)
    color_command_includes_brightness: bool = True

    #: Substrings of BLE advertised device names known to use this protocol.
    #: Used by discovery to suggest a protocol; not authoritative.
    name_hints: tuple[str, ...] = ()

    @abstractmethod
    def power_on(self) -> bytes:
        ...

    @abstractmethod
    def power_off(self) -> bytes:
        ...

    @abstractmethod
    def set_rgb(self, r: int, g: int, b: int) -> bytes:
        ...

    def set_brightness(self, percent: int) -> bytes:
        """0-100. Optional: not every protocol supports this separately
        from RGB (some fold brightness into the color write instead)."""
        raise NotImplementedError(f"{self.name} has no separate brightness command")

    def set_white(self, percent: int) -> bytes:
        """0-100, for strips with a dedicated white channel."""
        raise NotImplementedError(f"{self.name} has no white channel command")

    def set_effect(self, effect_id: int, speed: int = 50) -> bytes:
        raise NotImplementedError(f"{self.name} has no effect command")

    @staticmethod
    def _clamp(value: int, lo: int = 0, hi: int = 255) -> int:
        return max(lo, min(hi, value))
