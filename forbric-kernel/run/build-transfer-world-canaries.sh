#!/usr/bin/env bash
# Three separate ecology fixtures for M33. Common classes live only in the Fabric jar.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
canary_scratch transfer-world
kernel_jar
export M33_BUILD_WORK="$WORK" M33_BUILD_KERNEL="$KERNEL" M33_BUILD_OLD="$OLD"
python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess, zipfile
kernel = pathlib.Path(os.environ['M33_BUILD_KERNEL'])
old = pathlib.Path(os.environ['M33_BUILD_OLD'])
work = pathlib.Path(os.environ['M33_BUILD_WORK'])
mc = pathlib.Path(os.environ.get('MC_DIR', pathlib.Path.home() / 'Library/Application Support/minecraft'))
fapi = pathlib.Path(os.environ.get('M33_FABRIC_API', old.parent / 'forbric-kernel/run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar'))
merged = pathlib.Path(os.environ.get('MERGED', old / 'run/merged-base/patched-mc-merged-26.2.jar'))
forge = pathlib.Path(os.environ.get('FORGE_RT', old / 'run/merged-base/forge-runtime-interop.jar'))
if not forge.is_file(): forge = old / 'run/forge-runtime/forge-runtime.jar'
neo = pathlib.Path(os.environ.get('NEO_RT', old / 'run/neoforge-runtime/neoforge-runtime.jar'))
compile_game = pathlib.Path(os.environ.get('M33_COMPILE_GAME', old / 'run/neoforge-patched/patched-mc-neoforge-26.2.jar'))
boot = kernel / 'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar'
for path in (fapi, merged, forge, neo, compile_game, boot):
    if not path.is_file(): raise SystemExit(f'M33 prerequisite missing: {path}')
modules = work / 'modules'; modules.mkdir()
with zipfile.ZipFile(fapi) as source:
    for name in source.namelist():
        if name.startswith('META-INF/jars/') and name.endswith('.jar'):
            (modules / pathlib.PurePosixPath(name).name).write_bytes(source.read(name))
libraries = []
metadata = json.loads((mc / 'versions/26.2/26.2.json').read_text())
for library in metadata.get('libraries', []):
    artifact = library.get('downloads', {}).get('artifact', {}).get('path')
    if artifact and (mc / 'libraries' / artifact).is_file(): libraries.append(mc / 'libraries' / artifact)
# Native mods compile against an upstream game API, not the conflicting interface union. In particular,
# javac refuses a Block subclass against the raw union's two default beacon methods; the real kernel must
# resolve that at runtime. Do not conceal it with canary-only overrides. Runtime input remains merged.
classpath = [compile_game, merged, boot, forge, neo, *modules.glob('*.jar'), *libraries]
root = kernel / 'canary/transfer'
compiled = {}
for family in ('fabric', 'forge', 'neo'):
    destination = work / family; destination.mkdir()
    sources = sorted((root / family / 'src').rglob('*.java'))
    if family == 'fabric': sources += sorted((root / 'common/src').rglob('*.java'))
    cp = [*classpath] + ([compiled['fabric']] if family != 'fabric' else [])
    subprocess.run(['javac', '-proc:none', '--release', '21', '-cp', os.pathsep.join(map(str, cp)),
                    '-d', str(destination), *map(str, sources)], check=True)
    compiled[family] = destination
output = kernel / 'run/canary'; output.mkdir(parents=True, exist_ok=True)
staged = []
for family, destination in compiled.items():
    name = {'fabric':'forbrictransferfabric', 'forge':'forbrictransferforge', 'neo':'forbrictransferneo'}[family]
    jar = work / (name + '.jar')
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as target:
        for path in sorted(destination.rglob('*.class')): target.write(path, path.relative_to(destination).as_posix())
        if family == 'fabric': target.write(root / family / 'fabric.mod.json', 'fabric.mod.json')
        else:
            metadata_name = 'mods.toml' if family == 'forge' else 'neoforge.mods.toml'
            target.write(root / family / 'META-INF' / metadata_name, 'META-INF/' + metadata_name)
    staged.append((jar, output / jar.name))
for source, target in staged: os.replace(source, target)
def record(path):
    path = path.resolve()
    return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
inputs = {'fabricApi': record(fapi), 'merged': record(merged), 'forge': record(forge), 'neo': record(neo),
          'compileGame': record(compile_game), 'kernel': record(boot), 'mods': [record(target) for _, target in staged]}
(output / 'm33-build-inputs.json').write_text(json.dumps(inputs, indent=2) + '\n')
print('[M33Transfer] built separate Fabric, Forge and NeoForge machine mods')
PY
