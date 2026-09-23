#!/usr/bin/env bash
# Only javac/jar; no kernel Gradle. Gate owns the separately scheduled kernel build.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
canary_scratch entity-callbacks
export M37_WORK="$WORK" M37_KERNEL="$KERNEL" M37_OLD="$OLD"
python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess, zipfile
kernel, old, work = (pathlib.Path(os.environ[key]) for key in ('M37_KERNEL', 'M37_OLD', 'M37_WORK'))
mc = pathlib.Path(os.environ.get('MC_DIR', pathlib.Path.home() / 'Library/Application Support/minecraft'))
compile_game = pathlib.Path(os.environ.get('M37_COMPILE_GAME', old / 'run/neoforge-patched/patched-mc-neoforge-26.2.jar'))
merged = pathlib.Path(os.environ.get('MERGED', old / 'run/merged-base/patched-mc-merged-26.2.jar'))
forge = pathlib.Path(os.environ.get('FORGE_RT', old / 'run/merged-base/forge-runtime-interop.jar'))
if not forge.is_file(): forge = old / 'run/forge-runtime/forge-runtime.jar'
neo = pathlib.Path(os.environ.get('NEO_RT', old / 'run/neoforge-runtime/neoforge-runtime.jar'))
libraries = []
for entry in json.loads((mc / 'versions/26.2/26.2.json').read_text())['libraries']:
    artifact = entry.get('downloads', {}).get('artifact', {}).get('path')
    if artifact and (mc / 'libraries' / artifact).is_file(): libraries.append(mc / 'libraries' / artifact)
mixin = mc / 'libraries/net/fabricmc/sponge-mixin/0.17.3+mixin.0.8.7/sponge-mixin-0.17.3+mixin.0.8.7.jar'
fapi = pathlib.Path(os.environ.get('M37_FABRIC_API', old.parent / 'forbric-kernel/run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar'))
modules = kernel / 'run/canary/m37-modules'; modules.mkdir(parents=True, exist_ok=True)
selected=[]
with zipfile.ZipFile(fapi) as archive:
    for prefix in ('fabric-api-base-', 'fabric-entity-events-v1-'):
        names=[name for name in archive.namelist() if name.startswith('META-INF/jars/'+prefix) and name.endswith('.jar')]
        if len(names)!=1: raise SystemExit('required actual Fabric module missing or ambiguous: '+prefix)
        target=modules/pathlib.PurePosixPath(names[0]).name;target.write_bytes(archive.read(names[0]));selected.append(target)
classpath = [compile_game, forge, neo, mixin, *selected, *libraries]
for path in [*classpath, merged]:
    if not path.is_file(): raise SystemExit(f'M37 prerequisite absent: {path}')
root = kernel / 'canary/entity-callbacks'
classes = work / 'classes'; classes.mkdir()
subprocess.run(['javac', '-proc:none', '--release', '21', '-cp', os.pathsep.join(map(str, classpath)),
                '-d', str(classes), *map(str, sorted((root / 'src').rglob('*.java')))], check=True)
output = kernel / 'run/canary'; output.mkdir(exist_ok=True)
jar = output / 'forbricentitycallbacks.jar'
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as target:
    for path in sorted(classes.rglob('*.class')): target.write(path, path.relative_to(classes).as_posix())
    for name in ('META-INF/neoforge.mods.toml',): target.write(root / name, name)
def record(path):
    path = path.resolve(); return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
(output / 'm37-build-inputs.json').write_text(json.dumps({'mod': record(jar), 'compileGame': record(compile_game),
    'compileClasspath': [record(path) for path in classpath], 'fabricApi': record(fapi), 'modules': [record(path) for path in selected], 'merged': record(merged), 'forge': record(forge), 'neo': record(neo)}, indent=2) + '\n')
print('[M37Entity] built real entity callback fixture')
PY
