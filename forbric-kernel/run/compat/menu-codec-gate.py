#!/usr/bin/env python3
"""Open Farmer's Delight's cooking pot (the Fabric build) on a real kernel server under the strict policy.

A positive check, kept as a manual tool (it is not a gate). Until Stage 4 it also ran a baseline with
-Dforbric.wrapperEntryEvents=off, which turned off the merged base's MinecraftForge-wrapper half of the registries'
entry events, so fabric-menu-api never heard of the menu type and opening the pot threw "Codec for
farmersdelight:cooking_pot is not registered!". That switch is retired with the merged base: on NeoForge's base the
baseline passed all three checks (menu.extended, menu.codec, pot.opens) and its "negative control" could only fail, so
it went. Nothing on NeoForge's base is switched off here; the fixed run must pass every check with no confirmed loss.
"""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--farmers-delight', type=Path, required=True,
                        help="Unmodified FarmersDelight-26.2-3.6.26+refabricated.jar (the Fabric build)")
    parser.add_argument('--fabric-api', type=Path,
                        help='fabric-api-0.155.2+26.2.jar (default: tools/dev.py prepare\'s, else the compatibility pack\'s)')
    parser.add_argument('--staged-root', type=Path, help='The staged run/ directory (default: FORBRIC_OLD, .dev/staged, forbric-loader)')
    parser.add_argument('--output', type=Path, help='A new directory for logs, worlds and reports')
    args = parser.parse_args()
    kernel = Path(__file__).resolve().parents[2]
    # The same defaults as the kernel build: FORBRIC_OLD, then tools/dev.py prepare's tree, then the substrate's.
    dev = kernel / '.dev'
    staged = os.environ.get('FORBRIC_OLD') or (dev / 'staged' if (dev / 'staged/run/neoforge-patched').is_dir() else kernel.parent / 'forbric-loader')
    stage = (args.staged_root or Path(staged) / 'run').resolve()
    # Like the build: .dev/minecraft only while it has the version JSON this script reads below.
    mc = Path(os.environ.get('MC_DIR') or (dev / 'minecraft' if (dev / 'minecraft/versions/26.2/26.2.json').is_file()
                                         else Path.home() / 'Library/Application Support/minecraft')).resolve()
    delight = args.farmers_delight.resolve()
    api = args.fabric_api or next((path for path in (dev / 'api/fabric-api-0.155.2+26.2.jar',
                                                     kernel / 'run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar')
                                   if path.is_file()), kernel / 'run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar')
    api = api.resolve()
    with zipfile.ZipFile(delight) as jar:
        metadata = json.loads(jar.read('fabric.mod.json'))
        if metadata.get('id') != 'farmersdelight' or metadata.get('version') != '26.2-3.6.26+refabricated':
            parser.error("This gate targets Farmer's Delight 26.2-3.6.26+refabricated")
    with zipfile.ZipFile(api) as jar:
        if json.loads(jar.read('fabric.mod.json')).get('version') != '0.155.2+26.2':
            parser.error('This gate targets Fabric API 0.155.2+26.2')
    output = (args.output or kernel / 'build/verification' / ('menu-codec-' + datetime.now().strftime('%Y%m%d-%H%M%S'))).resolve()
    output.mkdir(parents=True, exist_ok=False)
    root = kernel / 'canary/menu-codec'
    # The game base and its NeoForge carrier, as the launcher runs them (its defaults under the stage).
    game = stage / 'neoforge-patched/patched-mc-neoforge-26.2.jar'
    neo = stage / 'neoforge-runtime/neoforge-runtime.jar'
    # The kernel's boot jar carries Fabric Loader's API (ModInitializer); launch-kernel-server.sh rebuilds it below.
    loader = kernel / 'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar'
    for path in (game, neo, loader, delight, api):
        if not path.is_file():
            parser.error(f'Missing prerequisite: {path}')
    with tempfile.TemporaryDirectory(prefix='menu-probe-', dir=output) as temp:
        temp = Path(temp)
        cp = [game, neo, loader, delight]
        with zipfile.ZipFile(api) as jar:
            for name in jar.namelist():
                if name.startswith('META-INF/jars/') and name.endswith('.jar'):
                    module = temp / 'fabric-api' / Path(name).name
                    module.parent.mkdir(exist_ok=True)
                    module.write_bytes(jar.read(name))
                    cp.append(module)
        for lib in json.loads((mc / 'versions/26.2/26.2.json').read_text())['libraries']:
            path = lib.get('downloads', {}).get('artifact', {}).get('path')
            if path and (mc / 'libraries' / path).is_file():
                cp.append(mc / 'libraries' / path)
        classes = temp / 'classes'
        subprocess.run(['javac', '-proc:none', '--release', '21', '-cp', os.pathsep.join(map(str, cp)),
                        '-d', str(classes), *map(str, sorted((root / 'src').rglob('*.java')))], check=True)
        probe = output / 'forbricmenuprobe.jar'
        with zipfile.ZipFile(probe, 'w', zipfile.ZIP_DEFLATED) as jar:
            for path in sorted(classes.rglob('*.class')):
                jar.write(path, path.relative_to(classes).as_posix())
            jar.write(root / 'fabric.mod.json', 'fabric.mod.json')
    results = {}
    for phase in ('fixed',):
        run = output / phase
        (run / 'mods').mkdir(parents=True)
        for jar in (probe, delight, api):
            shutil.copy2(jar, run / 'mods' / jar.name)
        (run / 'server.properties').write_text(
            'server-ip=127.0.0.1\nserver-port=0\nlevel-name=world\nlevel-type=minecraft:flat\n'
            'generate-structures=false\nonline-mode=false\nmax-tick-time=-1\npause-when-empty-seconds=0\n'
            'view-distance=2\nsimulation-distance=2\nspawn-protection=0\n')
        # No MC_DIR: the launcher reads the game's libraries from its own default, as every gate does.
        env = dict(os.environ, FORBRIC_OLD=str(stage.parent), RUNDIR=str(run), FORBRIC_COMPAT_POLICY='strict',
                   FORBRIC_JVM='-Xmx2G')
        with (run / 'console.log').open('w') as log:
            subprocess.run([str(kernel / 'run/launch-kernel-server.sh')], env=env,
                           stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)
        text = (run / 'console.log').read_text()
        if 'All dimensions are saved' not in text:
            raise RuntimeError(f'{phase}: server did not complete normal shutdown')
        cases = {c['name']: c for c in json.loads((run / 'menu-probe.json').read_text())['cases']}
        if set(cases) != {'menu.extended', 'menu.codec', 'pot.opens'}:
            raise RuntimeError(f'{phase}: expected the three menu checks, got {sorted(cases)}')
        failed = sorted(name for name, case in cases.items() if not case['pass'])
        results[phase] = {'passed': len(cases) - len(failed), 'failed': failed}
        compatibility = json.loads((run / '.forbric-kernel/compatibility-report.json').read_text())
        confirmed = [f for f in compatibility['findings']
                     if f.get('modId') in ('farmersdelight', 'fabric-menu-api-v1') and f.get('confidence') == 'CONFIRMED']
        if failed or confirmed or compatibility['policy'] != 'STRICT':
            raise RuntimeError(f'Fixed run failed: cases={[cases[name] for name in failed]}, compatibility={confirmed}')
        print(f'{phase}: {len(cases) - len(failed)}/{len(cases)} menu checks passed', flush=True)
    for name, path in (('farmers_delight', delight), ('fabric_api', api),
                       ('kernel', kernel / 'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar')):
        results[name + '_sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
    (output / 'summary.json').write_text(json.dumps(results, indent=2) + '\n')
    print(f'Menu codec gate passed: {output}', flush=True)


if __name__ == '__main__':
    main()
