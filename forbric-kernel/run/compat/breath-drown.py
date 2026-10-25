#!/usr/bin/env python3
"""gate-m57's measurements against the native loaders: breathing, drowning, the drowning damage chain and a liquid's
bucket round trip, each family's listeners alone and both in one pack.

  run        one dedicated server with the canaries (native MinecraftForge / NeoForge / Fabric image, or Forbric) and
             its probe result: breath-drown.py run --engine native-forge --mods a.jar ... --driver forge --out DIR
  reference  rebuild the canaries, run each native loader --runs times (3 by default), and write
             canary/forge-breath-drown/native-reference.json — or stop, naming the differences, if any run of one loader
             disagrees with its first on a stored field (everything judged, and the random draws)
  judge      one Forbric result against the reference, for a gate phase: prints a line per row and exits 1 unless the
             rows that failed are exactly the ones that phase predicts, each for that phase's cause
  compare    two results field by field, including what the gate does not judge (the entity events the server
             broadcast, and the random draws outside the rows that check them) — for the developer probe

What the gate judges, per row: the air and health sampled at the end of every tick (or a liquid row's result), and the
view of the listener families the row names in EXPECT, each against that family's native loader running the same jar.
A tag of the other family does nothing on a native loader, so the native run of a row is what its deciding family's
listeners produce alone: where both families act, EXPECT names the one whose answer stands, and adds what the other
family must have seen. NeoForge's listeners answer first, then MinecraftForge's, because the kernel posts
MinecraftForge's events from NeoForge's at LOWEST after every mod is constructed and before any mod's setup runs: that
holds for every NeoForge listener above LOWEST and every one registered before the kernel's bridges (a mod's
constructor, an @EventBusSubscriber class). The NeoForge canary holds both: its LOW listeners, registered from common
setup (after the bridges), would answer after a bridge at any priority above LOWEST, and its LOWEST listeners,
registered in its constructor, after bridges installed before the mods are constructed. A NeoForge listener
registered at LOWEST later answers after MinecraftForge's, and the gate's canaries have none (introduction §19). The
order is recorded, never assumed: a row where a NeoForge listener and MinecraftForge's both answered an event must
record the order of a tick they both answered. One row is a documented deliberate difference ('owner-refill': the
owner's vanilla refill, which the reference must still show natively). The body's random draws are judged only where
a row must draw what another row draws ('rng-as': a MinecraftForge listener's bubble count, which natively nothing
reads), and only once the probe read them on every tick; the entity events never are (both documented in
introduction §19).
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

KERNEL = Path(__file__).resolve().parents[2]
CANARY = KERNEL / 'canary/forge-breath-drown'
REFERENCE = CANARY / 'native-reference.json'
NATIVE = Path(os.environ.get('M57_NATIVE', KERNEL / 'build/native-controls/native'))
JARS = {'forge': 'forbricbreathforge.jar', 'neo': 'forbricbreathneo.jar'}
NATIVE_VERSIONS = {'forge': ('net/minecraftforge/forge', '26.2-65.0.1'), 'neo': ('net/neoforged/neoforge', '26.2.0.88')}
FABRIC_LAUNCHER = 'fabric-server-mc26.2-loader0.19.5-launcher1.1.2.jar'
# The server.properties every arm runs with (gate-m57.sh writes the same lines; the port is the caller's).
PROPERTIES = ['level-seed=forbrickernel', 'level-name=world', 'level-type=minecraft:flat', 'generate-structures=false',
              'max-tick-time=-1', 'pause-when-empty-seconds=0', 'online-mode=false', 'spawn-animals=false',
              'view-distance=3', 'simulation-distance=3', 'spawn-protection=0', 'sync-chunk-writes=false']

F, N = 'forge', 'neo'
# Row -> (families whose native run of the row is the expectation, checks on the other family in a mixed pack).
#   ('none', family, kind)        that family's listener was never asked for this body (NeoForge's answer stopped it)
#   ('asked', family, kind)       that family's listener was asked
#   ('sees', family, kind, text)  that family's first note of that kind contains text (it was handed the other's answer)
#   ('rng-as', row)               the body drew from its random source what <row>'s body drew, tick for tick, here and
#                                 in every native run the row is judged against
#   ('owner-refill', row, native, merged)
#                                 a deliberate difference (the owner's vanilla refill, introduction §6 and §8.1): the
#                                 body's air is what <row>'s body does on native MinecraftForge (the vanilla refill) and
#                                 MinecraftForge's breathe listener's first note contains <merged>, where this row's own
#                                 native run holds the air and its note contains <native> (the reference must still show
#                                 that); the rest of the row (health, every view's count and ticks) is judged as native
EXPECT = {
    'drown-read': ({F, N}, []),
    'drown-late': ({F, N}, []),
    'drown-f-cancel': ({F}, []),
    'drown-f-notdrowning': ({F}, []),
    'drown-f-damage3': ({F}, []),
    'drown-f-damage0': ({F}, []),
    # A MinecraftForge listener's bubble count: MinecraftForge's hook never reads it, so natively the body draws what it
    # draws with none (drown-late); NeoForge's hook draws six numbers per bubble, so a count carried back would not.
    'drown-f-bubbles0': ({F}, [('rng-as', 'drown-late')]),
    'drown-f-bubbles50': ({F}, [('rng-as', 'drown-late')]),
    'drown-f-ann-cancel': ({F}, []),
    # Water breathing: nobody drowns. Native NeoForge also holds the air (its hook refills nothing where the eye is in
    # water); the merged hook refills like vanilla and MinecraftForge, the owner's choice (introduction §6).
    'drown-read-waterbreathing': ({F}, [('none', F, 'drown'), ('none', N, 'drown')]),
    'drown-n-cancel': ({N}, [('none', F, 'drown'), ('none', F, 'drown-ann')]),
    'drown-n-notdrowning': ({N}, [('none', F, 'drown'), ('none', F, 'drown-ann')]),
    'drown-n-damage3': ({N}, [('sees', F, 'drown', 'damage=3.0 ')]),
    'drown-n-bubbles0': ({N}, [('sees', F, 'drown', 'bubbles=0 ')]),
    # A NeoForge listener that makes a body drown before air -20: MinecraftForge's listeners are asked from then on,
    # with the air the body has (natively MinecraftForge asks only from -20; no native loader has both families).
    'drown-n-drowning': ({N}, [('sees', F, 'drown', 'drowning=true damage=2.0 bubbles=8 air=0 ')]),
    # MinecraftForge's attack event is posted at the head of hurtServer, its own position and before NeoForge's
    # incoming-damage event, so a NeoForge cancel there stops MinecraftForge's hurt and damage but not its attack.
    'drown-n-incoming-cancel': ({N}, [('asked', F, 'attack'), ('none', F, 'hurt'), ('none', F, 'damage-ann')]),
    'drown-n-damage3-f-damage0': ({F}, [('sees', F, 'drown', 'damage=3.0 ')]),
    'drown-n-cancel-f-damage3': ({N}, [('none', F, 'drown'), ('none', F, 'drown-ann')]),
    'drown-n-damage3-f-cancel': ({F}, [('sees', F, 'drown', 'damage=3.0 ')]),
    # NeoForge's LOW listeners, registered from common setup, after the kernel's bridges: they answer before
    # MinecraftForge's all the same (a bridge above LOWEST would answer before them, and these rows would fail).
    'drown-nlow-damage3-f-damage0': ({F}, [('sees', F, 'drown', 'damage=3.0 ')]),
    'drown-nlow-cancel-f-damage3': ({N}, [('none', F, 'drown'), ('none', F, 'drown-ann')]),
    # NeoForge's LOWEST listeners, registered in its constructor, before the kernel's bridges: they answer before
    # MinecraftForge's (bridges installed before the mods are constructed would answer before them, and these rows fail).
    'drown-nlowest-damage3-f-damage0': ({F}, [('sees', F, 'drown', 'damage=3.0 ')]),
    'breathe-read': ({F, N}, []),
    'breathe-f-refill2': ({F}, []),
    'breathe-f-cant': ({F}, []),
    'breathe-f-ann-refill2': ({F}, []),
    'breathe-f-can-water': ({F}, []),
    'breathe-n-refill0': ({N}, [('sees', F, 'breathe', 'refill=0 ->')]),
    'breathe-n-cant': ({N}, [('sees', F, 'breathe', 'forge can=false canRefill=true consume=3 ')]),
    'breathe-n-refill0-f-refill2': ({F}, [('sees', F, 'breathe', 'refill=0 ->')]),
    'breathe-n-cant-f-can': ({F}, [('sees', F, 'breathe', 'forge can=false ')]),
    'breathe-nlow-refill0-f-refill2': ({F}, [('sees', F, 'breathe', 'refill=0 ->')]),
    'breathe-nlowest-refill0-f-refill2': ({F}, [('sees', F, 'breathe', 'refill=0 ->')]),
    # NeoForge refills nothing, so MinecraftForge's own rule decides canRefillAir, each row by the one clause that says
    # yes: water breathing in water (the effects), Breath of the Nautilus alone in open air (the eye in air); the
    # bubble column below. MinecraftForge's listener is offered NeoForge's nothing, may refill, and its refill of 2 is taken.
    'breathe-waterbreathing-water-n-refill0-f-refill2': ({F}, [('sees', F, 'breathe', 'forge can=true canRefill=true consume=1 refill=0 ->')]),
    'breathe-nautilus-air-n-refill0-f-refill2': ({F}, [('sees', F, 'breathe', 'forge can=true canRefill=true consume=1 refill=0 ->')]),
    # Breath of the Nautilus alone: the body breathes; in water MinecraftForge's listeners are told it may not refill
    # (canRefillAir false: a refill they set is not taken, unless they also let it refill), in a bubble column that it may.
    'breathe-nautilus-water': ({F, N}, []),
    'breathe-nautilus-water-f-refill2': ({F}, []),
    'breathe-nautilus-water-f-can': ({F}, []),
    'breathe-nautilus-bubbles': ({F, N}, []),
    # NeoForge refills nothing, so MinecraftForge's own rule decides canRefillAir — the bubble column says yes — and
    # NeoForge's nothing is what MinecraftForge's listener is offered.
    'breathe-nautilus-bubbles-n-refill0-f-refill2': ({F}, [('sees', F, 'breathe', 'forge can=true canRefill=true consume=1 refill=0 ->')]),
    'drown-read-forgemurk': ({F}, []),
    'drown-f-damage3-forgemurk': ({F}, []),
    'breathe-nautilus-forgemurk': ({F}, []),
    'drown-read-neomurk': ({N}, []),
    # MinecraftForge's liquid built canDrown(false): only its type's canDrownIn says a body does not drown there.
    'drown-read-forgecalm': ({F}, []),
    # Breath of the Nautilus alone in that liquid: the owner's vanilla refill (§6) refills as in open air (breathe-read)
    # and MinecraftForge's listener reads canRefillAir true; native MinecraftForge reads false and holds the air.
    'breathe-nautilus-forgecalm': ({F}, [('owner-refill', 'breathe-read', 'canRefill=false', 'canRefill=true')]),
    'player-drown-read': ({F, N}, []),
    'player-drown-f-hurt-halve': ({F}, []),
    'player-drown-f-ann-damage-cancel': ({F}, []),
    # MinecraftForge's attack event is posted at the head of Player.hurtServer, its own position and before NeoForge's
    # incoming-damage event, so a NeoForge cancel there stops MinecraftForge's hurt and damage but not its attack.
    'player-drown-n-incoming-cancel': ({N}, [('asked', F, 'attack'), ('none', F, 'hurt'), ('none', F, 'damage-ann')]),
    'water-bucket': ({F, N}, []),
    'forge-bucket': ({F}, []),
    'neo-bucket': ({N}, []),
}
# Rows where both families act and MinecraftForge's answer stands: its listeners were handed NeoForge's answer, so what
# they saw (the first half of each note) is not what they see natively; their counts and ticks are still compared, and
# what they were handed is checked by the 'sees' checks above.
HANDED_NEOFORGES_ANSWER = {'drown-n-damage3-f-damage0', 'drown-n-damage3-f-cancel', 'breathe-n-refill0-f-refill2', 'breathe-n-cant-f-can',
                           'breathe-nautilus-bubbles-n-refill0-f-refill2', 'drown-nlow-damage3-f-damage0', 'breathe-nlow-refill0-f-refill2',
                           'drown-nlowest-damage3-f-damage0', 'breathe-nlowest-refill0-f-refill2',
                           'breathe-waterbreathing-water-n-refill0-f-refill2', 'breathe-nautilus-air-n-refill0-f-refill2'}
# The rows in or with MinecraftForge's liquids, which the getters decide (each liquid block is built from a supplier).
FORGE_LIQUID_ROWS = {'drown-read-forgemurk', 'drown-f-damage3-forgemurk', 'breathe-nautilus-forgemurk', 'drown-read-forgecalm',
                     'breathe-nautilus-forgecalm', 'forge-bucket'}
# The rows with a body in MinecraftForge's drowning liquid, which its own type decides: without it the untagged liquid is
# air to NeoForge and the body breathes there. (In the liquid built canDrown(false) a body breathes either way, natively
# too, and refills as in any liquid but water: own-type-off holds those rows; only the type's canDrownIn decides the
# first, and the second refills as the owner's vanilla refill does wherever the eye is not in water.)
FORGE_LIQUID_BODY_ROWS = {'drown-read-forgemurk', 'drown-f-damage3-forgemurk', 'breathe-nautilus-forgemurk'}
JUDGED = ('air', 'health', 'result')
# Stored in the reference besides what is judged directly: the random draws, for the 'rng-as' checks.
STORED = JUDGED + ('rng',)
# MinecraftForge's events each phase switches off: breathe and drown (the bridges), hurt (the damage seams).
BRIDGED_KINDS = ('breathe', 'breathe-ann', 'drown', 'drown-ann')
SEAM_KINDS = ('attack', 'hurt', 'damage-ann')
# The NeoForge kinds whose answering order against MinecraftForge's the probe records (Core.ORDERED), by the event
# they answer, with the name it records them under; MinecraftForge's answers are its 'breathe' and 'drown' kinds.
NEO_ORDERED = {'breathe': (('breathe', N), ('breathe-low', N + '-low'), ('breathe-lowest', N + '-lowest')),
               'drown': (('drown', N), ('drown-low', N + '-low'), ('drown-lowest', N + '-lowest'))}
FORGE_MURK = 'forbricbreathforge:murk'
PHASES = ('positive', 'bridges-off', 'getters-off', 'own-type-off', 'seams-off')


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


# ---- running a server -----------------------------------------------------------------------------------------------

def clone(source, target):
    """A copy of a native image's file or tree: an APFS clone on macOS (instant, no space), a plain copy elsewhere."""
    source, target = Path(source), Path(target)
    if sys.platform == 'darwin':
        subprocess.run(['cp', '-cR' if source.is_dir() else '-c', str(source), str(target)], check=True)
    elif source.is_dir():
        shutil.copytree(source, target)
    else:
        shutil.copy2(source, target)


def run(engine, mods, driver, out, port, jvm='', tree=None, staged=None, timeout=900, policy='strict'):
    """One server in a fresh instance under <out>; returns the probe result (or None) and writes run.json beside it."""
    out = Path(out)
    if out.exists(): shutil.rmtree(out)
    (out / 'mods').mkdir(parents=True)
    for m in mods: shutil.copy(m, out / 'mods' / Path(m).name)
    (out / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
    (out / 'server.properties').write_text('\n'.join(PROPERTIES + [f'server-port={port}', 'server-ip=127.0.0.1']) + '\n', encoding='utf-8')
    result = out / 'breathdrown.json'
    props = [f'-Dforbric.breathDrownDriver={driver}', f'-Dforbric.breathDrownProbe={result}', *jvm.split()]
    env = os.environ.copy()
    if engine.startswith('native-'):
        family = engine.split('-', 1)[1]
        image = NATIVE / family
        if not image.is_dir(): raise SystemExit(f'no native {family} image at {image} (run/compat/native-controls.py prepare)')
        for rel in ('libraries', '.fabric'):
            if (image / rel).exists(): clone(image / rel, out / rel)
        for p in image.glob('*.jar'): clone(p, out / p.name)
        java = ['java', '-Xms256M', '-Xmx2G', '-Djava.awt.headless=true', *props]
        if family == 'fabric': cmd = [*java, '-jar', FABRIC_LAUNCHER, '--nogui']
        else:
            group, version = NATIVE_VERSIONS[family]
            # The installers write both argument files; Windows takes the one with its path separator.
            cmd = [*java, f'@libraries/{group}/{version}/{"win" if os.name == "nt" else "unix"}_args.txt', 'nogui']
    elif engine == 'forbric':
        tree = Path(tree) if tree else KERNEL.parent
        cmd = ['bash', str(tree / 'forbric-kernel/run/launch-kernel-server.sh')]
        env['RUNDIR'] = str(out); env['FORBRIC_COMPAT_POLICY'] = policy
        if staged: env['FORBRIC_OLD'] = str(staged)
        env['GRADLE_OPTS'] = (env.get('GRADLE_OPTS', '') + ' -Dorg.gradle.daemon=false').strip()
        env['FORBRIC_JVM'] = ' '.join(['-Djava.awt.headless=true', *props])
    else:
        raise SystemExit(f'unknown engine {engine}')
    started = time.time()
    with (out / 'server.log').open('w') as log:
        process = subprocess.Popen(cmd, cwd=out, env=env, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            process.wait(timeout)
            timed_out = False
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL); process.wait(); timed_out = True
    record = {'engine': engine, 'driver': driver, 'jvm': jvm, 'port': port, 'command': cmd, 'rc': process.returncode,
              'timedOut': timed_out, 'seconds': round(time.time() - started), 'mods': {Path(m).name: sha256(m) for m in mods},
              'environment': {k: env[k] for k in ('RUNDIR', 'FORBRIC_OLD', 'FORBRIC_JVM', 'FORBRIC_COMPAT_POLICY') if k in env}}
    (out / 'run.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
    return json.loads(result.read_text(encoding='utf-8')) if result.is_file() else None


# ---- what is judged -------------------------------------------------------------------------------------------------

def judged_row(row, families, fields=JUDGED):
    """The part of a probe row the gate compares, with the views of <families> only."""
    if 'skipped' in row: return {'skipped': row['skipped']}
    kept = {k: row[k] for k in fields if k in row}
    kept['views'] = {f: row.get('views', {}).get(f, {}) for f in sorted(families)}
    return kept


def native_rows(result, family):
    """A native result's rows as the reference stores them: what is judged, and the random draws."""
    return {row['name']: judged_row(row, {family}, STORED) for row in result['rows']}


def diff(a, b, path=''):
    """Human-readable differences between two judged values (first few)."""
    out = []
    if isinstance(a, dict) and isinstance(b, dict):
        for k in sorted(set(a) | set(b)):
            if k not in a: out.append(f'{path}{k}: only in Forbric = {str(b[k])[:200]}')
            elif k not in b: out.append(f'{path}{k}: only native = {str(a[k])[:200]}')
            else: out += diff(a[k], b[k], f'{path}{k}.')
    elif a != b:
        out.append(f'{path.rstrip(".")}: native={str(a)[:200]} forbric={str(b)[:200]}')
    return out


def without_notes(judged, family):
    out = json.loads(json.dumps(judged))
    for view in out.get('views', {}).get(family, {}).values(): view.pop('notes', None)
    return out


def first_difference(a, b):
    """Where two per-tick lists first part: '(tick 4: 3 vs 99)'."""
    for i, (x, y) in enumerate(zip(a, b)):
        if x != y: return f'tick {i}: {x} vs {y}'
    return f'lengths {len(a)} vs {len(b)}'


def unreadable_rng(row):
    """Why a row's random draws cannot be compared, or None: the probe counted the body's draws on every sampled tick."""
    rng = row.get('rng')
    if not isinstance(rng, list) or not rng: return 'no random draws recorded'
    if row.get('rngNote'): return f'the seed was not readable ({row["rngNote"]})'
    if len(rng) != len(row.get('air', [])): return f'{len(rng)} draw counts for {len(row.get("air", []))} samples'
    if any(type(x) is not int or x < 0 for x in rng): return 'a tick whose draws were not counted (-1)'
    return None


def judge_rows(reference, result):
    """{row: [reasons]} for every row EXPECT names; an empty list is a pass."""
    rows = {row['name']: row for row in result['rows']}
    native_of = lambda family, name: reference['families'][family]['rows'].get(name)
    verdicts = {}
    for name, (families, checks) in EXPECT.items():
        reasons = []
        row = rows.get(name)
        if row is None: verdicts[name] = ['no such row in the Forbric result']; continue
        if 'skipped' in row: verdicts[name] = [f'skipped on Forbric: {row["skipped"]}']; continue
        for family in sorted(families):
            native = native_of(family, name)
            if native is None or 'skipped' in native:
                reasons.append(f'the {family} reference has no measured row (regenerate it)'); continue
            native = {k: v for k, v in native.items() if k not in STORED or k in JUDGED}
            mine = judged_row(row, {family})
            if family == F and (name in HANDED_NEOFORGES_ANSWER or owner_refill(checks)):
                native, mine = without_notes(native, family), without_notes(mine, family)
            if family == F and owner_refill(checks):
                native.pop('air', None); mine.pop('air', None)   # judged by the 'owner-refill' check below
            reasons += [f'vs native {family}: {d}' for d in diff(native, mine)]
        views = row.get('views', {})
        for check in checks:
            if check[0] == 'none':
                _, family, kind = check
                if views.get(family, {}).get(kind, {}).get('count', 0):
                    reasons.append(f'{family} {kind} was asked {views[family][kind]["count"]}x; it must not be')
            elif check[0] == 'asked':
                _, family, kind = check
                if not views.get(family, {}).get(kind, {}).get('count', 0):
                    reasons.append(f'{family} {kind} was never asked; it must be')
            elif check[0] == 'sees':
                _, family, kind, text = check
                notes = views.get(family, {}).get(kind, {}).get('notes', [])
                if not notes or text not in notes[0]:
                    reasons.append(f'{family} {kind} first note must contain {text!r}: {notes[:1]}')
            elif check[0] == 'rng-as':
                _, other = check
                for family in sorted(families):
                    a, b = (native_of(family, n) or {} for n in (name, other))
                    why = unreadable_rng(a) or unreadable_rng(b)
                    if why or a.get('rng') != b.get('rng'):
                        reasons.append(f'the native {family} reference does not show {name} drawing what {other} draws '
                                       f'({why or "they differ"}; regenerate it, or the expectation is wrong)')
                theirs = rows.get(other, {})
                unread = [f'{n}: {why}' for n, why in ((name, unreadable_rng(row)), (other, unreadable_rng(theirs))) if why]
                if unread:
                    reasons.append(f'rng: the probe did not count the body\'s draws ({"; ".join(unread)})')
                elif row.get('rng') != theirs.get('rng'):
                    reasons.append(f'rng: the body did not draw what {other}\'s drew '
                                   f'({first_difference(row.get("rng", []), theirs.get("rng", []))})')
            elif check[0] == 'owner-refill':
                _, other, native_text, merged_text = check
                held, vanilla = native_of(F, name) or {}, (native_of(F, other) or {}).get('air')
                notes = held.get('views', {}).get(F, {}).get('breathe', {}).get('notes', [])
                if not vanilla or held.get('air') == vanilla or not notes or native_text not in notes[0]:
                    reasons.append(f'the native {F} reference does not show the documented difference ({name} holding its air, '
                                   f'its breathe note with {native_text!r}; regenerate it, or the expectation is stale)')
                if row.get('air') != vanilla:
                    reasons.append(f'air: not the vanilla refill {other} shows natively ({first_difference(vanilla or [], row.get("air", []))})')
                told = views.get(F, {}).get('breathe', {}).get('notes', [])
                if not told or merged_text not in told[0]:
                    reasons.append(f'{F} breathe first note must contain {merged_text!r}: {told[:1]}')
        reasons += order_reasons(row)
        verdicts[name] = reasons
    return verdicts


def owner_refill(checks):
    return any(c[0] == 'owner-refill' for c in checks)


def order_reasons(row):
    """What is wrong with a row's answering order: MinecraftForge before a NeoForge listener in some tick, or a NeoForge
    listener and MinecraftForge's that both answered one event with no tick recording which answered first."""
    reasons, views = [], row.get('views', {})
    recorded = {event: [orders] if isinstance(orders, str) else list(orders) for event, orders in row.get('order', {}).items()}
    for event, orders in recorded.items():
        # Every distinct order of a tick more than one listener answered; neo-low, neo-lowest: the NeoForge canary's
        # LOW listener from common setup and LOWEST listener from its constructor.
        for order in orders:
            fams = order.split(',')
            if F in fams and any(f.split('-')[0] == N and fams.index(f) > fams.index(F) for f in fams):
                reasons.append(f'{event}: MinecraftForge answered before a NeoForge listener in one tick ({order})')
    for event, kinds in NEO_ORDERED.items():
        if not views.get(F, {}).get(event, {}).get('count'): continue
        for kind, label in kinds:
            if views.get(N, {}).get(kind, {}).get('count') and not any({F, label} <= set(o.split(',')) for o in recorded.get(event, [])):
                reasons.append(f'{event}: {label} and {F} both answered, but no tick records which answered first')
    return reasons


def native_forge_kinds(reference, name):
    """The MinecraftForge listener kinds the native MinecraftForge run of a row heard from."""
    native = reference['families'][F]['rows'].get(name, {})
    return {kind for kind, view in native.get('views', {}).get(F, {}).items() if view.get('count')}


def predicted_failures(reference, phase):
    """Which rows a phase must fail, read from EXPECT and the reference; exact both ways."""
    if phase == 'positive': return set()
    if phase == 'bridges-off':
        # Every row judged on what a MinecraftForge listener heard natively, and every row where MinecraftForge must be
        # handed NeoForge's answer ('sees'). Not the 'asked' attack checks: MinecraftForge's attack is posted at its own
        # position in hurtServer, no bridge of NeoForge's.
        out = set()
        for name, (families, checks) in EXPECT.items():
            if F in families and native_forge_kinds(reference, name): out.add(name)
            if any(c[0] == 'sees' and c[1] == F for c in checks): out.add(name)
        return out
    if phase == 'getters-off': return set(FORGE_LIQUID_ROWS)
    if phase == 'own-type-off': return set(FORGE_LIQUID_BODY_ROWS)
    if phase == 'seams-off':
        # MinecraftForge's Hurt has a seam on every merged base (NeoForge's actuallyHurt never reads its answer), so every
        # row whose native MinecraftForge run heard it, judged on MinecraftForge's view, loses it.
        return {name for name, (families, _) in EXPECT.items() if F in families and 'hurt' in native_forge_kinds(reference, name)}
    raise SystemExit(f'unknown phase {phase}')


def phase_cause(phase, row, reasons):
    """Whether a row a phase predicted to fail failed for that phase's cause, not for another; with what was looked for."""
    if phase == 'bridges-off':
        hit = any(f'views.{F}.{kind}' in r or f'{F} {kind} ' in r for r in reasons for kind in BRIDGED_KINDS)
        return hit, "a MinecraftForge breathe or drown listener's view differs (it was not asked)"
    if phase == 'getters-off':
        text = ' '.join([str(row.get('skipped', ''))] + [str(r) for r in row.get('result', [])])
        return '"this.fluid" is null' in text, 'its liquid block threw with "this.fluid" is null (no fluid without the getter)'
    if phase == 'own-type-off':
        inside = f'blockAtEye={FORGE_MURK} ' in (row.get('first') or '')
        moved = any(r.startswith(f'vs native {F}: air') or f'views.{F}.breathe' in r for r in reasons)
        return inside and moved, f'the body was in {FORGE_MURK} and its air or MinecraftForge breathe view differs'
    if phase == 'seams-off':
        hit = any(f'views.{F}.{kind}' in r or f'{F} {kind} ' in r for r in reasons for kind in SEAM_KINDS)
        return hit, "a MinecraftForge attack, hurt or damage listener's view differs"
    return True, ''


def cmd_judge(args):
    reference = json.loads(Path(args.reference).read_text(encoding='utf-8'))
    if not Path(args.result).is_file():
        print(f'[kernel] FAIL {args.phase}: no probe result at {args.result}'); return 1
    result = json.loads(Path(args.result).read_text(encoding='utf-8'))
    if result.get('error'): print(f'[kernel] FAIL {args.phase}: the probe stopped on {result["error"]}'); return 1
    verdicts = judge_rows(reference, result)
    failed = {name for name, reasons in verdicts.items() if reasons}
    for name in EXPECT:
        reasons = verdicts[name]
        print(f'[kernel]   {args.phase}: {"ok  " if not reasons else "FAIL"} {name}' + ('' if not reasons else ' — ' + '; '.join(reasons[:3])[:600]))
    extra = sorted({row['name'] for row in result['rows']} - set(EXPECT))
    if extra: print(f'[kernel]   {args.phase}: rows the gate does not judge: {", ".join(extra)}')
    predicted = predicted_failures(reference, args.phase)
    rows = {row['name']: row for row in result['rows']}
    wrong_cause = []
    for name in sorted(failed & predicted):
        ok, wanted = phase_cause(args.phase, rows.get(name, {}), verdicts[name])
        if not ok: wrong_cause.append(name); print(f'[kernel]   {args.phase}: {name} failed, but not because {wanted}')
    if failed == predicted and not wrong_cause:
        print(f'[kernel] PASS {args.phase}: {len(EXPECT) - len(failed)} rows as native, the {len(failed)} predicted failure(s) '
              f'and no others' + (', each for the phase\'s cause' if failed else ''))
        return 0
    print(f'[kernel] FAIL {args.phase}: failed rows differ from the prediction — unexpected: {sorted(failed - predicted)}, '
          f'expected but held: {sorted(predicted - failed)}, failed for another cause: {wrong_cause}')
    return 1


# ---- the reference --------------------------------------------------------------------------------------------------

def cmd_reference(args):
    env = os.environ.copy()
    if args.staged: env['FORBRIC_OLD'] = str(args.staged)
    subprocess.run(['bash', str(KERNEL / 'run/build-breath-drown-canary.sh')], check=True, env=env)
    built = json.loads((KERNEL / 'run/canary/m57-build-inputs.json').read_text(encoding='utf-8'))
    work = Path(args.work); work.mkdir(parents=True, exist_ok=True)
    families = {}
    for family in (F, N):
        jar = KERNEL / 'run/canary' / JARS[family]
        runs = []
        for i in range(args.runs):
            result = run(f'native-{family}', [jar], family, work / f'native-{family}-{i + 1}', args.port, timeout=args.timeout)
            if result is None or result.get('error'): raise SystemExit(f'native {family} run {i + 1} gave no clean result (see {work})')
            runs.append(native_rows(result, family))
            print(f'native {family} run {i + 1}: {len(result["rows"])} rows')
        for i, other in enumerate(runs[1:], 2):
            if other != runs[0]:
                for name in sorted(set(runs[0]) | set(other)):
                    for d in diff(runs[0].get(name), other.get(name)): print(f'  run 1 vs {i}: {name}: {d}')
                raise SystemExit(f'native {family}: run {i} disagrees with run 1; a reference needs every run to agree')
        image = NATIVE / family
        families[family] = {'loader': '/'.join(NATIVE_VERSIONS[family]), 'runs': args.runs,
                            'installation': sha256(image / 'installation.json') if (image / 'installation.json').is_file() else None,
                            'rows': runs[0]}
    reference = {'about': 'gate-m57 native reference: what each native loader does with the same canary jar, every stored field '
                          '(what is judged, and the random draws) agreeing across all runs. Regenerate with: '
                          'python3 run/compat/breath-drown.py reference',
                 'canarySources': built['canarySources'], 'properties': PROPERTIES, 'families': families}
    write_reference(Path(args.out), reference)
    print(f'wrote {args.out}: {sum(len(f["rows"]) for f in families.values())} rows from {args.runs} agreeing runs per loader')
    return 0


def write_reference(path, reference):
    """One line per row, so a regenerated reference diffs row by row."""
    lines = ['{']
    for key in ('about', 'canarySources', 'properties'):
        lines.append(f' {json.dumps(key)}: {json.dumps(reference[key])},')
    lines.append(' "families": {')
    for i, (family, data) in enumerate(reference['families'].items()):
        lines.append(f'  {json.dumps(family)}: {{')
        for key in (k for k in data if k != 'rows'): lines.append(f'   {json.dumps(key)}: {json.dumps(data[key])},')
        lines.append('   "rows": {')
        rows = list(data['rows'].items())
        for j, (name, row) in enumerate(rows):
            lines.append(f'    {json.dumps(name)}: {json.dumps(row, separators=(",", ":"))}' + (',' if j < len(rows) - 1 else ''))
        lines.append('   }')
        lines.append('  }' + (',' if i < len(reference['families']) - 1 else ''))
    lines.append(' }')
    lines.append('}')
    text = '\n'.join(lines) + '\n'
    if json.loads(text) != reference: raise AssertionError('reference writer lost data')
    path.write_bytes(text.encode('utf-8'))   # LF on every platform: the file is committed


def sources_digest(root=CANARY):
    """The canary's .java and .toml sources, line endings read as LF: a Windows checkout's CRLF is the same source."""
    sources = sorted(p for p in root.rglob('*') if p.is_file() and p.suffix in ('.java', '.toml'))
    return hashlib.sha256(b''.join(p.relative_to(root).as_posix().encode() + b'\0' + p.read_bytes().replace(b'\r\n', b'\n')
                                   for p in sources)).hexdigest()


def cmd_check_sources(args):
    reference = json.loads(Path(args.reference).read_text(encoding='utf-8'))
    now = sources_digest()
    if reference['canarySources'] != now:
        print(f'[kernel] FAIL the native reference was measured with other canary sources ({reference["canarySources"][:12]}, '
              f'the tree has {now[:12]}): regenerate it with run/compat/breath-drown.py reference')
        return 1
    print(f'[kernel] PASS the native reference was measured with these canary sources ({now[:12]})')
    return 0


# ---- the developer probe's comparison -------------------------------------------------------------------------------

def cmd_compare(args):
    a, b = (json.loads(Path(p).read_text(encoding='utf-8')) for p in (args.a, args.b))
    rows_a, rows_b = ({r['name']: r for r in x['rows']} for x in (a, b))
    fields = args.fields.split(',')
    same = differ = 0
    for name in [r['name'] for r in a['rows']]:
        x, y = rows_a[name], rows_b.get(name)
        if y is None or 'skipped' in x or 'skipped' in y:
            print(f'  ONE-SIDE {name}: A={x.get("skipped", "measured")} B={(y or {}).get("skipped", "measured") if y else "absent"}')
            continue
        dx = {k: x.get(k) for k in fields}; dy = {k: y.get(k) for k in fields}
        if args.views: dx['views'] = {f: x.get('views', {}).get(f) for f in args.views.split(',')}; dy['views'] = {f: y.get('views', {}).get(f) for f in args.views.split(',')}
        d = diff(dx, dy)
        same += not d; differ += bool(d)
        print(f'  {"SAME" if not d else "DIFF"} {name}')
        for line in d[:6]: print(f'        {line}'.replace('native=', 'A=').replace('forbric=', 'B='))
    print(f'SUMMARY {args.a} vs {args.b}: {same} same, {differ} different')
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest='command', required=True)
    r = sub.add_parser('run'); r.add_argument('--engine', required=True); r.add_argument('--mods', nargs='+', required=True)
    r.add_argument('--driver', required=True); r.add_argument('--out', required=True); r.add_argument('--port', type=int, required=True)
    r.add_argument('--jvm', default=''); r.add_argument('--tree'); r.add_argument('--staged'); r.add_argument('--timeout', type=int, default=900)
    r.add_argument('--policy', default='strict')
    g = sub.add_parser('reference'); g.add_argument('--runs', type=int, default=3); g.add_argument('--port', type=int, default=int(os.environ.get('GATE_PORT', 25565)))
    g.add_argument('--work', default=str(KERNEL / 'build/m57-reference')); g.add_argument('--out', default=str(REFERENCE))
    g.add_argument('--staged'); g.add_argument('--timeout', type=int, default=900)
    j = sub.add_parser('judge'); j.add_argument('--reference', default=str(REFERENCE)); j.add_argument('--result', required=True)
    j.add_argument('--phase', required=True, choices=PHASES)
    s = sub.add_parser('check-sources'); s.add_argument('--reference', default=str(REFERENCE))
    sub.add_parser('properties', help='the server.properties lines every arm runs with (the gate appends them)')
    c = sub.add_parser('compare'); c.add_argument('a'); c.add_argument('b'); c.add_argument('--fields', default='air,health,result,rng,entityEvents')
    c.add_argument('--views', default='')
    args = p.parse_args(argv)
    if args.command == 'run':
        result = run(args.engine, args.mods, args.driver, args.out, args.port, args.jvm, args.tree, args.staged, args.timeout, args.policy)
        print(f'{args.out}: result={"yes" if result else "no"} rows={len(result["rows"]) if result else 0} error={result and result.get("error")}')
        return 0 if result and not result.get('error') else 1
    if args.command == 'properties':
        print('\n'.join(PROPERTIES)); return 0
    return {'reference': cmd_reference, 'judge': cmd_judge, 'check-sources': cmd_check_sources, 'compare': cmd_compare}[args.command](args)


if __name__ == '__main__':
    sys.exit(main())
