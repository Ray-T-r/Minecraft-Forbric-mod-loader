#!/usr/bin/env python3
"""Run the regress probe (canary/regress) in dedicated servers: three Fabric-on-NeoForge adaptations, each with its switch off.

The probe is a Fabric mod with a NeoForge half. It measures, in the game, three things native Fabric does that NeoForge's
base had stopped doing until the kernel adapted the Fabric mixin:
  damage.*  fabric-item-api's CustomDamageHandler (FabricItemDamageMixinAdapter, -Dforbric.itemDamageHandler);
  shears.*  BCLib's tag shears on sheep and snow golems (MixinShearsRelay's mob rows, -Dforbric.shearsRelayMobs), beside a
            Fabric item and a NeoForge item that bring an entity entry of their own (Fabric's is pre-empted, as on
            Fabric; NeoForge's runs, as on NeoForge);
  fuel.*    fabric-item-api's stack-aware furnace remainder, which NeoForge's consumeFuel already honours (no adaptation:
            a standing check that it stays so).
The fixed run must pass every case and leave the two adapted mixins' report rows unsuspected; each switch-off run must
fail exactly its cases and show its mixin suspected again, so each check is known to be able to fail.
"""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

CASES = {'damage.mob', 'damage.player', 'damage.vanillaControl', 'damage.breaks',
         'fuel.remainder.consumeFuel', 'fuel.remainder.lavaControl', 'fuel.remainder.furnace',
         'shears.tagLoaded', 'shears.sheep.vanilla', 'shears.sheep.tag', 'shears.sheep.ownEntry.fabric',
         'shears.sheep.ownEntry.neo', 'shears.snowGolem.vanilla', 'shears.snowGolem.tag', 'shears.stickControl'}
# phase -> (switch, the cases it must fail exactly, the mixins whose report rows it must show suspected)
PHASES = {
    'fixed': ('', set(), ()),
    'item-damage-off': ('-Dforbric.itemDamageHandler=off', {'damage.mob', 'damage.player'},
                        ('fabric-item-api-v1.mixins.json:net.fabricmc.fabric.mixin.item.ItemStackMixin',)),
    'shears-mobs-off': ('-Dforbric.shearsRelayMobs=off', {'shears.sheep.tag', 'shears.snowGolem.tag', 'shears.sheep.ownEntry.fabric'},
                        ('bclib.mixins.common.json:org.betterx.bclib.mixin.common.shears.SheepMixin',
                         'bclib.mixins.common.json:org.betterx.bclib.mixin.common.shears.SnowGolemMixin')),
}
WATCHED = tuple(m for _, _, mixins in PHASES.values() for m in mixins)
# fabric-item-api's furnace redirect: reported SUSPECTED on NeoForge's base although fuel.remainder.* measure the behaviour
# as native (its target, Item.getCraftingRemainder, is gone from consumeFuel, which asks the unshrunk stack itself). Listed in
# the summary, not judged, until the census can say "native" for it (s4/c3 handoff).
KNOWN_FALSE = 'fabric-item-api-v1.mixins.json:net.fabricmc.fabric.mixin.item.AbstractFurnaceBlockEntityMixin'


def rows(compatibility, mixin):
    return sorted(f['id'] for f in compatibility['findings'] if f.get('confidence') in ('SUSPECTED', 'CONFIRMED')
                  and re.search(rf'[:]{re.escape(mixin)}(#|$)', f['id']))


def javac(cp, sources, out):
    subprocess.run(['javac', '-proc:none', '--release', '21', '-cp', os.pathsep.join(map(str, cp)), '-d', str(out),
                    *map(str, sorted(sources))], check=True)


def pack(jar, classes, extras):
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
        for path in sorted(classes.rglob('*.class')):
            z.write(path, path.relative_to(classes).as_posix())
        for source, name in extras:
            z.write(source, name)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--fabric-api', type=Path, required=True, help='fabric-api-0.155.2+26.2.jar (or the staged one)')
    parser.add_argument('--bclib', type=Path, required=True, help='bclib-26.201.2.jar')
    parser.add_argument('--mod', type=Path, action='append', default=[],
                        help="BCLib's dependencies (worldweaver, wunderlib); repeat")
    parser.add_argument('--staged-root', type=Path, help='The staged run directory (default $FORBRIC_OLD/run)')
    parser.add_argument('--port', type=int, default=0, help='First server port; each phase takes the next (0: any)')
    parser.add_argument('--output', type=Path, help='A new directory for logs, worlds and reports')
    args = parser.parse_args()
    kernel = Path(__file__).resolve().parents[2]
    stage = (args.staged_root or Path(os.environ.get('FORBRIC_OLD', kernel.parent / 'forbric-loader')) / 'run').resolve()
    mc = Path(os.environ.get('MC_DIR', Path.home() / 'Library/Application Support/minecraft'))
    game = stage / 'neoforge-patched/patched-mc-neoforge-26.2.jar'
    neo = stage / 'neoforge-runtime/neoforge-runtime.jar'
    mods = [args.fabric_api.resolve(), args.bclib.resolve(), *(m.resolve() for m in args.mod)]
    for path in (game, neo, *mods):
        if not path.is_file():
            parser.error(f'Missing prerequisite: {path}')
    ids = {}
    for path in mods:
        with zipfile.ZipFile(path) as jar:
            if 'fabric.mod.json' in jar.namelist():
                ids[json.loads(jar.read('fabric.mod.json')).get('id')] = path
    if 'fabric-api' not in ids or 'bclib' not in ids:
        parser.error(f'--fabric-api and --bclib must be the Fabric API and BCLib jars (read ids: {sorted(ids)})')
    output = (args.output or kernel / 'build/verification' / ('regress-' + datetime.now().strftime('%Y%m%d-%H%M%S'))).resolve()
    output.mkdir(parents=True, exist_ok=False)
    # The kernel jar carries Fabric Loader's API (ModInitializer); the launcher builds it too, before every boot.
    subprocess.run([str(kernel / 'gradlew'), '--offline', '-q', '-p', str(kernel), 'jar'], check=True)
    boot = sorted((kernel / 'build/libs').glob('forbric-kernel-*.jar'))[0]
    libraries = []
    for lib in json.loads((mc / 'versions/26.2/26.2.json').read_text())['libraries']:
        path = lib.get('downloads', {}).get('artifact', {}).get('path')
        if path and (mc / 'libraries' / path).is_file():
            libraries.append(mc / 'libraries' / path)
    root = kernel / 'canary/regress'
    with tempfile.TemporaryDirectory(prefix='regress-probe-', dir=output) as temp:
        temp = Path(temp)
        modules = temp / 'fabric-api'
        modules.mkdir()
        with zipfile.ZipFile(ids['fabric-api']) as jar:
            for name in jar.namelist():
                if name.startswith('META-INF/jars/') and name.endswith('.jar'):
                    (modules / Path(name).name).write_bytes(jar.read(name))
        neo_classes, fabric_classes = temp / 'neo', temp / 'fabric'
        javac([game, neo, *libraries], (root / 'neo/src').rglob('*.java'), neo_classes)
        javac([game, neo, boot, *sorted(modules.glob('*.jar')), *libraries], (root / 'fabric/src').rglob('*.java'), fabric_classes)
        neo_jar, fabric_jar = output / 'regressprobeneo.jar', output / 'regressprobe.jar'
        pack(neo_jar, neo_classes, [(root / 'neo/META-INF/neoforge.mods.toml', 'META-INF/neoforge.mods.toml'),
                                    (root / 'neo/data/c/tags/item/tools/shear.json', 'data/c/tags/item/tools/shear.json')])
        pack(fabric_jar, fabric_classes, [(root / 'fabric/fabric.mod.json', 'fabric.mod.json'),
                                          (root / 'fabric/data/c/tags/item/tools/shear.json', 'data/c/tags/item/tools/shear.json')])
    results = {}
    for index, (phase, (switch, expected, suspected)) in enumerate(PHASES.items()):
        run = output / phase
        (run / 'mods').mkdir(parents=True)
        for jar in (fabric_jar, neo_jar, *mods):
            shutil.copy2(jar, run / 'mods' / jar.name)
        port = args.port + index if args.port else 0
        (run / 'server.properties').write_text(
            f'server-ip=127.0.0.1\nserver-port={port}\nlevel-name=world\nlevel-seed=4242\ngenerate-structures=false\n'
            'online-mode=false\nenforce-secure-profile=false\nmax-tick-time=-1\npause-when-empty-seconds=0\n'
            'view-distance=2\nsimulation-distance=2\nspawn-protection=0\n')
        env = dict(os.environ, FORBRIC_OLD=str(stage.parent), RUNDIR=str(run), FORBRIC_JVM=('-Xmx2G ' + switch).strip())
        with (run / 'console.log').open('w') as log:
            subprocess.run([str(kernel / 'run/launch-kernel-server.sh')], env=env,
                           stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=420)
        text = (run / 'console.log').read_text(errors='replace')
        if 'All dimensions are saved' not in text:
            raise RuntimeError(f'{phase}: server did not complete normal shutdown')
        cases = json.loads((run / 'regress-probe.json').read_text())['cases']
        names = [c['name'] for c in cases]
        if set(names) != CASES or len(names) != len(CASES):
            raise RuntimeError(f'{phase}: expected the {len(CASES)} distinct cases, got {sorted(names)}')
        failed = {c['name'] for c in cases if not c['pass']}
        compatibility = json.loads((run / '.forbric-kernel/compatibility-report.json').read_text())
        watched = {m: rows(compatibility, m) for m in WATCHED}
        results[phase] = {'switch': switch, 'passed': len(cases) - len(failed), 'failed': sorted(failed),
                          'details': {c['name']: c['detail'] for c in cases if c['name'] in failed | expected},
                          'suspectedRows': {m: r for m, r in watched.items() if r},
                          'knownFalseSuspected': rows(compatibility, KNOWN_FALSE), 'policy': compatibility.get('policy')}
        if failed != expected:
            raise RuntimeError(f'{phase}: unexpected failures {sorted(failed - expected)}, did not fail {sorted(expected - failed)}: '
                               f'{results[phase]["details"]}')
        for mixin, found in watched.items():
            if mixin in suspected and not found:
                raise RuntimeError(f'{phase}: the switch-off run should report {mixin} suspected (so the check can fail); report has none')
            if mixin not in suspected and found:
                raise RuntimeError(f'{phase}: {mixin} still reported incomplete: {found}')
        print(f'{phase}: {len(cases) - len(failed)}/{len(cases)} cases passed'
              + (f', failed exactly {sorted(failed)}' if failed else ''), flush=True)
    results['inputs_sha256'] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (*mods, boot, game, neo)}
    (output / 'summary.json').write_text(json.dumps(results, indent=2) + '\n')
    print(f'Regress gate passed: {output}', flush=True)


if __name__ == '__main__':
    main()
