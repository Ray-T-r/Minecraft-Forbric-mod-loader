#!/usr/bin/env bash
# The gate builds the kernel first; resolve its exact ASM dependency for the public Mixin plugin signature.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
canary_scratch mixin-outcome
kernel_classpath
export M36_BUILD_CP="$KERNEL_CP"
export M36_WORK="$WORK" M36_KERNEL="$KERNEL" M36_OLD="$OLD"
python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess, zipfile
kernel, old, work = (pathlib.Path(os.environ[key]) for key in ('M36_KERNEL', 'M36_OLD', 'M36_WORK'))
mc = pathlib.Path(os.environ.get('MC_DIR', pathlib.Path.home() / 'Library/Application Support/minecraft'))
compile_game = pathlib.Path(os.environ.get('M36_COMPILE_GAME', old / 'run/neoforge-patched/patched-mc-neoforge-26.2.jar'))
merged = pathlib.Path(os.environ.get('MERGED', old / 'run/merged-base/patched-mc-merged-26.2.jar'))
forge = pathlib.Path(os.environ.get('FORGE_RT', old / 'run/merged-base/forge-runtime-interop.jar'))
if not forge.is_file(): forge = old / 'run/forge-runtime/forge-runtime.jar'
neo = pathlib.Path(os.environ.get('NEO_RT', old / 'run/neoforge-runtime/neoforge-runtime.jar'))
libraries = []
for entry in json.loads((mc / 'versions/26.2/26.2.json').read_text())['libraries']:
    artifact = entry.get('downloads', {}).get('artifact', {}).get('path')
    if artifact and (mc / 'libraries' / artifact).is_file(): libraries.append(mc / 'libraries' / artifact)
mixin = mc / 'libraries/net/fabricmc/sponge-mixin/0.17.3+mixin.0.8.7/sponge-mixin-0.17.3+mixin.0.8.7.jar'
asm = [pathlib.Path(p) for p in os.environ['M36_BUILD_CP'].split(os.pathsep)
       if pathlib.Path(p).is_file() and pathlib.Path(p).name.startswith('asm-')]
if not any(p.name.startswith('asm-tree-') for p in asm): raise SystemExit('resolved ASM tree dependency missing')
classpath = [compile_game, forge, neo, mixin, *asm, *libraries]
for path in [*classpath, merged]:
    if not path.is_file(): raise SystemExit(f'M36 prerequisite absent: {path}')
root = kernel / 'canary/mixin-outcome'
classes = work / 'classes'; classes.mkdir()
subprocess.run(['javac', '-proc:none', '--release', '21', '-cp', os.pathsep.join(map(str, classpath)),
                '-d', str(classes), *map(str, sorted((root / 'src').rglob('*.java')))], check=True)
output = kernel / 'run/canary'; output.mkdir(exist_ok=True)
jar = output / 'forbricoutcome.jar'
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as target:
    for path in sorted(classes.rglob('*.class')): target.write(path, path.relative_to(classes).as_posix())
    for name in ('META-INF/neoforge.mods.toml', 'outcome-canary.mixins.json'): target.write(root / name, name)
def record(path):
    path = path.resolve(); return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
(output / 'm36-build-inputs.json').write_text(json.dumps({'mod': record(jar), 'compileGame': record(compile_game),
    'compileClasspath': [record(path) for path in classpath], 'merged': record(merged), 'forge': record(forge), 'neo': record(neo)}, indent=2) + '\n')
print('[M36Outcome] built actual final Mixin application fixture')
PY
