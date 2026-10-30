#!/usr/bin/env python3
"""With no mods, lava and water must react on Forbric exactly as they do on vanilla 26.2; with a NeoForge and a
MinecraftForge mod's fluid rules, each rule must run where its own loader runs it.

    fluid-parity-gate.py [--output DIR] [--unfixed]
    fluid-parity-gate.py --mods [--native-controls DIR] [--output DIR] [--unfixed]

Runs one datapack, unchanged, on two dedicated servers in turn: pure vanilla (launch-vanilla-server.sh, the player's own
26.2 jar, as gate-m31 runs it) and the kernel with zero mods (launch-kernel-server.sh). The datapack builds every case
in the air over a superflat world, each inside its own stone shell, and classifies the cells into scores; the console is
asked for the scores, the world is saved, and the saved region files are read back. PASS needs both servers to save
and stop, vanilla to show the reactions the scenario is about (so a scenario that measured nothing cannot pass), and
Forbric to match vanilla on every score and every block in the build box.

Cases (z=8, y=80): A lava source set beside water -> obsidian; B flowing lava set beside water -> cobblestone; C water
set beside lava (neighborChanged) -> obsidian; D lava set on soul soil beside blue ice -> basalt; E lava flows into a
cell under a waterlogged slab -> cobblestone; F water flows to a lava source -> obsidian; G and H a cobblestone and a
basalt generator, each cell emptied every tick for 600 ticks (vanilla makes 20 of each); I lava flows down into water
-> stone. A, B, D, E, G and H are LiquidBlock.onPlace; C and F neighborChanged; I LavaFluid.spreadTo.

TEETH (recorded 2026-10-02, kernel jar 67aede69): --unfixed runs the kernel with -Dforbric.fluidInteractions=off, which puts
back the neuter of MinecraftForge's FluidInteractionRegistry.canInteract that the merged onPlace asks. Then A and B stay
lava and are washed over by water, D and E stay lava, both generators make nothing — 14 scores and 6 cells differ from
vanilla, and this gate is RED. Only C, F and I (paths that never asked that registry) still match.

--mods compares against the loaders themselves: native NeoForge 26.2.0.93 with a NeoForge canary mod (lava next to an
iron or emerald block -> glowstone) and native MinecraftForge 26.2-65.0.1 with a MinecraftForge canary mod (lava next to
a lapis or emerald block -> shroomlight; water next to lapis -> sponge), both compiled from canary/fluid-interactions
against that loader's own jars, and the kernel with both jars, unchanged. The native images are the ones
native-controls.py prepare installs (--native-controls, default build/native-controls); they are copied per run, never
written. On NeoForge a mod's rule runs when a block next to the liquid changes and never when the liquid is placed (its
onPlace runs vanilla's rules alone); on MinecraftForge it runs on both, and at each neighbour every rule is tried before
the next neighbour, so a mod's rule above beats vanilla's water to the east. Each case is decided by the loader whose
LiquidBlock entry point the merged game uses there (placement: MinecraftForge's; a neighbour change: NeoForge's, then
MinecraftForge mods' rules at the same neighbour): the cell must match that loader's server, immediately and 100 ticks
later, and the canaries must report the same firings at that cell -- so a rule that ran twice, or ran where its loader
would not, is RED. X_* has both rules match one emerald block: placement must run the MinecraftForge rule once, a
neighbour change the NeoForge rule once. The canaries register at common setup, as mods do.

TEETH of --mods (recorded 2026-10-02): the kernel before this mode existed (jar e0aa078c) ran the NeoForge rule on
placement (N_place glowstone, native lava), let vanilla's water to the east beat a MinecraftForge rule above on
placement and on a neighbour change (M_first, M_firstNeighbour obsidian, native shroomlight), and ran the NeoForge rule
on X_place: RED on those four. --mods --unfixed (jar b8e09a2b) runs no MinecraftForge rule at all and no placement
reaction: RED on every M_* case, X_place and V_place.
"""
import argparse
from datetime import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import zipfile

Y, Z = 80, 8
CODES = ['air', 'lava', 'water', 'obsidian', 'cobblestone', 'stone', 'basalt', 'blue_ice', 'soul_soil', 'stone_slab']
CELLS = {'A': (4, Y), 'B': (12, Y), 'C': (20, Y), 'D': (28, Y), 'E': (37, Y), 'F': (44, Y), 'G': (53, Y), 'H': (61, Y),
         'I': (68, Y), 'Isrc': (68, Y + 1), 'Gsrc': (52, Y), 'Hsrc': (60, Y)}
# What vanilla must show for the scenario to be measuring anything (the generators must also produce, see below).
VANILLA = {'A': 'obsidian', 'B': 'cobblestone', 'C': 'obsidian', 'D': 'basalt', 'E': 'cobblestone', 'F': 'obsidian',
           'I': 'stone', 'A_end': 'obsidian', 'B_end': 'cobblestone', 'D_end': 'basalt', 'E_end': 'cobblestone'}
GENERATORS = ['g_cobblestone', 'g_obsidian', 'g_stone', 'g_basalt', 'g_mined', 'h_basalt', 'h_cobblestone', 'h_obsidian',
              'h_stone', 'h_mined']
BOX = ((0, 76, 0), (127, 86, 15))
PROPERTIES = ('server-ip=127.0.0.1\nserver-port={port}\nlevel-name=world\nlevel-type=minecraft:flat\nlevel-seed=fluidparity\n'
              'generate-structures=false\nonline-mode=false\nmax-tick-time=-1\npause-when-empty-seconds=0\n'
              'view-distance=2\nsimulation-distance=2\nspawn-protection=0\nsync-chunk-writes=true\n')

# --mods. Each case is one cell (x, 80, 8) in a 5x5x5 stone block; "{xp}" is east of it, "{yp}" above, "{zm}" north.
# Above is the first neighbour both registries walk and east the last. `strict` sets the liquid without running its
# onPlace, so the andesite set north of it next is the neighbour change that decides the cell.
MOD_CODES = ['air', 'lava', 'water', 'obsidian', 'cobblestone', 'stone', 'glowstone', 'shroomlight', 'sponge',
             'iron_block', 'lapis_block', 'emerald_block', 'andesite']
MOD_CASES = [  # name, the loader whose server decides it, commands
    ('N_place', 'neoforge', ['setblock {xp} {y} {z} iron_block', 'setblock {x} {y} {z} lava']),
    ('N_neighbour', 'neoforge', ['setblock {x} {y} {z} lava', 'setblock {xp} {y} {z} iron_block']),
    ('N_first', 'neoforge', ['setblock {x} {yp} {z} iron_block', 'setblock {xp} {y} {z} water',
                             'setblock {x} {y} {z} lava strict', 'setblock {x} {y} {zm} andesite']),
    ('M_place', 'minecraftforge', ['setblock {xp} {y} {z} lapis_block', 'setblock {x} {y} {z} lava']),
    ('M_neighbour', 'minecraftforge', ['setblock {x} {y} {z} lava', 'setblock {xp} {y} {z} lapis_block']),
    ('M_water', 'minecraftforge', ['setblock {xp} {y} {z} lapis_block', 'setblock {x} {y} {z} water']),
    ('M_flowing', 'minecraftforge', ['setblock {xp} {y} {z} lapis_block', 'setblock {x} {y} {z} lava[level=1]']),
    ('M_first', 'minecraftforge', ['setblock {x} {yp} {z} lapis_block', 'setblock {xp} {y} {z} water',
                                   'setblock {x} {y} {z} lava']),
    ('M_firstNeighbour', 'minecraftforge', ['setblock {x} {yp} {z} lapis_block', 'setblock {xp} {y} {z} water',
                                            'setblock {x} {y} {z} lava strict', 'setblock {x} {y} {zm} andesite']),
    ('X_place', 'minecraftforge', ['setblock {xp} {y} {z} emerald_block', 'setblock {x} {y} {z} lava']),
    ('X_neighbour', 'neoforge', ['setblock {x} {y} {z} lava', 'setblock {xp} {y} {z} emerald_block']),
    ('V_place', 'both', ['setblock {xp} {y} {z} water', 'setblock {x} {y} {z} lava']),
    ('V_neighbour', 'both', ['setblock {x} {y} {z} lava', 'setblock {xp} {y} {z} water']),
]
NATIVE = {'neoforge': ('neo', 'net/neoforged/neoforge/26.2.0.93',
                       'libraries/net/neoforged/minecraft-server-patched/26.2.0.93/minecraft-server-patched-26.2.0.93.jar'),
          'minecraftforge': ('forge', 'net/minecraftforge/forge/26.2-65.0.1',
                             'libraries/net/minecraftforge/forge/26.2-65.0.1/forge-26.2-65.0.1-server.jar')}
FIRED = re.compile(r'\[FluidCanary\] (neoforge|minecraftforge) \w+ rule fired at (-?\d+), (-?\d+), (-?\d+)')


def mod_cell(index):
    return 4 + 6 * index, Y, Z


def mod_classify(score, x, y, z):
    return ([f'scoreboard players set #{score} fp -1']
            + [f'execute if block {x} {y} {z} minecraft:{block} run scoreboard players set #{score} fp {code}'
               for code, block in enumerate(MOD_CODES)])


def mod_functions():
    build = ['say [fluidparity] building']
    end = ['say [fluidparity] final state']
    for index, (name, _, commands) in enumerate(MOD_CASES):
        x, y, z = mod_cell(index)
        build += [f'fill {x - 2} {y - 2} {z - 2} {x + 2} {y + 2} {z + 2} minecraft:stone', f'setblock {x} {y} {z} air']
        build += [c.format(x=x, y=y, z=z, xp=x + 1, yp=y + 1, zm=z - 1) for c in commands]
        build += mod_classify(name, x, y, z)
        end += mod_classify(name + '_end', x, y, z)
    end.append('say FLUIDPARITY DONE')
    tick = ['execute if loaded 0 80 0 if loaded 127 80 15 run scoreboard players add #t fp 1',
            'execute if score #t fp matches 20 run function fluidparity:build',
            'execute if score #t fp matches 120 run function fluidparity:end']
    load = ['scoreboard objectives add fp dummy', 'scoreboard players set #t fp 0', 'forceload add 0 0 127 15']
    return dict(load=load, tick=tick, build=build, end=end)


def mod_scores():
    return [name for name, _, _ in MOD_CASES] + [name + '_end' for name, _, _ in MOD_CASES]


def shell(x0, x1, top):
    return f'fill {x0} {Y - 1} {Z - 1} {x1} {top} {Z + 1} minecraft:stone'


def classify(score, x, y):
    lines = [f'scoreboard players set #{score} fp -1']
    lines += [f'execute if block {x} {y} {Z} minecraft:{block} run scoreboard players set #{score} fp {code}'
              for code, block in enumerate(CODES)]
    return lines


def functions():
    slab = 'minecraft:stone_slab[type=bottom,waterlogged=true]'
    build = ['say [fluidparity] building',
             shell(3, 6, Y + 1), f'setblock 4 {Y} {Z} air', f'setblock 5 {Y} {Z} water', f'setblock 4 {Y} {Z} lava',
             shell(11, 14, Y + 1), f'setblock 12 {Y} {Z} air', f'setblock 13 {Y} {Z} water', f'setblock 12 {Y} {Z} lava[level=1]',
             shell(19, 22, Y + 1), f'setblock 20 {Y} {Z} air', f'setblock 21 {Y} {Z} air', f'setblock 20 {Y} {Z} lava',
             f'setblock 21 {Y} {Z} water',
             shell(27, 30, Y + 1), f'setblock 28 {Y - 1} {Z} soul_soil', f'setblock 29 {Y} {Z} blue_ice', f'setblock 28 {Y} {Z} air',
             f'setblock 28 {Y} {Z} lava',
             shell(35, 38, Y + 2), f'setblock 37 {Y + 1} {Z} {slab}', f'setblock 37 {Y} {Z} air', f'setblock 36 {Y} {Z} lava',
             shell(43, 47, Y + 1), f'setblock 45 {Y} {Z} air', f'setblock 44 {Y} {Z} lava', f'setblock 46 {Y} {Z} water',
             shell(51, 54, Y + 2), f'setblock 53 {Y + 1} {Z} {slab}', f'setblock 53 {Y} {Z} air', f'setblock 52 {Y} {Z} lava',
             shell(59, 63, Y + 1), f'setblock 61 {Y - 1} {Z} soul_soil', f'setblock 62 {Y} {Z} blue_ice', f'setblock 61 {Y} {Z} air',
             f'setblock 60 {Y} {Z} lava',
             shell(67, 69, Y + 2), f'setblock 68 {Y} {Z} water', f'setblock 68 {Y + 1} {Z} lava']
    for case in 'ABCD':
        build += classify(case, *CELLS[case])
    check = ['say [fluidparity] checking'] + classify('E', *CELLS['E']) + classify('F', *CELLS['F']) + classify('I', *CELLS['I'])
    end = ['say [fluidparity] final state']
    for case, (x, y) in CELLS.items():
        end += classify(case + '_end', x, y)
    end.append('say FLUIDPARITY DONE')
    mine = []
    for gen, x in (('g', 53), ('h', 61)):
        for block in ('cobblestone', 'obsidian', 'stone', 'basalt'):
            mine.append(f'execute if block {x} {Y} {Z} minecraft:{block} run scoreboard players add #{gen}_{block} fp 1')
        solid = f'execute unless block {x} {Y} {Z} minecraft:air unless block {x} {Y} {Z} minecraft:lava run'
        mine += [f'{solid} scoreboard players add #{gen}_mined fp 1', f'{solid} setblock {x} {Y} {Z} minecraft:air']
    tick = ['execute if loaded 0 80 0 if loaded 127 80 15 run scoreboard players add #t fp 1',
            'execute if score #t fp matches 20 run function fluidparity:build',
            'execute if score #t fp matches 21..620 run function fluidparity:mine',
            'execute if score #t fp matches 100 run function fluidparity:check',
            'execute if score #t fp matches 640 run function fluidparity:end']
    load = ['scoreboard objectives add fp dummy', 'scoreboard players set #t fp 0', 'forceload add 0 0 127 15']
    return dict(load=load, tick=tick, build=build, check=check, mine=mine, end=end)


def scores():
    return ['A', 'B', 'C', 'D', 'E', 'F', 'I'] + [case + '_end' for case in CELLS] + GENERATORS


def stage(run, port, pack_functions=None, mods=()):
    run.mkdir(parents=True, exist_ok=True)
    (run / 'mods').mkdir(exist_ok=True)         # genuinely zero-mod on the Forbric side, unless mods are given
    for jar in mods:
        shutil.copy2(jar, run / 'mods' / jar.name)
    (run / 'eula.txt').write_text('eula=true\n')
    (run / 'server.properties').write_text(PROPERTIES.format(port=port))
    pack = run / 'world/datapacks/fluidparity'
    (pack / 'data/fluidparity/function').mkdir(parents=True)
    (pack / 'data/minecraft/tags/function').mkdir(parents=True)
    (pack / 'pack.mcmeta').write_text(json.dumps({'pack': {'description': 'Forbric fluid parity', 'min_format': [107, 0], 'max_format': 107}}))
    for name, body in (pack_functions or functions()).items():
        (pack / f'data/fluidparity/function/{name}.mcfunction').write_text('\n'.join(body) + '\n')
    (pack / 'data/minecraft/tags/function/load.json').write_text(json.dumps({'values': ['fluidparity:load']}))
    (pack / 'data/minecraft/tags/function/tick.json').write_text(json.dumps({'values': ['fluidparity:tick']}))


def boot(launcher, run, env, names=None, cwd=None):
    """Start a server, wait for the datapack's last line, ask for every score, save, stop. The console's lines."""
    lines, done = [], threading.Event()
    with (run / 'console.log').open('w') as console:
        command = launcher if isinstance(launcher, list) else [str(launcher)]
        process = subprocess.Popen(command, env=env, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, bufsize=1, start_new_session=True)

        def pump():
            for line in process.stdout:
                console.write(line)
                console.flush()
                lines.append(line)
                if 'FLUIDPARITY DONE' in line:
                    done.set()
        reader = threading.Thread(target=pump, daemon=True)
        reader.start()
        deadline = time.time() + 600
        while not done.is_set() and process.poll() is None and time.time() < deadline:
            done.wait(1)
        if process.poll() is None:
            for score in names or scores():
                process.stdin.write(f'scoreboard players get #{score} fp\n')
            process.stdin.write('save-all flush\n')
            process.stdin.flush()
            time.sleep(8)
            process.stdin.write('stop\n')
            process.stdin.flush()
            try:
                process.wait(timeout=120)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, 9)
                process.wait()
        reader.join(timeout=10)
    return ''.join(lines), done.is_set(), process.returncode


def read_scores(text):
    raw = {m.group(1): int(m.group(2)) for m in re.finditer(r'#([A-Za-z0-9_]+) has (-?\d+) \[fp\]', text)}
    named = {}
    for name in scores():
        value = raw.get(name)
        if name in GENERATORS:
            named[name] = value or 0                 # nothing ever added: the score is unset
        else:
            named[name] = CODES[value] if value is not None and 0 <= value < len(CODES) else value
    return named


def read_blocks(region):
    spec = importlib.util.spec_from_file_location('world_parity', Path(__file__).with_name('world-parity.py'))
    parity = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parity)
    (x0, y0, z0), (x1, y1, z1) = BOX
    cells = {}
    for cx, cz, root in parity.chunks(region):
        if cx * 16 > x1 or cx * 16 + 15 < x0 or cz * 16 > z1 or cz * 16 + 15 < z0:
            continue
        for section in root.get('sections', []):
            sy = section.get('Y')
            states = section.get('block_states') or {}
            palette = states.get('palette') or []
            if sy is None or sy * 16 > y1 or sy * 16 + 15 < y0 or not palette:
                continue
            bits = max(4, (len(palette) - 1).bit_length()) if len(palette) > 1 else 0
            for index in range(4096):
                x, y, z = cx * 16 + (index & 15), sy * 16 + (index >> 8), cz * 16 + ((index >> 4) & 15)
                if not (x0 <= x <= x1 and y0 <= y <= y1 and z0 <= z <= z1):
                    continue
                if bits:
                    per_long = 64 // bits
                    word = states['data'][index // per_long] % (1 << 64)
                    entry = palette[(word >> ((index % per_long) * bits)) & ((1 << bits) - 1)]
                else:
                    entry = palette[0]
                if entry['Name'] != 'minecraft:air':
                    props = entry.get('Properties') or {}
                    cells[f'{x},{y},{z}'] = entry['Name'] + ('[' + ','.join(f'{k}={props[k]}' for k in sorted(props)) + ']' if props else '')
    return cells


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--output', type=Path, help='A new directory for logs, worlds and the report')
    parser.add_argument('--unfixed', action='store_true', help='Run the kernel with -Dforbric.fluidInteractions=off (must go RED)')
    parser.add_argument('--mods', action='store_true', help="Compare mods' fluid rules against native NeoForge and MinecraftForge")
    parser.add_argument('--native-controls', type=Path, help="native-controls.py's directory (default build/native-controls)")
    args = parser.parse_args()
    kernel = Path(__file__).resolve().parents[2]
    output = (args.output or kernel / 'build/verification' / ('fluid-parity-' + datetime.now().strftime('%Y%m%d-%H%M%S'))).resolve()
    output.mkdir(parents=True, exist_ok=False)
    if args.mods:
        sys.exit(mods_main(args, kernel, output))
    port = 25540 + os.getpid() % 200
    arms = {}
    for arm, launcher, extra in (('vanilla', kernel / 'run/launch-vanilla-server.sh', {}),
                                 ('forbric', kernel / 'run/launch-kernel-server.sh',
                                  {'FORBRIC_JVM': '-Xmx2G' + (' -Dforbric.fluidInteractions=off' if args.unfixed else ''),
                                   'FORBRIC_COMPAT_POLICY': 'strict'})):
        run = output / arm
        stage(run, port)
        port += 1
        env = dict(os.environ, RUNDIR=str(run), **extra)
        print(f'[fluid-parity] {arm}: booting {launcher.name}', flush=True)
        text, finished, code = boot(launcher, run, env)
        region = run / 'world/dimensions/minecraft/overworld/region'
        arms[arm] = dict(finished=finished, exit=code, saved='All dimensions are saved' in text or 'Saved the game' in text,
                         scores=read_scores(text), cells=read_blocks(region) if region.is_dir() else {})
        print(f'[fluid-parity] {arm}: finished={finished} exit={code} saved={arms[arm]["saved"]} '
              f'scores={json.dumps(arms[arm]["scores"])}', flush=True)

    vanilla, forbric = arms['vanilla'], arms['forbric']
    failures = []
    for arm, result in arms.items():
        if not (result['finished'] and result['saved'] and result['exit'] == 0):
            failures.append(f'{arm} did not run the scenario to the end, save and stop')
    wrong = {k: vanilla['scores'].get(k) for k, v in VANILLA.items() if vanilla['scores'].get(k) != v}
    if wrong or not vanilla['scores'].get('g_cobblestone') or not vanilla['scores'].get('h_basalt'):
        failures.append(f'vanilla did not show the reactions the scenario measures: {wrong} '
                        f'generators={vanilla["scores"].get("g_cobblestone")}/{vanilla["scores"].get("h_basalt")}')
    differing_scores = sorted(k for k in scores() if vanilla['scores'].get(k) != forbric['scores'].get(k))
    differing_cells = sorted(k for k in set(vanilla['cells']) | set(forbric['cells'])
                             if vanilla['cells'].get(k) != forbric['cells'].get(k))
    if not vanilla['cells']:
        failures.append('vanilla saved no blocks in the build box')
    if differing_scores:
        failures.append(f'{len(differing_scores)} score(s) differ from vanilla: '
                        + ', '.join(f'{k} vanilla={vanilla["scores"].get(k)} forbric={forbric["scores"].get(k)}' for k in differing_scores))
    if differing_cells:
        failures.append(f'{len(differing_cells)} block(s) differ from vanilla: '
                        + ', '.join(f'{k} vanilla={vanilla["cells"].get(k)} forbric={forbric["cells"].get(k)}' for k in differing_cells[:12]))
    jar = kernel / 'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar'
    mc = Path(os.environ.get('MC_DIR', Path.home() / 'Library/Application Support/minecraft'))
    vanilla_jar = Path(os.environ.get('VANILLA_JAR', mc / 'versions/26.2/26.2.jar'))
    summary = dict(unfixed=args.unfixed, passed=not failures, failures=failures,
                   kernel_sha256=hashlib.sha256(jar.read_bytes()).hexdigest() if jar.is_file() else None,
                   vanilla_jar_sha256=hashlib.sha256(vanilla_jar.read_bytes()).hexdigest() if vanilla_jar.is_file() else None,
                   scores={arm: result['scores'] for arm, result in arms.items()},
                   block_counts={arm: {b: list(result['cells'].values()).count(b) for b in sorted(set(result['cells'].values()))}
                                 for arm, result in arms.items()},
                   differing_scores=differing_scores, differing_cells=differing_cells)
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    for failure in failures:
        print(f'[fluid-parity] FAIL {failure}', flush=True)
    print(f'[fluid-parity] {"PASS" if not failures else "RED"}: {output}', flush=True)
    sys.exit(0 if not failures else 1)


def compile_canary(kernel, family, image, output):
    """canary/fluid-interactions/<family>, compiled against that loader's own installed jars only; deterministic bytes."""
    short, _, game = NATIVE[family]
    source = kernel / 'canary/fluid-interactions' / short
    classpath = [image / game] + sorted((image / 'libraries').rglob('*.jar'))
    toml = 'META-INF/' + ('neoforge.mods.toml' if family == 'neoforge' else 'mods.toml')
    jar = output / f'forbricfluid{short}-1.0.0.jar'
    with tempfile.TemporaryDirectory(prefix=f'canary-{short}-', dir=output) as temp:
        subprocess.run(['javac', '-proc:none', '--release', '21', '-cp', os.pathsep.join(map(str, classpath)), '-d', temp,
                        *map(str, sorted((source / 'src').rglob('*.java')))], check=True)
        entries = [(p.relative_to(temp).as_posix(), p.read_bytes()) for p in sorted(Path(temp).rglob('*.class'))]
    entries.append((toml, (source / toml).read_bytes()))
    other = b'net/minecraftforge' if family == 'neoforge' else b'net/neoforged'
    if any(other in data for name, data in entries if name.endswith('.class')):
        raise RuntimeError(f'{jar.name} names the other loader')
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in entries:
            archive.writestr(zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0)), data, zipfile.ZIP_DEFLATED)
    return jar


def copy_image(image, server):
    """The installed loader, copied for this run (a clone where the file system has them); the image is never written."""
    if subprocess.run(['cp', '-c', '-R', str(image), str(server)], capture_output=True).returncode != 0:
        shutil.rmtree(server, ignore_errors=True)
        shutil.copytree(image, server, symlinks=True)


def read_mod_scores(text):
    raw = {m.group(1): int(m.group(2)) for m in re.finditer(r'#([A-Za-z0-9_]+) has (-?\d+) \[fp\]', text)}
    return {name: (MOD_CODES[raw[name]] if 0 <= raw.get(name, -1) < len(MOD_CODES) else raw.get(name)) for name in mod_scores()}


def fired(text):
    """Per case: how many times each canary reported its rule firing at that case's cell."""
    cells = {mod_cell(i): name for i, (name, _, _) in enumerate(MOD_CASES)}
    counts = {name: {'neoforge': 0, 'minecraftforge': 0} for name, _, _ in MOD_CASES}
    for m in FIRED.finditer(text):
        name = cells.get((int(m.group(2)), int(m.group(3)), int(m.group(4))))
        if name:
            counts[name][m.group(1)] += 1
    return counts


def mods_main(args, kernel, output):
    controls = (args.native_controls or kernel / 'build/native-controls').resolve()
    images = {family: controls / 'native' / short for family, (short, _, _) in NATIVE.items()}
    for family, image in images.items():
        if not (image / 'installation.json').is_file():
            print(f'[fluid-parity] FAIL no native {family} image at {image}: run native-controls.py prepare', flush=True)
            return 2
    jars = {family: compile_canary(kernel, family, image, output) for family, image in images.items()}
    port = 25560 + os.getpid() % 200
    arms = {}
    for arm in ('neoforge', 'minecraftforge', 'forbric'):
        run = output / arm
        if arm == 'forbric':
            stage(run, port, mod_functions(), list(jars.values()))
            launcher, cwd = kernel / 'run/launch-kernel-server.sh', None
            env = dict(os.environ, RUNDIR=str(run), FORBRIC_COMPAT_POLICY='strict',
                       FORBRIC_JVM='-Xmx2G' + (' -Dforbric.fluidInteractions=off' if args.unfixed else ''))
        else:
            server = run / 'server'
            run.mkdir(parents=True)
            copy_image(images[arm], server)
            stage(server, port, mod_functions(), [jars[arm]])
            launcher = ['java', '-Xmx2G', '-Djava.awt.headless=true', f'@libraries/{NATIVE[arm][1]}/unix_args.txt', 'nogui']
            cwd, env = server, dict(os.environ)
        port += 1
        print(f'[fluid-parity] {arm}: booting', flush=True)
        text, finished, code = boot(launcher, run, env, names=mod_scores(), cwd=cwd)
        arms[arm] = dict(finished=finished, exit=code, saved='All dimensions are saved' in text or 'Saved the game' in text,
                         registered=sorted(set(re.findall(r'\[FluidCanary\] (\w+) rules registered', text))),
                         scores=read_mod_scores(text), fired=fired(text))
        print(f'[fluid-parity] {arm}: finished={finished} exit={code} saved={arms[arm]["saved"]} '
              f'registered={arms[arm]["registered"]} scores={json.dumps(arms[arm]["scores"])}', flush=True)

    failures = []
    for arm, result in arms.items():
        if not (result['finished'] and result['saved'] and result['exit'] == 0):
            failures.append(f'{arm} did not run the scenario to the end, save and stop')
        expected = ['minecraftforge', 'neoforge'] if arm == 'forbric' else [arm]
        if result['registered'] != expected:
            failures.append(f'{arm} registered the rules of {result["registered"]}, not {expected}')
    neo, forge, forbric = arms['neoforge'], arms['minecraftforge'], arms['forbric']
    if neo['scores'].get('N_neighbour') != 'glowstone' or forge['scores'].get('M_place') != 'shroomlight' \
            or {neo['scores'].get('V_place'), forge['scores'].get('V_place')} != {'obsidian'}:
        failures.append('the native servers did not show the reactions the scenario measures')
    cases = {}
    for name, judge, _ in MOD_CASES:
        natives = [neo, forge] if judge == 'both' else [arms[judge]]
        for key in (name, name + '_end'):
            want = {n['scores'].get(key) for n in natives}
            got = forbric['scores'].get(key)
            if len(want) != 1:
                failures.append(f'{key}: the two native servers disagree ({neo["scores"].get(key)} / {forge["scores"].get(key)})')
            elif got not in want:
                failures.append(f'{key}: forbric={got}, native {judge}={next(iter(want))}')
        reference = {family: max(n['fired'][name][family] for n in natives) for family in ('neoforge', 'minecraftforge')}
        if forbric['fired'][name] != reference:
            failures.append(f'{name}: the canaries fired {forbric["fired"][name]} on forbric, {reference} on {judge}')
        cases[name] = dict(judge=judge, forbric=[forbric['scores'].get(name), forbric['scores'].get(name + '_end')],
                           native=[[n['scores'].get(name), n['scores'].get(name + '_end')] for n in natives],
                           fired=forbric['fired'][name], native_fired=reference)
    jar = kernel / 'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar'
    summary = dict(mode='mods', unfixed=args.unfixed, passed=not failures, failures=failures,
                   kernel_sha256=hashlib.sha256(jar.read_bytes()).hexdigest() if jar.is_file() else None,
                   canaries={j.name: hashlib.sha256(j.read_bytes()).hexdigest() for j in jars.values()},
                   native_controls=str(controls), cases=cases, arms=arms)
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    for failure in failures:
        print(f'[fluid-parity] FAIL {failure}', flush=True)
    print(f'[fluid-parity] {"PASS" if not failures else "RED"}: {output}', flush=True)
    return 0 if not failures else 1


if __name__ == '__main__':
    main()

