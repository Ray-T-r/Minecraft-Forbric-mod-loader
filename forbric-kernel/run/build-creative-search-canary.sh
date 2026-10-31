#!/usr/bin/env bash
# Builds gate-m55's canary: run/canary/forbriccreativesearch.jar, a client-only Fabric mod compiled against vanilla's jar
# whose one mixin does what TCDCommons does on every join — rebuild the creative tabs, then refresh the creative search
# through vanilla's two SessionSearchTrees methods. (Its MinecraftForge reader, forbriccreativesearchforge.jar, went
# with MinecraftForge: Forbric refuses a MinecraftForge-only jar, and gate-m55 stopped staging it in Stage 3.)
# Only javac/jar; no kernel Gradle.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
canary_scratch creative-search
export M54_WORK="$WORK" M54_KERNEL="$KERNEL"
python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess, zipfile
kernel, work = (pathlib.Path(os.environ[key]) for key in ('M54_KERNEL', 'M54_WORK'))
mc = pathlib.Path(os.environ.get('MC_DIR', pathlib.Path.home() / 'Library/Application Support/minecraft'))
vanilla = mc / 'versions/26.2/26.2.jar'
kernel_jar = kernel / 'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar'
mixin = mc / 'libraries/net/fabricmc/sponge-mixin/0.17.3+mixin.0.8.7/sponge-mixin-0.17.3+mixin.0.8.7.jar'
# A vanilla Minecraft tree (a CI runner's .dev/minecraft) has no Fabric libraries: take the kernel's own copy.
if not mixin.is_file() and os.environ.get('FORBRIC_SPONGE_MIXIN'): mixin = pathlib.Path(os.environ['FORBRIC_SPONGE_MIXIN'])
libraries = []
for entry in json.loads((mc / 'versions/26.2/26.2.json').read_text())['libraries']:
    artifact = entry.get('downloads', {}).get('artifact', {}).get('path')
    if artifact and (mc / 'libraries' / artifact).is_file(): libraries.append(mc / 'libraries' / artifact)
for path in [vanilla, kernel_jar, mixin]:
    if not path.is_file(): raise SystemExit(f'M54 prerequisite absent: {path}')
root = kernel / 'canary/creative-search'
output = kernel / 'run/canary'; output.mkdir(exist_ok=True)
classes = work / 'classes'; classes.mkdir()
subprocess.run(['javac', '-proc:none', '-nowarn', '--release', '21',
                '-cp', os.pathsep.join(map(str, [vanilla, kernel_jar, mixin, *libraries])),
                '-d', str(classes), *map(str, sorted((root / 'src').rglob('*.java')))], check=True)
staged = work / 'forbriccreativesearch.jar'
with zipfile.ZipFile(staged, 'w', zipfile.ZIP_DEFLATED) as target:
    for path in sorted(classes.rglob('*.class')): target.write(path, path.relative_to(classes).as_posix())
    target.write(root / 'fabric.mod.json', 'fabric.mod.json')
    target.write(root / 'forbriccreativesearch.mixins.json', 'forbriccreativesearch.mixins.json')
jar = output / 'forbriccreativesearch.jar'
os.replace(staged, jar)
def record(path):
    path = path.resolve(); return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
(output / 'm54-build-inputs.json').write_text(json.dumps({'canary': record(jar), 'vanilla': record(vanilla),
    'mixin': record(mixin)}, indent=2) + '\n')
print('[M54CreativeSearch] built the creative-search canary')
PY
