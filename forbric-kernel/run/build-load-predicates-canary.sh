#!/usr/bin/env bash
# Builds run/canary/forbricpredicates.jar for gate-m50: a NeoForge mod whose data carries lithostitched load predicates.
# Only javac/jar; no kernel Gradle. The gate owns the separately scheduled kernel build.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
canary_scratch load-predicates
export M50_WORK="$WORK" M50_KERNEL="$KERNEL" M50_OLD="$OLD"
python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess, zipfile
kernel, old, work = (pathlib.Path(os.environ[key]) for key in ('M50_KERNEL', 'M50_OLD', 'M50_WORK'))
mc = pathlib.Path(os.environ.get('MC_DIR', pathlib.Path.home() / 'Library/Application Support/minecraft'))
compile_game = pathlib.Path(os.environ.get('M50_COMPILE_GAME', old / 'run/neoforge-patched/patched-mc-neoforge-26.2.jar'))
neo = pathlib.Path(os.environ.get('NEO_RT', old / 'run/neoforge-runtime/neoforge-runtime.jar'))
litho = kernel / 'run/client-merged-pack/mods/lithostitched-1.7.13-fabric-26.2.jar'
libraries = []
for entry in json.loads((mc / 'versions/26.2/26.2.json').read_text())['libraries']:
    artifact = entry.get('downloads', {}).get('artifact', {}).get('path')
    if artifact and (mc / 'libraries' / artifact).is_file(): libraries.append(mc / 'libraries' / artifact)
for path in [compile_game, neo, litho]:
    if not path.is_file(): raise SystemExit(f'M50 prerequisite absent: {path}')
root = kernel / 'canary/load-predicates'
classes = work / 'classes'; classes.mkdir()
subprocess.run(['javac', '-proc:none', '--release', '21', '-cp', os.pathsep.join(map(str, [compile_game, neo, *libraries])),
                '-d', str(classes), *map(str, sorted((root / 'src').rglob('*.java')))], check=True)
output = kernel / 'run/canary'; output.mkdir(exist_ok=True)
staged = work / 'forbricpredicates.jar'
with zipfile.ZipFile(staged, 'w', zipfile.ZIP_DEFLATED) as target:
    for path in sorted(classes.rglob('*.class')): target.write(path, path.relative_to(classes).as_posix())
    target.write(root / 'META-INF/neoforge.mods.toml', 'META-INF/neoforge.mods.toml')
    for path in sorted((root / 'data').rglob('*.json')): target.write(path, path.relative_to(root).as_posix())
jar = output / 'forbricpredicates.jar'
os.replace(staged, jar)
def record(path):
    path = path.resolve(); return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
(output / 'm50-build-inputs.json').write_text(json.dumps({'mod': record(jar), 'lithostitched': record(litho),
    'compileGame': record(compile_game), 'neo': record(neo)}, indent=2) + '\n')
print('[M50Predicate] built the load-predicates probe')
PY
