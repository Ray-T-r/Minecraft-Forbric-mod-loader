"""forge-swap.py: the probe stays inside its box, the drill reads the game's answers, the census finds every loader key,
and the classifier tells a Forbric loss from the mod's own format."""
import copy
import importlib.util
import json
import re
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent


def _load(name, file):
    spec = importlib.util.spec_from_file_location(name, HERE / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


fs = _load("forge_swap", "forge-swap.py")
ws = _load("world_snapshot", "world-snapshot.py")

LOG = """[12:00:00] [Server thread/INFO]: Done (1.0s)! For help, type "help"
[12:00:01] [Server thread/INFO]: [Server] [forge-swap] check brickfurnace.block.kept expect Test passed
[12:00:01] [Server thread/INFO]: Test passed
[12:00:01] [Server thread/INFO]: [Server] [forge-swap] check brickfurnace.items.kept expect Test passed\\. Count: 3
[12:00:01] [Server thread/INFO]: Test passed. Count: 2
[12:00:02] [Server thread/INFO]: [Server] [forge-swap] check tombstone.place expect Changed the block
[12:00:02] [Server thread/INFO]: [Forbric/Something] an unrelated line
[12:00:02] [Server thread/INFO]: Unknown block type 'tombstone:white_marble'
[12:00:03] [Server thread/INFO]: [Server] [forge-swap] check packedup.craft expect Test passed\\. Count: 1
"""


class Probe(unittest.TestCase):
    def test_the_console_only_writes_inside_the_exported_box(self):
        # The world-population script places into chunks 0..1 (x, z 0..31); the drill writes only beyond the exported
        # box (BOX, chunks -2..3: x up to 63), so its crafters and dropped items are never part of a diff.
        coords = re.compile(r"(?:setblock|summon \S+) (-?\d+) -?\d+ (-?\d+)")
        found = coords.findall(fs.console_text())
        self.assertGreater(len(found), 20)
        for x, z in found:
            self.assertTrue(0 <= int(x) <= 31 and 0 <= int(z) <= 31, (x, z))
        for line in fs.checks_console():
            m = re.match(r"(?:setblock|execute if (?:items )?block) (-?\d+) -?\d+ (-?\d+)", line)
            if m and int(m.group(1)) <= 31 and line.startswith("execute"):
                continue  # a check that reads the probe itself
            m = m or re.search(r"x=(-?\d+),y=-?\d+,z=(-?\d+)", line)
            if m:
                x0, z0, x1, z1 = (int(v) for v in fs.BOX.split(","))
                self.assertGreater(int(m.group(1)), x1 * 16 + 15, line)

    def test_every_check_is_announced_with_an_expectation(self):
        lines = fs.checks_console()
        named = [row for row in fs.checks() if row[0]]
        self.assertGreater(len(named), 20)
        for name, expect, command in named:
            mark = "say [forge-swap] check %s expect %s" % (name, expect)
            self.assertIn(mark, lines)
            self.assertEqual(command, lines[lines.index(mark) + 1])
            re.compile(expect)

    def test_each_gate_representative_has_a_place_use_or_craft_drill(self):
        names = {row[0] for row in fs.checks() if row[0]}
        for mod in ("brickfurnace", "packedup", "tombstone"):
            self.assertIn(mod + ".craft", names)
            self.assertIn(mod + ".items.kept", names)
        self.assertTrue({"brickfurnace.place", "brickfurnace.use", "tombstone.place"} <= names)


class Drill(unittest.TestCase):
    def test_the_answer_after_each_announcement_decides(self):
        rows = {name: (verdict, answer) for name, _expect, verdict, answer in fs.drill(LOG)}
        self.assertEqual(("PASS", "Test passed"), rows["brickfurnace.block.kept"])
        self.assertEqual(("FAIL", "Test passed. Count: 2"), rows["brickfurnace.items.kept"])
        self.assertEqual(("FAIL", "Unknown block type 'tombstone:white_marble'"), rows["tombstone.place"])
        self.assertEqual(("FAIL", ""), rows["packedup.craft"])


def _snap(**facets):
    snap = {"schema": ws.SCHEMA, "meta": {"exporter": ws.EXPORTER, "world": "w", "chunks": None, "label": ""}, "level": None}
    for facet in ws.FACETS:
        if facet != "level":
            snap[facet] = {}
    snap.update(facets)
    return snap


def _entity(ident, name, **extra):
    nbt = {"id": ["string", ident], "CustomName": ["string", name], "Health": ["float", 20.0]}
    nbt.update(extra)
    return {"dim": "minecraft:overworld", "nbt": ["compound", nbt]}


U1, U2, U3, U4 = ("00000000-0000-0000-0000-00000000000%d" % i for i in range(1, 5))


class Census(unittest.TestCase):
    def test_every_loader_subtree_is_listed_with_its_keys(self):
        snap = _snap(entities={U1: _entity("minecraft:wolf", "W", ForgeCaps=["compound", {"tombstone:cap_familiar": ["compound", {}]}],
                                           NeoForgeData=["compound", {"tb_x": ["byte", 1]}])},
                     block_entities={"minecraft:overworld 1 2 3": ["compound", {"ForgeCaps": ["compound", {}]}]})
        found = fs.census(snap)
        self.assertEqual(["tombstone:cap_familiar"], found["entities/%s/nbt/ForgeCaps" % U1])
        self.assertEqual(["tb_x"], found["entities/%s/nbt/NeoForgeData" % U1])
        self.assertEqual([], found["block_entities/minecraft:overworld 1 2 3/ForgeCaps"])


class Classify(unittest.TestCase):
    """Two arms that differ in exactly the ways the verdicts name: a block only Forbric loses, a capability both lose,
    a persistent-data key only native loses, and the same entities under other UUIDs in each world."""

    def _arms(self, root):
        chest = ["compound", {"id": ["string", "minecraft:chest"], "Items": ["list", "compound", [
            {"id": ["string", "brickfurnace:brick_furnace"], "count": ["int", 3], "Slot": ["byte", 0]}]]}]
        caps = ["compound", {"tombstone:cap_familiar": ["compound", {"familiar_active": ["byte", 0]}]}]
        old_f = _snap(blocks={"minecraft:overworld 2 -60 0": "brickfurnace:brick_furnace[facing=south]"},
                      block_entities={"minecraft:overworld 8 -60 0": chest},
                      entities={U1: _entity("minecraft:wolf", "Swap Wolf", ForgeCaps=caps),
                                U2: _entity("minecraft:zombie", "Swap Zombie", ForgeData=["compound", {"tb_x": ["byte", 1]}],
                                            NeoForgeData=["compound", {"tb_x": ["byte", 1]}])})
        new_f = _snap(block_entities={"minecraft:overworld 8 -60 0": chest},
                      entities={U1: _entity("minecraft:wolf", "Swap Wolf"),
                                U2: _entity("minecraft:zombie", "Swap Zombie", NeoForgeData=["compound", {"tb_x": ["byte", 1]}])})
        old_n = copy.deepcopy(old_f)
        old_n["entities"] = {U3: _entity("minecraft:wolf", "Swap Wolf", ForgeCaps=caps),
                             U4: _entity("minecraft:zombie", "Swap Zombie", ForgeData=["compound", {"tb_x": ["byte", 1]}])}
        new_n = _snap(blocks={"minecraft:overworld 2 -60 0": "brickfurnace:brick_furnace[facing=south]"},
                      block_entities={"minecraft:overworld 8 -60 0": chest},
                      entities={U3: _entity("minecraft:wolf", "Swap Wolf"), U4: _entity("minecraft:zombie", "Swap Zombie")})
        f, n = root / "forbric", root / "native"
        for d, files in ((f, {"old": old_f, "neo-open": new_f}), (n, {"forge-reopen": old_n, "neo-open": new_n})):
            d.mkdir()
            for name, snap in files.items():
                (d / ("%s.snapshot.json" % name)).write_text(json.dumps(snap))
        return f, n

    def test_each_kind_of_difference_gets_its_verdict(self):
        with tempfile.TemporaryDirectory() as tmp:
            f, n = self._arms(Path(tmp))
            rows = {r["key"]: r for r in fs.classify(f, n)["rows"]}
        block = rows["blocks /minecraft:overworld 2 -60 0"]
        self.assertEqual(("lost", "kept", "FORBRIC-CAUSED", "brickfurnace"),
                         (block["forbric"], block["native"], block["verdict"], block["mod"]))
        caps = [r for k, r in rows.items() if "cap_familiar" in k]
        self.assertEqual(1, len(caps), list(rows))
        self.assertEqual(("rule", "rule", "MOD FORMAT (native loses it too)", "tombstone"),
                         (caps[0]["forbric"], caps[0]["native"], caps[0]["verdict"], caps[0]["mod"]))
        data = [r for k, r in rows.items() if "ForgeData" in k]
        self.assertEqual(1, len(data), list(rows))
        self.assertEqual(("kept", "rule", "FORBRIC KEEPS (native loses)"), (data[0]["forbric"], data[0]["native"], data[0]["verdict"]))
        self.assertFalse([k for k in rows if "8 -60 0" in k], list(rows))

    def test_a_key_native_cannot_have_is_judged_without_a_native_row(self):
        with tempfile.TemporaryDirectory() as tmp:
            f, n = self._arms(Path(tmp))
            old = json.loads((f / "old.snapshot.json").read_text())
            old["blocks"]["minecraft:overworld 1 -60 4"] = "classicpipes:copper_pipe"
            (f / "old.snapshot.json").write_text(json.dumps(old))
            rows = {r["key"]: r for r in fs.classify(f, n)["rows"]}
        pipe = rows["blocks /minecraft:overworld 1 -60 4"]
        self.assertEqual(("lost", "n/a", "NO NATIVE ROW (judged by the mod's code)"), (pipe["forbric"], pipe["native"], pipe["verdict"]))


    def test_the_player_comes_from_the_client_never_from_a_server_arm(self):
        with tempfile.TemporaryDirectory() as tmp:
            f, n = self._arms(Path(tmp))
            old = json.loads((f / "old.snapshot.json").read_text())
            player = ["compound", {"ForgeCaps": ["compound", {"tombstone:cap_tombstone": ["compound", {"knowledge": ["int", 8]}]}],
                                   "XpLevel": ["int", 23]}]
            old["players"] = {"00000000-0000-0000-0000-000000000000": player}
            (f / "old.snapshot.json").write_text(json.dumps(old))
            new = json.loads((f / "neo-open.snapshot.json").read_text())
            new["players"] = {"00000000-0000-0000-0000-000000000000": player}  # a server never loaded it
            (f / "neo-open.snapshot.json").write_text(json.dumps(new))
            self.assertFalse([r for r in fs.classify(f, n)["rows"] if "player" in r["key"]])
            client = Path(tmp) / "client.json"
            seen = copy.deepcopy(new)
            seen["players"] = {"00000000-0000-0000-0000-000000000000": ["compound", {"XpLevel": ["int", 23]}]}
            client.write_text(json.dumps(seen))
            rows = [r for r in fs.classify(f, n, client)["rows"] if "player" in r["key"]]
        self.assertEqual(1, len(rows), rows)
        self.assertEqual(("rule", "n/a", "NO NATIVE ROW (judged by the mod's code)", "tombstone"),
                         (rows[0]["forbric"], rows[0]["native"], rows[0]["verdict"], rows[0]["mod"]))


class WorldFiles(unittest.TestCase):
    def test_a_mods_own_files_are_kept_changed_or_lost(self):
        with tempfile.TemporaryDirectory() as tmp:
            old, new = Path(tmp) / "old", Path(tmp) / "new"
            for w in (old, new):
                (w / "packedup/backpacks").mkdir(parents=True); (w / "region").mkdir()
                (w / "region/r.0.0.mca").write_bytes(b"ignored")
            (old / "packedup/backpacks/inventory0.nbt").write_bytes(b"same")
            (new / "packedup/backpacks/inventory0.nbt").write_bytes(b"same")
            (old / "packedup/backpacks/inventory1.nbt").write_bytes(b"one")
            (new / "packedup/backpacks/inventory1.nbt").write_bytes(b"two")
            (old / "packedup/backpacks/inventory2.nbt").write_bytes(b"x")
            (new / "tombstone").mkdir(); (new / "tombstone/new.txt").write_text("n")
            rows = {r[0]: r[1] for r in fs.world_files(old, new, ws)}
        self.assertEqual({"packedup/backpacks/inventory0.nbt": "KEPT", "packedup/backpacks/inventory1.nbt": "CHANGED",
                          "packedup/backpacks/inventory2.nbt": "LOST", "tombstone/new.txt": "GAINED"}, rows)

    def test_typed_values_differ_by_path(self):
        a = ["compound", {"Items": ["list", "compound", [{"id": ["string", "x:a"], "count": ["int", 1]}]], "k": ["int", 1]}]
        b = ["compound", {"Items": ["list", "compound", [{"id": ["string", "x:a"], "count": ["int", 2]}]]}]
        self.assertEqual([("/Items[0]/count", ["int", 1], ["int", 2]), ("/k", ["int", 1], None)], fs._typed_diff(a, b))


class Configs(unittest.TestCase):
    def test_a_mark_is_kept_reset_or_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            inst = Path(tmp)
            for rel, key, default, _mark in fs.CONFIG_MARKS:
                (inst / rel).parent.mkdir(parents=True, exist_ok=True)
                (inst / rel).write_text("[a]\n    %s = %s\n" % (key, default))
            self.assertEqual([len(fs.CONFIG_MARKS)], [len(fs.mark_configs(inst))])
            self.assertEqual({"KEPT"}, {r[3] for r in fs.configs(inst)})
            rel, key, default, _ = fs.CONFIG_MARKS[0]
            (inst / rel).write_text("%s = %s\n" % (key, default))
            (inst / fs.CONFIG_MARKS[1][0]).unlink()
            self.assertEqual(["RESET", "MISSING", "KEPT"], [r[3] for r in fs.configs(inst)])
            with self.assertRaises(SystemExit):
                fs.mark_configs(inst)


class Datapack(unittest.TestCase):
    def test_the_kit_runs_once_after_a_grave_and_on_this_pack_format(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = fs.write_datapack(tmp)
            meta = json.loads((root / "pack.mcmeta").read_text())["pack"]
            self.assertEqual(fs.PACK_FORMAT, meta["max_format"])
            self.assertEqual(["forgeswap:tick"], json.loads((root / "data/minecraft/tags/function/tick.json").read_text())["values"])
            tick = (root / "data/forgeswap/function/tick.mcfunction").read_text()
            self.assertIn("matches 40 run function forgeswap:grave", tick)
            self.assertIn("matches 100 run function forgeswap:kit", tick)
            self.assertIn("kill @a", (root / "data/forgeswap/function/grave.mcfunction").read_text())
            self.assertIn("kit given", (root / "data/forgeswap/function/kit.mcfunction").read_text())


if __name__ == "__main__":
    unittest.main()
