#!/usr/bin/env bash
# Builds run/canary/forbricchainprobe.jar for gate-m41: a NeoForge mod whose MinecraftForge listener vetoes one
# tagged entity joining the level. Compiled against the same staged jars as the other canaries; records its inputs.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
canary_scratch chain-probe
export M41_WORK="$WORK" M41_KERNEL="$KERNEL" M41_OLD="$OLD"
python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess, zipfile
kernel, old, work = (pathlib.Path(os.environ[key]) for key in ('M41_KERNEL', 'M41_OLD', 'M41_WORK'))
mc = pathlib.Path(os.environ.get('MC_DIR', pathlib.Path.home() / 'Library/Application Support/minecraft'))
compile_game = old / 'run/neoforge-patched/patched-mc-neoforge-26.2.jar'
forge = pathlib.Path(os.environ.get('FORGE_RT', old / 'run/merged-base/forge-runtime-interop.jar'))
if not forge.is_file(): forge = old / 'run/forge-runtime/forge-runtime.jar'
# Optional for a NeoForge canary: on the compile classpath when staged, left out (and recorded as null) when not.
forge = [forge] if forge.is_file() else []
neo = pathlib.Path(os.environ.get('NEO_RT', old / 'run/neoforge-runtime/neoforge-runtime.jar'))
libraries = []
for entry in json.loads((mc / 'versions/26.2/26.2.json').read_text())['libraries']:
    artifact = entry.get('downloads', {}).get('artifact', {}).get('path')
    if artifact and (mc / 'libraries' / artifact).is_file(): libraries.append(mc / 'libraries' / artifact)
classpath = [compile_game, *forge, neo, *libraries]
for path in [compile_game, *forge, neo]:
    if not path.is_file(): raise SystemExit(f'M41 prerequisite absent: {path}')
root = kernel / 'canary/event-chain'
# Without a MinecraftForge carrier only a canary whose own sources name none of it compiles: say so, not javac.
if not forge and any('net.minecraftforge' in source.read_text(errors='replace') for source in root.rglob('*.java')):
    raise SystemExit(f'M41 prerequisite absent: no MinecraftForge carrier is staged, and {root} still names net.minecraftforge')
classes = work / 'classes'; classes.mkdir()
subprocess.run(['javac', '-proc:none', '--release', '21', '-cp', os.pathsep.join(map(str, classpath)),
                '-d', str(classes), *map(str, sorted((root / 'src').rglob('*.java')))], check=True)
output = kernel / 'run/canary'; output.mkdir(exist_ok=True)
staged = work / 'forbricchainprobe.jar'
with zipfile.ZipFile(staged, 'w', zipfile.ZIP_DEFLATED) as target:
    for path in sorted(classes.rglob('*.class')): target.write(path, path.relative_to(classes).as_posix())
    target.write(root / 'META-INF/neoforge.mods.toml', 'META-INF/neoforge.mods.toml')
jar = output / 'forbricchainprobe.jar'
os.replace(staged, jar)
def record(path):
    path = path.resolve(); return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
(output / 'm41-build-inputs.json').write_text(json.dumps({'mod': record(jar), 'compileGame': record(compile_game),
    'forge': record(forge[0]) if forge else None, 'neo': record(neo)}, indent=2) + '\n')
print('[M41Chain] built the entity-join veto fixture')
PY
