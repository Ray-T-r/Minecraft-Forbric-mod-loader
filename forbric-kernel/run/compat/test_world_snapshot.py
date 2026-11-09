"""world-snapshot.py: the typed reader/writer, export facets, the upgrade/reopen rules, mutate and the census."""
import copy
import gzip
import importlib.util
import json
import struct
import tempfile
import unittest
import zlib
from pathlib import Path

_spec = importlib.util.spec_from_file_location("world_snapshot", Path(__file__).with_name("world-snapshot.py"))
ws = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ws)
wp = ws.wp

TESTDATA = Path(__file__).with_name("testdata") / "world-snapshot"


def C(**kv):
    return ["compound", dict(kv)]


def S(text):
    return ["string", text]


def I(n):
    return ["int", n]


def pack(indices, bits):
    per = 64 // bits
    longs = [0] * ((len(indices) + per - 1) // per)
    for i, v in enumerate(indices):
        longs[i // per] |= v << ((i % per) * bits)
    return [x - (1 << 64) if x >= 1 << 63 else x for x in longs]


def chunk(cx, cz, palette, indices, block_entities=(), extra=None):
    section = {"Y": ["byte", -4], "block_states": C(palette=["list", "compound", [p[1] for p in palette]],
                                                      data=["long_array", pack(indices, 4)]),
               "biomes": C(palette=["list", "string", ["minecraft:plains"]])}
    root = {"xPos": I(cx), "zPos": I(cz), "Status": S("minecraft:full"), "DataVersion": I(4903),
            "sections": ["list", "compound", [section]],
            "block_entities": ["list", "compound", [b[1] for b in block_entities]]}
    root.update(extra or {})
    return ["compound", root]


def write_region(directory, chunks):
    directory.mkdir(parents=True, exist_ok=True)
    regions = {}
    for cx, cz, root in chunks:
        regions.setdefault((cx >> 5, cz >> 5), []).append((cx, cz, root))
    for (rx, rz), items in regions.items():
        path = directory / ("r.%d.%d.mca" % (rx, rz))
        path.write_bytes(b"\x00" * 8192)
        for cx, cz, root in items:
            ws.rewrite_chunk(path, cx, cz, root)


def write_dat(path, root):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(gzip.compress(ws.encode_root(root)))


def make_world(base, level_packs=("vanilla", "forbric/carrier/1-forge-runtime-interop", "forbric/carrier/2-neoforge-runtime",
                                  "forbric/data/mcwbridges"), pig_extra=None):
    world = base / "world"
    write_dat(world / "level.dat", C(Data=C(LastPlayed=["long", 1], Time=["long", 10], DayTime=["long", 6000],
                                            DataPacks=C(Enabled=["list", "string", list(level_packs)],
                                                        Disabled=["list", "string", []])),
                                     fml=C(LoadingModList=["list", "compound", [{"ModId": S("forbricneolive")}]])))
    palette = [C(Name=S("minecraft:air")), C(Name=S("minecraft:bedrock")),
               C(Name=S("minecraft:oak_stairs"), Properties=C(half=S("top"), facing=S("east"))),
               C(Name=S("mcwbridges:acacia_bridge_pier")), C(Name=S("minecraft:chest"))]
    indices = [0] * 4096
    indices[0] = 1                 # (0,-64,0) bedrock
    indices[1] = 2                 # (1,-64,0) stairs
    indices[2] = 3                 # (2,-64,0) pier
    indices[3] = 4                 # (3,-64,0) chest
    chest = C(id=S("minecraft:chest"), x=I(3), y=I(-64), z=I(0),
              Items=["list", "compound", [{"Slot": ["byte", 0], "id": S("minecraft:diamond_sword"), "count": I(1),
                                           "components": C(**{"minecraft:damage": I(5)})},
                                          {"Slot": ["byte", 1], "id": S("mcwbridges:acacia_bridge_pier"), "count": I(3)}]])
    write_region(world / "dimensions/minecraft/overworld/region",
                 [(0, 0, chunk(0, 0, palette, indices, [chest], {"neoforge:attachments": C(**{"forbricupgradeneo:chunk": I(7)})}))])
    pig = {"id": S("minecraft:pig"), "UUID": ["int_array", [1, 2, 3, 4]], "NeoForgeData": C(k=I(1)),
           "attributes": ["list", "compound", [{"id": S("minecraft:max_health")}, {"id": S("minecraft:armor")}]]}
    pig.update(pig_extra or {})
    write_region(world / "dimensions/minecraft/overworld/entities",
                 [(0, 0, C(Entities=["list", "compound", [pig]], Position=["int_array", [0, 0]]))])
    write_dat(world / "players/data/00000000-0000-0000-0000-000000000000.dat",
              C(Pos=["list", "double", [3.5, -59.0, 7.5]], Motion=["list", "double", [0.0, -0.07, 0.0]],
                Inventory=["list", "compound", [{"Slot": ["byte", 0], "id": S("minecraft:stone"), "count": I(4)}]]))
    write_dat(world / "data/minecraft/weather.dat", C(data=C(rain_time=I(5))))
    write_dat(world / "data/forbricupgradeneo/state.dat", C(data=C(value=I(42))))
    return world


class NbtRoundTrip(unittest.TestCase):
    def test_hand_written_bytes_read_typed(self):
        # {"a": byte 1, "b": int -2, "l": [short 3]} written by hand, not by our writer.
        raw = (b"\x0a\x00\x00" + b"\x01\x00\x01a\x01" + b"\x03\x00\x01b\xff\xff\xff\xfe"
               + b"\x09\x00\x01l\x02\x00\x00\x00\x01\x00\x03" + b"\x00")
        root = wp.root_typed(raw)
        self.assertEqual(root, ["compound", {"a": ["byte", 1], "b": ["int", -2], "l": ["list", "short", [3]]}])
        self.assertEqual(ws.encode_root(root), raw)

    def test_types_are_kept_and_round_trip(self):
        value = C(b=["byte", -1], s=["short", 2], i=I(3), l=["long", -4], f=["float", 0.5], d=["double", 1.25],
                  ba=["byte_array", [-1, 2]], ia=["int_array", [1]], la=["long_array", [-9]], str=S("x"),
                  nested=["list", "list", [["int", [1, 2]], ["string", ["a"]]]], empty=["list", "end", []],
                  comp=["list", "compound", [{"k": I(1)}]])
        raw = ws.encode_root(value)
        self.assertEqual(wp.root_typed(raw), value)

    def test_the_untyped_view_is_what_world_parity_always_read(self):
        value = C(b=["byte", -1], ba=["byte_array", [-1, 2]], l=["list", "compound", [{"k": ["byte", 200 - 256]}]])
        self.assertEqual(wp.plain(value), {"b": 255, "ba": bytes([255, 2]), "l": [{"k": 200}]})

    def test_a_byte_and_an_int_are_different_values(self):
        self.assertNotEqual(wp.root_typed(ws.encode_root(C(v=["byte", 5]))),
                            wp.root_typed(ws.encode_root(C(v=I(5)))))


class Export(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.world = make_world(Path(self.tmp.name))

    def tearDown(self):
        self.tmp.cleanup()

    def test_facets(self):
        snap = ws.export(self.world, [-1, -1, 1, 1])
        self.assertEqual(snap["schema"], ws.SCHEMA)
        self.assertEqual(snap["blocks"], {"minecraft:overworld 0 -64 0": "minecraft:bedrock",
                                          "minecraft:overworld 1 -64 0": "minecraft:oak_stairs[facing=east,half=top]",
                                          "minecraft:overworld 2 -64 0": "mcwbridges:acacia_bridge_pier",
                                          "minecraft:overworld 3 -64 0": "minecraft:chest"})
        self.assertIn("minecraft:overworld 3 -64 0", snap["block_entities"])
        self.assertEqual(ws.get(snap["chunk_extra"]["minecraft:overworld 0 0"], "neoforge:attachments"),
                         {"forbricupgradeneo:chunk": I(7)})
        self.assertEqual(list(snap["entities"]), ["00000001-0000-0002-0000-000300000004"])
        self.assertEqual(list(snap["players"]), ["00000000-0000-0000-0000-000000000000"])
        self.assertIn("data/forbricupgradeneo/state.dat", snap["saved_data"])

    def test_the_box_limits_chunks(self):
        snap = ws.export(self.world, [5, 5, 6, 6])
        self.assertEqual(snap["blocks"], {})
        self.assertEqual(snap["entities"], {})

    def test_a_save_the_game_wrote_reads(self):
        """A level.dat and a one-chunk region the game itself wrote (old kernel, flat world), not our writer."""
        level = TESTDATA / "level.dat"
        region = TESTDATA / "r.0.0.mca"
        # A failure, never a skip: this is the one test that reads bytes the game wrote, and the testdata is in the tree.
        self.assertTrue(level.is_file() and region.is_file(), "game-written testdata missing: %s" % TESTDATA)
        root = ws.read_nbt_file(level)
        self.assertIn("DataPacks", root[1]["Data"][1])
        chunks = list(wp.chunks_typed(TESTDATA))
        self.assertEqual(len(chunks), 1)
        blocks = ws.section_blocks(next(s for s in ws.get(chunks[0][2], "sections") if ws.get(s, "Y") == -4))
        self.assertEqual(blocks[0], "minecraft:bedrock")
        self.assertTrue(all(b.split("[")[0] in ws.AIR or ":" in b for b in blocks))


class Rules(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        base = Path(self.tmp.name)
        self.old = ws.export(make_world(base / "old", pig_extra={"ForgeData": C(k=I(1)), "CanUpdate": ["byte", 1]}))
        self.new = ws.export(make_world(base / "new", level_packs=("vanilla", "forbric/carrier/2-neoforge-runtime",
                                                                   "forbric/data/mcwbridges")))

    def tearDown(self):
        self.tmp.cleanup()

    def test_an_upgrade_with_only_allowed_losses_is_clean(self):
        found, rules = ws.diff(self.old, self.new, "upgrade")
        self.assertEqual(found, [])
        self.assertEqual(rules.used["drop merged-only ForgeData"], 1)
        self.assertEqual(rules.used["drop merged-only CanUpdate"], 1)
        self.assertEqual(rules.used["drop MinecraftForge carrier pack"], 1)

    def test_reopen_rules_refuse_merged_keys(self):
        found, _ = ws.diff(self.old, self.old, "reopen")
        self.assertTrue(any(f == "rule" and "merged-only" in p for f, p, _, _ in found))

    def test_forge_data_must_mirror_neoforge_data(self):
        old = copy.deepcopy(self.old)
        pig = next(iter(old["entities"].values()))["nbt"]
        pig[1]["ForgeData"] = C(k=I(2))
        found, _ = ws.diff(old, self.new, "upgrade")
        self.assertTrue(any(f == "rule" and "ForgeData differs" in p for f, p, _, _ in found))

    def test_a_changed_slot_is_exactly_one_difference(self):
        new = copy.deepcopy(self.new)
        be = new["block_entities"]["minecraft:overworld 3 -64 0"]
        be[1]["Items"][2][0]["count"] = I(2)
        found, _ = ws.diff(self.old, new, "upgrade")
        self.assertEqual([(f, p) for f, p, _, _ in found], [("block_entities", "/minecraft:overworld 3 -64 0/Items[0]/count")])

    def test_a_field_that_changes_type_is_a_difference(self):
        new = copy.deepcopy(self.new)
        new["saved_data"]["data/forbricupgradeneo/state.dat"][1]["data"][1]["value"] = ["long", 42]
        found, _ = ws.diff(self.old, new, "upgrade")
        self.assertEqual(len(found), 1)

    def test_a_key_only_the_new_side_has_is_a_difference(self):
        new = copy.deepcopy(self.new)
        new["players"]["00000000-0000-0000-0000-000000000000"][1]["Renamed"] = I(1)
        found, _ = ws.diff(self.old, new, "upgrade")
        self.assertEqual([p for _, p, _, _ in found], ["/00000000-0000-0000-0000-000000000000/Renamed"])

    def test_the_carrier_must_stay_below_the_mods(self):
        new = copy.deepcopy(self.new)
        new["level"][1]["Data"][1]["DataPacks"][1]["Enabled"] = ["list", "string", [
            "vanilla", "forbric/data/mcwbridges", "forbric/carrier/2-neoforge-runtime"]]
        found, _ = ws.diff(self.old, new, "upgrade")
        self.assertTrue(any(f == "rule" and "sorts after a mod pack" in p for f, p, _, _ in found))

    def test_a_carrier_under_its_installed_file_name_is_flagged(self):
        new = copy.deepcopy(self.new)
        new["level"][1]["Data"][1]["DataPacks"][1]["Enabled"] = ["list", "string", [
            "vanilla", "forbric/carrier/2-neoforge-runtime-26.2.0.93", "forbric/data/mcwbridges"]]
        found, _ = ws.diff(self.old, new, "upgrade")
        self.assertTrue(any(f == "rule" and "other than" in p for f, p, _, _ in found))

    def test_ignore_namespace_hides_only_that_namespace(self):
        new = copy.deepcopy(self.new)
        new["blocks"].pop("minecraft:overworld 2 -64 0")
        be = new["block_entities"]["minecraft:overworld 3 -64 0"]
        be[1]["Items"][2].pop(1)
        found, _ = ws.diff(self.old, new, "upgrade")
        self.assertEqual(len(found), 2)
        self.assertTrue(all("mcwbridges" in json.dumps([p, x, y]) for _, p, x, y in found))
        found, rules = ws.diff(self.old, new, "upgrade", ["mcwbridges"])
        self.assertEqual(found, [])
        self.assertGreaterEqual(rules.used["ignore-namespace mcwbridges"], 2)

    def test_attribute_order_is_not_a_difference(self):
        new = copy.deepcopy(self.new)
        pig = next(iter(new["entities"].values()))["nbt"]
        pig[1]["attributes"][2].reverse()
        found, _ = ws.diff(self.old, new, "upgrade")
        self.assertEqual(found, [])


class Mutate(unittest.TestCase):
    def test_a_chest_slot_and_a_palette_name_change_and_nothing_else(self):
        with tempfile.TemporaryDirectory() as tmp:
            world = make_world(Path(tmp))
            before = ws.export(world)
            changed = ws.mutate(world, [("minecraft:overworld 3 -64 0", 0, 6)],
                                ["mcwbridges:acacia_bridge_pier=mcwbridges:acacia_bridge_piex"])
            self.assertEqual(changed, ["chest minecraft:overworld 3 -64 0 slot 0 count 1 -> 6",
                                       "palette mcwbridges:acacia_bridge_pier -> mcwbridges:acacia_bridge_piex in 1 section palette entry"])
            after = ws.export(world)
            found, _ = ws.diff(before, after, "reopen")
            self.assertEqual(sorted(p for _, p, _, _ in found),
                             ["/minecraft:overworld 2 -64 0", "/minecraft:overworld 3 -64 0/Items[0]/count"])

    def test_a_chunk_that_outgrows_its_sectors_is_appended(self):
        with tempfile.TemporaryDirectory() as tmp:
            region = Path(tmp) / "region"
            write_region(region, [(0, 0, C(v=I(1)))])
            big = C(v=["byte_array", [i % 100 for i in range(40000)]])
            ws.rewrite_chunk(region / "r.0.0.mca", 0, 0, big)
            self.assertEqual([r for _, _, r in wp.chunks_typed(region)], [big])


class Census(unittest.TestCase):
    def test_names_missing_from_the_registries_are_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            snap = ws.export(make_world(Path(tmp)))
            regs = {"registries": {
                "minecraft:block": ["minecraft:bedrock", "minecraft:oak_stairs", "mcwbridges:acacia_bridge_pier", "minecraft:chest"],
                "minecraft:item": ["minecraft:diamond_sword", "mcwbridges:acacia_bridge_pier", "minecraft:stone"],
                "minecraft:block_entity_type": ["minecraft:chest"], "minecraft:entity_type": ["minecraft:pig"],
                "minecraft:data_component_type": ["minecraft:damage"], "attachment_types": ["forbricupgradeneo:chunk"]}}
            self.assertEqual(ws.census(snap, [regs]), [])
            self.assertEqual(ws.census(snap, [regs], ["item=minecraft:stone"]), [("item", "minecraft:stone")])


if __name__ == "__main__":
    unittest.main()
