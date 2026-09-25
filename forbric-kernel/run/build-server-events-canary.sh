#!/usr/bin/env bash
# Builds run/canary/forbricserverevents.jar for gate-m48: a NeoForge mod whose MinecraftForge server listeners are
# written the way packedup's and Tombstone's are. Only javac/jar; no kernel Gradle. The gate owns the separately scheduled kernel build.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
canary_scratch server-events
export M48_WORK="$WORK" M48_KERNEL="$KERNEL" M48_OLD="$OLD"
python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess, zipfile
kernel, old, work = (pathlib.Path(os.environ[key]) for key in ('M48_KERNEL', 'M48_OLD', 'M48_WORK'))
mc = pathlib.Path(os.environ.get('MC_DIR', pathlib.Path.home() / 'Library/Application Support/minecraft'))
compile_game = pathlib.Path(os.environ.get('M48_COMPILE_GAME', old / 'run/neoforge-patched/patched-mc-neoforge-26.2.jar'))
forge = pathlib.Path(os.environ.get('FORGE_RT', old / 'run/merged-base/forge-runtime-interop.jar'))
if not forge.is_file(): forge = old / 'run/forge-runtime/forge-runtime.jar'
neo = pathlib.Path(os.environ.get('NEO_RT', old / 'run/neoforge-runtime/neoforge-runtime.jar'))
libraries = []
for entry in json.loads((mc / 'versions/26.2/26.2.json').read_text())['libraries']:
    artifact = entry.get('downloads', {}).get('artifact', {}).get('path')
    if artifact and (mc / 'libraries' / artifact).is_file(): libraries.append(mc / 'libraries' / artifact)
for path in [compile_game, forge, neo]:
    if not path.is_file(): raise SystemExit(f'M48 prerequisite absent: {path}')
root = kernel / 'canary/server-events'
classes = work / 'classes'; classes.mkdir()
subprocess.run(['javac', '-proc:none', '--release', '21', '-cp', os.pathsep.join(map(str, [compile_game, forge, neo, *libraries])),
                '-d', str(classes), *map(str, sorted((root / 'src').rglob('*.java')))], check=True)
output = kernel / 'run/canary'; output.mkdir(exist_ok=True)
staged = work / 'forbricserverevents.jar'
with zipfile.ZipFile(staged, 'w', zipfile.ZIP_DEFLATED) as target:
    for path in sorted(classes.rglob('*.class')): target.write(path, path.relative_to(classes).as_posix())
    target.write(root / 'META-INF/neoforge.mods.toml', 'META-INF/neoforge.mods.toml')
jar = output / 'forbricserverevents.jar'
os.replace(staged, jar)
def record(path):
    path = path.resolve(); return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
(output / 'm48-build-inputs.json').write_text(json.dumps({'mod': record(jar), 'compileGame': record(compile_game),
    'forge': record(forge), 'neo': record(neo)}, indent=2) + '\n')
print('[M48Server] built the server-events probe')
PY
