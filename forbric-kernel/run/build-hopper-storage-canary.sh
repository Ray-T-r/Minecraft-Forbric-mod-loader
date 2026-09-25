#!/usr/bin/env bash
# Builds run/canary/forbrichopper.jar for gate-m52: a Fabric mod compiled against vanilla's jar and the unmodified
# fabric-api modules, with three Fabric item storages (slotted on a block entity, unslotted, block-only) and the probe
# that drives hoppers against them. Only javac/jar; no kernel Gradle.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
canary_scratch hopper-storage
export M52_WORK="$WORK" M52_KERNEL="$KERNEL"
python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess, zipfile
kernel, work = (pathlib.Path(os.environ[key]) for key in ('M52_KERNEL', 'M52_WORK'))
mc = pathlib.Path(os.environ.get('MC_DIR', pathlib.Path.home() / 'Library/Application Support/minecraft'))
fapi = pathlib.Path(os.environ.get('M52_FABRIC_API', kernel / 'run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar'))
vanilla = mc / 'versions/26.2/26.2.jar'
kernel_jar = kernel / 'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar'
for path in [fapi, vanilla, kernel_jar]:
    if not path.is_file(): raise SystemExit(f'M52 prerequisite absent: {path}')
libraries = []
for entry in json.loads((mc / 'versions/26.2/26.2.json').read_text())['libraries']:
    artifact = entry.get('downloads', {}).get('artifact', {}).get('path')
    if artifact and (mc / 'libraries' / artifact).is_file(): libraries.append(mc / 'libraries' / artifact)
modules = work / 'modules'; modules.mkdir()
with zipfile.ZipFile(fapi) as source:
    for name in source.namelist():
        if name.startswith('META-INF/jars/') and name.endswith('.jar'):
            (modules / pathlib.PurePosixPath(name).name).write_bytes(source.read(name))
root = kernel / 'canary/hopper-storage'
classes = work / 'classes'; classes.mkdir()
subprocess.run(['javac', '-proc:none', '-nowarn', '--release', '21', '-cp', os.pathsep.join(map(str, [vanilla, kernel_jar, *modules.glob('*.jar'), *libraries])),
                '-d', str(classes), *map(str, sorted((root / 'src').rglob('*.java')))], check=True)
output = kernel / 'run/canary'; output.mkdir(exist_ok=True)
staged = work / 'forbrichopper.jar'
with zipfile.ZipFile(staged, 'w', zipfile.ZIP_DEFLATED) as target:
    for path in sorted(classes.rglob('*.class')): target.write(path, path.relative_to(classes).as_posix())
    target.write(root / 'fabric.mod.json', 'fabric.mod.json')
jar = output / 'forbrichopper.jar'
os.replace(staged, jar)
def record(path):
    path = path.resolve(); return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
(output / 'm52-build-inputs.json').write_text(json.dumps({'mod': record(jar), 'vanilla': record(vanilla), 'fabricApi': record(fapi)}, indent=2) + '\n')
print('[M52Hopper] built the hopper storage probe')
PY
