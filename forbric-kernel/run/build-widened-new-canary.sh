#!/usr/bin/env bash
# Builds gate-m53's canaries: run/canary/forbricnewprobe.jar, a Fabric mod whose HEAD mixins capture the BLOCK particle
# options the server sends and adds, and run/canary/forbricnewdriver.jar, a NeoForge mod that makes a zombie land and
# sprint on stone and reads both NeoForge's position and fabric-particles' ground block off the options. The unmodified
# fabric-particles-v1 and its dependencies go to run/canary/m53-modules. Only javac/jar; no kernel Gradle.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
canary_scratch widened-new
export M53_WORK="$WORK" M53_KERNEL="$KERNEL" M53_OLD="$OLD"
python3 - <<'PY'
import hashlib, json, os, pathlib, shutil, subprocess, zipfile
kernel, old, work = (pathlib.Path(os.environ[key]) for key in ('M53_KERNEL', 'M53_OLD', 'M53_WORK'))
mc = pathlib.Path(os.environ.get('MC_DIR', pathlib.Path.home() / 'Library/Application Support/minecraft'))
compile_game = pathlib.Path(os.environ.get('M53_COMPILE_GAME', old / 'run/neoforge-patched/patched-mc-neoforge-26.2.jar'))
forge = pathlib.Path(os.environ.get('FORGE_RT', old / 'run/merged-base/forge-runtime-interop.jar'))
if not forge.is_file(): forge = old / 'run/forge-runtime/forge-runtime.jar'
neo = pathlib.Path(os.environ.get('NEO_RT', old / 'run/neoforge-runtime/neoforge-runtime.jar'))
fapi = pathlib.Path(os.environ.get('M53_FABRIC_API', kernel / 'run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar'))
vanilla = mc / 'versions/26.2/26.2.jar'
kernel_jar = kernel / 'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar'
mixin = mc / 'libraries/net/fabricmc/sponge-mixin/0.17.3+mixin.0.8.7/sponge-mixin-0.17.3+mixin.0.8.7.jar'
libraries = []
for entry in json.loads((mc / 'versions/26.2/26.2.json').read_text())['libraries']:
    artifact = entry.get('downloads', {}).get('artifact', {}).get('path')
    if artifact and (mc / 'libraries' / artifact).is_file(): libraries.append(mc / 'libraries' / artifact)
for path in [compile_game, forge, neo, fapi, vanilla, kernel_jar, mixin]:
    if not path.is_file(): raise SystemExit(f'M53 prerequisite absent: {path}')
modules = kernel / 'run/canary/m53-modules'
shutil.rmtree(modules, ignore_errors=True); modules.mkdir(parents=True)
selected = []
with zipfile.ZipFile(fapi) as archive:
    for prefix in ('fabric-api-base-', 'fabric-networking-api-v1-', 'fabric-particles-v1-'):
        names = [n for n in archive.namelist() if n.startswith('META-INF/jars/' + prefix) and n.endswith('.jar')]
        if len(names) != 1: raise SystemExit('required actual Fabric module missing or ambiguous: ' + prefix)
        target = modules / pathlib.PurePosixPath(names[0]).name; target.write_bytes(archive.read(names[0])); selected.append(target)
root = kernel / 'canary/widened-new'
output = kernel / 'run/canary'; output.mkdir(exist_ok=True)
def build(name, sources, classpath, extra):
    classes = work / (name + '-classes'); classes.mkdir()
    subprocess.run(['javac', '-proc:none', '-nowarn', '--release', '21', '-cp', os.pathsep.join(map(str, classpath)),
                    '-d', str(classes), *map(str, sorted(sources.rglob('*.java')))], check=True)
    staged = work / (name + '.jar')
    with zipfile.ZipFile(staged, 'w', zipfile.ZIP_DEFLATED) as target:
        for path in sorted(classes.rglob('*.class')): target.write(path, path.relative_to(classes).as_posix())
        for source, arc in extra: target.write(source, arc)
    jar = output / (name + '.jar'); os.replace(staged, jar); return jar
probe = build('forbricnewprobe', root / 'probe/src', [vanilla, kernel_jar, mixin, *libraries],
              [(root / 'probe/fabric.mod.json', 'fabric.mod.json'), (root / 'probe/forbricnewprobe.mixins.json', 'forbricnewprobe.mixins.json')])
driver = build('forbricnewdriver', root / 'driver/src', [compile_game, forge, neo, *selected, *libraries],
               [(root / 'driver/META-INF/neoforge.mods.toml', 'META-INF/neoforge.mods.toml')])
def record(path):
    path = path.resolve(); return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
(output / 'm53-build-inputs.json').write_text(json.dumps({'probe': record(probe), 'driver': record(driver), 'vanilla': record(vanilla),
    'compileGame': record(compile_game), 'forge': record(forge), 'neo': record(neo), 'fabricApi': record(fapi),
    'modules': [record(p) for p in selected]}, indent=2) + '\n')
print('[M53New] built the widened-construction probes')
PY
