#!/usr/bin/env python3
"""The Forge->NeoForge build swap (Stage 4 C5c): what a world written with a mod's MinecraftForge build keeps when the
player replaces that jar with the same mod's NeoForge build.

    forge-swap.py console                         the world-population console lines (old create, native create)
    forge-swap.py checks                          the in-game drill lines (old create, native create, every NeoForge open)
    forge-swap.py datapack <world>                write the singleplayer player-kit datapack into <world>/datapacks/
    forge-swap.py session --log L --cwd D [--console F]... [--env K=V]... -- COMMAND...
                                                  one server session: Done, the console lines, save-all flush, stop
    forge-swap.py mark-configs <instance>         write a non-default value into one config per candidate (CONFIG_MARKS)
    forge-swap.py configs <instance>              whether each mark is still there (KEPT) or the default is back (RESET)
    forge-swap.py drill <log>                     the drill's verdicts from a session log, one line per check
    forge-swap.py census <snapshot.json>          the loader-key census: every ForgeCaps / ForgeData / NeoForgeData /
                                                  neoforge:attachments subtree, with its path and keys
    forge-swap.py keys <pid> <key>...             post key presses to one process's window (macOS, CGEventPostToPid)
    forge-swap.py classify --forbric DIR --native DIR [--client SNAP] [--out F.json]
                                                  the per-mod loss table (L_F / L_N) from the two arms' snapshots, plus
                                                  each arm's mod world files and config marks
    forge-swap.py files <old-world> <new-world>   a mod's own world files (packedup/, tombstone/, ...): kept / changed / lost
    forge-swap.py bag-agent <dir>                 build ForgeSwapBagFill.java into <dir>/forge-swap-bagfill.jar (the packedup
                                                  bag step, a -javaagent for the old create, the native create and the old
                                                  singleplayer client); prints the jar's path
    forge-swap.py bags <world>                    what packedup's saved backpack inventories hold (packedup/backpacks/)
    forge-swap.py rewritten <old-world> <new-world>  whether each backpack inventory was saved again (the "read" proof)
    forge-swap.py tie <fixture>                   whether the fixture's native/ is the native arm of its old side
    forge-swap.py lastupdate <world> [--chunks B]  each probe chunk's LastUpdate (the re-save proof)
    forge-swap.py pin --forbric DIR --native DIR [--client SNAP] --tsv FILE
                                                  gate-m59's verdict: the Forbric arm's losses against expected-losses.tsv

WHY. A world a Forge build wrote holds the mod's data in places a NeoForge build may not read: MinecraftForge's own
capability storage (ForgeCaps) and persistent data (ForgeData) on entities, block entities and players, renamed ids,
and whatever else the mod's two builds disagree on. Some of that loss is the mod's own format and happens natively
too (MinecraftForge 26.2-65.0.1 -> NeoForge 26.2.0.93 with the same jars); any loss native does not have is
Forbric's. Both questions are answered from the saves on disk (world-snapshot.py), and the native one by
native-controls.py swap.

THE PROBE. One flat world (seed native-controls.SEED). The console script places each candidate's blocks, block
entities, chests of its items and its entities in chunks 0,0..1,1; every export uses `--chunks -2,-2,3,3` (BOX), which
also holds the spawn area, where the player phase's death leaves its tombstone grave. Those chunks are forceloaded, so
every phase loads and re-saves them. The drill runs in chunks 6..7 (x 96..127, outside the box, so its own blocks and
the items it crafts are never part of a diff), is cleared before and after, and asks the running game whether each
mod's blocks are still there, its items still in their slots, its block can be placed, its machine still works and its
recipe still crafts (a vanilla crafter powered by a redstone block). Each check is announced by
`say [forge-swap] check <name> expect <regex>` so `drill` can read the answer that follows it. The player half
(inventory, armour, ender chest, xp, tombstone's player data and effect, a grave, and a filled packedup backpack) needs
a singleplayer client: `datapack` writes a tick function that gives the player a kit once, and forge-swap-make-old.sh
drives the client.

THE BAGS. A packedup backpack's contents are not in the item: the stack carries only `packedup:inventory_id`, and the
stacks live in packedup/backpacks/inventory<id>.nbt, written by packedup's own save. packedup creates that inventory
only when a player opens the bag (server side, PackedUpCommon.openBackpackInventory), and no command or datapack
reaches it; key presses into the old client never filled one (9488359f). So ForgeSwapBagFill.java, a -javaagent, runs
the server-side half of "open the bag, move stacks in" with packedup's own classes: on the create (old Forbric and
native MinecraftForge alike) the obsidian backpack in BAG's chest gets the BAG_SOURCE chest's stacks, and in the old
singleplayer client the player's backpack (hotbar 0, the kit's) gets PLAYER_BAG_SOURCE's. `bags` reads the saved files
back; the drill's packedup.bag.kept asks the running game whether the bag still names its inventory.

CONFIG_MARKS puts one non-default value into one config per candidate, so a NeoForge build that
ignores the old file is visible.
"""
import argparse
import importlib.util
import json
import os
import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent


def _load(name, file):
    spec = importlib.util.spec_from_file_location(name, HERE / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# --- the probe --------------------------------------------------------------------------------------------------------

BOX = "-2,-2,3,3"
Y = -60  # the first air block of the default flat world (bedrock -64, dirt -63..-62, grass -61)

# Keep the probe still: no spawning, no weather, no random ticks, and midnight (an undead mob in daylight burns).
STILL = """
gamerule spawn_mobs false
gamerule spawn_monsters false
gamerule advance_time false
gamerule advance_weather false
gamerule random_tick_speed 0
time set 18000
forceload add -32 -32 63 63
forceload add 96 0 127 31
wait 3
"""

CONSOLE = STILL + """
# vanilla baseline: a damaged, named, enchanted sword and a full stack
setblock 0 -60 0 minecraft:chest[facing=south]{Items:[{Slot:0b,id:"minecraft:diamond_sword",count:1,components:{"minecraft:damage":17,"minecraft:custom_name":"Vanilla Sword","minecraft:enchantments":{"minecraft:sharpness":3}}},{Slot:1b,id:"minecraft:cobblestone",count:64}]}
# brickfurnace: one lit furnace (progress moves), two idle machines, a chest of its items
setblock 2 -60 0 brickfurnace:brick_furnace[facing=south,lit=true]{Items:[{Slot:0b,id:"minecraft:raw_iron",count:16},{Slot:1b,id:"minecraft:coal_block",count:4},{Slot:2b,id:"minecraft:iron_ingot",count:5}],cooking_time_spent:40s,cooking_total_time:200s,lit_time_remaining:15000s,lit_total_time:16000s}
setblock 4 -60 0 brickfurnace:brick_smoker[facing=south]{Items:[{Slot:0b,id:"minecraft:beef",count:5}]}
setblock 6 -60 0 brickfurnace:brick_blast_furnace[facing=south]{Items:[{Slot:2b,id:"minecraft:iron_ingot",count:7}]}
setblock 8 -60 0 minecraft:chest[facing=south]{Items:[{Slot:0b,id:"brickfurnace:brick_furnace",count:3},{Slot:1b,id:"brickfurnace:brick_smoker",count:1,components:{"minecraft:custom_name":"Named Smoker"}},{Slot:2b,id:"brickfurnace:brick_blast_furnace",count:2}]}
# classicpipes: chest -> pipes -> chest, a fluid pipe, a routing pipe, a chest of pipes
setblock 0 -60 4 minecraft:chest[facing=south]{Items:[{Slot:0b,id:"minecraft:cobblestone",count:64},{Slot:1b,id:"minecraft:oak_log",count:32}]}
setblock 1 -60 4 classicpipes:copper_pipe
setblock 2 -60 4 classicpipes:oak_pipe
setblock 3 -60 4 classicpipes:oak_pipe
setblock 4 -60 4 classicpipes:golden_pipe
setblock 5 -60 4 minecraft:chest[facing=south]
setblock 6 -60 4 classicpipes:copper_fluid_pipe
setblock 7 -60 4 classicpipes:routing_pipe
setblock 8 -60 4 minecraft:chest[facing=south]{Items:[{Slot:0b,id:"classicpipes:copper_pipe",count:16},{Slot:1b,id:"classicpipes:routing_pipe",count:2}]}
# packedup: backpacks as items; the bag chest's obsidian backpack is FILLED from the source chest by ForgeSwapBagFill on
# the create (BAG), the player's from the second source chest in the singleplayer phase (PLAYER_BAG_SOURCE)
setblock 0 -60 8 minecraft:chest[facing=south]{Items:[{Slot:0b,id:"packedup:basicbackpack",count:1},{Slot:1b,id:"packedup:ironbackpack",count:1,components:{"minecraft:custom_name":"Iron Pack"}}]}
setblock 2 -60 8 minecraft:chest[facing=south]{Items:[{Slot:0b,id:"packedup:obsidianbackpack",count:1,components:{"minecraft:custom_name":"Swap Bag"}}]}
setblock 4 -60 8 minecraft:chest[facing=south]{Items:[{Slot:0b,id:"minecraft:diamond",count:9},{Slot:1b,id:"minecraft:diamond_sword",count:1,components:{"minecraft:damage":5,"minecraft:custom_name":"Bag Sword","minecraft:enchantments":{"minecraft:sharpness":2}}},{Slot:2b,id:"brickfurnace:brick_furnace",count:2},{Slot:3b,id:"biomesoplenty:fir_log",count:16},{Slot:4b,id:"tombstone:grave_dust",count:5},{Slot:5b,id:"animalgarden_owl:mob_spawn_egg",count:1}]}
setblock 6 -60 8 minecraft:chest[facing=south]{Items:[{Slot:0b,id:"minecraft:emerald",count:7},{Slot:1b,id:"classicpipes:copper_pipe",count:4},{Slot:2b,id:"tombstone:white_marble",count:3}]}
# tombstone: a decorative grave, marble, a chest of its items, two mobs (one with its effect)
setblock 0 -60 12 tombstone:decorative_grave_simple[facing=south]
setblock 2 -60 12 tombstone:white_marble
setblock 3 -60 12 tombstone:dark_marble_stairs[facing=east]
setblock 5 -60 12 minecraft:chest[facing=south]{Items:[{Slot:0b,id:"tombstone:grave_dust",count:12},{Slot:1b,id:"tombstone:strange_scroll",count:1},{Slot:2b,id:"tombstone:scroll_of_knowledge",count:2},{Slot:3b,id:"tombstone:impregnated_diamond",count:1}]}
summon minecraft:zombie 8 -60 12 {PersistenceRequired:1b,NoAI:1b,CustomName:"Swap Zombie",Tags:["forge_swap"]}
summon minecraft:wolf 10 -60 12 {PersistenceRequired:1b,NoAI:1b,CustomName:"Swap Wolf"}
effect give @e[type=minecraft:wolf,name="Swap Wolf"] tombstone:silent_bond infinite 0 true
# BiomesOPlenty (+TerraBlender, GlitchCore): plain blocks, a chest of its items, its boat
setblock 0 -60 16 biomesoplenty:fir_log[axis=y]
setblock 1 -60 16 biomesoplenty:fir_planks
setblock 2 -60 16 biomesoplenty:white_sand
setblock 3 -60 16 biomesoplenty:rose_quartz_block
setblock 4 -60 16 biomesoplenty:fir_leaves[persistent=true]
setblock 6 -60 16 minecraft:chest[facing=south]{Items:[{Slot:0b,id:"biomesoplenty:fir_log",count:20},{Slot:1b,id:"biomesoplenty:rose_quartz_chunk",count:5},{Slot:2b,id:"biomesoplenty:fir_boat",count:1}]}
summon biomesoplenty:fir_boat 9 -60 17 {CustomName:"Swap Boat"}
# animalgarden-owl (+aquariuslibs): the mob and its egg
summon animalgarden_owl:owl 2 -60 20 {PersistenceRequired:1b,NoAI:1b,CustomName:"Swap Owl"}
setblock 4 -60 20 minecraft:chest[facing=south]{Items:[{Slot:0b,id:"animalgarden_owl:mob_spawn_egg",count:2}]}
wait 3
"""

# name | expected answer (regex) | command; a `wait N` row is a pause. The drill area is x 96..127, z 0..31 (chunks 6..7, 0..1).
_CHECKS = r"""
vanilla.chest.sword | Test passed\. Count: 1 | execute if items block 0 -60 0 container.0 minecraft:diamond_sword
brickfurnace.block.kept | Test passed | execute if block 2 -60 0 brickfurnace:brick_furnace
brickfurnace.blast.kept | Test passed | execute if block 6 -60 0 brickfurnace:brick_blast_furnace
brickfurnace.items.kept | Test passed\. Count: 3 | execute if items block 8 -60 0 container.0 brickfurnace:brick_furnace
brickfurnace.place | Changed the block | setblock 104 -60 0 brickfurnace:brick_furnace[facing=south]
setblock 106 -60 0 brickfurnace:brick_furnace[facing=south]{Items:[{Slot:0b,id:"minecraft:raw_iron",count:4},{Slot:1b,id:"minecraft:coal",count:2}],cooking_time_spent:195s,cooking_total_time:200s}
setblock 96 -60 4 minecraft:crafter[orientation=up_north]{Items:[{Slot:0b,id:"minecraft:brick",count:1},{Slot:1b,id:"minecraft:brick",count:1},{Slot:2b,id:"minecraft:brick",count:1},{Slot:3b,id:"minecraft:brick",count:1},{Slot:5b,id:"minecraft:brick",count:1},{Slot:6b,id:"minecraft:brick",count:1},{Slot:7b,id:"minecraft:brick",count:1},{Slot:8b,id:"minecraft:brick",count:1}]}
setblock 97 -60 4 minecraft:redstone_block
classicpipes.block.kept | Test passed | execute if block 1 -60 4 classicpipes:copper_pipe
classicpipes.items.kept | Test passed\. Count: 16 | execute if items block 8 -60 4 container.0 classicpipes:copper_pipe
classicpipes.place | Changed the block | setblock 108 -60 0 classicpipes:copper_pipe
packedup.items.kept | Test passed\. Count: 1 | execute if items block 0 -60 8 container.0 packedup:basicbackpack
packedup.bag.kept | Test passed\. Count: 1 | execute if items block 2 -60 8 container.0 packedup:obsidianbackpack[packedup:inventory_id]
setblock 100 -60 4 minecraft:crafter[orientation=up_north]{Items:[{Slot:0b,id:"minecraft:stick",count:1},{Slot:1b,id:"minecraft:string",count:1},{Slot:2b,id:"minecraft:stick",count:1},{Slot:3b,id:"minecraft:leather",count:1},{Slot:4b,id:"minecraft:chest",count:1},{Slot:5b,id:"minecraft:leather",count:1},{Slot:6b,id:"minecraft:leather",count:1},{Slot:7b,id:"minecraft:leather",count:1},{Slot:8b,id:"minecraft:leather",count:1}]}
setblock 101 -60 4 minecraft:redstone_block
tombstone.block.kept | Test passed | execute if block 0 -60 12 tombstone:decorative_grave_simple
tombstone.items.kept | Test passed\. Count: 12 | execute if items block 5 -60 12 container.0 tombstone:grave_dust
tombstone.place | Changed the block | setblock 110 -60 0 tombstone:white_marble
setblock 104 -60 4 minecraft:crafter[orientation=up_north]{Items:[{Slot:0b,id:"tombstone:grave_dust",count:1},{Slot:1b,id:"minecraft:stone",count:1},{Slot:2b,id:"minecraft:lapis_lazuli",count:1}]}
setblock 105 -60 4 minecraft:redstone_block
tombstone.zombie.kept | Test passed\. Count: 1 | execute if entity @e[type=minecraft:zombie,name="Swap Zombie"]
biomesoplenty.block.kept | Test passed | execute if block 0 -60 16 biomesoplenty:fir_log
biomesoplenty.items.kept | Test passed\. Count: 20 | execute if items block 6 -60 16 container.0 biomesoplenty:fir_log
biomesoplenty.boat.kept | Test passed\. Count: 1 | execute if entity @e[type=biomesoplenty:fir_boat]
biomesoplenty.place | Changed the block | setblock 112 -60 0 biomesoplenty:white_sand
animalgarden_owl.owl.kept | Test passed\. Count: 1 | execute if entity @e[type=animalgarden_owl:owl,name="Swap Owl"]
animalgarden_owl.egg.kept | Test passed\. Count: 2 | execute if items block 4 -60 20 container.0 animalgarden_owl:mob_spawn_egg
wait 4
brickfurnace.use | Test passed | execute if items block 106 -60 0 container.2 minecraft:iron_ingot
brickfurnace.craft | Test passed\. Count: 1 | execute if entity @e[type=minecraft:item,x=96,y=-60,z=4,distance=..3,nbt={Item:{id:"brickfurnace:brick_furnace"}}]
packedup.craft | Test passed\. Count: 1 | execute if entity @e[type=minecraft:item,x=100,y=-60,z=4,distance=..3,nbt={Item:{id:"packedup:basicbackpack"}}]
tombstone.craft | Test passed\. Count: 1 | execute if entity @e[type=minecraft:item,x=104,y=-60,z=4,distance=..3,nbt={Item:{id:"tombstone:blue_marble"}}]
"""


def checks():
    """The drill as (name, expect, command) rows; name None for a plain console line or a wait."""
    rows = []
    for raw in _CHECKS.strip().splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [p.strip() for p in line.split(" | ", 2)]
        rows.append(tuple(parts) if len(parts) == 3 else (None, None, line))
    return rows


def checks_console():
    # The drill area is cleared first: a world the drill already ran in (the old create, the native create) keeps its
    # crafters, redstone blocks and placed blocks, and `setblock` of a block already there answers "Could not set".
    out = ["forceload add -32 -32 63 63", "forceload add 96 0 127 31", "wait 2", "fill 96 -60 0 127 -50 31 minecraft:air",
           "kill @e[type=minecraft:item]", "wait 1"]
    for name, expect, command in checks():
        if name:
            out.append("say [forge-swap] check %s expect %s" % (name, expect))
        out.append(command)
    # ...and cleared again afterwards, so the items it crafted (which can drift into the probe's chunks) and its blocks
    # are in no snapshot.
    return out + ["kill @e[type=minecraft:item]", "fill 96 -60 0 127 -50 31 minecraft:air", "wait 1"]


def console_text():
    return "\n".join(l.strip() for l in CONSOLE.strip().splitlines()) + "\n"


_MARK = re.compile(r"\[forge-swap\] check (\S+) expect (.+?)\s*$")


def drill(text):
    """[(name, expect, verdict, answer)] for every announced check in a console log; verdict PASS or FAIL."""
    lines = text.splitlines()
    found = []
    for i, line in enumerate(lines):
        m = _MARK.search(line)
        if not m:
            continue
        name, expect = m.group(1), m.group(2)
        answer = ""
        for later in lines[i + 1:i + 40]:
            if _MARK.search(later):
                break
            # The command's own answer: the next server line that is not the echo of a `say`.
            if re.search(r"\]: ", later) and "[forge-swap]" not in later and not later.startswith("\t"):
                body = later.split("]: ", 1)[1]
                if re.search(r"Test (passed|failed)|Changed the block|Could not set the block|No entity was found|"
                             r"Unknown|Incorrect argument|Expected|Error|Found no elements", body):
                    answer = body
                    break
        found.append((name, expect, "PASS" if re.search(expect, answer) else "FAIL", answer))
    return found



def drill_names():
    """The named checks of this tree's drill, in order."""
    return [name for name, _expect, _command in checks() if name]


def drill_missing(rows):
    """The drill's named checks that no announce line in the log answered (review A-2): `drill` counts only the checks
    whose announce line reached the log, so a truncated console or a lost announce line shrinks the drill with no FAIL."""
    seen = {row[0] for row in rows}
    return [name for name in drill_names() if name not in seen]

# --- config files ----------------------------------------------------------------------------------------------------

# One non-default value per candidate config, written into the old instance after the create (relative path, key, the
# default the Forge build wrote, the mark). A NeoForge build that reads the old file keeps the mark; one that ignores
# or resets it shows the default again. brickfurnace's is a server config, so it lives in the world (serverconfig/).
CONFIG_MARKS = (
    ("world/serverconfig/brickfurnace-server.toml", "cookTimeFactor", "1.0", "1.25"),
    ("config/packedup-common.toml", "maxBagInBagLayer", "-1", "3"),
    ("config/tombstone-common.toml", "sniffer_range", "5", "7"),
)


def _config_value(path, key):
    if not Path(path).is_file():
        return None
    for line in Path(path).read_text(errors="replace").splitlines():
        m = re.match(r"\s*%s\s*=\s*(.*?)\s*$" % re.escape(key), line)
        if m:
            return m.group(1)
    return None


def mark_configs(instance):
    """Write each CONFIG_MARKS mark over the default; returns [(rel, key, before, after)]. A file or default that is not
    there is an error: the probe would then measure nothing."""
    done = []
    for rel, key, default, mark in CONFIG_MARKS:
        path = Path(instance) / rel
        if not path.is_file():
            raise SystemExit("[forge-swap] cannot mark %s: the file is not there" % rel)
        text = path.read_text()
        new, n = re.subn(r"(?m)^(\s*%s\s*=\s*)%s\s*$" % (re.escape(key), re.escape(default)), lambda m: m.group(1) + mark, text)
        if n != 1:
            raise SystemExit("[forge-swap] cannot mark %s %s: %d line(s) read %s = %s" % (rel, key, n, key, default))
        path.write_text(new)
        done.append((rel, key, default, mark))
    return done


def configs(instance):
    """[(rel, key, value now, verdict)]: KEPT when the mark survived, RESET when the default is back, else what is there."""
    rows = []
    for rel, key, default, mark in CONFIG_MARKS:
        value = _config_value(Path(instance) / rel, key)
        verdict = "KEPT" if value == mark else "RESET" if value == default else "MISSING" if value is None else "OTHER"
        rows.append((rel, key, value, verdict))
    return rows


# --- the singleplayer player kit -------------------------------------------------------------------------------------

PACK_FORMAT = 107  # 26.2 data_major (version.json pack_version)
KIT_LOAD = "scoreboard objectives add forge_swap dummy\n"
KIT_TICK = """execute if entity @a run scoreboard players add #t forge_swap 1
execute if score #t forge_swap matches 40 run function forgeswap:grave
execute if score #t forge_swap matches 100 run function forgeswap:kit
"""
KIT_GRAVE = """gamerule immediate_respawn true
gamerule keep_inventory false
clear @a
give @a minecraft:iron_sword[minecraft:custom_name="Grave Sword"] 1
give @a tombstone:grave_dust 3
give @a brickfurnace:brick_smoker 2
kill @a
say [forge-swap] kit grave done
"""
KIT = """item replace entity @a hotbar.0 with packedup:obsidianbackpack
item replace entity @a hotbar.1 with brickfurnace:brick_furnace 4
item replace entity @a hotbar.2 with biomesoplenty:fir_log 16
item replace entity @a hotbar.3 with tombstone:scroll_of_knowledge 1
item replace entity @a hotbar.4 with classicpipes:copper_pipe 8
item replace entity @a hotbar.5 with minecraft:diamond 9
item replace entity @a inventory.0 with animalgarden_owl:mob_spawn_egg 1
item replace entity @a armor.head with minecraft:iron_helmet[minecraft:damage=10]
item replace entity @a armor.chest with minecraft:diamond_chestplate[minecraft:enchantments={"minecraft:protection":2}]
item replace entity @a weapon.offhand with minecraft:shield
item replace entity @a enderchest.0 with tombstone:grave_dust 7
item replace entity @a enderchest.1 with brickfurnace:brick_smoker 1
xp set @a 23 levels
effect give @a tombstone:silent_bond infinite 0 true
tbknowledge @p increase 7
tbalignment @p increase 120
say [forge-swap] kit given
"""


def write_datapack(world):
    root = Path(world) / "datapacks/forge-swap"
    files = {
        "pack.mcmeta": json.dumps({"pack": {"description": "forge-swap player kit (forge-swap.py)",
                                            "min_format": [PACK_FORMAT, 1], "max_format": PACK_FORMAT}}, indent=2),
        "data/minecraft/tags/function/load.json": json.dumps({"values": ["forgeswap:load"]}),
        "data/minecraft/tags/function/tick.json": json.dumps({"values": ["forgeswap:tick"]}),
        "data/forgeswap/function/load.mcfunction": KIT_LOAD,
        "data/forgeswap/function/tick.mcfunction": KIT_TICK,
        "data/forgeswap/function/grave.mcfunction": KIT_GRAVE,
        "data/forgeswap/function/kit.mcfunction": KIT,
    }
    for rel, text in files.items():
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    return root


# --- the packedup bags (see THE BAGS above) --------------------------------------------------------------------------

BAG = "2,-60,8"                 # the chest whose slot 0 holds the obsidian backpack the create fills
BAG_SOURCE = "4,-60,8"          # ...from this chest
PLAYER_BAG_SOURCE = "6,-60,8"   # the player's backpack (hotbar 0, the kit's) is filled from this one in the client phase
BAG_AGENT_SOURCE = HERE / "ForgeSwapBagFill.java"


def bag_agent_args(target, source):
    return "bag=%s;source=%s" % (target, source)


def build_bag_agent(out_dir):
    """Compile ForgeSwapBagFill.java (no dependencies) into <out_dir>/forge-swap-bagfill.jar with Premain-Class."""
    import subprocess
    out_dir = Path(out_dir).resolve()
    classes = out_dir / "bagfill-classes"
    classes.mkdir(parents=True, exist_ok=True)
    subprocess.run(["javac", "-d", str(classes), str(BAG_AGENT_SOURCE)], check=True)
    manifest = out_dir / "bagfill-manifest.txt"
    manifest.write_text("Premain-Class: ForgeSwapBagFill\n")
    jar = out_dir / "forge-swap-bagfill.jar"
    subprocess.run(["jar", "--create", "--file", str(jar), "--manifest", str(manifest), "-C", str(classes), "."], check=True)
    return jar


BAG_FILLED = re.compile(r"\[forge-swap\] bag filled: (\S+(?: slot 0)?) inventory_id=(\d+) type=(\S+) stacks=(\d+)")


def bags(world, ws=None):
    """{file name: [(slot, item id, count, sorted component names)]} for every packedup/backpacks/inventory*.nbt, as
    packedup's own save wrote it ({"stacks": n, "stack<i>": item, ...}; an empty slot is an empty or air entry)."""
    ws = ws or _load("world_snapshot", "world-snapshot.py")
    out = {}
    for path in sorted((Path(world) / "packedup/backpacks").glob("*.nbt")):
        root = ws.read_nbt_file(path)
        entries = root[1] if isinstance(root, list) and root[:1] == ["compound"] else {}
        stacks = []
        for key in sorted(entries, key=lambda k: (len(k), k)):
            m = re.fullmatch(r"stack(\d+)", key)
            item = entries[key][1] if m and entries[key][:1] == ["compound"] else None
            if not item or "id" not in item:
                continue
            ident = item["id"][1]
            if ident == "minecraft:air":
                continue
            count = item.get("count", ["int", 1])[1]
            components = sorted((item.get("components") or ["compound", {}])[1])
            stacks.append((int(m.group(1)), ident, count, components))
        out[path.name] = stacks
    return out



def rewritten(old_world, new_world):
    """[(file, verdict, old mtime ns, new mtime ns)] for every packedup/backpacks/*.nbt the old world has: REWRITTEN when
    the new world's copy was saved after the old one, NOT REWRITTEN when it still has the old file's time, LOST when it
    is gone. Both clones the gate makes keep the old file's time (cp -c -R, and copytree's copy2), so only a save moves
    it. Measured (review A-1): the NeoForge open rewrites both inventories, the player's too, which no player opened on
    the server, so packedup loads every inventory with the world and writes every loaded one on a save. A file that is
    REWRITTEN and still holds the same stacks (gate-m59's same_bags) is one packedup read; a NeoForge side that never
    loaded packedup leaves every file byte-identical, which `files` and same_bags alone call KEPT."""
    old_dir, new_dir = Path(old_world) / "packedup/backpacks", Path(new_world) / "packedup/backpacks"
    rows = []
    for path in sorted(old_dir.glob("*.nbt")):
        target = new_dir / path.name
        old_ns = path.stat().st_mtime_ns
        if not target.is_file():
            rows.append((path.name, "LOST", old_ns, None))
            continue
        new_ns = target.stat().st_mtime_ns
        rows.append((path.name, "REWRITTEN" if new_ns > old_ns else "NOT REWRITTEN", old_ns, new_ns))
    return rows


# --- the fixture's two halves ----------------------------------------------------------------------------------------

def _manifest(path):
    """{first word: [rest of line, ...]} of a fixture MANIFEST (comments skipped)."""
    out = {}
    for raw in Path(path).read_text().splitlines():
        if raw.strip() and not raw.startswith("#"):
            key, _, rest = raw.partition(" ")
            out.setdefault(key, []).append(rest.strip())
    return out


def _sha256(path):
    import hashlib
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _stem(jar):
    """A mod jar's name before its loader (-forge / -neoforge), shared by the mod's two builds."""
    return re.split(r"-(?:neo)?forge", jar, maxsplit=1)[0]


def _namespace(state):
    return state.split(":", 1)[0] if state and ":" in state else None


def tie(fixture, native_create=None):
    """[(check, ok, detail)]: whether the fixture's native/ is the native arm of the old side beside it (review A-3).

    The MANIFEST names the old side's S0; nothing in native/ named it, so a native/ from another console, seed or mod
    set would be pinned against without a word. Asked from what both halves recorded:
      seed, box     native/inputs.json against MANIFEST's seed and box;
      console       (a MANIFEST that names create-console-sha256) the native create ran the same console script;
      neo mods      the native NeoForge jars are, by SHA-256, exactly the fixture's mods-neo/ (the jars the open uses);
      forge mods    the native MinecraftForge jars are fixture mods-forge/ jars (it may lack one native cannot load);
      s0            S0 holds every block the old side's create placed (old-create.snapshot.json), more only where
                    its player phase ran (MANIFEST client 1);
      placed        the old side's create (old-create.snapshot.json, before its player phase) and the native create
                    (native/forge-create.snapshot.json, or native_create) hold the same blocks and block entities in
                    the box, except blocks of a namespace the native create has none of (a mod native cannot load).
                    Both came from forge-swap.py's console on native-controls' seed, so a different console is red here."""
    fixture = Path(fixture)
    manifest = _manifest(fixture / "MANIFEST")
    inputs = json.loads((fixture / "native/inputs.json").read_text())
    rows = []
    seed = (manifest.get("seed") or [""])[0]
    rows.append(("seed", str(inputs.get("seed")) == seed, "native %s, MANIFEST %s" % (inputs.get("seed"), seed)))
    box = (manifest.get("box") or [""])[0]
    nbox = ",".join(str(v) for v in inputs.get("chunks") or [])
    rows.append(("box", nbox == box, "native %s, MANIFEST %s" % (nbox, box)))
    forge = {_sha256(j): j.name for j in sorted((fixture / "mods-forge").glob("*.jar"))}
    nforge = {r["sha256"]: r["name"] for r in inputs.get("forgeMods") or []}
    lacks = sorted(n for s, n in forge.items() if s not in nforge)
    rows.append(("forge mods", bool(nforge) and set(nforge) <= set(forge), "%d native, %s in mods-forge/; native lacks: %s" % (
        len(nforge), "all" if set(nforge) <= set(forge) else "NOT all (%s)" % ", ".join(
            n for s, n in sorted(nforge.items()) if s not in forge), ", ".join(lacks) or "none")))
    neo = {_sha256(j): j.name for j in sorted((fixture / "mods-neo").glob("*.jar"))}
    nneo = {r["sha256"]: r["name"] for r in inputs.get("neoMods") or []}
    # The native arm leaves out of its NeoForge set exactly the mods it left out of its MinecraftForge set (a mod's two
    # builds share the name before -forge / -neoforge).
    neo_lacks = sorted(n for s, n in neo.items() if s not in nneo)
    rows.append(("neo mods", bool(nneo) and set(nneo) <= set(neo) and {_stem(n) for n in neo_lacks} == {_stem(n) for n in lacks},
                 "%d native, %s in mods-neo/; native lacks: %s" % (len(nneo), "all" if set(nneo) <= set(neo) else "NOT all (%s)" % ", ".join(
                     n for s, n in sorted(nneo.items()) if s not in neo), ", ".join(neo_lacks) or "none")))
    console = (manifest.get("create-console-sha256") or [None])[0]
    if console:  # a MANIFEST written since review A-3 names the console its create ran
        nconsole = (inputs.get("console") or {}).get("sha256")
        rows.append(("console", nconsole == console, "native %s, MANIFEST %s" % (nconsole, console)))
    old = json.loads((fixture / "old-create.snapshot.json").read_text())
    s0 = json.loads((fixture / "old-snapshot.json").read_text())
    # S0 is the old create after its player phase and A/A: it holds every block the create placed as placed, and
    # more only where the player phase ran (MANIFEST client 1; its grave).
    client = (manifest.get("client") or ["0"])[0] == "1"
    moved = [k for k in sorted(set(old.get("blocks", {})) | set(s0.get("blocks", {})))
             if old["blocks"].get(k) != s0["blocks"].get(k) and not (client and k not in old["blocks"])]
    added = [k for k in s0.get("blocks", {}) if k not in old.get("blocks", {})]
    rows.append(("s0", not moved, "S0 keeps every block the old create placed (%d differ%s); %d added after it%s" % (
        len(moved), "".join("; %s: %s / %s" % (k, old["blocks"].get(k), s0["blocks"].get(k)) for k in moved[:4]),
        len(added), " by the player phase" if client else "")))
    nat = json.loads(Path(native_create or fixture / "native/forge-create.snapshot.json").read_text())
    native_ns = {_namespace(s) for s in nat.get("blocks", {}).values()}
    bad, skipped = [], {}
    for key in sorted(set(old.get("blocks", {})) | set(nat.get("blocks", {}))):
        a, b = old["blocks"].get(key), nat["blocks"].get(key)
        if a == b:
            continue
        if b is None and _namespace(a) not in native_ns:
            skipped[_namespace(a)] = skipped.get(_namespace(a), 0) + 1
            continue
        bad.append("%s: %s / %s" % (key, a, b))
    for key in sorted(set(old.get("block_entities", {})) ^ set(nat.get("block_entities", {}))):
        if key in old.get("block_entities", {}) and key not in nat.get("block_entities", {}) \
                and _namespace(old["blocks"].get(key)) not in native_ns:
            continue
        bad.append("%s: block entity only in the %s create" % (key, "old" if key in old.get("block_entities", {}) else "native"))
    rows.append(("placed", not bad, "%d block(s) differ%s%s" % (len(bad), "".join("; " + d for d in bad[:4]),
                 "; set aside (no native block of that namespace): " + ", ".join("%s x%d" % kv for kv in sorted(skipped.items()))
                 if skipped else "")))
    return rows


def lastupdate(world, box=BOX):
    """{"CX CZ": LastUpdate} for every overworld chunk in the box (the re-save proof: a chunk a phase saved has a
    later game time than the one before it)."""
    wp = _load("world_parity", "world-parity.py")
    ws = _load("world_snapshot", "world-snapshot.py")
    x0, z0, x1, z1 = (int(v) for v in box.split(","))
    out = {}
    overworld = ws.dimensions(Path(world)).get("minecraft:overworld")
    if overworld is None:
        return out
    for cx, cz, root in wp.chunks_typed(overworld / "region"):
        if x0 <= cx <= x1 and z0 <= cz <= z1:
            value = root[1].get("LastUpdate") if isinstance(root, list) else root.get("LastUpdate")
            if value is not None:
                out["%d %d" % (cx, cz)] = value[1]
    return out


# --- gate-m59's pin: expected-losses.tsv --------------------------------------------------------------------------

LOSS_KINDS = ("lost", "rule")
TSV_COLUMNS = ("key", "mod", "verdict", "why")


def read_tsv(path):
    """[{key, mod, verdict, why}] from expected-losses.tsv (tab-separated, `#` comments, header row `key\tmod\t...`)."""
    rows = []
    for raw in Path(path).read_text().splitlines():
        if not raw.strip() or raw.startswith("#"):
            continue
        cells = raw.split("\t")
        if cells[0] == "key":
            continue
        if len(cells) != len(TSV_COLUMNS):
            raise ValueError("expected-losses.tsv: %d cells, want %d: %r" % (len(cells), len(TSV_COLUMNS), raw))
        rows.append(dict(zip(TSV_COLUMNS, cells)))
    return rows


def pin(table, expected):
    """gate-m59's three verdicts on a classify() table against expected-losses.tsv rows:
    unpinned  a Forbric loss (its own row lost or rule) that no TSV row names;
    stale     a TSV row whose key Forbric no longer loses, or loses under another verdict (a row may only stay while
              it is measured exactly as pinned);
    changed   a value Forbric changes that native keeps (FORBRIC CHANGED) or, with no native row, a value move of a
              key no TSV row names (NO NATIVE ROW, a value moved) - a Forbric-caused change that is not a loss."""
    by_key = {row["key"]: row for row in table["rows"]}
    pinned = {row["key"]: row for row in expected}
    lost = {k: r for k, r in by_key.items() if r["forbric"] in LOSS_KINDS}
    unpinned = sorted(k for k in lost if k not in pinned)
    stale = sorted(k for k, row in pinned.items() if k not in lost or lost[k]["verdict"] != row["verdict"])
    changed = sorted(k for k, r in by_key.items() if k not in pinned and k not in lost
                     and r["verdict"] in (FORBRIC_CHANGED, NO_NATIVE_CHANGED))
    return {"unpinned": unpinned, "stale": stale, "changed": changed,
            "lost": sorted(lost), "verdicts": {k: lost[k]["verdict"] for k in sorted(lost)}}


# --- gate-m59's controls (each breaks one thing in a world AFTER the NeoForge open; the gate must go red on it) -----

def drop_slot(world, x, y, z, slot):
    """Remove the stack in `slot` of the container block entity at x y z (overworld) from the region file: a key the
    swap kept, lost. Returns what was dropped, or None when nothing matched."""
    ws = _load("world_snapshot", "world-snapshot.py")
    wp = _load("world_parity", "world-parity.py")
    cx, cz = x >> 4, z >> 4
    region = ws.dimensions(Path(world))["minecraft:overworld"] / "region"
    for c1, c2, root in wp.chunks_typed(region):
        if (c1, c2) != (cx, cz):
            continue
        for be in ws.get(root, "block_entities") or []:
            if (be["x"][1], be["y"][1], be["z"][1]) != (x, y, z):
                continue
            items = be.get("Items", ["list", "compound", []])[2]
            for item in list(items):
                if item.get("Slot", ["byte", -1])[1] == slot:
                    items.remove(item)
                    ws.rewrite_chunk(region / ("r.%d.%d.mca" % (cx >> 5, cz >> 5)), cx, cz, root)
                    return "%s*%s" % (item["id"][1], item.get("count", ["int", 1])[1])
    return None


def drop_pack(world, pack):
    """Take `pack` out of level.dat's enabled data packs (gzip NBT, written back). True when it was there."""
    import gzip as _gzip
    ws = _load("world_snapshot", "world-snapshot.py")
    path = Path(world) / "level.dat"
    root = ws.read_nbt_file(path)
    data = root[1]["Data"][1]
    packs = data.get("DataPacks", ["compound", {}])[1]
    enabled = packs.get("Enabled", ["list", "string", []])
    if pack not in enabled[2]:
        return False
    enabled[2].remove(pack)
    path.write_bytes(_gzip.compress(ws.encode_root(root)))
    return True



def drop_bag_stack(world, name, slot):
    """Remove stack<slot> from packedup/backpacks/<name>, written back with the file's own compression (packedup writes
    it raw; gzip is kept if found). Returns the item id dropped, or None when the slot is empty or missing."""
    import gzip as _gzip
    ws = _load("world_snapshot", "world-snapshot.py")
    path = Path(world) / "packedup/backpacks" / name
    data = path.read_bytes()
    root = ws.read_nbt_file(path)
    entry = root[1].pop("stack%d" % slot, None) if root else None
    if not entry or entry[:1] != ["compound"] or "id" not in entry[1]:
        return None
    raw = ws.encode_root(root)
    path.write_bytes(_gzip.compress(raw) if data[:2] == b"\x1f\x8b" else raw)
    return entry[1]["id"][1]


def restore_bags(old_world, new_world):
    """Put the old world's packedup/backpacks/*.nbt back over the new world's, bytes and time (copy2): what a NeoForge
    side that never loaded packedup would leave. Returns the names put back."""
    import shutil
    done = []
    for path in sorted((Path(old_world) / "packedup/backpacks").glob("*.nbt")):
        target = Path(new_world) / "packedup/backpacks" / path.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        done.append(path.name)
    return done


def enabled_packs(world):
    ws = _load("world_snapshot", "world-snapshot.py")
    root = ws.read_nbt_file(Path(world) / "level.dat")
    data = root[1]["Data"][1]
    return list(data.get("DataPacks", ["compound", {}])[1].get("Enabled", ["list", "string", []])[2])


# --- the loader-key census -------------------------------------------------------------------------------------------

LOADER_KEYS = ("ForgeCaps", "ForgeData", "NeoForgeData", "neoforge:attachments", "fabric:attachments")


def census(snapshot):
    """{facet/holder/.../key: sorted child keys} for every loader-owned subtree anywhere in the snapshot."""
    out = {}

    def visit(value, path):
        if isinstance(value, list) and len(value) >= 2 and value[0] == "compound" and isinstance(value[1], dict):
            for k, v in value[1].items():
                if k in LOADER_KEYS:
                    inner = v[1] if isinstance(v, list) and len(v) >= 2 and v[0] == "compound" else {}
                    out[path + "/" + k] = sorted(inner) if isinstance(inner, dict) else []
                visit(v, path + "/" + k)
        elif isinstance(value, list) and len(value) == 3 and value[0] == "list":
            for i, item in enumerate(value[2]):
                if value[1] == "compound":
                    visit(["compound", item], "%s[%d]" % (path, i))
        elif isinstance(value, dict):
            for k, v in value.items():
                visit(v, path + "/" + k)
    for facet in ("level", "block_entities", "chunk_extra", "entities", "players", "saved_data"):
        visit(snapshot.get(facet), facet)
    return out


# --- key presses -----------------------------------------------------------------------------------------------------

# macOS virtual key codes (Carbon kVK_ANSI_*) for the keys the client drill uses.
KEYCODES = {"o": 31, "1": 18, "2": 19, "3": 20, "4": 21, "5": 23, "6": 22, "escape": 53, "e": 14, "return": 36, "f2": 120,
            "kp7": 89}


def post_keys(pid, keys, gap=0.6):
    """Key down + up for each key, to that process's window only (keyboard events reach a background MC window; mouse
    clicks do not). A key "+N" waits N seconds instead."""
    import Quartz  # pyobjc; macOS only
    for key in keys:
        if key.startswith("+"):
            time.sleep(float(key[1:]))  # "+2" waits two seconds (a screen opening, a round trip to the server)
            continue
        code = KEYCODES[key.lower()]
        for down in (True, False):
            event = Quartz.CGEventCreateKeyboardEvent(None, code, down)
            Quartz.CGEventPostToPid(int(pid), event)
            time.sleep(0.08)
        time.sleep(gap)


# --- one server session ----------------------------------------------------------------------------------------------

def session(command, cwd, log, consoles=(), env_pairs=(), timeout=900):
    nc = _load("native_controls", "native-controls.py")
    lines = []
    for c in consoles:
        lines += nc.console_lines(c)
    env = os.environ.copy()
    for pair in env_pairs:
        k, _, v = pair.partition("=")
        if v == "" and pair.endswith("="):
            env.pop(k, None)
        else:
            env[k] = v
    return nc.swap_session(command, cwd, env, log, lines, timeout)


# --- one client session ----------------------------------------------------------------------------------------------

CRASHED = "#@!@# Game crashed!"


def _java_pid(rundir):
    """The game JVM whose command line names --gameDir <rundir> (the launcher execs java after its own Gradle step)."""
    import subprocess
    out = subprocess.run(["pgrep", "-f", "--", "--gameDir " + str(rundir)], capture_output=True, text=True).stdout.split()
    for pid in out:
        comm = subprocess.run(["ps", "-o", "comm=", "-p", pid], capture_output=True, text=True).stdout.strip()
        if comm.endswith("java"):
            return int(pid)
    return None


def client_session(command, env, log, rundir, triggers=(), timeout=900):
    """Run a client to its own end (KernelClientSmoke's clean disconnect stops it). Each trigger is (regex, keys): the
    first time regex appears in the log, the keys are posted to the game window. A crash ends it 5 s later."""
    import signal
    import subprocess
    started = time.monotonic(); fired = []; crashed = None; timed_out = False
    pending = list(triggers)
    with Path(log).open("w") as output:
        process = subprocess.Popen(command, env=env, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        while process.poll() is None:
            text = Path(log).read_text(errors="replace")
            if CRASHED in text and crashed is None:
                crashed = time.monotonic()
            for trig in list(pending):
                if re.search(trig[0], text):
                    pid = _java_pid(rundir)
                    if pid:
                        post_keys(pid, trig[1])
                        fired.append({"on": trig[0], "keys": list(trig[1]), "pid": pid, "at": round(time.monotonic() - started, 1)})
                        pending.remove(trig)
            if (crashed and time.monotonic() - crashed > 5) or time.monotonic() - started > timeout:
                timed_out = crashed is None
                os.killpg(process.pid, signal.SIGKILL); process.wait(); break
            time.sleep(0.5)
    text = Path(log).read_text(errors="replace")
    return {"exitCode": process.returncode, "crashed": crashed is not None, "timedOut": timed_out,
            "cleanDisconnect": "clean disconnect observed" in text, "keys": fired, "unfired": [t[0] for t in pending],
            "seconds": round(time.monotonic() - started, 1)}


# --- classification --------------------------------------------------------------------------------------------------

# Every namespace a candidate brings (mod ids, plus what their content is registered under).
MODS = ("brickfurnace", "classicpipes", "packedup", "supermartijn642corelib", "supermartijn642configlib", "tombstone",
        "biomesoplenty", "terrablender", "glitchcore", "journeymap", "animalgarden_owl", "aquarius_libs")
_UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
_NS = re.compile(r"([a-z0-9_.-]+):[a-z0-9_./-]+")


def _labels(snapshot):
    """uuid -> a label stable across worlds ("minecraft:wolf 'Swap Wolf'"), for entities and players."""
    out = {}
    for key, value in snapshot.get("entities", {}).items():
        nbt = value.get("nbt", ["compound", {}])[1]
        ident = (nbt.get("id") or ["string", "?"])[1]
        name = nbt.get("CustomName")
        out[key] = "%s%s" % (ident, " '%s'" % name[1] if name and name[0] == "string" else "")
    for key in snapshot.get("players", {}):
        out[key] = "player"
    return out


def _mods_named(*values):
    found = set()
    for value in values:
        text = value if isinstance(value, str) else json.dumps(value)
        for ns in _NS.findall(text):
            if ns in MODS:
                found.add(ns)
        for ns in MODS:
            if "/data/" + ns + "/" in text or (ns == "packedup" and "packedup/backpacks" in text):
                found.add(ns)
    return found


# Lists whose elements are named by one of their own keys, not by their position: an inventory slot, an effect, an
# attribute or a modifier by its id. world-snapshot.py's diff compares a list of another length as one value and a
# list of the same length index by index, so a lost slot, effect or attribute came out as one CHANGED row of the whole
# list (review A-2: the player's `silent_bond` effect and `forge:swim_speed` attribute were found by hand). losses()
# re-diffs these lists element by element; an element is named `[Slot=3]` / `[id=minecraft:speed]` in the key.
KEYED_LISTS = {"Items": "Slot", "Inventory": "Slot", "EnderItems": "Slot", "active_effects": "id", "attributes": "id",
               "modifiers": "id"}
_KEYED_SEGMENT = re.compile(r"/(%s)(?=$|\[)" % "|".join(KEYED_LISTS))
_MISSING = object()


def _compound(value):
    """The entries of a typed compound or a plain dict (a snapshot's facet or holder), else None."""
    if isinstance(value, list) and len(value) >= 2 and value[0] == "compound" and isinstance(value[1], dict):
        return value[1]
    if isinstance(value, dict):
        return value
    return None


def _element_key(item, field):
    v = item.get(field) if isinstance(item, dict) else None
    return str(v[1]) if isinstance(v, list) and len(v) == 2 and not isinstance(v[1], (list, dict)) else None


def _keyed(value, field):
    """{key: element} for a typed list of compounds whose every element has a distinct `field`, else None."""
    if not (isinstance(value, list) and len(value) == 3 and value[0] == "list" and value[1] == "compound"):
        return None
    out = {}
    for item in value[2]:
        k = _element_key(item, field)
        if k is None or k in out:
            return None
        out[k] = item
    return out


def _get(value, path):
    """The value at a diff path (world-snapshot.py's `/key` and `[i]`, and this file's `[field=key]` segments), or
    _MISSING. A holder's key can hold `/` (a SavedData file), so every key the path continues from is tried."""
    if path == "":
        return value
    entries = _compound(value)
    if entries is not None and path.startswith("/"):
        rest = path[1:]
        for k in sorted(entries, key=len, reverse=True):
            if rest == k or rest.startswith(k + "/") or rest.startswith(k + "["):
                found = _get(entries[k], rest[len(k):])
                if found is not _MISSING:
                    return found
        return _MISSING
    if isinstance(value, list) and len(value) == 3 and value[0] == "list" and path.startswith("["):
        m = re.match(r"\[(\d+)\]|\[([^=\]]+)=([^\]]*)\]", path)
        if not m:
            return _MISSING
        if m.group(1) is not None:
            i = int(m.group(1))
            item = value[2][i] if i < len(value[2]) else _MISSING
        else:
            item = next((x for x in value[2] if _element_key(x, m.group(2)) == m.group(3)), _MISSING)
        if item is _MISSING:
            return _MISSING
        return _get(["compound", item] if value[1] == "compound" else item, path[m.end():])
    return _MISSING


def _retag(element, item):
    return ["list"] + list(item) if element == "list" else [element, item]


def _walk_keyed(a, b, path, out):
    """world-snapshot.py's _walk, except that a KEYED_LISTS list is compared element by element under its key (when
    every element on both sides has a distinct one; otherwise as _walk does)."""
    if a == b:
        return
    field = KEYED_LISTS.get(re.split(r"[/\]]", path)[-1])
    ka, kb = (_keyed(a, field), _keyed(b, field)) if field else (None, None)
    if ka is not None and kb is not None:
        for k in sorted(set(ka) | set(kb)):
            seg = "%s[%s=%s]" % (path, field, k)
            if k not in kb:
                out.append((seg, ["compound", ka[k]], None))
            elif k not in ka:
                out.append((seg, None, ["compound", kb[k]]))
            else:
                _walk_keyed(["compound", ka[k]], ["compound", kb[k]], seg, out)
        return
    ca, cb = _compound(a), _compound(b)
    if ca is not None and cb is not None:
        for k in sorted(set(ca) | set(cb)):
            if k not in ca:
                out.append((path + "/" + k, None, cb[k]))
            elif k not in cb:
                out.append((path + "/" + k, ca[k], None))
            else:
                _walk_keyed(ca[k], cb[k], path + "/" + k, out)
        return
    if isinstance(a, list) and isinstance(b, list) and len(a) == 3 and len(b) == 3 and a[0] == b[0] == "list" \
            and a[1] == b[1] and len(a[2]) == len(b[2]):
        for i, (x, y) in enumerate(zip(a[2], b[2])):
            _walk_keyed(_retag(a[1], x), _retag(b[1], y), "%s[%d]" % (path, i), out)
        return
    out.append((path, a, b))


def _by_element(found, na, nb):
    """`found` with every row inside a KEYED_LISTS list replaced by that list's element-by-element rows."""
    out, done = [], set()
    for facet, path, a, b in found:
        m = _KEYED_SEGMENT.search(path) if facet != "rule" else None
        if not m:
            out.append((facet, path, a, b))
            continue
        top = path[:m.end()]
        if (facet, top) in done:
            continue
        done.add((facet, top))
        va, vb = _get(na.get(facet), top), _get(nb.get(facet), top)
        if va is _MISSING or vb is _MISSING:
            out += [(f, p, x, y) for f, p, x, y in found
                    if f == facet and (p == top or p.startswith(top + "[") or p.startswith(top + "/"))]
            continue
        rows = []
        _walk_keyed(va, vb, top, rows)
        out += [(facet, p, x, y) for p, x, y in rows]
    return out


def _normalised(snapshot, side, ws):
    return ws.normalise(snapshot, ws.Rules("upgrade"), side)


def losses(old, new, ws, na=None, nb=None):
    """{normalised key: (kind, mods, detail)} for one arm's swap; kind lost / changed / gained / rule. na / nb are the
    two snapshots under the upgrade rules (normalised here when not given)."""
    found, _rules = ws.diff(old, new, "upgrade")
    na = na if na is not None else _normalised(old, "a", ws)
    nb = nb if nb is not None else _normalised(new, "b", ws)
    found = _by_element(found, na, nb)
    labels = _labels(old)
    labels.update({k: v for k, v in _labels(new).items() if k not in labels})
    out = {}
    for facet, path, a, b in found:
        m = _UUID.search(path)
        label = labels.get(m.group(0), "entity") if m else None
        key = facet + " " + _UUID.sub(lambda u: "<%s>" % labels.get(u.group(0), "entity"), path)
        if facet == "rule":
            kind = "rule"
        elif a is not None and b is None:
            kind = "lost"
        elif a is None:
            kind = "gained"
        else:
            kind = "changed"
        idents = []
        if facet == "block_entities":
            # A block entity's path names only its position: attribute it by its own id too (review A-2: the brick
            # furnace and the tombstone grave came out as "vanilla").
            holder = path[1:].split("/", 1)[0].split("[", 1)[0]
            for side in (na, nb):
                ident = (_compound(side.get("block_entities", {}).get(holder)) or {}).get("id")
                if isinstance(ident, list) and len(ident) == 2 and isinstance(ident[1], str):
                    idents.append(ident[1])
        mods = _mods_named(path, a, b, label or "", *idents)
        out[key] = (kind, sorted(mods) or ["vanilla"], {"a": a, "b": b})
    return out


def _by_label(snapshot):
    """label -> [uuid] for one snapshot (the inverse of _labels)."""
    out = {}
    for uuid, label in _labels(snapshot).items():
        out.setdefault(label, []).append(uuid)
    return out


def _present(norm, key, by_label):
    """Whether a classify key names a value the (normalised) snapshot holds. A rule row is not a path: True."""
    facet, _, path = key.partition(" ")
    if facet == "rule" or facet not in norm:
        return True
    paths = [path]
    for label in set(re.findall(r"<([^<>]*)>", path)):
        paths = [p.replace("<%s>" % label, u) for p in paths for u in by_label.get(label, [])]
    return any(_get(norm[facet], p) is not _MISSING for p in paths)


def _state(key, found, norm, by_label):
    """One arm's state for a classify key: its own row's kind; else the kind of a row above it (a whole value the diff
    reported once); else "changed" when rows below it moved; else "kept" when that arm's old world holds the key and
    "absent" when it never had it (review A-1: `fml/Registries`, which only native MinecraftForge writes, was called
    kept on Forbric)."""
    if key in found:
        return found[key][0]
    for other, row in found.items():
        if key.startswith(other + "/") or key.startswith(other + "["):
            return row[0]
    if any(other.startswith(key + "/") or other.startswith(key + "[") for other in found):
        return "changed"
    return "kept" if _present(norm, key, by_label) else "absent"


# A mod's own files in the world, outside what world-snapshot.py exports (data/, players/, regions): packedup's backpack
# inventories, tombstone's saved player inventories, and anything else a candidate writes there.
WORLD_SKIP = {"region", "entities", "poi", "data", "players", "dimensions", "datapacks", "serverconfig", "level.dat",
              "level.dat_old", "session.lock", "icon.png", "playerdata", "advancements", "stats"}


def _typed_diff(a, b, path=""):
    """[(path, a, b)] where two typed NBT values (world-snapshot.py's read_nbt_file) differ: compounds by key, lists of
    equal length by index, anything else as a whole."""
    if a == b:
        return []
    if isinstance(a, list) and isinstance(b, list) and a[:1] == b[:1] == ["compound"]:
        out = []
        for k in sorted(set(a[1]) | set(b[1])):
            out += _typed_diff(a[1].get(k), b[1].get(k), path + "/" + k)
        return out
    if isinstance(a, list) and isinstance(b, list) and len(a) == len(b) == 3 and a[0] == b[0] == "list" and len(a[2]) == len(b[2]):
        out = []
        for i, (x, y) in enumerate(zip(a[2], b[2])):
            out += _typed_diff(["compound", x] if a[1] == "compound" else x, ["compound", y] if b[1] == "compound" else y,
                               "%s[%d]" % (path, i))
        return out
    return [(path, a, b)]


def world_files(old_world, new_world, ws=None):
    """[(path, verdict, detail)] for every file under a mod's own directory in the old world: KEPT (same NBT, or the same
    bytes when it is not NBT), CHANGED (with the first differing paths), LOST; files only the new world has are GAINED."""
    ws = ws or _load("world_snapshot", "world-snapshot.py")
    old_world, new_world = Path(old_world), Path(new_world)

    def files(root):
        return sorted(str(p.relative_to(root)) for p in root.rglob("*") if p.is_file()
                      and p.relative_to(root).parts[0] not in WORLD_SKIP)
    rows = []
    old_files, new_files = files(old_world), files(new_world)
    for rel in old_files:
        a, b = old_world / rel, new_world / rel
        if not b.is_file():
            rows.append((rel, "LOST", ""))
            continue
        ta, tb = ws.read_nbt_file(a), ws.read_nbt_file(b)
        if ta is None or tb is None:
            same = a.read_bytes() == b.read_bytes()
            rows.append((rel, "KEPT" if same else "CHANGED", "bytes"))
        else:
            found = _typed_diff(ta, tb)
            rows.append((rel, "KEPT" if not found else "CHANGED", "; ".join("%s: %s -> %s" % (p, json.dumps(x)[:80], json.dumps(y)[:80])
                                                                         for p, x, y in found[:4])))
    for rel in new_files:
        if rel not in old_files:
            rows.append((rel, "GAINED", ""))
    return rows


FORBRIC_CAUSED = "FORBRIC-CAUSED"
FORBRIC_CHANGED = "FORBRIC CHANGED (native keeps it: a Forbric-caused candidate)"
NO_NATIVE_LOSS = "NO NATIVE ROW (judged by the mod's code)"
NO_NATIVE_CHANGED = "NO NATIVE ROW (a value moved; judged by the mod's code)"
NOT_IN_FORBRIC_OLD = "NOT IN FORBRIC'S OLD WORLD (neither a loss nor a keep)"
LOADER_METADATA = "LOADER METADATA (the loader's mod list, rewritten by every save)"
GAINED = "GAINED (not a loss)"
CHANGED = "CHANGED (a value moved; judged)"
VERDICTS = {
    ("lost", "kept"): FORBRIC_CAUSED, ("rule", "kept"): FORBRIC_CAUSED,
    ("lost", "changed"): FORBRIC_CAUSED, ("rule", "changed"): FORBRIC_CAUSED,
    ("changed", "kept"): FORBRIC_CHANGED,
    ("lost", "lost"): "MOD FORMAT (native loses it too)", ("rule", "rule"): "MOD FORMAT (native loses it too)",
    ("lost", "rule"): "MOD FORMAT (native loses it too)", ("rule", "lost"): "MOD FORMAT (native loses it too)",
    ("kept", "lost"): "FORBRIC KEEPS (native loses)", ("kept", "rule"): "FORBRIC KEEPS (native loses)",
    ("changed", "lost"): "FORBRIC KEEPS (native loses)", ("changed", "rule"): "FORBRIC KEEPS (native loses)",
}
# The verdicts that can be a loss Forbric causes: each one is fixed or shown native-identical (PLAN SWAP acceptance).
CANDIDATES = (FORBRIC_CAUSED, FORBRIC_CHANGED, NO_NATIVE_LOSS, NO_NATIVE_CHANGED)


def verdict(key, fk, nk, no_native):
    """One row's verdict from the two arms' states (lost / rule / changed / gained / kept / absent)."""
    if key.startswith("level /fml/LoadingModList"):
        return LOADER_METADATA
    if fk == "absent":
        return NOT_IN_FORBRIC_OLD
    if no_native or nk == "absent":
        return {"lost": NO_NATIVE_LOSS, "rule": NO_NATIVE_LOSS, "changed": NO_NATIVE_CHANGED, "gained": GAINED}.get(fk, "KEPT")
    if "gained" in (fk, nk) and not {"lost", "rule"} & {fk, nk}:
        return GAINED
    if (fk, nk) in VERDICTS:
        return VERDICTS[(fk, nk)]
    if "changed" in (fk, nk):
        return CHANGED
    return "%s/%s (judged)" % (fk, nk)


def PLAYER_KEY(key):
    """A classify key of the player's (players / advancements / stats facets, or a rule about the player file)."""
    return key.startswith(("players ", "advancements ", "stats ", "rule player "))


def classify(forbric, native, client=None):
    """The L_F / L_N table. forbric: a `native-controls.py swap --engine forbric` result dir (old, neo-open snapshots);
    native: the `--engine native` one (forge-reopen, neo-open). A key the native arm cannot have (classicpipes, which
    native MinecraftForge 65.0.1 does not load; the player, which a native server has none of) has no native row and is
    judged from the mod's own code.

    Each arm's state for a key is its own diff row's kind (lost / rule / changed / gained); a key with no row of its
    own takes the kind of a row above it (a whole value), "changed" when rows below it moved, "kept" when that arm's
    old world holds it and "absent" when that arm's old world never had it. The verdicts in CANDIDATES are the ones a
    Forbric-caused loss can hide behind; `candidates` lists their keys."""
    ws = _load("world_snapshot", "world-snapshot.py")
    read = lambda d, n: json.loads((d / ("%s.snapshot.json" % n)).read_text())
    f_old, f_new = read(forbric, "old"), read(forbric, "neo-open")
    n_old, n_new = read(native, "forge-reopen"), read(native, "neo-open")
    nf_old, nn_old = _normalised(f_old, "a", ws), _normalised(n_old, "a", ws)
    lf, ln = losses(f_old, f_new, ws, na=nf_old), losses(n_old, n_new, ws, na=nn_old)
    player = PLAYER_KEY
    # A dedicated server never loads a player file, so its export has the old file untouched: no player row comes from
    # a server arm (native has no player at all).
    lf = {k: v for k, v in lf.items() if not player(k)}
    ln = {k: v for k, v in ln.items() if not player(k)}
    if client:
        # A dedicated server never loads a player file, so the player's rows come from the singleplayer client that
        # opened the old world with the NeoForge builds (its own export): only its player facets are taken.
        c_new = json.loads(Path(client).read_text())
        lc = losses(f_old, c_new, ws, na=nf_old)
        lf.update({k: v for k, v in lc.items() if player(k)})
    native_mods = _mods_named(n_old.get("blocks", {}), n_old.get("block_entities", {}), n_old.get("entities", {}),
                              sorted(n_old.get("saved_data", {})))
    f_labels, n_labels = _by_label(f_old), _by_label(n_old)
    rows = []
    for key in sorted(set(lf) | set(ln)):
        # A key missing from one arm's diff is not "kept" by default: that arm may report it as part of a whole value,
        # or its old world may never have had it (review A-1).
        fk = _state(key, lf, nf_old, f_labels)
        nk = _state(key, ln, nn_old, n_labels)
        mods = (lf.get(key) or ln.get(key))[1]
        no_native = player(key) or (mods != ["vanilla"] and not set(mods) & native_mods)
        rows.append({"key": key, "mod": ",".join(mods), "forbric": fk, "native": "n/a" if no_native else nk,
                     "verdict": verdict(key, fk, nk, no_native), "forbricDetail": lf.get(key, (None, None, None))[2],
                     "nativeDetail": ln.get(key, (None, None, None))[2]})
    drills = {}
    for arm, d in (("forbric", forbric), ("native", native)):
        log = d / "neo-open.log"
        drills[arm] = [list(r) for r in drill(log.read_text(errors="replace"))] if log.is_file() else []
    counts = {}
    for row in rows:
        counts[row["verdict"]] = counts.get(row["verdict"], 0) + 1
    result = {"rows": rows, "drill": drills, "nativeMods": sorted(native_mods), "counts": dict(sorted(counts.items())),
              "candidates": [row["key"] for row in rows if row["verdict"] in CANDIDATES]}
    for arm, d in (("forbric", forbric), ("native", native)):
        inputs = json.loads((d / "inputs.json").read_text()) if (d / "inputs.json").is_file() else {}
        old_world = Path(inputs.get("oldWorld") or "/nonexistent") if arm == "forbric" else d / "forge-instance/world"
        new_world = d / "neo-instance/world"
        if old_world.is_dir() and new_world.is_dir():
            result.setdefault("worldFiles", {})[arm] = [list(r) for r in world_files(old_world, new_world, ws)]
        if (d / "neo-instance").is_dir():
            result.setdefault("configs", {})[arm] = [list(r) for r in configs(d / "neo-instance")]
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("console")
    sub.add_parser("checks")
    d = sub.add_parser("datapack"); d.add_argument("world")
    s = sub.add_parser("session")
    s.add_argument("--log", required=True); s.add_argument("--cwd", required=True)
    s.add_argument("--console", action="append", default=[]); s.add_argument("--env", action="append", default=[])
    s.add_argument("--timeout", type=int, default=900)
    s.add_argument("command", nargs=argparse.REMAINDER)
    cs = sub.add_parser("client")
    cs.add_argument("--log", required=True); cs.add_argument("--rundir", required=True)
    cs.add_argument("--env", action="append", default=[])
    cs.add_argument("--on", action="append", default=[], help="REGEX=KEY,KEY,... keys posted when REGEX first shows in the log")
    cs.add_argument("--timeout", type=int, default=900)
    cs.add_argument("command", nargs=argparse.REMAINDER)
    mc = sub.add_parser("mark-configs"); mc.add_argument("instance")
    cf = sub.add_parser("configs"); cf.add_argument("instance")
    dr = sub.add_parser("drill"); dr.add_argument("log")
    c = sub.add_parser("census"); c.add_argument("snapshot")
    k = sub.add_parser("keys"); k.add_argument("pid", type=int); k.add_argument("keys", nargs="+")
    k.add_argument("--gap", type=float, default=0.6)
    cl = sub.add_parser("classify")
    cl.add_argument("--forbric", required=True); cl.add_argument("--native", required=True); cl.add_argument("--out")
    cl.add_argument("--client", help="the NEW singleplayer client's export of the old world (the player's rows)")
    wf = sub.add_parser("files"); wf.add_argument("old_world"); wf.add_argument("new_world")
    ba = sub.add_parser("bag-agent"); ba.add_argument("dir")
    bg = sub.add_parser("bags"); bg.add_argument("world")
    lu = sub.add_parser("lastupdate"); lu.add_argument("world"); lu.add_argument("--chunks", default=BOX)
    pn = sub.add_parser("pin")
    pn.add_argument("--forbric", required=True); pn.add_argument("--native", required=True)
    pn.add_argument("--client"); pn.add_argument("--tsv", required=True); pn.add_argument("--out")
    pn.add_argument("--no-player", action="store_true", help="no client ran: player rows of the TSV are not asked for")
    ct = sub.add_parser("control", help="gate-m59's reverse controls on a world after the open")
    ct.add_argument("name", choices=["drop-slot", "drop-pack", "drop-bag-stack", "restore-bags"]); ct.add_argument("world")
    ct.add_argument("args", nargs="*")
    rw = sub.add_parser("rewritten", help="whether every packedup inventory of the old world was saved again in the new one")
    rw.add_argument("old_world"); rw.add_argument("new_world")
    ti = sub.add_parser("tie", help="whether the fixture's native/ is the native arm of the old side beside it")
    ti.add_argument("fixture"); ti.add_argument("--native-create", help="read this native create snapshot instead (gate-m59's control)")
    pk = sub.add_parser("packs", help="whether every forbric/data/* pack enabled in the old world is enabled in the new one")
    pk.add_argument("old_world"); pk.add_argument("new_world")
    args = parser.parse_args(argv)
    if args.action == "console":
        sys.stdout.write(console_text())
    elif args.action == "checks":
        sys.stdout.write("\n".join(checks_console()) + "\n")
    elif args.action == "datapack":
        print(write_datapack(args.world))
    elif args.action == "session":
        command = args.command[1:] if args.command[:1] == ["--"] else args.command
        result = session(command, args.cwd, args.log, args.console, args.env, args.timeout)
        print(json.dumps(result))
        return 0 if result["outcome"] == "DONE" and result["saved"] else 1
    elif args.action == "client":
        command = args.command[1:] if args.command[:1] == ["--"] else args.command
        env = os.environ.copy()
        for pair in args.env:
            k, _, v = pair.partition("=")
            if v == "":
                env.pop(k, None)
            else:
                env[k] = v
        triggers = [(on.rsplit("=", 1)[0], on.rsplit("=", 1)[1].split(",")) for on in args.on]
        result = client_session(command, env, args.log, Path(args.rundir).resolve(), triggers, args.timeout)
        print(json.dumps(result))
        return 0 if result["exitCode"] == 0 and not result["crashed"] and result["cleanDisconnect"] else 1
    elif args.action == "bag-agent":
        print(build_bag_agent(args.dir))
    elif args.action == "bags":
        found = bags(args.world)
        for name, stacks in found.items():
            print("[forge-swap] bag %s: %d stack(s) %s" % (name, len(stacks), " ".join(
                "%d=%s*%d%s" % (slot, ident, count, "{%s}" % ",".join(comps) if comps else "")
                for slot, ident, count, comps in stacks)))
        print("[forge-swap] bags: %d file(s), %d holding stacks" % (len(found), sum(1 for v in found.values() if v)))
        return 0 if any(found.values()) else 1
    elif args.action == "lastupdate":
        for chunk, value in sorted(lastupdate(args.world, args.chunks).items()):
            print("[forge-swap] LastUpdate %s %d" % (chunk, value))
    elif args.action == "pin":
        table = classify(Path(args.forbric), Path(args.native), args.client)
        expected = read_tsv(args.tsv)
        if args.no_player:
            expected = [row for row in expected if not PLAYER_KEY(row["key"])]
            table = dict(table, rows=[row for row in table["rows"] if not PLAYER_KEY(row["key"])])
        result = pin(table, expected)
        result["candidates"] = table["candidates"]
        if args.out:
            Path(args.out).write_text(json.dumps({"pin": result, "table": table}, indent=1, sort_keys=True))
        for kind in ("unpinned", "stale", "changed"):
            for key in result[kind]:
                print("[forge-swap] pin %s %s" % (kind, key))
        for key in result["lost"]:
            print("[forge-swap] loss %s | %s" % (key, result["verdicts"][key]))
        print("[forge-swap] pin: %d loss(es), %d unpinned, %d stale, %d changed" % (
            len(result["lost"]), len(result["unpinned"]), len(result["stale"]), len(result["changed"])))
        return 0 if not (result["unpinned"] or result["stale"] or result["changed"]) else 1
    elif args.action == "control":
        if args.name == "drop-slot":
            x, y, z, slot = (int(v) for v in args.args)
            dropped = drop_slot(args.world, x, y, z, slot)
            print("[forge-swap] control drop-slot %d %d %d slot %d: %s" % (x, y, z, slot, dropped or "nothing matched"))
            return 0 if dropped else 1
        if args.name == "drop-bag-stack":
            name, slot = args.args[0], int(args.args[1])
            dropped = drop_bag_stack(args.world, name, slot)
            print("[forge-swap] control drop-bag-stack %s stack%d: %s" % (name, slot, dropped or "nothing there"))
            return 0 if dropped else 1
        if args.name == "restore-bags":
            done = restore_bags(args.args[0], args.world)
            print("[forge-swap] control restore-bags: put back %s with the old time" % (" ".join(done) or "nothing"))
            return 0 if done else 1
        dropped = drop_pack(args.world, args.args[0])
        print("[forge-swap] control drop-pack %s: %s" % (args.args[0], "dropped" if dropped else "not enabled"))
        return 0 if dropped else 1
    elif args.action == "packs":
        old = [p for p in enabled_packs(args.old_world) if p.startswith("forbric/data/")]
        new = set(enabled_packs(args.new_world))
        gone = [p for p in old if p not in new]
        print("[forge-swap] packs: %d forbric/data pack(s) in the old world, %d no longer enabled%s" % (
            len(old), len(gone), (": " + " ".join(gone)) if gone else ""))
        return 0 if old and not gone else 1
    elif args.action == "rewritten":
        rows = rewritten(args.old_world, args.new_world)
        for name, verdict, old_ns, new_ns in rows:
            print("[forge-swap] bag file %s %s (old %s, new %s)" % (verdict, name, time.strftime("%H:%M:%S", time.localtime(old_ns / 1e9)),
                                                                 time.strftime("%H:%M:%S", time.localtime(new_ns / 1e9)) if new_ns else "-"))
        done = sum(r[1] == "REWRITTEN" for r in rows)
        print("[forge-swap] rewritten: %s bag file(s) saved again" % (
            "all %d" % done if rows and done == len(rows) else "%d of %d" % (done, len(rows))))
        return 0 if rows and done == len(rows) else 1
    elif args.action == "tie":
        rows = tie(args.fixture, args.native_create)
        for check, ok, detail in rows:
            print("[forge-swap] tie %s %s: %s" % ("PASS" if ok else "FAIL", check, detail))
        ok = sum(r[1] for r in rows)
        print("[forge-swap] tie: %s" % ("all %d rows PASS" % ok if ok == len(rows) else "%d of %d rows PASS" % (ok, len(rows))))
        return 0 if all(r[1] for r in rows) else 1
    elif args.action == "files":
        for rel, verdict, detail in world_files(args.old_world, args.new_world):
            print("[forge-swap] world file %s %s %s" % (verdict, rel, detail))
    elif args.action == "mark-configs":
        for rel, key, default, mark in mark_configs(args.instance):
            print("[forge-swap] marked %s %s: %s -> %s" % (rel, key, default, mark))
    elif args.action == "configs":
        rows = configs(args.instance)
        for rel, key, value, verdict in rows:
            print("[forge-swap] config %s %s %s = %s" % (verdict, rel, key, value))
        return 0 if all(r[3] == "KEPT" for r in rows) else 1
    elif args.action == "drill":
        rows = drill(Path(args.log).read_text(errors="replace"))
        for name, expect, verdict, answer in rows:
            print("[forge-swap] %s %s (want /%s/; got %s)" % (verdict, name, expect, answer or "no answer"))
        missing = drill_missing(rows)
        for name in missing:
            print("[forge-swap] MISSING %s (no announce line in the log)" % name)
        print("[forge-swap] drill: %d PASS, %d FAIL, %d missing of %d" % (
            sum(r[2] == "PASS" for r in rows), sum(r[2] == "FAIL" for r in rows), len(missing), len(drill_names())))
        return 0 if rows and all(r[2] == "PASS" for r in rows) and not missing else 1
    elif args.action == "census":
        for path, keys in sorted(census(json.loads(Path(args.snapshot).read_text())).items()):
            print("%s: %s" % (path, ",".join(keys) or "(empty)"))
    elif args.action == "keys":
        post_keys(args.pid, args.keys, args.gap)
    elif args.action == "classify":
        table = classify(Path(args.forbric), Path(args.native), args.client)
        if args.out:
            Path(args.out).write_text(json.dumps(table, indent=1, sort_keys=True))
        for row in table["rows"]:
            print("%-24s L_F=%-7s L_N=%-7s %-40s %s" % (row["mod"], row["forbric"], row["native"], row["verdict"], row["key"]))
        for name, n in table["counts"].items():
            print("[forge-swap] %3d %s" % (n, name))
        print("[forge-swap] %d Forbric-caused candidate(s) (%s)" % (len(table["candidates"]), ", ".join(CANDIDATES)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
