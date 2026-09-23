#!/usr/bin/env bash
# Only javac/jar; no kernel Gradle. Gate owns the separately scheduled kernel build.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
canary_scratch enchantment-contracts
export M38_WORK="$WORK" M38_KERNEL="$KERNEL" M38_OLD="$OLD"
python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess, zipfile
kernel, old, work = (pathlib.Path(os.environ[key]) for key in ('M38_KERNEL', 'M38_OLD', 'M38_WORK'))
mc = pathlib.Path(os.environ.get('MC_DIR', pathlib.Path.home() / 'Library/Application Support/minecraft'))
compile_game = pathlib.Path(os.environ.get('M38_COMPILE_GAME', old / 'run/neoforge-patched/patched-mc-neoforge-26.2.jar'))
merged = pathlib.Path(os.environ.get('MERGED', old / 'run/merged-base/patched-mc-merged-26.2.jar'))
forge = pathlib.Path(os.environ.get('FORGE_RT', old / 'run/merged-base/forge-runtime-interop.jar'))
if not forge.is_file(): forge = old / 'run/forge-runtime/forge-runtime.jar'
neo = pathlib.Path(os.environ.get('NEO_RT', old / 'run/neoforge-runtime/neoforge-runtime.jar'))
libraries = []
for entry in json.loads((mc / 'versions/26.2/26.2.json').read_text())['libraries']:
    artifact = entry.get('downloads', {}).get('artifact', {}).get('path')
    if artifact and (mc / 'libraries' / artifact).is_file(): libraries.append(mc / 'libraries' / artifact)
mixin = mc / 'libraries/net/fabricmc/sponge-mixin/0.17.3+mixin.0.8.7/sponge-mixin-0.17.3+mixin.0.8.7.jar'
fapi = pathlib.Path(os.environ.get('M38_FABRIC_API', old.parent / 'forbric-kernel/run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar'))
modules = kernel / 'run/canary/m38-modules'; modules.mkdir(parents=True, exist_ok=True)
selected=[]
with zipfile.ZipFile(fapi) as archive:
    import io
    candidates={}
    for name in archive.namelist():
        if name.startswith('META-INF/jars/') and name.endswith('.jar'):
            data=archive.read(name)
            with zipfile.ZipFile(io.BytesIO(data)) as module:
                if 'fabric.mod.json' in module.namelist():
                    meta=json.loads(module.read('fabric.mod.json'));candidates[meta['id']]=(name,data,meta)
    pending=['fabric-api-base','fabric-item-api-v1'];seen=set()
    while pending:
        id=pending.pop(0)
        if id in seen:continue
        seen.add(id)
        name,data,meta=candidates[id]
        target=modules/pathlib.PurePosixPath(name).name;target.write_bytes(data);selected.append(target)
        for dependency in meta.get('depends',{}):
            if dependency in candidates:pending.append(dependency)
            elif dependency.startswith('fabric-'):raise SystemExit('required upstream module missing: '+dependency)
    print('[M38Enchant] actual module dependency closure:', ', '.join(sorted(seen)))
classpath = [compile_game, forge, neo, mixin, kernel / "build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar", *selected, *libraries]
for path in [*classpath, merged]:
    if not path.is_file(): raise SystemExit(f'M38 prerequisite absent: {path}')
root = kernel / 'canary/enchantment-contracts'
classes = work / 'classes'; classes.mkdir()
subprocess.run(['javac', '-proc:none', '--release', '21', '-cp', os.pathsep.join(map(str, classpath)),
                '-d', str(classes), *map(str, sorted((root / 'src').rglob('*.java')))], check=True)
output = kernel / 'run/canary'; output.mkdir(exist_ok=True)
jar = output / 'forbricenchantment.jar'
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as target:
    for path in sorted(classes.rglob('*.class')): target.write(path, path.relative_to(classes).as_posix())
    for name in ('META-INF/neoforge.mods.toml',): target.write(root / name, name)
language = output / 'forbriclangprobe.jar'
with zipfile.ZipFile(language, 'w', zipfile.ZIP_DEFLATED) as target:
    target.writestr('fabric.mod.json', json.dumps({'schemaVersion':1,'id':'forbriclangprobe','version':'1.0.0','name':'Fabric-only language probe'}))
    target.writestr('assets/forbriclangprobe/lang/en_us.json', json.dumps({'forbric.lang.probe':'Fabric language probe'}))
selected.append(language)
def record(path):
    path = path.resolve(); return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
(output / 'm38-build-inputs.json').write_text(json.dumps({'mod': record(jar), 'compileGame': record(compile_game),
    'compileClasspath': [record(path) for path in classpath], 'fabricApi': record(fapi), 'modules': [record(path) for path in selected], 'merged': record(merged), 'forge': record(forge), 'neo': record(neo)}, indent=2) + '\n')
print('[M38Enchant] built real enchantment fixture')
PY
