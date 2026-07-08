from nexuslights.protocols import get_protocol, guess_protocol
from nexuslights.protocols.elkbledom import ElkBledomProtocol
from nexuslights.protocols.triones import TrionesProtocol


def test_triones_power_frames():
    proto = TrionesProtocol()
    assert proto.power_on() == bytes([0xCC, 0x23, 0x33])
    assert proto.power_off() == bytes([0xCC, 0x24, 0x33])


def test_triones_set_rgb_clamps_out_of_range():
    proto = TrionesProtocol()
    assert proto.set_rgb(300, -10, 128) == bytes([0x56, 255, 0, 128, 0x00, 0xF0, 0xAA])


def test_triones_set_white_scales_percent_to_byte():
    proto = TrionesProtocol()
    assert proto.set_white(100) == bytes([0x56, 0x00, 0x00, 0x00, 255, 0x0F, 0xAA])
    assert proto.set_white(0) == bytes([0x56, 0x00, 0x00, 0x00, 0, 0x0F, 0xAA])


def test_elkbledom_power_frames():
    proto = ElkBledomProtocol()
    assert proto.power_on() == bytes([0x7E, 0x00, 0x04, 0xF0, 0x00, 0x01, 0xFF, 0x00, 0xEF])
    assert proto.power_off() == bytes([0x7E, 0x00, 0x04, 0x00, 0x00, 0x00, 0xFF, 0x00, 0xEF])


def test_elkbledom_set_rgb():
    proto = ElkBledomProtocol()
    assert proto.set_rgb(1, 2, 3) == bytes([0x7E, 0x00, 0x05, 0x03, 1, 2, 3, 0x00, 0xEF])


def test_elkbledom_brightness_clamped_to_100():
    proto = ElkBledomProtocol()
    assert proto.set_brightness(150) == bytes([0x7E, 0x00, 0x01, 100, 0xFF, 0xFF, 0xFF, 0x00, 0xEF])


def test_get_protocol_known_and_unknown():
    assert isinstance(get_protocol("triones"), TrionesProtocol)
    assert isinstance(get_protocol("elkbledom"), ElkBledomProtocol)
    try:
        get_protocol("not-a-real-protocol")
    except ValueError as exc:
        assert "Unknown protocol" in str(exc)
    else:
        raise AssertionError("expected ValueError")


def test_guess_protocol_matches_name_hints():
    assert guess_protocol("Triones-A1B2") == "triones"
    assert guess_protocol("ELK-BLEDOM") == "elkbledom"
    assert guess_protocol("Some Random Device") is None
    assert guess_protocol(None) is None
