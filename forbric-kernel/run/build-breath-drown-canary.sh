#!/usr/bin/env bash
# Builds gate-m57's two canaries from canary/forge-breath-drown into run/canary/:
#   forbricbreathforge.jar — a MinecraftForge mod, compiled against MinecraftForge's patched game and its runtime;
#   forbricbreathneo.jar   — a NeoForge mod, compiled against NeoForge's patched game and its runtime.
# Each carries its own copy of common/src/Core.java under its own package, so both can sit in one pack.
# Only javac/jar; no kernel Gradle. The gate owns the separately scheduled kernel build. The same jars run unchanged
# on the native loaders (run/compat/breath-drown.py reference).
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
canary_scratch breath-drown
export M57_WORK="$WORK" M57_KERNEL="$KERNEL" M57_OLD="$OLD"
python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess, zipfile
kernel, old, work = (pathlib.Path(os.environ[key]) for key in ('M57_KERNEL', 'M57_OLD', 'M57_WORK'))
mc = pathlib.Path(os.environ.get('MC_DIR', pathlib.Path.home() / 'Library/Application Support/minecraft'))
inputs = {
    'forge': [old / 'run/forge-patched/patched-mc-forge-26.2.jar', old / 'run/forge-runtime/forge-runtime.jar'],
    'neo': [old / 'run/neoforge-patched/patched-mc-neoforge-26.2.jar', old / 'run/neoforge-runtime/neoforge-runtime.jar'],
}
mods = {'forge': ('forbricbreathforge', 'META-INF/mods.toml'), 'neo': ('forbricbreathneo', 'META-INF/neoforge.mods.toml')}
libraries = []
for entry in json.loads((mc / 'versions/26.2/26.2.json').read_text())['libraries']:
    artifact = entry.get('downloads', {}).get('artifact', {}).get('path')
    if artifact and (mc / 'libraries' / artifact).is_file(): libraries.append(mc / 'libraries' / artifact)
for path in [p for paths in inputs.values() for p in paths]:
    if not path.is_file(): raise SystemExit(f'M57 prerequisite absent: {path}')
root = kernel / 'canary/forge-breath-drown'
core = (root / 'common/src/Core.java').read_text()
output = kernel / 'run/canary'; output.mkdir(exist_ok=True)
def record(path):
    path = pathlib.Path(path).resolve(); return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
built = {}
for family, classpath in inputs.items():
    src = work / family / 'src'; classes = work / family / 'classes'
    (src / f'forbric/breathdrown/{family}').mkdir(parents=True); classes.mkdir(parents=True)
    (src / f'forbric/breathdrown/{family}/Core.java').write_text(core.replace('PKG', family))
    sources = sorted((root / family / 'src').rglob('*.java')) + [src / f'forbric/breathdrown/{family}/Core.java']
    subprocess.run(['javac', '-proc:none', '--release', '21', '-nowarn', '-cp', os.pathsep.join(map(str, [*classpath, *libraries])),
                    '-d', str(classes), *map(str, sources)], check=True)
    modid, toml = mods[family]
    staged = work / f'{modid}.jar'
    with zipfile.ZipFile(staged, 'w', zipfile.ZIP_DEFLATED) as target:
        for path in sorted(classes.rglob('*.class')): target.write(path, path.relative_to(classes).as_posix())
        target.write(root / family / toml, toml)
    jar = output / f'{modid}.jar'
    os.replace(staged, jar)
    built[family] = {'mod': record(jar), 'compile': [record(p) for p in classpath]}
sources = sorted(p for p in root.rglob('*') if p.is_file() and p.suffix in ('.java', '.toml'))
digest = hashlib.sha256(b''.join(p.relative_to(root).as_posix().encode() + b'\0' + p.read_bytes() for p in sources)).hexdigest()
(output / 'm57-build-inputs.json').write_text(json.dumps({'canarySources': digest, **built}, indent=2) + '\n')
print('[M57BreathDrown] built forbricbreathforge.jar and forbricbreathneo.jar, sources ' + digest[:12])
PY
