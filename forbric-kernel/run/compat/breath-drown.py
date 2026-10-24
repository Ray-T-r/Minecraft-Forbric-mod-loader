#!/usr/bin/env python3
"""gate-m57's measurements against the native loaders: breathing, drowning, the drowning damage chain and a liquid's
bucket round trip, each family's listeners alone and both in one pack.

  run        one dedicated server with the canaries (native MinecraftForge / NeoForge / Fabric image, or Forbric) and
             its probe result: breath-drown.py run --engine native-forge --mods a.jar ... --driver forge --out DIR
  reference  rebuild the canaries, run each native loader --runs times (3 by default), require every run of one loader
             to agree on everything the gate judges, and write canary/forge-breath-drown/native-reference.json
  judge      one Forbric result against the reference, for a gate phase: prints a line per row and exits 1 unless the
             rows that failed are exactly the ones that phase predicts
  compare    two results field by field, including what the gate does not judge (the body's random draws per tick and
             the entity events the server broadcast) — for the developer probe

What the gate judges, per row: the air and health sampled at the end of every tick (or a liquid row's result), and the
view of the listener families the row names in EXPECT, each against that family's native loader running the same jar.
A tag of the other family does nothing on a native loader, so the native run of a row is what its deciding family's
listeners produce alone: where both families act, EXPECT names the one whose answer stands (NeoForge's listeners answer
first, then MinecraftForge's, as the kernel posts MinecraftForge's events from NeoForge's at LOWEST), and adds what the
other family must have seen. Not judged: the random source and the entity events (documented in introduction §19).
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
MC = Path(os.environ.get('MC_DIR', Path.home() / 'Library/Application Support/minecraft'))
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
EXPECT = {
    'drown-read': ({F, N}, []),
    'drown-f-cancel': ({F}, []),
    'drown-f-notdrowning': ({F}, []),
    'drown-f-damage3': ({F}, []),
    'drown-f-damage0': ({F}, []),
    'drown-f-ann-cancel': ({F}, []),
    'drown-n-cancel': ({N}, [('none', F, 'drown'), ('none', F, 'drown-ann')]),
    'drown-n-notdrowning': ({N}, [('none', F, 'drown'), ('none', F, 'drown-ann')]),
    'drown-n-damage3': ({N}, [('sees', F, 'drown', 'damage=3.0 ')]),
    'drown-n-bubbles0': ({N}, [('sees', F, 'drown', 'bubbles=0 ')]),
    # A NeoForge listener that makes a body drown before air -20: MinecraftForge's listeners are asked from then on,
    # with the air the body has (natively MinecraftForge asks only from -20; no native loader has both families).
    'drown-n-drowning': ({N}, [('sees', F, 'drown', 'drowning=true damage=2.0 bubbles=8 air=0 ')]),
    'drown-n-damage3-f-damage0': ({F}, [('sees', F, 'drown', 'damage=3.0 ')]),
    'drown-n-cancel-f-damage3': ({N}, [('none', F, 'drown'), ('none', F, 'drown-ann')]),
    'drown-n-damage3-f-cancel': ({F}, [('sees', F, 'drown', 'damage=3.0 ')]),
    'breathe-read': ({F, N}, []),
    'breathe-f-refill2': ({F}, []),
    'breathe-f-cant': ({F}, []),
    'breathe-f-ann-refill2': ({F}, []),
    'breathe-f-can-water': ({F}, []),
    'breathe-n-refill0': ({N}, [('sees', F, 'breathe', 'refill=0 ->')]),
    'breathe-n-cant': ({N}, [('sees', F, 'breathe', 'forge can=false canRefill=true consume=3 ')]),
    'breathe-n-refill0-f-refill2': ({F}, [('sees', F, 'breathe', 'refill=0 ->')]),
    'breathe-n-cant-f-can': ({F}, [('sees', F, 'breathe', 'forge can=false ')]),
    'drown-read-forgemurk': ({F}, []),
    'drown-f-damage3-forgemurk': ({F}, []),
    'drown-read-neomurk': ({N}, []),
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
HANDED_NEOFORGES_ANSWER = {'drown-n-damage3-f-damage0', 'drown-n-damage3-f-cancel', 'breathe-n-refill0-f-refill2', 'breathe-n-cant-f-can'}
# The rows in or with MinecraftForge's liquid, which the getter and fluid-type repairs decide.
FORGE_LIQUID_ROWS = {'drown-read-forgemurk', 'drown-f-damage3-forgemurk', 'forge-bucket'}
FORGE_LIQUID_BODY_ROWS = {'drown-read-forgemurk', 'drown-f-damage3-forgemurk'}
JUDGED = ('air', 'health', 'result')


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


# ---- running a server -----------------------------------------------------------------------------------------------

def run(engine, mods, driver, out, port, jvm='', tree=None, staged=None, timeout=900, policy='strict'):
    """One server in a fresh instance under <out>; returns the probe result (or None) and writes run.json beside it."""
    out = Path(out)
    if out.exists(): shutil.rmtree(out)
    (out / 'mods').mkdir(parents=True)
    for m in mods: shutil.copy(m, out / 'mods' / Path(m).name)
    (out / 'eula.txt').write_text('eula=true\n')
    (out / 'server.properties').write_text('\n'.join(PROPERTIES + [f'server-port={port}', 'server-ip=127.0.0.1']) + '\n')
    result = out / 'breathdrown.json'
    props = [f'-Dforbric.breathDrownDriver={driver}', f'-Dforbric.breathDrownProbe={result}', *jvm.split()]
    env = os.environ.copy()
    if engine.startswith('native-'):
        family = engine.split('-', 1)[1]
        image = NATIVE / family
        if not image.is_dir(): raise SystemExit(f'no native {family} image at {image} (run/compat/native-controls.py prepare)')
        for rel in ('libraries', '.fabric'):
            if (image / rel).exists(): subprocess.run(['cp', '-cR', str(image / rel), str(out / rel)], check=True)
        for p in image.glob('*.jar'): subprocess.run(['cp', '-c', str(p), str(out / p.name)], check=True)
        java = ['java', '-Xms256M', '-Xmx2G', '-Djava.awt.headless=true', *props]
        if family == 'fabric': cmd = [*java, '-jar', FABRIC_LAUNCHER, '--nogui']
        else:
            group, version = NATIVE_VERSIONS[family]
            cmd = [*java, f'@libraries/{group}/{version}/unix_args.txt', 'nogui']
    elif engine == 'forbric':
        tree = Path(tree) if tree else KERNEL.parent
        cmd = [str(tree / 'forbric-kernel/run/launch-kernel-server.sh')]
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
    (out / 'run.json').write_text(json.dumps(record, indent=2) + '\n')
    return json.loads(result.read_text()) if result.is_file() else None


# ---- what is judged -------------------------------------------------------------------------------------------------

def judged_row(row, families):
    """The part of a probe row the gate compares, with the views of <families> only."""
    if 'skipped' in row: return {'skipped': row['skipped']}
    kept = {k: row[k] for k in JUDGED if k in row}
    kept['views'] = {f: row.get('views', {}).get(f, {}) for f in sorted(families)}
    return kept


def native_rows(result, family):
    return {row['name']: judged_row(row, {family}) for row in result['rows']}


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


def judge_rows(reference, result):
    """{row: [reasons]} for every row EXPECT names; an empty list is a pass."""
    rows = {row['name']: row for row in result['rows']}
    verdicts = {}
    for name, (families, checks) in EXPECT.items():
        reasons = []
        row = rows.get(name)
        if row is None: verdicts[name] = ['no such row in the Forbric result']; continue
        if 'skipped' in row: verdicts[name] = [f'skipped on Forbric: {row["skipped"]}']; continue
        for family in sorted(families):
            native = reference['families'][family]['rows'].get(name)
            if native is None or 'skipped' in native:
                reasons.append(f'the {family} reference has no measured row (regenerate it)'); continue
            mine = judged_row(row, {family})
            if name in HANDED_NEOFORGES_ANSWER and family == F:
                native, mine = without_notes(native, family), without_notes(mine, family)
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
        for kind, order in row.get('order', {}).items():
            fams = order.split(',')
            if F in fams and N in fams and fams.index(N) > fams.index(F):
                reasons.append(f'{kind}: MinecraftForge answered before NeoForge in one tick ({order})')
        verdicts[name] = reasons
    return verdicts


def predicted_failures(reference, phase):
    """Which rows a phase must fail, read from EXPECT and the reference; exact both ways."""
    if phase == 'positive': return set()
    if phase == 'bridges-off':
        # Every row whose verdict needs a MinecraftForge breathe/drown/damage listener to have been asked.
        out = set()
        for name, (families, checks) in EXPECT.items():
            native = reference['families'][F]['rows'].get(name, {})
            if F in families and any(native.get('views', {}).get(F, {}).values()): out.add(name)
            if any(c[0] == 'sees' and c[1] == F for c in checks): out.add(name)
        return out
    if phase == 'getters-off': return set(FORGE_LIQUID_ROWS)
    if phase == 'own-type-off': return set(FORGE_LIQUID_BODY_ROWS)
    raise SystemExit(f'unknown phase {phase}')


def cmd_judge(args):
    reference = json.loads(Path(args.reference).read_text())
    if not Path(args.result).is_file():
        print(f'[kernel] FAIL {args.phase}: no probe result at {args.result}'); return 1
    result = json.loads(Path(args.result).read_text())
    if result.get('error'): print(f'[kernel] FAIL {args.phase}: the probe stopped on {result["error"]}'); return 1
    verdicts = judge_rows(reference, result)
    failed = {name for name, reasons in verdicts.items() if reasons}
    for name in EXPECT:
        reasons = verdicts[name]
        print(f'[kernel]   {args.phase}: {"ok  " if not reasons else "FAIL"} {name}' + ('' if not reasons else ' — ' + '; '.join(reasons[:3])[:600]))
    extra = sorted({row['name'] for row in result['rows']} - set(EXPECT))
    if extra: print(f'[kernel]   {args.phase}: rows the gate does not judge: {", ".join(extra)}')
    predicted = predicted_failures(reference, args.phase)
    if failed == predicted:
        print(f'[kernel] PASS {args.phase}: {len(EXPECT) - len(failed)} rows as native, the {len(failed)} predicted failure(s) and no others')
        return 0
    print(f'[kernel] FAIL {args.phase}: failed rows differ from the prediction — unexpected: {sorted(failed - predicted)}, '
          f'expected but held: {sorted(predicted - failed)}')
    return 1


# ---- the reference --------------------------------------------------------------------------------------------------

def cmd_reference(args):
    env = os.environ.copy()
    if args.staged: env['FORBRIC_OLD'] = str(args.staged)
    subprocess.run(['bash', str(KERNEL / 'run/build-breath-drown-canary.sh')], check=True, env=env)
    built = json.loads((KERNEL / 'run/canary/m57-build-inputs.json').read_text())
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
    reference = {'about': 'gate-m57 native reference: what each native loader does with the same canary jar, every judged field '
                          'agreeing across all runs. Regenerate with: python3 run/compat/breath-drown.py reference',
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
    path.write_text(text)


def sources_digest():
    sources = sorted(p for p in CANARY.rglob('*') if p.is_file() and p.suffix in ('.java', '.toml'))
    return hashlib.sha256(b''.join(p.relative_to(CANARY).as_posix().encode() + b'\0' + p.read_bytes() for p in sources)).hexdigest()


def cmd_check_sources(args):
    reference = json.loads(Path(args.reference).read_text())
    now = sources_digest()
    if reference['canarySources'] != now:
        print(f'[kernel] FAIL the native reference was measured with other canary sources ({reference["canarySources"][:12]}, '
              f'the tree has {now[:12]}): regenerate it with run/compat/breath-drown.py reference')
        return 1
    print(f'[kernel] PASS the native reference was measured with these canary sources ({now[:12]})')
    return 0


# ---- the developer probe's comparison -------------------------------------------------------------------------------

def cmd_compare(args):
    a, b = (json.loads(Path(p).read_text()) for p in (args.a, args.b))
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
    j = sub.add_parser('judge'); j.add_argument('--reference', default=str(REFERENCE)); j.add_argument('--result', required=True); j.add_argument('--phase', required=True)
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
