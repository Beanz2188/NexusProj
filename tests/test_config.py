from pathlib import Path

from nexuslights.config import Config, LightConfig, load, save


def test_save_and_load_round_trip(tmp_path: Path):
    path = tmp_path / "lights.yaml"
    config = Config(
        lights=[
            LightConfig(address="AA:BB:CC:DD:EE:FF", name="Desk Strip", protocol="triones"),
            LightConfig(address="11:22:33:44:55:66", name="Bed Strip", protocol="elkbledom"),
        ]
    )

    save(config, path)
    loaded = load(path)

    assert loaded.lights == config.lights


def test_load_missing_file_returns_empty_config(tmp_path: Path):
    loaded = load(tmp_path / "does_not_exist.yaml")
    assert loaded.lights == []


def test_upsert_replaces_existing_by_address():
    config = Config(lights=[LightConfig(address="AA:BB", name="Old Name", protocol="triones")])
    config.upsert(LightConfig(address="aa:bb", name="New Name", protocol="elkbledom"))

    assert len(config.lights) == 1
    assert config.lights[0].name == "New Name"
    assert config.lights[0].protocol == "elkbledom"


def test_get_is_case_insensitive():
    config = Config(lights=[LightConfig(address="AA:BB:CC", name="Desk", protocol="triones")])
    assert config.get("aa:bb:cc") is not None
    assert config.get("zz:zz:zz") is None
