#!/usr/bin/env bash
# The two world-upgrade canaries gate-m58 writes and reads its saves with: forbricupgradefabric (Fabric attachments,
# component type, SavedData; carries the shared UpgradeProbe) and forbricupgradeneo (NeoForge attachments, persistent
# data, component type, SavedData, and the world/player scripts; depends on the Fabric one). Sources:
# canary/world-upgrade (README.md there says what each writes and reads).
#
# Built ONCE from this tree and booted on BOTH sides of the upgrade: the old kernel (merged base) writes with the same
# bytes the new kernel reads with, so a difference is the loader's, not the canary's. They compile against NeoForge's
# patched game + its carrier + fabric-api 0.155.2 (the kernel's own compile fixture, .dev/api) -- the genuine base's API,
# which the merged base also carries.
#
# WORLD_UPGRADE_CANARY_OUT redirects the output (default run/canary); the hashes of every input, the three scripts
# included, go to <out>/m58-build-inputs.json, like m33-m57.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
canary_scratch world-upgrade
kernel_jar
export M58_BUILD_WORK="$WORK" M58_BUILD_KERNEL="$KERNEL" M58_BUILD_OLD="$OLD"
python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess, zipfile
kernel = pathlib.Path(os.environ['M58_BUILD_KERNEL'])
old = pathlib.Path(os.environ['M58_BUILD_OLD'])
work = pathlib.Path(os.environ['M58_BUILD_WORK'])
mc = pathlib.Path(os.environ.get('MC_DIR', pathlib.Path.home() / 'Library/Application Support/minecraft'))
fapi = pathlib.Path(os.environ.get('M58_FABRIC_API', kernel / '.dev/api/fabric-api-0.155.2+26.2.jar'))
game = pathlib.Path(os.environ.get('M58_COMPILE_GAME', old / 'run/neoforge-patched/patched-mc-neoforge-26.2.jar'))
neo = pathlib.Path(os.environ.get('M58_COMPILE_NEO', old / 'run/neoforge-runtime/neoforge-runtime.jar'))
boot = kernel / 'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar'
for path in (fapi, game, neo, boot):
    if not path.is_file(): raise SystemExit(f'M58 canary prerequisite missing: {path}')
modules = work / 'modules'; modules.mkdir()
with zipfile.ZipFile(fapi) as source:
    for name in source.namelist():
        if name.startswith('META-INF/jars/') and name.endswith('.jar'):
            (modules / pathlib.PurePosixPath(name).name).write_bytes(source.read(name))
libraries = []
metadata = json.loads((mc / 'versions/26.2/26.2.json').read_text())
for library in metadata.get('libraries', []):
    artifact = library.get('downloads', {}).get('artifact', {}).get('path')
    if artifact and (mc / 'libraries' / artifact).is_file(): libraries.append(mc / 'libraries' / artifact)
classpath = [game, boot, neo, *modules.glob('*.jar'), *libraries]
root = kernel / 'canary/world-upgrade'
compiled = {}
for family in ('fabric', 'neo'):
    destination = work / family; destination.mkdir()
    sources = sorted((root / family / 'src').rglob('*.java'))
    if family == 'fabric': sources += sorted((root / 'common/src').rglob('*.java'))
    cp = [*classpath] + ([compiled['fabric']] if family != 'fabric' else [])
    subprocess.run(['javac', '-proc:none', '--release', '21', '-nowarn', '-cp', os.pathsep.join(map(str, cp)),
                    '-d', str(destination), *map(str, sources)], check=True)
    compiled[family] = destination
output = pathlib.Path(os.environ.get('WORLD_UPGRADE_CANARY_OUT', kernel / 'run/canary')); output.mkdir(parents=True, exist_ok=True)
staged = []
# Fixed timestamps and order: the same sources give the same jar bytes, so the build-inputs hashes say "same canary".
stamp = (1980, 1, 1, 0, 0, 0)
for family, destination in compiled.items():
    name = {'fabric': 'forbricupgradefabric', 'neo': 'forbricupgradeneo'}[family]
    jar = work / (name + '.jar')
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as target:
        for path in sorted(destination.rglob('*.class')):
            target.writestr(zipfile.ZipInfo(path.relative_to(destination).as_posix(), stamp), path.read_bytes(), zipfile.ZIP_DEFLATED)
        meta = ('fabric.mod.json', root / family / 'fabric.mod.json') if family == 'fabric' else \
            ('META-INF/neoforge.mods.toml', root / family / 'META-INF/neoforge.mods.toml')
        target.writestr(zipfile.ZipInfo(meta[0], stamp), meta[1].read_bytes(), zipfile.ZIP_DEFLATED)
    staged.append((jar, output / jar.name))
for source, target in staged: os.replace(source, target)
def record(path):
    path = path.resolve()
    return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
inputs = {'fabricApi': record(fapi), 'compileGame': record(game), 'neo': record(neo), 'kernel': record(boot),
          'scripts': [record(root / s) for s in ('world.commands', 'player.commands', 'forge.commands')],
          'mods': [record(target) for _, target in staged]}
(output / 'm58-build-inputs.json').write_text(json.dumps(inputs, indent=2) + '\n')
print('[M58Upgrade] built forbricupgradefabric + forbricupgradeneo into ' + str(output))
PY
