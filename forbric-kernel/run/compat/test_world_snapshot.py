"""world-snapshot.py: the typed reader/writer, export facets, the upgrade/reopen rules, mutate and the census."""
import contextlib
import copy
import gzip
import importlib.util
import io
import json
import re
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

    def test_forge_capability_data_is_a_loss_not_an_allowance(self):
        # The merged base serialised a Forge mod's capabilities under ForgeCaps; the genuine base reads none of it.
        old = copy.deepcopy(self.old)
        pig = next(iter(old["entities"].values()))["nbt"]
        pig[1]["ForgeCaps"] = C(**{"somemod:mana": C(v=I(3))})
        found, _ = ws.diff(old, self.new, "upgrade")
        self.assertTrue(any(f == "rule" and "ForgeCaps" in p and "somemod:mana" in p for f, p, _, _ in found), found)
        # Empty, it is nothing to lose; and a mod meant to be gone (--ignore-namespace) takes its capability with it.
        pig[1]["ForgeCaps"] = C()
        found, rules = ws.diff(old, self.new, "upgrade")
        self.assertEqual(found, [])
        self.assertEqual(rules.used["drop merged-only ForgeCaps"], 1)
        pig[1]["ForgeCaps"] = C(**{"somemod:mana": C(v=I(3))})
        found, rules = ws.diff(old, self.new, "upgrade", ["somemod"])
        self.assertEqual(found, [])
        self.assertEqual(rules.used["ignore-namespace somemod"], 1)

    def test_forge_data_without_neoforge_data_is_a_loss(self):
        old = copy.deepcopy(self.old)
        pig = next(iter(old["entities"].values()))["nbt"]
        del pig[1]["NeoForgeData"]
        new = copy.deepcopy(self.new)
        del next(iter(new["entities"].values()))["nbt"][1]["NeoForgeData"]
        found, _ = ws.diff(old, new, "upgrade")
        self.assertTrue(any(f == "rule" and "ForgeData without NeoForgeData" in p for f, p, _, _ in found), found)
        pig[1]["ForgeData"] = C()
        found, rules = ws.diff(old, new, "upgrade")
        self.assertEqual(found, [])
        self.assertEqual(rules.used["drop merged-only ForgeData"], 1)

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

    def test_chunk_ticket_order_is_not_a_difference(self):
        tickets = ["list", "compound", [{"chunk_pos": ["int_array", [x, 0]], "level": I(31), "type": S("minecraft:forced")}
                                        for x in range(-2, 3)]]
        old, new = copy.deepcopy(self.new), copy.deepcopy(self.new)
        rel = "dimensions/minecraft/overworld/data/minecraft/chunk_tickets.dat"
        old["saved_data"][rel] = C(data=C(tickets=tickets))
        swapped = copy.deepcopy(tickets)
        swapped[2].reverse()
        new["saved_data"][rel] = C(data=C(tickets=swapped))
        found, rules = ws.diff(old, new, "reopen")
        self.assertEqual(found, [])
        self.assertGreaterEqual(rules.used.get("unordered list tickets", 0), 1)
        swapped[2][0]["level"] = I(33)
        found, _ = ws.diff(old, new, "reopen")
        self.assertEqual(len(found), 1, found)

    def test_session_noise_of_a_player_who_was_online(self):
        """Measured on gate-m58's A/A (old -> old): what a few seconds online changes in a player's files."""
        old, new = copy.deepcopy(self.new), copy.deepcopy(self.new)
        player = "00000000-0000-0000-0000-000000000000"
        for snap, seed, ticks, order in ((old, 0, 208, ["minecraft:book", "minecraft:oak_planks"]),
                                         (new, -442425167, 423, ["minecraft:oak_planks", "minecraft:book"])):
            d = snap["players"][player][1]
            d["XpSeed"] = I(seed)
            d["warden_spawn_tracker"] = C(ticks_since_last_warning=I(ticks), warning_level=I(0))
            d["recipeBook"] = C(recipes=["list", "string", list(order)], toBeDisplayed=["list", "string", list(order)])
        old["stats"][player] = {"stats": {"minecraft:custom": {"minecraft:play_time": 207, "minecraft:leave_game": 1,
                                                               "minecraft:jump": 3}}}
        new["stats"][player] = {"stats": {"minecraft:custom": {"minecraft:play_time": 421, "minecraft:leave_game": 2,
                                                               "minecraft:jump": 3}}}
        found, rules = ws.diff(old, new, "reopen")
        self.assertEqual(found, [])
        for name in ("player XpSeed first drawn", "player per-tick warden_spawn_tracker.ticks_since_last_warning",
                     "unordered list recipes", "stats session counter minecraft:play_time",
                     "stats session counter minecraft:leave_game"):
            self.assertIn(name, rules.used)
        # What is not session noise still differs: a drawn seed that changes, a counter that drops, another stat, a
        # tracker field that is not the tick count, a recipe that is gone.
        changed = copy.deepcopy(new)
        d = changed["players"][player][1]
        d["warden_spawn_tracker"] = C(ticks_since_last_warning=I(423), warning_level=I(2))
        d["recipeBook"] = C(recipes=["list", "string", ["minecraft:book"]], toBeDisplayed=d["recipeBook"][1]["toBeDisplayed"])
        changed["stats"][player]["stats"]["minecraft:custom"].update({"minecraft:play_time": 100, "minecraft:jump": 4})
        found, _ = ws.diff(old, changed, "reopen")
        self.assertEqual(sorted(p for _, p, _, _ in found),
                         sorted(["/%s/recipeBook/recipes" % player, "/%s/warden_spawn_tracker/warning_level" % player,
                                 "/%s/stats/minecraft:custom/minecraft:play_time" % player,
                                 "/%s/stats/minecraft:custom/minecraft:jump" % player]), found)
        reseeded = copy.deepcopy(new)
        found, _ = ws.diff(new, dict(reseeded, players={player: ["compound", dict(reseeded["players"][player][1], XpSeed=I(5))]}), "reopen")
        self.assertEqual([p for _, p, _, _ in found], ["/%s/XpSeed" % player])

    def test_recipe_list_order_is_allowed_only_in_a_players_recipe_book(self):
        """The recipes/toBeDisplayed order allowance was measured in a player's recipeBook only (review w2 UPG A-6): a
        list of the same name in a block entity, or at a player's top level, keeps its order."""
        old, new = copy.deepcopy(self.new), copy.deepcopy(self.new)
        player, pos = "00000000-0000-0000-0000-000000000000", "minecraft:overworld 3 -64 0"
        for snap, order in ((old, ["minecraft:book", "minecraft:oak_planks"]), (new, ["minecraft:oak_planks", "minecraft:book"])):
            snap["block_entities"][pos][1]["recipes"] = ["list", "string", list(order)]
            snap["players"][player][1]["toBeDisplayed"] = ["list", "string", list(order)]
        found, rules = ws.diff(old, new, "reopen")
        self.assertEqual(sorted(p for _, p, _, _ in found),
                         sorted(["/%s/recipes[0]" % pos, "/%s/recipes[1]" % pos,
                                 "/%s/toBeDisplayed[0]" % player, "/%s/toBeDisplayed[1]" % player]), found)
        self.assertNotIn("unordered list recipes", rules.used)
        self.assertNotIn("unordered list toBeDisplayed", rules.used)
        # The same reorder inside the player's recipeBook is the measured noise.
        for snap, order in ((old, ["minecraft:book", "minecraft:oak_planks"]), (new, ["minecraft:oak_planks", "minecraft:book"])):
            snap["block_entities"][pos][1].pop("recipes")
            snap["players"][player][1].pop("toBeDisplayed")
            snap["players"][player][1]["recipeBook"] = C(recipes=["list", "string", list(order)])
        found, rules = ws.diff(old, new, "reopen")
        self.assertEqual(found, [])
        self.assertEqual(rules.used.get("unordered list recipes"), 1)


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


TAGS = {"byte", "short", "int", "long", "float", "double", "string", "byte_array", "int_array", "long_array", "list",
        "compound"}
DIM = r"[a-z0-9_.-]+:[a-z0-9_./-]+"
# The chunk-root keys chunk_extra leaves out, as the header lists them (spelled out here, not read from the module).
CHUNK_SKIP_FROZEN = {"sections", "block_entities", "Heightmaps", "block_ticks", "fluid_ticks", "LastUpdate",
                     "InhabitedTime", "xPos", "zPos"}


def untagged_ok(element, item):
    """An item of a typed list carries no tag of its own (the frozen schema's list rule)."""
    if element == "compound":
        return isinstance(item, dict) and all(typed_ok(v) for v in item.values())
    if element == "list":
        return isinstance(item, list) and len(item) == 2 and all(untagged_ok(item[0], x) for x in item[1])
    return typed_ok([element, item])


def typed_ok(v):
    """Whether `v` is a TYPED value exactly as world-snapshot.py's header defines it."""
    if not isinstance(v, list) or not v or v[0] not in TAGS:
        return False
    tag = v[0]
    if tag in ("byte", "short", "int", "long"):
        return len(v) == 2 and isinstance(v[1], int) and not isinstance(v[1], bool)
    if tag in ("float", "double"):
        return len(v) == 2 and isinstance(v[1], (int, float)) and not isinstance(v[1], bool)
    if tag == "string":
        return len(v) == 2 and isinstance(v[1], str)
    if tag.endswith("_array"):
        return len(v) == 2 and isinstance(v[1], list) and all(isinstance(x, int) for x in v[1])
    if tag == "compound":
        return len(v) == 2 and isinstance(v[1], dict) and all(typed_ok(x) for x in v[1].values())
    return len(v) == 3 and (v[1] in TAGS or v[1] == "end") and all(untagged_ok(v[1], x) for x in v[2])


class FrozenSchema(unittest.TestCase):
    """U2: the snapshot file, the diff report and the import API are frozen (header of world-snapshot.py). A change that
    fails here is a new schema (/2) and a note to lane SWAP, whose fixture holds a /1 snapshot nobody can write again."""

    def test_the_frozen_names(self):
        self.assertEqual(ws.SCHEMA, "forbric-world-snapshot/1")
        self.assertEqual(ws.DIFF_SCHEMA, "forbric-world-snapshot-diff/1")
        self.assertEqual(ws.EXPORTER, 1)
        self.assertEqual(ws.FACETS, ("level", "blocks", "biomes", "block_entities", "chunk_extra", "entities", "players",
                                     "advancements", "stats", "saved_data"))
        self.assertEqual(ws.CHUNK_SKIP, CHUNK_SKIP_FROZEN)
        for name in ("export", "diff", "diff_report", "read_nbt_file", "get", "referenced_names", "census"):
            self.assertTrue(callable(getattr(ws, name)), name)
        doc = ws.__doc__
        self.assertIn("THE SNAPSHOT FILE IS FROZEN", doc)
        for word in ("schema", "meta") + ws.FACETS + (ws.SCHEMA, ws.DIFF_SCHEMA):
            self.assertIn('"%s"' % word if word in ("schema", "meta") + ws.FACETS else word, doc, word)

    def test_a_save_the_game_wrote_exports_in_the_frozen_shape(self):
        with tempfile.TemporaryDirectory() as tmp:
            world = Path(tmp) / "world"
            (world / "region").mkdir(parents=True)
            self.assertTrue((TESTDATA / "level.dat").is_file(), "game-written testdata missing: %s" % TESTDATA)
            (world / "level.dat").write_bytes((TESTDATA / "level.dat").read_bytes())
            (world / "region" / "r.0.0.mca").write_bytes((TESTDATA / "r.0.0.mca").read_bytes())
            snap = ws.export(world, [-1, -1, 1, 1], "frozen")
        self.assertEqual(set(snap), {"schema", "meta"} | set(ws.FACETS))
        self.assertEqual(snap["meta"], {"exporter": 1, "world": str(world), "chunks": [-1, -1, 1, 1], "label": "frozen"})
        self.assertTrue(typed_ok(snap["level"]) and snap["level"][0] == "compound")
        self.assertTrue(snap["blocks"])
        for key, state in snap["blocks"].items():
            self.assertRegex(key, r"^%s -?\d+ -?\d+ -?\d+$" % DIM)
            self.assertRegex(state, r"^%s(\[[^\]]+\])?$" % DIM)
        self.assertTrue(snap["biomes"])
        for key, runs in snap["biomes"].items():
            self.assertRegex(key, r"^%s -?\d+ -?\d+ -?\d+$" % DIM)
            self.assertTrue(all(isinstance(b, str) and isinstance(n, int) for b, n in runs))
            self.assertEqual(sum(n for _, n in runs), 64)
        self.assertTrue(snap["chunk_extra"])
        for key, extra in snap["chunk_extra"].items():
            self.assertRegex(key, r"^%s -?\d+ -?\d+$" % DIM)
            self.assertTrue(typed_ok(extra) and extra[0] == "compound")
            self.assertFalse(set(extra[1]) & CHUNK_SKIP_FROZEN)
        # A snapshot is plain JSON: what a gate writes is what it reads back.
        self.assertEqual(json.loads(json.dumps(snap, sort_keys=True)), snap)

    def test_every_facet_of_a_full_world_is_typed_as_documented(self):
        with tempfile.TemporaryDirectory() as tmp:
            snap = ws.export(make_world(Path(tmp)))
        for facet in ("block_entities", "players"):
            self.assertTrue(snap[facet], facet)
            self.assertTrue(all(typed_ok(v) and v[0] == "compound" for v in snap[facet].values()), facet)
        self.assertTrue(all("x" not in v[1] and "y" not in v[1] and "z" not in v[1] for v in snap["block_entities"].values()))
        for key, ent in snap["entities"].items():
            self.assertRegex(key, r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
            self.assertEqual(set(ent), {"dim", "nbt"})
            self.assertTrue(typed_ok(ent["nbt"]))
        for rel, value in snap["saved_data"].items():
            self.assertIn("data/", "/" + rel)
            self.assertTrue(typed_ok(value) or set(value) <= {"sha1", "unreadable_sha1"}, rel)

    def test_the_diff_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            world = make_world(Path(tmp))
            a, b, out = Path(tmp) / "a.json", Path(tmp) / "b.json", Path(tmp) / "diff.json"
            self.assertEqual(ws.main(["export", str(world), "--out", str(a)]), 0)
            ws.mutate(world, [("minecraft:overworld 3 -64 0", 0, 6)])
            self.assertEqual(ws.main(["export", str(world), "--out", str(b)]), 0)
            self.assertEqual(ws.main(["diff", str(a), str(a), "--rules", "reopen", "--json", str(out)]), 0)
            clean = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual((clean["count"], clean["differences"], clean["by_facet"]), (0, [], {}))
            self.assertEqual(clean["rules_used"]["level clock Time"], 2)
            self.assertEqual(ws.main(["diff", str(a), str(b), "--rules", "reopen", "--ignore-namespace", "zzz",
                                      "--json", str(out)]), 1)
            report = json.loads(out.read_text(encoding="utf-8"))
        self.assertEqual(set(report), {"schema", "rules", "ignore_namespace", "rules_used", "by_facet", "count",
                                       "differences"})
        self.assertEqual((report["schema"], report["rules"], report["ignore_namespace"]),
                         ("forbric-world-snapshot-diff/1", "reopen", ["zzz"]))
        self.assertEqual(report["count"], 1)
        self.assertEqual(report["by_facet"], {"block_entities": 1})
        self.assertEqual(report["differences"], [{"facet": "block_entities", "path": "/minecraft:overworld 3 -64 0/Items[0]/count",
                                                  "a": ["int", 1], "b": ["int", 6]}])

    def test_another_schema_or_exporter_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            good = ws.export(make_world(Path(tmp)))
            paths = {}
            for name, change in (("good", {}), ("schema2", {"schema": "forbric-world-snapshot/2"}),
                                 ("exporter2", {"meta": dict(good["meta"], exporter=2)})):
                paths[name] = Path(tmp) / (name + ".json")
                paths[name].write_text(json.dumps(dict(good, **change)), encoding="utf-8")
            regs = Path(tmp) / "regs.json"
            regs.write_text('{"registries": {}}', encoding="utf-8")
            self.assertEqual(ws.main(["diff", str(paths["good"]), str(paths["schema2"]), "--rules", "reopen"]), 3)
            self.assertEqual(ws.main(["diff", str(paths["good"]), str(paths["exporter2"]), "--rules", "reopen"]), 3)
            self.assertEqual(ws.main(["census", str(paths["schema2"]), str(regs)]), 3)
            self.assertEqual(ws.main(["diff", str(paths["good"]), str(paths["good"]), "--rules", "reopen"]), 0)


class GateArgv(unittest.TestCase):
    """gate-m58 calls this script by its CLI: every subcommand and option it spells must be one the parser takes, so a
    renamed option fails here and not forty minutes into the gate (the gate-m25 REGION_PROBE contract, for this tool)."""
    GATE = Path(__file__).resolve().parent.parent / "gate-m58-world-upgrade.sh"

    def calls(self):
        text = self.GATE.read_text(encoding="utf-8").replace("\\\n", " ")  # one line per continued command
        found = []
        for line in text.splitlines():
            if line.lstrip().startswith("#"):
                continue
            for m in re.finditer(r'python3 "\$WS" (\w+)([^|;&>]*)', line):
                found.append((m.group(1), re.findall(r"(--[a-z][a-z-]*)", m.group(2))))
        return found

    def test_every_call_names_a_subcommand_and_options_it_takes(self):
        calls = self.calls()
        self.assertGreaterEqual({c for c, _ in calls}, {"export", "diff", "mutate", "census"}, calls)
        helps = {}
        for command, options in calls:
            if command not in helps:
                out = io.StringIO()
                with contextlib.redirect_stdout(out), self.assertRaises(SystemExit):
                    ws.main([command, "--help"])
                helps[command] = out.getvalue()
            for option in options:
                self.assertIn(option, helps[command], "gate-m58 passes %s to `%s`, which does not take it" % (option, command))


if __name__ == "__main__":
    unittest.main()
