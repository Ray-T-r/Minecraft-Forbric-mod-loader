#!/usr/bin/env bash
# Builds gate-m46's canaries: run/canary/forbricstubmixins.jar, a Fabric mod whose mixin is compiled against vanilla
# (a name-only getDestroySpeed injection, the shape architectury and Collective ship; malilib's language @ModifyArgs), and run/canary/forbricstubdriver.jar,
# a NeoForge mod that calls those methods the way the merged game does and listens through fabric-entity-events-v1.
# Only javac/jar; no kernel Gradle. The gate owns the separately scheduled kernel build.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
canary_scratch stub-rebind
export M46_WORK="$WORK" M46_KERNEL="$KERNEL" M46_OLD="$OLD"
python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess, zipfile
kernel, old, work = (pathlib.Path(os.environ[key]) for key in ('M46_KERNEL', 'M46_OLD', 'M46_WORK'))
mc = pathlib.Path(os.environ.get('MC_DIR', pathlib.Path.home() / 'Library/Application Support/minecraft'))
compile_game = pathlib.Path(os.environ.get('M46_COMPILE_GAME', old / 'run/neoforge-patched/patched-mc-neoforge-26.2.jar'))
forge = pathlib.Path(os.environ.get('FORGE_RT', old / 'run/merged-base/forge-runtime-interop.jar'))
if not forge.is_file(): forge = old / 'run/forge-runtime/forge-runtime.jar'
neo = pathlib.Path(os.environ.get('NEO_RT', old / 'run/neoforge-runtime/neoforge-runtime.jar'))
fapi = pathlib.Path(os.environ.get('M46_FABRIC_API', kernel / 'run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar'))
vanilla = mc / 'versions/26.2/26.2.jar'
kernel_jar = kernel / 'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar'
mixin = mc / 'libraries/net/fabricmc/sponge-mixin/0.17.3+mixin.0.8.7/sponge-mixin-0.17.3+mixin.0.8.7.jar'
libraries = []
for entry in json.loads((mc / 'versions/26.2/26.2.json').read_text())['libraries']:
    artifact = entry.get('downloads', {}).get('artifact', {}).get('path')
    if artifact and (mc / 'libraries' / artifact).is_file(): libraries.append(mc / 'libraries' / artifact)
for path in [compile_game, forge, neo, fapi, vanilla, kernel_jar, mixin]:
    if not path.is_file(): raise SystemExit(f'M46 prerequisite absent: {path}')
modules = kernel / 'run/canary/m46-modules'; modules.mkdir(parents=True, exist_ok=True)
selected = []
with zipfile.ZipFile(fapi) as archive:
    for prefix in ('fabric-api-base-', 'fabric-entity-events-v1-'):
        names = [n for n in archive.namelist() if n.startswith('META-INF/jars/' + prefix) and n.endswith('.jar')]
        if len(names) != 1: raise SystemExit('required actual Fabric module missing or ambiguous: ' + prefix)
        target = modules / pathlib.PurePosixPath(names[0]).name; target.write_bytes(archive.read(names[0])); selected.append(target)
root = kernel / 'canary/stub-rebind'
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
# MixinExtras' annotations (the language mixin's @Local), as the kernel ships them.
extras = work / 'mixinextras-fabric.jar'
with zipfile.ZipFile(kernel_jar) as archive: extras.write_bytes(archive.read('META-INF/jars/mixinextras-fabric.jar'))
# The Fabric mod: against vanilla and Mixin's annotations only, as a Fabric mod is built.
mixins = build('forbricstubmixins', root / 'fabric-mixins/src', [vanilla, kernel_jar, mixin, extras, *libraries],
               [(root / 'fabric-mixins/fabric.mod.json', 'fabric.mod.json'),
                (root / 'fabric-mixins/forbricstubmixins.mixins.json', 'forbricstubmixins.mixins.json')])
driver = build('forbricstubdriver', root / 'driver/src', [compile_game, forge, neo, *selected, *libraries],
               [(root / 'driver/META-INF/neoforge.mods.toml', 'META-INF/neoforge.mods.toml')])
def record(path):
    path = path.resolve(); return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
(output / 'm46-build-inputs.json').write_text(json.dumps({'mixins': record(mixins), 'driver': record(driver), 'vanilla': record(vanilla), 'mixin': record(mixin),
    'compileGame': record(compile_game), 'forge': record(forge), 'neo': record(neo), 'fabricApi': record(fapi),
    'modules': [record(p) for p in selected]}, indent=2) + '\n')
print('[M46StubRebind] built the stub-rebind probes')
PY
