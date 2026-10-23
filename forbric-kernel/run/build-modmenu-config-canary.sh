#!/usr/bin/env bash
# Builds gate-m56's canaries into run/canary/modmenu-config/ from canary/modmenu-config/:
#   forbricmmcfga.jar   Fabric, client: a Mod Menu entrypoint with its own screen (forbric.mmcfg.a.CanaryAScreen), which
#                       also provides one for canary E; carries forbricmmcfgc.jar and forbricmmcfgd.jar inside itself
#                       (jar-in-jar): C names no parent, D names a parent object that is no mod.
#   forbricmmcfgb.jar   Fabric, client: a second Mod Menu entrypoint with its own screen.
#   forbricmmcfge.jar   Fabric, client: no entrypoint; its screen is the one canary A provides for it.
#   forbricmmplain.jar  Fabric, client: no config screen anywhere.
#   forbricmmuni.jar    one jar for both Fabric and NeoForge (NeoForge wins it): NeoForge registers its screen on its
#                       own container, the Fabric half declares a Mod Menu entrypoint that never runs on Forbric.
# The Mod Menu API the Fabric halves implement is two COMPILE-ONLY interfaces with Mod Menu 20.0.3's descriptors
# (canary/modmenu-config/api), never packed: the jars link against whatever ModMenuApi the game defines, as a real mod
# does. No third-party jar is needed or shipped. Only javac/jar; no kernel Gradle.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
canary_scratch modmenu-config
export M56_WORK="$WORK" M56_KERNEL="$KERNEL" M56_OLD="$OLD"
python3 - <<'PY'
import hashlib, json, os, pathlib, subprocess, zipfile
kernel, work, old = (pathlib.Path(os.environ[key]) for key in ('M56_KERNEL', 'M56_WORK', 'M56_OLD'))
mc = pathlib.Path(os.environ.get('MC_DIR', pathlib.Path.home() / 'Library/Application Support/minecraft'))
vanilla = mc / 'versions/26.2/26.2.jar'
neo = pathlib.Path(os.environ.get('NEO_RT', old / 'run/neoforge-runtime/neoforge-runtime.jar'))
libraries = []
for entry in json.loads((mc / 'versions/26.2/26.2.json').read_text())['libraries']:
    artifact = entry.get('downloads', {}).get('artifact', {}).get('path')
    if artifact and (mc / 'libraries' / artifact).is_file(): libraries.append(mc / 'libraries' / artifact)
for path in [vanilla, neo]:
    if not path.is_file(): raise SystemExit(f'M56 prerequisite absent: {path}')
root = kernel / 'canary/modmenu-config'
output = kernel / 'run/canary/modmenu-config'; output.mkdir(parents=True, exist_ok=True)

def javac(classpath, out, sources):
    out.mkdir(parents=True, exist_ok=True)
    subprocess.run(['javac', '-proc:none', '-nowarn', '--release', '21', '-cp', os.pathsep.join(map(str, classpath)),
                    '-d', str(out), *map(str, sources)], check=True)

api = work / 'api'
javac([vanilla, *libraries], api, sorted((root / 'api').rglob('*.java')))

def screen(package, cls, title):
    """One canary's own screen class, from the template, so the class name says whose screen it is."""
    text = (root / 'template/Screen.java.in').read_text()
    text = text.replace('@PACKAGE@', package).replace('@CLASS@', cls).replace('@TITLE@', title)
    target = work / 'gen' / package.replace('.', '/') / (cls + '.java')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text)
    return target

def jar(name, manifest_files, classes=None, nested=(), extra=()):
    staged = work / (name + '.jar')
    with zipfile.ZipFile(staged, 'w', zipfile.ZIP_DEFLATED) as target:
        if classes:
            for path in sorted(classes.rglob('*.class')): target.write(path, path.relative_to(classes).as_posix())
        for source, entry in manifest_files: target.write(source, entry)
        for path in nested: target.write(path, 'META-INF/jars/' + path.name)
        for entry, data in extra: target.writestr(entry, data)
    return staged

def fabric(letter, screens):
    sources = [screen(f'forbric.mmcfg.{letter}', cls, title) for cls, title in screens]
    src = root / letter / 'src'
    if src.is_dir(): sources += sorted(src.rglob('*.java'))
    classes = work / f'classes-{letter}'
    javac([vanilla, api, *libraries], classes, sources)
    return classes

# Carried canaries first: A packs them.
c = jar('forbricmmcfgc', [(root / 'c/fabric.mod.json', 'fabric.mod.json')],
        fabric('c', [('CanaryCScreen', 'Forbric Config Canary C')]))
d = jar('forbricmmcfgd', [(root / 'd/fabric.mod.json', 'fabric.mod.json')],
        fabric('d', [('CanaryDScreen', 'Forbric Config Canary D')]))
built = {
    'forbricmmcfga': jar('forbricmmcfga', [(root / 'a/fabric.mod.json', 'fabric.mod.json')],
                         fabric('a', [('CanaryAScreen', 'Forbric Config Canary A'),
                                      ('CanaryEScreen', 'Forbric Config Canary E (provided by A)')]), nested=[c, d]),
    'forbricmmcfgb': jar('forbricmmcfgb', [(root / 'b/fabric.mod.json', 'fabric.mod.json')],
                         fabric('b', [('CanaryBScreen', 'Forbric Config Canary B')])),
    'forbricmmcfge': jar('forbricmmcfge', [(root / 'e/fabric.mod.json', 'fabric.mod.json')]),
    'forbricmmplain': jar('forbricmmplain', [(root / 'plain/fabric.mod.json', 'fabric.mod.json')]),
}
# The universal canary: NeoForge half against NeoForge's API, Fabric half against the compile-only Mod Menu API.
uni_sources = [screen('forbric.mmcfg.uni', 'UniNeoScreen', 'Forbric Universal Config Canary (NeoForge)'),
               screen('forbric.mmcfg.uni', 'UniFabricScreen', 'Forbric Universal Config Canary (Fabric)'),
               *sorted((root / 'uni/src').rglob('*.java'))]
uni_classes = work / 'classes-uni'
javac([vanilla, neo, api, *libraries], uni_classes, uni_sources)
# A 1x1 PNG, so Mod Menu has an icon to draw for the identity Fabric keeps for this mod.
icon = bytes.fromhex('89504e470d0a1a0a0000000d4948445200000001000000010806000000'
                     '1f15c4890000000d49444154789c63f8cfc0f01f0005000201ad3a4a3a0000000049454e44ae426082')
built['forbricmmuni'] = jar('forbricmmuni', [(root / 'uni/fabric.mod.json', 'fabric.mod.json'),
                                             (root / 'uni/META-INF/neoforge.mods.toml', 'META-INF/neoforge.mods.toml')],
                            uni_classes, extra=[('assets/forbricmmuni/icon.png', icon)])
record = {}
for name, staged in built.items():
    target = output / (name + '.jar')
    os.replace(staged, target)
    record[name] = {'path': str(target), 'sha256': hashlib.sha256(target.read_bytes()).hexdigest()}
record['vanilla'] = {'path': str(vanilla), 'sha256': hashlib.sha256(vanilla.read_bytes()).hexdigest()}
record['neoforge'] = {'path': str(neo), 'sha256': hashlib.sha256(neo.read_bytes()).hexdigest()}
(output / 'm56-build-inputs.json').write_text(json.dumps(record, indent=2) + '\n')
print(f'[M56ConfigScreens] built {len(built)} canaries into {output}')
PY
