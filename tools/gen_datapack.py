#!/usr/bin/env python3
"""Generate the mod's worldgen datapack from vanilla 1.21.1 data.

Usage: gen_datapack.py <extracted vanilla data dir (contains data/minecraft)> <src/main/resources>

Writes, under data/fastdiffusion/:
  dimension_type/scale_N.json         overworld type with a height fitting scale N
  worldgen/noise_settings/scale_N.json  constant router (terrain comes from the native pipeline)
  worldgen/world_preset/scale_N.json
  worldgen/biome/*.json               sparse forests and a warm dry grove
  worldgen/placed_feature/*.json      sparse tree placements
and appends the presets to minecraft:normal.
"""
import copy
import json
import sys
from pathlib import Path

MOD = "fastdiffusion"
MIN_Y = -64
SEA_LEVEL = 63
MAX_METERS = 10_000
NATIVE_RESOLUTION = 30.0
SCALES = [1, 2, 3, 4, 5, 6]


def height_for_scale(scale: int) -> int:
    top = SEA_LEVEL - 1 + int(MAX_METERS / (NATIVE_RESOLUTION / scale)) + 1
    height = ((top - MIN_Y + 15) // 16) * 16
    return min(height, 2032 - MIN_Y)


def write(root: Path, rel: str, obj):
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(obj, indent=2) + "\n")


def main():
    vanilla = Path(sys.argv[1]) / "data" / "minecraft"
    out = Path(sys.argv[2]) / "data"
    load = lambda rel: json.loads((vanilla / rel).read_text())

    overworld_type = load("dimension_type/overworld.json")
    normal_preset = load("worldgen/world_preset/normal.json")
    router_keys = load("worldgen/noise_settings/overworld.json")["noise_router"].keys()

    presets = []
    for s in SCALES:
        h = height_for_scale(s)
        dt = copy.deepcopy(overworld_type)
        dt.update(min_y=MIN_Y, height=h, logical_height=h)
        write(out, f"{MOD}/dimension_type/scale_{s}.json", dt)

        write(out, f"{MOD}/worldgen/noise_settings/scale_{s}.json", {
            # The fluid picker derives from this sea level; putting it at the floor keeps carved
            # caves dry. The generator reports the real sea level (63) to the game.
            "sea_level": MIN_Y,
            "disable_mob_generation": False,
            "aquifers_enabled": False,
            "ore_veins_enabled": False,
            "legacy_random_source": False,
            "default_block": {"Name": "minecraft:stone"},
            "default_fluid": {"Name": "minecraft:water", "Properties": {"level": "0"}},
            "noise": {"min_y": MIN_Y, "height": h, "size_horizontal": 1, "size_vertical": 1},
            "noise_router": {k: 0 for k in router_keys},
            "spawn_target": [],
            # Only consulted by carvers when they expose a dirt block that was below grass.
            "surface_rule": {"type": "minecraft:block", "result_state": {"Name": "minecraft:grass_block", "Properties": {"snowy": "false"}}},
        })

        preset = copy.deepcopy(normal_preset)
        preset["dimensions"]["minecraft:overworld"] = {
            "type": f"{MOD}:scale_{s}",
            "generator": {
                "type": f"{MOD}:terrain",
                "settings": f"{MOD}:scale_{s}",
                "scale": s,
                "biome_source": {"type": f"{MOD}:terrain"},
            },
        }
        write(out, f"{MOD}/worldgen/world_preset/scale_{s}.json", preset)
        presets.append(f"{MOD}:scale_{s}")
        print(f"scale {s}: height {h} (y {MIN_Y}..{MIN_Y + h - 1})")

    write(out, "minecraft/tags/worldgen/world_preset/normal.json", {"replace": False, "values": presets})

    # Sparse tree placements (3-4 per chunk instead of 10-11).
    sparse_count = {"type": "minecraft:weighted_list", "distribution": [{"data": 3, "weight": 9}, {"data": 4, "weight": 1}]}
    for name, src in (("trees_birch_and_oak_sparse", "trees_birch_and_oak"), ("trees_taiga_sparse", "trees_taiga")):
        pf = load(f"worldgen/placed_feature/{src}.json")
        for mod in pf["placement"]:
            if mod["type"] == "minecraft:count":
                mod["count"] = sparse_count
        write(out, f"{MOD}/worldgen/placed_feature/{name}.json", pf)

    for name, src, old, new in (
        ("forest_sparse", "forest", "minecraft:trees_birch_and_oak", f"{MOD}:trees_birch_and_oak_sparse"),
        ("taiga_sparse", "taiga", "minecraft:trees_taiga", f"{MOD}:trees_taiga_sparse"),
        ("snowy_taiga_sparse", "snowy_taiga", "minecraft:trees_taiga", f"{MOD}:trees_taiga_sparse"),
    ):
        b = load(f"worldgen/biome/{src}.json")
        b["features"] = [[new if f == old else f for f in step] for step in b["features"]]
        assert any(new in step for step in b["features"]), name
        write(out, f"{MOD}/worldgen/biome/{name}.json", b)

    # Grove look-alike for dry, treeless uplands: warm, no snow, grassland animals.
    grove = load("worldgen/biome/grove.json")
    plains = load("worldgen/biome/plains.json")
    grove.update(temperature=2.0, downfall=0.0, has_precipitation=False, spawners=plains["spawners"])
    grove["effects"]["sky_color"] = 7254527
    grove["effects"].pop("music", None)
    write(out, f"{MOD}/worldgen/biome/dry_grove.json", grove)


if __name__ == "__main__":
    main()
