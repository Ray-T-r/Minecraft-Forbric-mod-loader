#!/usr/bin/env python3
"""Compile the fluid HEAD-handler probe and compare server runs with and without MixinFluidReactionAdapter's HEAD wrap.

fabric-block-api's FluidFlowEvents veto and Create Fly's lava reaction are cancellable HEAD @Injects on vanilla's
LiquidBlock.shouldSpreadLiquid, which vanilla asks on placement and on a neighbour change. NeoForge's base asks it on
placement only (its neighborChanged asks NeoForge's FluidInteractionRegistry), so without the HEAD wrap the handlers run
on placement alone. The fixed run (STRICT) must pass every case; the head-off run
(-Dforbric.fluidReactionNeighbourChange.head=off) must fail exactly the neighbour-change cases.
"""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

# Pass in both runs: placement asks vanilla's method on NeoForge's base, and an un-vetoed neighbour change asks the registry.
CONTROLS = {'place.veto', 'neighbor.allow.reaction'}
CREATE_CONTROLS = {'create.place.reaction'}
# Fail with only the HEAD wrap off: the handlers then run where NeoForge's base runs them, on placement alone.
HEAD = {'neighbor.veto', 'neighbor.veto.reaction'}
CREATE_HEAD = {'create.neighbor.reaction'}
HEAD_OFF = '-Dforbric.fluidReactionNeighbourChange.head=off'


def nested(jar, prefix, into):
    """The first nested jar of `jar` whose name starts with META-INF/jars/<prefix>, extracted into `into`."""
    with zipfile.ZipFile(jar) as outer:
        for name in outer.namelist():
            if name.startswith('META-INF/jars/' + prefix) and name.endswith('.jar'):
                path = into / Path(name).name
                path.write_bytes(outer.read(name))
                return path
    raise SystemExit(f'{jar} has no nested {prefix}*.jar')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fabric-api', type=Path, required=True, help='fabric-api 0.161.0+26.2 or later (fabric-block-api 3.1.0+)')
    parser.add_argument('--create', type=Path, help='Create Fly (adds its lava + honey cases)')
    parser.add_argument('--staged-root', type=Path, help='The staged run directory (default: $FORBRIC_OLD/run)')
    parser.add_argument('--output', type=Path, help='A new directory for logs, worlds and reports')
    parser.add_argument('--off', default=HEAD_OFF, help='JVM switch of the negative run (default: the HEAD wrap off). Any other '
                        'switch only reports which cases fail, without asserting them.')
    args = parser.parse_args()
    kernel = Path(__file__).resolve().parents[2]
    stage = (args.staged_root or Path(os.environ.get('FORBRIC_OLD', kernel.parent / 'forbric-loader')) / 'run').resolve()
    mc = stage.parent / 'minecraft'
    if not (mc / 'versions/26.2/26.2.json').is_file():
        mc = Path(os.environ.get('MC_DIR', Path.home() / 'Library/Application Support/minecraft'))
    output = (args.output or kernel / 'build/verification' / ('fluid-head-' + datetime.now().strftime('%Y%m%d-%H%M%S'))).resolve()
    output.mkdir(parents=True, exist_ok=False)
    root = kernel / 'canary/fluidhead'
    game = stage / 'neoforge-patched/patched-mc-neoforge-26.2.jar'
    neo = stage / 'neoforge-runtime/neoforge-runtime.jar'
    mods = [args.fabric_api.resolve()] + ([args.create.resolve()] if args.create else [])
    for path in [game, neo, *mods]:
        if not path.is_file():
            parser.error(f'Missing prerequisite: {path}')
    with tempfile.TemporaryDirectory(prefix='fluid-head-probe-', dir=output) as temp:
        temp = Path(temp)
        cp = [game, neo, nested(mods[0], 'fabric-block-api-v1-', temp), nested(mods[0], 'fabric-api-base-', temp)]
        for lib in json.loads((mc / 'versions/26.2/26.2.json').read_text())['libraries']:
            path = lib.get('downloads', {}).get('artifact', {}).get('path')
            if path and (mc / 'libraries' / path).is_file():
                cp.append(mc / 'libraries' / path)
        classes = temp / 'classes'
        subprocess.run(['javac', '-proc:none', '--release', '21', '-cp', os.pathsep.join(map(str, cp)),
                        '-d', str(classes), *map(str, sorted((root / 'src').rglob('*.java')))], check=True)
        probe = output / 'forbricfluidheadprobe.jar'
        with zipfile.ZipFile(probe, 'w', zipfile.ZIP_DEFLATED) as jar:
            for path in sorted(classes.rglob('*.class')):
                jar.write(path, path.relative_to(classes).as_posix())
            jar.write(root / 'META-INF/neoforge.mods.toml', 'META-INF/neoforge.mods.toml')
    controls = CONTROLS | (CREATE_CONTROLS if args.create else set())
    head = HEAD | (CREATE_HEAD if args.create else set())
    expected = len(controls | head)
    port = int(os.environ.get('GATE_PORT', '0'))
    results = {}
    for index, phase in enumerate(('off', 'fixed')):
        run = output / phase
        (run / 'mods').mkdir(parents=True)
        shutil.copy2(probe, run / 'mods' / probe.name)
        for mod in mods:
            shutil.copy2(mod, run / 'mods' / mod.name)
        (run / 'server.properties').write_text(
            f'server-ip=127.0.0.1\nserver-port={port + index if port else 0}\nlevel-name=world\nlevel-type=minecraft:flat\n'
            'generate-structures=false\nonline-mode=false\nmax-tick-time=-1\npause-when-empty-seconds=0\n'
            'view-distance=2\nsimulation-distance=2\nspawn-protection=0\n')
        env = dict(os.environ, FORBRIC_OLD=str(stage.parent), RUNDIR=str(run),
                   FORBRIC_COMPAT_POLICY='strict' if phase == 'fixed' else 'continue',
                   FORBRIC_JVM=os.environ.get('FORBRIC_JVM', '') + ' -Xmx3G' + (' ' + args.off if phase == 'off' else ''))
        with (run / 'console.log').open('w') as log:
            subprocess.run([str(kernel / 'run/launch-kernel-server.sh')], env=env,
                           stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=600)
        text = (run / 'console.log').read_text(errors='replace')
        if 'All dimensions are saved' not in text:
            raise RuntimeError(f'{phase}: server did not complete normal shutdown')
        cases = json.loads((run / 'fluid-head-probe.json').read_text())['cases']
        names = {c['name'] for c in cases}
        if len(cases) != expected or names != controls | head:
            raise RuntimeError(f'{phase}: expected the {expected} cases {sorted(controls | head)}, got {sorted(names)}')
        failed = {c['name'] for c in cases if not c['pass']}
        wraps = sorted({line.split('[Forbric/Mixin] ', 1)[1].split(': ')[0] for line in text.splitlines()
                        if '[Forbric/Mixin] ' in line and 'shouldSpreadLiquid now also runs on a neighbour change' in line})
        relayed = sorted({line.split('[Forbric/Mixin] ', 1)[1].split(': ')[0] for line in text.splitlines()
                          if '[Forbric/Mixin] ' in line and 'is asked again by' in line})
        results[phase] = {'switch': args.off if phase == 'off' else '', 'passed': len(cases) - len(failed), 'failed': sorted(failed),
                          'wrapped': wraps, 'relayed': relayed, 'details': {c['name']: c['detail'] for c in cases if not c['pass']}}
        print(f'{phase}: {len(cases) - len(failed)}/{len(cases)} cases passed, wraps {wraps}')
    (output / 'summary.json').write_text(json.dumps(results, indent=2) + '\n')
    off, fixed = results['off'], results['fixed']
    if fixed['failed']:
        raise SystemExit(f'Fixed run failed {fixed["failed"]}: {fixed["details"]}')
    if args.off != HEAD_OFF:
        print(f'fluid-head gate: fixed run passes; with {args.off} the run failed {off["failed"]} (reported, not asserted)')
        return
    if set(off['failed']) != head:
        raise SystemExit(f'Negative control failed: head-off failed {off["failed"]}, expected exactly {sorted(head)}')
    if off['wrapped']:
        raise SystemExit(f'Negative control failed: head-off still wrapped {off["wrapped"]}')
    want = {'net.fabricmc.fabric.mixin.block.LiquidBlockMixin'} | ({'com.zurrtum.create.mixin.LiquidBlockMixin'} if args.create else set())
    if not want <= set(fixed['wrapped']):
        raise SystemExit(f'Fixed run did not announce the HEAD wraps of {sorted(want - set(fixed["wrapped"]))}')
    print('fluid-head gate: PASS')


if __name__ == '__main__':
    main()
