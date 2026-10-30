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
             broadcast, and the random draws) — for the developer probe

What the gate judges, per row: the air and health sampled at the end of every tick (or a liquid row's result), and what
the NeoForge canary's listeners saw, against native NeoForge running the same jar. Since Stage 3 of dropping
MinecraftForge, Forbric refuses the MinecraftForge canary (a MinecraftForge-only jar), so the NeoForge canary is the
only one that runs: its listeners, vanilla, and the kernel's repairs of the merged game make every row, and a row's
f-* tags do nothing, as they do on native NeoForge. What only the MinecraftForge canary produced is no longer judged:
its listeners' views, the answering order against NeoForge's, its bubble count's random draws, its liquids' rows, and
the comparison with native MinecraftForge (the reference still holds it: a native loader runs its own jar). Instead,
no row may carry a view of a MinecraftForge listener, and the rows in its liquids must be skipped because the block or
item is not registered, as on native NeoForge. Two rows are a documented deliberate difference ('vanilla-refill': the
owner's vanilla refill, which the merged breathing hook gives a body that breathes with its eye in water, where native
NeoForge holds the air; NeoForge's breathe listener is handed vanilla's refill, and the reference must still show
native NeoForge handing it nothing, and where the body keeps that refill its air must be what native MinecraftForge's
hook, which refills as vanilla does, gives the same row, and not native NeoForge's). The random draws and the entity
events are not judged (introduction §19).
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
NATIVE_VERSIONS = {'forge': ('net/minecraftforge/forge', '26.2-65.0.1'), 'neo': ('net/neoforged/neoforge', '26.2.0.93')}
FABRIC_LAUNCHER = 'fabric-server-mc26.2-loader0.19.5-launcher1.1.2.jar'
# The server.properties every arm runs with (gate-m57.sh writes the same lines; the port is the caller's).
PROPERTIES = ['level-seed=forbrickernel', 'level-name=world', 'level-type=minecraft:flat', 'generate-structures=false',
              'max-tick-time=-1', 'pause-when-empty-seconds=0', 'online-mode=false', 'spawn-animals=false',
              'view-distance=3', 'simulation-distance=3', 'spawn-protection=0', 'sync-chunk-writes=false']

F, N = 'forge', 'neo'
# Row -> checks, every row judged against native NeoForge running the same jar (STAGE 3 OF DROPPING MinecraftForge: the
# MinecraftForge canary is refused, so its f-* tags do nothing on Forbric, as on native NeoForge), and no row may carry a
# view of a MinecraftForge listener (nothing of the refused jar runs). On a native loader every row is what that loader's
# own canary's listeners do alone, so the native NeoForge run is the expectation of every row, the mixed ones included.
#   ('none', family, kind)   that family's listener was never asked for this body
#   ('vanilla-refill', air)  a deliberate difference (the owner's vanilla refill, VanillaAirRefillInjector, introduction
#                            §6): a body that breathes with its eye in water refills as vanilla does, so NeoForge's breathe
#                            listener's first note contains VANILLA_REFILL where native NeoForge's contains NEO_REFILL (the
#                            reference must still show that). With air=True the body keeps that refill: its air is what
#                            native MinecraftForge, whose hook refills as vanilla does there, gives the same row (no
#                            MinecraftForge listener acts in it), and native NeoForge's air must differ from it. With
#                            air=False a NeoForge listener takes the refill and the air is native NeoForge's. The rest of the
#                            row (health, every NeoForge view's count and ticks) is judged as native NeoForge.
#   ('refused',)             a row in the MinecraftForge canary's liquids: it is played only where that jar runs, so it must
#                            be skipped because its block or item is not registered, as on native NeoForge
EXPECT = {
    'drown-read': [],
    'drown-late': [],
    # The f-* rows: the MinecraftForge canary's listeners would cancel, change or count here; with it refused, the body
    # drowns as on native NeoForge, where those tags do nothing either.
    'drown-f-cancel': [],
    'drown-f-notdrowning': [],
    'drown-f-damage3': [],
    'drown-f-damage0': [],
    'drown-f-bubbles0': [],
    'drown-f-bubbles50': [],
    'drown-f-ann-cancel': [],
    # Water breathing: nobody drowns. Native NeoForge holds the air (its hook refills nothing where the eye is in water);
    # the merged hook refills as vanilla does, the owner's choice.
    'drown-read-waterbreathing': [('none', N, 'drown'), ('vanilla-refill', True)],
    'drown-n-cancel': [],
    'drown-n-notdrowning': [],
    'drown-n-damage3': [],
    'drown-n-bubbles0': [],
    # A NeoForge listener that makes a body drown before air -20.
    'drown-n-drowning': [],
    'drown-n-incoming-cancel': [],
    'drown-n-damage3-f-damage0': [],
    'drown-n-cancel-f-damage3': [],
    'drown-n-damage3-f-cancel': [],
    # NeoForge's LOW listeners, registered from common setup, and its LOWEST ones, registered in its constructor.
    'drown-nlow-damage3-f-damage0': [],
    'drown-nlow-cancel-f-damage3': [],
    'drown-nlowest-damage3-f-damage0': [],
    'breathe-read': [],
    'breathe-f-refill2': [],
    'breathe-f-cant': [],
    'breathe-f-ann-refill2': [],
    'breathe-f-can-water': [],
    'breathe-n-refill0': [],
    'breathe-n-cant': [],
    'breathe-n-refill0-f-refill2': [],
    'breathe-n-cant-f-can': [],
    'breathe-nlow-refill0-f-refill2': [],
    'breathe-nlowest-refill0-f-refill2': [],
    # Water breathing in water: NeoForge's listener is handed the vanilla refill and takes it (refill 0), so the air holds
    # as on native NeoForge, where the hook handed it nothing.
    'breathe-waterbreathing-water-n-refill0-f-refill2': [('vanilla-refill', False)],
    'breathe-nautilus-air-n-refill0-f-refill2': [],
    # Breath of the Nautilus alone: the body breathes, and in water holds its air (vanilla holds it there too), in a
    # bubble column refills.
    'breathe-nautilus-water': [],
    'breathe-nautilus-water-f-refill2': [],
    'breathe-nautilus-water-f-can': [],
    'breathe-nautilus-bubbles': [],
    'breathe-nautilus-bubbles-n-refill0-f-refill2': [],
    'drown-read-forgemurk': [('refused',)],
    'drown-f-damage3-forgemurk': [('refused',)],
    'breathe-nautilus-forgemurk': [('refused',)],
    'drown-read-neomurk': [],
    'drown-read-forgecalm': [('refused',)],
    'breathe-nautilus-forgecalm': [('refused',)],
    'player-drown-read': [],
    'player-drown-f-hurt-halve': [],
    'player-drown-f-ann-damage-cancel': [],
    'player-drown-n-incoming-cancel': [],
    'water-bucket': [],
    'forge-bucket': [('refused',)],
    'neo-bucket': [],
}
# The rows in or with MinecraftForge's liquids (each liquid block and bucket is the MinecraftForge canary's).
FORGE_LIQUID_ROWS = {name for name, checks in EXPECT.items() if ('refused',) in checks}
# The rows the owner's vanilla refill decides.
VANILLA_REFILL_ROWS = {name for name, checks in EXPECT.items() if any(c[0] == 'vanilla-refill' for c in checks)}
# The refill NeoForge's breathe listener is handed with its eye in water: vanilla's, and native NeoForge's (nothing).
VANILLA_REFILL, NEO_REFILL = 'refill=4 ->', 'refill=0 ->'
JUDGED = ('air', 'health', 'result')
# Stored in the reference besides what is judged: the random draws, which every run of one loader must agree on.
STORED = JUDGED + ('rng',)
# The MinecraftForge canary's blocks and items: a row in them is skipped where that jar does not run.
REFUSED_SKIPS = ('no block forbricbreathforge:', 'no item forbricbreathforge:')
PHASES = ('positive', 'refused', 'vanilla-refill-off')


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


def judge_rows(reference, result):
    """{row: [reasons]} for every row EXPECT names; an empty list is a pass."""
    rows = {row['name']: row for row in result['rows']}
    verdicts = {}
    for name, checks in EXPECT.items():
        row = rows.get(name)
        native = reference['families'][N]['rows'].get(name)
        if row is None: verdicts[name] = ['no such row in the Forbric result']; continue
        if ('refused',) in checks: verdicts[name] = refused_reasons(row, native); continue
        if 'skipped' in row: verdicts[name] = [f'skipped on Forbric: {row["skipped"]}']; continue
        if native is None or 'skipped' in native:
            verdicts[name] = [f'the {N} reference has no measured row (regenerate it)']; continue
        reasons = []
        native = {k: v for k, v in native.items() if k not in STORED or k in JUDGED}
        mine = judged_row(row, {N})
        refill = next((c for c in checks if c[0] == 'vanilla-refill'), None)
        if refill:
            native, mine = without_notes(native, N), without_notes(mine, N)   # what the listener was handed: checked below
            if refill[1]: native.pop('air', None); mine.pop('air', None)      # the vanilla refill: checked below
        reasons += [f'vs native {N}: {d}' for d in diff(native, mine)]
        views = row.get('views', {})
        for check in checks:
            if check[0] == 'none':
                _, family, kind = check
                if views.get(family, {}).get(kind, {}).get('count', 0):
                    reasons.append(f'{family} {kind} was asked {views[family][kind]["count"]}x; it must not be')
            elif check[0] == 'vanilla-refill':
                reasons += vanilla_refill_reasons(reference, name, row, check[1])
        heard = sorted(kind for kind, v in views.get(F, {}).items() if v.get('count'))
        if heard: reasons.append(f'{F} {", ".join(heard)}: a listener of the refused MinecraftForge canary heard this body')
        verdicts[name] = reasons
    return verdicts


def refused_reasons(row, native):
    """A row in the MinecraftForge canary's liquids: skipped because the block or item is not registered, as natively."""
    skip = row.get('skipped', '')
    if not skip.startswith(REFUSED_SKIPS):
        return ['the refused MinecraftForge canary\'s liquid was there: ' + (f'skipped for another cause ({skip})' if skip else 'the row was played')]
    if (native or {}).get('skipped') != skip:
        return [f'native {N} does not skip it so ({(native or {}).get("skipped")!r}; regenerate the reference)']
    return []


def vanilla_refill_reasons(reference, name, row, keeps):
    """The owner's vanilla refill: NeoForge's breathe listener handed vanilla's refill, and the body's air the vanilla refill
    (native MinecraftForge's, which refills as vanilla does) where it keeps it; the reference must still show native NeoForge
    handing it nothing and, where the body keeps the refill, holding air that is not the vanilla refill."""
    reasons = []
    held, vanilla = reference['families'][N]['rows'].get(name) or {}, (reference['families'][F]['rows'].get(name) or {}).get('air')
    given = held.get('views', {}).get(N, {}).get('breathe', {}).get('notes', [])
    if not given or NEO_REFILL not in given[0] or (keeps and (not vanilla or held.get('air') == vanilla)):
        reasons.append(f'the native reference does not show the documented difference (native {N} handing its breathe listener '
                       f'{NEO_REFILL!r}' + (f', and its air not native {F}\'s vanilla refill' if keeps else '') +
                       '; regenerate it, or the expectation is stale)')
    if keeps and row.get('air') != vanilla:
        reasons.append(f'air: not the vanilla refill native {F} shows for this row ({first_difference(vanilla or [], row.get("air", []))})')
    told = row.get('views', {}).get(N, {}).get('breathe', {}).get('notes', [])
    if not told or VANILLA_REFILL not in told[0]:
        reasons.append(f'{N} breathe first note must contain {VANILLA_REFILL!r}: {told[:1]}')
    return reasons


def predicted_failures(phase):
    """Which rows a phase must fail; exact both ways."""
    if phase in ('positive', 'refused'): return set()
    if phase == 'vanilla-refill-off': return set(VANILLA_REFILL_ROWS)
    raise SystemExit(f'unknown phase {phase}')


def phase_cause(phase, row, reasons):
    """Whether a row a phase predicted to fail failed for that phase's cause, not for another; with what was looked for."""
    if phase == 'vanilla-refill-off':
        hit = any(r.startswith(f'{N} breathe first note must contain {VANILLA_REFILL!r}') for r in reasons)
        return hit, "NeoForge's breathe listener was handed NeoForge's own refill, not vanilla's"
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
    predicted = predicted_failures(args.phase)
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
