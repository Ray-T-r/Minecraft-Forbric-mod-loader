#!/usr/bin/env python3
"""Compile a real Carpet behavior probe and compare repaired and disabled-adapter server runs."""
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

CHECKS = 34
# Exactly these fail without the adapter (-Dforbric.playerWorldCallbacks=off): each needs a callback it restores.
# fill.direct.lamp.false is not one any more: MixinRetarget's helper-edge rule carries Level_fillUpdatesMixin's
# updateNeighborsMaybe redirect from setBlock onto markAndNotifyBlock on its own, so it passes with the adapter off too
# (measured on HEAD 4f83bc06 and after; native Fabric passes it as well).
# fluid.blackstone.true and fluid.deepslate.true are not either: NeoForge 26.2.0.93's LiquidBlock.onPlace calls vanilla's
# shouldSpreadLiquid (javap offset 4), so Carpet's two fluid rules bind there as compiled and run on placement with the
# adapters off (measured on the genuine stage). Only a neighbour change asks NeoForge's registry instead.
CARPET = {'fill.shape.false', 'fluid.blackstone.neighbor', 'fluid.deepslate.neighbor',
          'swap.scarpetCancel.true', 'swap.scarpetCancel.false', 'swap.scarpetClearsMain', 'swap.nativeVeto',
          'break.creative.scarpetCancel.true', 'break.creative.scarpetCancel.false',
          'break.survival.scarpetCancel.true', 'break.survival.scarpetCancel.false',
          'break.creative.bedCancel', 'break.survival.unstableTntCancel'}
# Vanilla's own lava/water reactions, not Carpet's: they must pass in every run. On placement NeoForge's LiquidBlock.onPlace
# calls vanilla's shouldSpreadLiquid; on a neighbour change its neighborChanged asks NeoForge's FluidInteractionRegistry
# (vanilla's reactions, in vanilla's direction order), which the kernel's neighbour-change wrap asks after running Carpet's
# rules where vanilla would have reached them. The .neighbor twins are that wrap's negative controls: with Carpet's rules
# on, a source stays obsidian, a reaction above zero stays cobblestone, the rule off gives cobblestone, basalt keeps
# precedence over blackstone and (blue ice reached before water) over deepslate, lava with no water stays lava, and
# flowing water next to water stays water.
BASE_FLUID = {'fluid.sourceStaysObsidian', 'fluid.aboveZeroStaysCobblestone', 'fluid.basaltPrecedesBlackstone',
              'fluid.deepslate.false', 'fluid.sourceStaysObsidian.neighbor', 'fluid.aboveZeroStaysCobblestone.neighbor',
              'fluid.deepslate.false.neighbor', 'fluid.basaltPrecedesBlackstone.neighbor',
              'fluid.basaltPrecedesDeepslate.neighbor', 'fluid.noWaterNoDeepslate.neighbor',
              'fluid.flowingWaterStaysWater.neighbor'}
# Exactly these fail with only the neighbour-change wrap off (-Dforbric.fluidReactionNeighbourChange=off): the rules then
# run where NeoForge's base runs them, on placement alone.
NEIGHBOUR = {'fluid.blackstone.neighbor', 'fluid.deepslate.neighbor'}
# The mixins the adapters rewrite. With them on, the preflight census judges what Mixin is handed, so none of these
# may be left suspected or "applies only partially"; with them off, the first two must be (the check can fail).
ADAPTED = ('Level_fillUpdatesMixin', 'ServerPlayerGameMode_scarpetEventsMixin',
           'ServerGamePacketListenerImpl_scarpetEventsMixin', 'LiquidBlock_renewableBlackstoneMixin',
           'LiquidBlock_renewableDeepslateMixin')


def stale(console, compatibility):
    """The census rows and console lines that call an adapted Carpet mixin incomplete."""
    rows = sorted(f['id'] for f in compatibility['findings'] if f.get('modId') == 'carpet'
                  and f.get('confidence') in ('SUSPECTED', 'CONFIRMED')
                  and any(re.search(rf'\.{m}(#|$)', f['id']) for m in ADAPTED))
    lines = sorted({m for line in console.splitlines() if 'applies only partially' in line
                    for m in ADAPTED if f':{m} ' in line})
    return rows, lines


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--carpet', type=Path, required=True, help='Unmodified fabric-carpet-26.2+v260616.jar')
    parser.add_argument('--staged-root', type=Path, help='The staged run/ directory (default: $FORBRIC_OLD/run)')
    parser.add_argument('--port', type=int, default=0, help='First server port; each run takes the next (0: any free one)')
    parser.add_argument('--output', type=Path, help='A new directory for logs, worlds and reports')
    args = parser.parse_args()
    kernel = Path(__file__).resolve().parents[2]
    stage = (args.staged_root or Path(os.environ.get('FORBRIC_OLD', kernel.parent / 'forbric-loader')) / 'run').resolve()
    mc = Path(os.environ.get('MC_DIR', Path.home() / 'Library/Application Support/minecraft'))
    carpet = args.carpet.resolve()
    with zipfile.ZipFile(carpet) as jar:
        metadata = json.loads(jar.read('fabric.mod.json'))
        if metadata.get('id') != 'carpet' or metadata.get('version') != '26.2+v260616':
            parser.error('This gate targets Carpet 26.2+v260616')
    output = (args.output or kernel / 'build/verification' / ('carpet-' + datetime.now().strftime('%Y%m%d-%H%M%S'))).resolve()
    output.mkdir(parents=True, exist_ok=False)
    root = kernel / 'canary/carpet'
    # NeoForge's patched game and its carrier: what the probe (a NeoForge mod) is built against and what the launcher runs.
    game = stage / 'neoforge-patched/patched-mc-neoforge-26.2.jar'
    neo = stage / 'neoforge-runtime/neoforge-runtime.jar'
    cp = [game, neo, carpet]
    for lib in json.loads((mc / 'versions/26.2/26.2.json').read_text())['libraries']:
        path = lib.get('downloads', {}).get('artifact', {}).get('path')
        if path and (mc / 'libraries' / path).is_file():
            cp.append(mc / 'libraries' / path)
    for path in cp[:3]:
        if not path.is_file():
            parser.error(f'Missing prerequisite: {path}')
    with tempfile.TemporaryDirectory(prefix='carpet-probe-', dir=output) as temp:
        classes = Path(temp)
        subprocess.run(['javac', '-proc:none', '--release', '21', '-cp', os.pathsep.join(map(str, cp)),
                        '-d', str(classes), *map(str, sorted((root / 'src').rglob('*.java')))], check=True)
        probe = output / 'forbriccarpetprobe.jar'
        with zipfile.ZipFile(probe, 'w', zipfile.ZIP_DEFLATED) as jar:
            for path in sorted(classes.rglob('*.class')):
                jar.write(path, path.relative_to(classes).as_posix())
            jar.write(root / 'META-INF/neoforge.mods.toml', 'META-INF/neoforge.mods.toml')
    results = {}
    for index, phase in enumerate(('baseline', 'fixed', 'neighbour-off')):
        run = output / phase
        (run / 'mods').mkdir(parents=True)
        (run / 'world/scripts').mkdir(parents=True)
        shutil.copy2(probe, run / 'mods' / probe.name)
        shutil.copy2(carpet, run / 'mods' / carpet.name)
        shutil.copy2(root / 'forbric_carpet_probe.sc', run / 'world/scripts')
        (run / 'server.properties').write_text(
            f'server-ip=127.0.0.1\nserver-port={args.port + index if args.port else 0}\nlevel-name=world\nlevel-type=minecraft:flat\n'
            'generate-structures=false\nonline-mode=false\nmax-tick-time=-1\npause-when-empty-seconds=0\n'
            'view-distance=2\nsimulation-distance=2\nspawn-protection=0\n')
        env = dict(os.environ, FORBRIC_OLD=str(stage.parent), RUNDIR=str(run),
                   FORBRIC_COMPAT_POLICY='strict' if phase == 'fixed' else 'continue',
                   FORBRIC_JVM='-Xmx2G' + {'baseline': ' -Dforbric.playerWorldCallbacks=off',
                                           'neighbour-off': ' -Dforbric.fluidReactionNeighbourChange=off'}.get(phase, ''))
        with (run / 'console.log').open('w') as log:
            subprocess.run([str(kernel / 'run/launch-kernel-server.sh')], env=env,
                           stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
        text = (run / 'console.log').read_text()
        if 'All dimensions are saved' not in text:
            raise RuntimeError(f'{phase}: server did not complete normal shutdown')
        report = json.loads((run / 'carpet-probe.json').read_text())
        cases = report['cases']
        if len(cases) != CHECKS or len({c['name'] for c in cases}) != CHECKS:
            raise RuntimeError(f'{phase}: expected all {CHECKS} distinct behavior checks')
        failed = {c['name'] for c in cases if not c['pass']}
        compatibility = json.loads((run / '.forbric-kernel/compatibility-report.json').read_text())
        rows, lines = stale(text, compatibility)
        results[phase] = {'passed': len(cases) - len(failed), 'failed': sorted(failed),
                          'carpetFailures': sorted(failed & CARPET), 'baseFluidFailures': sorted(failed & BASE_FLUID),
                          'otherFailures': sorted(failed - CARPET - BASE_FLUID),
                          'staleCensusRows': rows, 'partialLines': lines}
        if phase == 'baseline':
            if failed & BASE_FLUID:
                raise RuntimeError(f'Base game, not Carpet: vanilla lava/water checks failed with the adapter off: '
                                   f'{sorted(failed & BASE_FLUID)}')
            if failed != CARPET:
                raise RuntimeError(f'Negative control failed: unexpected {sorted(failed - CARPET)}, '
                                   f'did not fail {sorted(CARPET - failed)}')
            # Read off the compatibility report, not the console's wording (it no longer prints "applies only partially").
            reported = {m for m in ADAPTED for r in rows if re.search(rf'\.{m}(#|$)', r)}
            if not {'Level_fillUpdatesMixin', 'ServerPlayerGameMode_scarpetEventsMixin'} <= reported:
                raise RuntimeError(f'Negative control failed: the unadapted census reported rows={rows} lines={lines}')
        elif phase == 'neighbour-off':
            if failed != NEIGHBOUR:
                raise RuntimeError(f'Neighbour-change control failed: unexpected {sorted(failed - NEIGHBOUR)}, '
                                   f'did not fail {sorted(NEIGHBOUR - failed)}')
        else:
            confirmed = [f for f in compatibility['findings'] if f.get('modId') == 'carpet' and f.get('confidence') == 'CONFIRMED']
            if failed or confirmed or compatibility['policy'] != 'STRICT':
                raise RuntimeError(f'Fixed run failed: cases={failed}, compatibility={confirmed}')
            if rows or lines:
                raise RuntimeError(f'Fixed run still calls repaired mixins incomplete: rows={rows} lines={lines}')
        print(f'{phase}: {len(cases) - len(failed)}/{len(cases)} behavior checks passed', flush=True)
    results['carpet_sha256'] = hashlib.sha256(carpet.read_bytes()).hexdigest()
    results['kernel_sha256'] = hashlib.sha256((kernel / 'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar').read_bytes()).hexdigest()
    (output / 'summary.json').write_text(json.dumps(results, indent=2) + '\n')
    print(f'Carpet behavior gate passed: {output}', flush=True)


if __name__ == '__main__':
    main()
