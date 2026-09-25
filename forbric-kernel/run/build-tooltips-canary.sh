#!/usr/bin/env bash
# Builds the gate-m51 mods: run/canary/forbrictooltips.jar, a NeoForge mod that renders item tooltips on a dedicated
# server and registers one NeoForge tooltip appender, and run/canary/forbrictooltipsfabric.jar, a Fabric mod compiled
# against vanilla's jar that registers component tooltip providers through the unmodified fabric-item-api-v1 (copied
# with its dependencies to run/canary/m51-modules). Only javac/jar; no kernel Gradle.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
canary_scratch tooltips
export M51_WORK="$WORK" M51_KERNEL="$KERNEL" M51_OLD="$OLD"
python3 - <<'PY'
import hashlib, json, os, pathlib, shutil, subprocess, zipfile
kernel, old, work = (pathlib.Path(os.environ[key]) for key in ('M51_KERNEL', 'M51_OLD', 'M51_WORK'))
mc = pathlib.Path(os.environ.get('MC_DIR', pathlib.Path.home() / 'Library/Application Support/minecraft'))
compile_game = pathlib.Path(os.environ.get('M51_COMPILE_GAME', old / 'run/neoforge-patched/patched-mc-neoforge-26.2.jar'))
forge = pathlib.Path(os.environ.get('FORGE_RT', old / 'run/merged-base/forge-runtime-interop.jar'))
if not forge.is_file(): forge = old / 'run/forge-runtime/forge-runtime.jar'
neo = pathlib.Path(os.environ.get('NEO_RT', old / 'run/neoforge-runtime/neoforge-runtime.jar'))
fapi = pathlib.Path(os.environ.get('M51_FABRIC_API', kernel / 'run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar'))
vanilla = mc / 'versions/26.2/26.2.jar'
kernel_jar = kernel / 'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar'
libraries = []
for entry in json.loads((mc / 'versions/26.2/26.2.json').read_text())['libraries']:
    artifact = entry.get('downloads', {}).get('artifact', {}).get('path')
    if artifact and (mc / 'libraries' / artifact).is_file(): libraries.append(mc / 'libraries' / artifact)
for path in [compile_game, forge, neo, fapi, vanilla, kernel_jar]:
    if not path.is_file(): raise SystemExit(f'M51 prerequisite absent: {path}')
modules = kernel / 'run/canary/m51-modules'
shutil.rmtree(modules, ignore_errors=True); modules.mkdir(parents=True)
selected = []
with zipfile.ZipFile(fapi) as archive:
    for prefix in ('fabric-api-base-', 'fabric-resource-loader-v1-', 'fabric-item-api-v1-', 'fabric-lifecycle-events-v1-'):
        names = [n for n in archive.namelist() if n.startswith('META-INF/jars/' + prefix) and n.endswith('.jar')]
        if len(names) != 1: raise SystemExit('required actual Fabric module missing or ambiguous: ' + prefix)
        target = modules / pathlib.PurePosixPath(names[0]).name; target.write_bytes(archive.read(names[0])); selected.append(target)
root = kernel / 'canary/tooltips'
output = kernel / 'run/canary'; output.mkdir(exist_ok=True)
classes = work / 'classes'; classes.mkdir()
subprocess.run(['javac', '-proc:none', '--release', '21', '-cp', os.pathsep.join(map(str, [compile_game, forge, neo, *libraries])),
                '-d', str(classes), *map(str, sorted((root / 'src').rglob('*.java')))], check=True)
staged = work / 'forbrictooltips.jar'
with zipfile.ZipFile(staged, 'w', zipfile.ZIP_DEFLATED) as target:
    for path in sorted(classes.rglob('*.class')): target.write(path, path.relative_to(classes).as_posix())
    target.write(root / 'META-INF/neoforge.mods.toml', 'META-INF/neoforge.mods.toml')
jar = output / 'forbrictooltips.jar'
os.replace(staged, jar)
fabric = root / 'fabric'
fabric_classes = work / 'fabric-classes'; fabric_classes.mkdir()
subprocess.run(['javac', '-proc:none', '-nowarn', '--release', '21', '-cp', os.pathsep.join(map(str, [vanilla, kernel_jar, *selected, *libraries])),
                '-d', str(fabric_classes), *map(str, sorted((fabric / 'src').rglob('*.java')))], check=True)
staged_fabric = work / 'forbrictooltipsfabric.jar'
with zipfile.ZipFile(staged_fabric, 'w', zipfile.ZIP_DEFLATED) as target:
    for path in sorted(fabric_classes.rglob('*.class')): target.write(path, path.relative_to(fabric_classes).as_posix())
    target.write(fabric / 'fabric.mod.json', 'fabric.mod.json')
fabric_jar = output / 'forbrictooltipsfabric.jar'
os.replace(staged_fabric, fabric_jar)
def record(path):
    path = path.resolve(); return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
(output / 'm51-build-inputs.json').write_text(json.dumps({'mod': record(jar), 'fabricMod': record(fabric_jar), 'vanilla': record(vanilla),
    'compileGame': record(compile_game), 'forge': record(forge), 'neo': record(neo), 'fabricApi': record(fapi),
    'modules': [record(p) for p in selected]}, indent=2) + '\n')
print('[M51Tooltips] built the tooltip probes')
PY
