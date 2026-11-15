#!/usr/bin/env bash
# Builds gate-m24's and gate-m24d's NeoForge canary that fails on purpose inside NeoForge's mod loading ("stop like native":
# owner ruling D82 for the registration, D87 for every other phase):
#   run/canary/forbricregisterstop.jar — a NeoForge mod compiled against NeoForge's patched game and its runtime, with a
#   mixin on GameData.postRegisterEvents. -Dforbric.registerstop picks the failure: listener (default), try, construct,
#   constructevent, ebs, newregistry, datapackreg, tail, common, deferred, sided, regevents, imc, complete, network, off (see
#   canary/register-stop/src/forbric/registerstop/ForbricRegisterStop.java).
# Only javac/jar; no kernel Gradle. The same jar runs unchanged on native NeoForge 26.2.0.93 (the comparison runs).
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
canary_scratch register-stop
kernel_classpath
export RS_BUILD_CP="$KERNEL_CP" RS_WORK="$WORK" RS_KERNEL="$KERNEL" RS_OLD="$OLD"
python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess, zipfile
kernel, old, work = (pathlib.Path(os.environ[key]) for key in ('RS_KERNEL', 'RS_OLD', 'RS_WORK'))
mc = pathlib.Path(os.environ.get('MC_DIR', pathlib.Path.home() / 'Library/Application Support/minecraft'))
game = old / 'run/neoforge-patched/patched-mc-neoforge-26.2.jar'
neo = old / 'run/neoforge-runtime/neoforge-runtime.jar'
libraries = []
for entry in json.loads((mc / 'versions/26.2/26.2.json').read_text())['libraries']:
    artifact = entry.get('downloads', {}).get('artifact', {}).get('path')
    if artifact and (mc / 'libraries' / artifact).is_file(): libraries.append(mc / 'libraries' / artifact)
mixin = mc / 'libraries/net/fabricmc/sponge-mixin/0.17.3+mixin.0.8.7/sponge-mixin-0.17.3+mixin.0.8.7.jar'
# A vanilla Minecraft tree (a CI runner's .dev/minecraft) has no Fabric libraries: take the kernel's own copy.
if not mixin.is_file() and os.environ.get('FORBRIC_SPONGE_MIXIN'): mixin = pathlib.Path(os.environ['FORBRIC_SPONGE_MIXIN'])
if not mixin.is_file():
    mixin = next((pathlib.Path(p) for p in os.environ['RS_BUILD_CP'].split(os.pathsep)
                  if pathlib.Path(p).name.startswith('sponge-mixin') and pathlib.Path(p).is_file()), mixin)
classpath = [game, neo, mixin, *libraries]
for path in classpath[:3]:
    if not path.is_file(): raise SystemExit(f'register-stop canary prerequisite absent: {path}')
root = kernel / 'canary/register-stop'
classes = work / 'classes'; classes.mkdir()
subprocess.run(['javac', '-proc:none', '--release', '21', '-nowarn', '-cp', os.pathsep.join(map(str, classpath)),
                '-d', str(classes), *map(str, sorted((root / 'src').rglob('*.java')))], check=True)
output = kernel / 'run/canary'; output.mkdir(exist_ok=True)
staged = work / 'forbricregisterstop.jar'
with zipfile.ZipFile(staged, 'w', zipfile.ZIP_DEFLATED) as target:
    for path in sorted(classes.rglob('*.class')): target.write(path, path.relative_to(classes).as_posix())
    for name in ('META-INF/neoforge.mods.toml', 'forbricregisterstop.mixins.json'): target.write(root / name, name)
jar = output / 'forbricregisterstop.jar'
os.replace(staged, jar)
print('[RegisterStop] built ' + str(jar) + ' sha256 ' + hashlib.sha256(jar.read_bytes()).hexdigest()[:12])
PY
