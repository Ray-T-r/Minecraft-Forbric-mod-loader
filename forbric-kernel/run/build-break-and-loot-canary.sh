#!/usr/bin/env bash
# Builds run/canary/forbricbreakandloot.jar for gate-m43: a NeoForge mod that breaks blocks with a fake player while
# listening through the unmodified fabric-events-interaction-v0, and drives MinecraftForge loot-pool conditions.
# Only javac/jar; no kernel Gradle. The gate owns the separately scheduled kernel build.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
canary_scratch break-and-loot
export M43_WORK="$WORK" M43_KERNEL="$KERNEL" M43_OLD="$OLD"
python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess, zipfile
kernel, old, work = (pathlib.Path(os.environ[key]) for key in ('M43_KERNEL', 'M43_OLD', 'M43_WORK'))
mc = pathlib.Path(os.environ.get('MC_DIR', pathlib.Path.home() / 'Library/Application Support/minecraft'))
compile_game = pathlib.Path(os.environ.get('M43_COMPILE_GAME', old / 'run/neoforge-patched/patched-mc-neoforge-26.2.jar'))
forge = pathlib.Path(os.environ.get('FORGE_RT', old / 'run/merged-base/forge-runtime-interop.jar'))
if not forge.is_file(): forge = old / 'run/forge-runtime/forge-runtime.jar'
neo = pathlib.Path(os.environ.get('NEO_RT', old / 'run/neoforge-runtime/neoforge-runtime.jar'))
fapi = pathlib.Path(os.environ.get('M43_FABRIC_API', kernel / 'run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar'))
libraries = []
for entry in json.loads((mc / 'versions/26.2/26.2.json').read_text())['libraries']:
    artifact = entry.get('downloads', {}).get('artifact', {}).get('path')
    if artifact and (mc / 'libraries' / artifact).is_file(): libraries.append(mc / 'libraries' / artifact)
modules = kernel / 'run/canary/m43-modules'; modules.mkdir(parents=True, exist_ok=True)
selected = []
with zipfile.ZipFile(fapi) as archive:
    for prefix in ('fabric-api-base-', 'fabric-networking-api-v1-', 'fabric-events-interaction-v0-'):
        names = [n for n in archive.namelist() if n.startswith('META-INF/jars/' + prefix) and n.endswith('.jar')]
        if len(names) != 1: raise SystemExit('required actual Fabric module missing or ambiguous: ' + prefix)
        target = modules / pathlib.PurePosixPath(names[0]).name; target.write_bytes(archive.read(names[0])); selected.append(target)
classpath = [compile_game, forge, neo, *selected, *libraries]
for path in [compile_game, forge, neo, fapi]:
    if not path.is_file(): raise SystemExit(f'M43 prerequisite absent: {path}')
root = kernel / 'canary/break-and-loot'
classes = work / 'classes'; classes.mkdir()
subprocess.run(['javac', '-proc:none', '--release', '21', '-cp', os.pathsep.join(map(str, classpath)),
                '-d', str(classes), *map(str, sorted((root / 'src').rglob('*.java')))], check=True)
output = kernel / 'run/canary'; output.mkdir(exist_ok=True)
staged = work / 'forbricbreakandloot.jar'
with zipfile.ZipFile(staged, 'w', zipfile.ZIP_DEFLATED) as target:
    for path in sorted(classes.rglob('*.class')): target.write(path, path.relative_to(classes).as_posix())
    target.write(root / 'META-INF/neoforge.mods.toml', 'META-INF/neoforge.mods.toml')
    for path in sorted((root / 'data').rglob('*.json')): target.write(path, path.relative_to(root).as_posix())
jar = output / 'forbricbreakandloot.jar'
os.replace(staged, jar)
def record(path):
    path = path.resolve(); return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
(output / 'm43-build-inputs.json').write_text(json.dumps({'mod': record(jar), 'compileGame': record(compile_game),
    'forge': record(forge), 'neo': record(neo), 'fabricApi': record(fapi), 'modules': [record(p) for p in selected]}, indent=2) + '\n')
print('[M43Break] built the block-break and loot probe')
PY
