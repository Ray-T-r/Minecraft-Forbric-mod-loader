#!/usr/bin/env python3
"""Portable source-development entry point; Python standard library only."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
from functools import lru_cache
import json
import os
from pathlib import Path
import platform
import re
import shutil
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
KERNEL = ROOT / 'forbric-kernel'
STATE = KERNEL / '.dev'
MC_VERSION = '26.2'
API_PINS = (
    ('fabric-api-0.155.2+26.2.jar',
     'https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.155.2+26.2/fabric-api-0.155.2+26.2.jar',
     'd6518c770024cbe8a556248f16fcdbb91c6a62f50227a6c3bae8190511e2c1b8'),
    ('energy-5.0.0.jar', 'https://maven.modmuss50.me/teamreborn/energy/5.0.0/energy-5.0.0.jar',
     '889afc438d3e4add5cfdac76517da7987a2c495e4731690a56f2c5dee775db59'),
)
# The staged files a launch reads, by role. Named rather than picked out of STAGED_FILES by position: dropping the
# MinecraftForge entries from that tuple would otherwise have moved the NeoForge carrier into the Forge one's slot.
STAGED_BASE = 'merged-base/patched-mc-merged-26.2.jar'
STAGED_FORGE_CARRIER = 'forge-runtime/forge-runtime.jar'
STAGED_FORGE_INTEROP = 'merged-base/forge-runtime-interop.jar'
STAGED_NEO_CARRIER = 'neoforge-runtime/neoforge-runtime.jar'
# Launched (--nativeReference): how vanilla compiled each class, measured against the base.
VANILLA_REFERENCE = 'vanilla-reference/vanilla-reference-26.2.jar'
STAGED_FILES = (STAGED_BASE, STAGED_FORGE_CARRIER, STAGED_FORGE_INTEROP, STAGED_NEO_CARRIER,
                # Not launched, but the bytecode tests compare the merge against both patched sides.
                'forge-patched/patched-mc-forge-26.2.jar', 'neoforge-patched/patched-mc-neoforge-26.2.jar',
                # Also only read by tests: the merge's own report, the pins that tie the Forge side to this merge,
                # and the two canary mods (see CANARIES).
                'merged-base/merge-conflicts.txt', 'merged-base/patched-mc-merged-26.2.jar.pins',
                'forge-patched/patched-mc-forge-26.2.jar.pins',
                'forge-runtime/forbriclive.jar', 'neoforge-runtime/forbricneolive.jar',
                'neoforge-runtime/neoforge-runtime.jar.pins', VANILLA_REFERENCE)
# The build stamps beside staged artifacts. Each begins with the installer's Pins.stamp(); an artifact whose stamp
# names other pins was built from another NeoForge (or Forge, or NFRT) and must be rebuilt, not tested.
STAMPED_FILES = ('merged-base/patched-mc-merged-26.2.jar.pins', 'forge-patched/patched-mc-forge-26.2.jar.pins',
                 'neoforge-runtime/neoforge-runtime.jar.pins')
PINS_JAVA = ROOT / 'forbric-kernel-installer/src/main/java/net/forbric/installer/kernel/Pins.java'
# forbric-loader/run/build-testmods.sh's two canary mods, built the same way into the stage: (sources, jar, javac
# --release, the staged game jars they compile against). Their sources go beside them, as in forbric-loader/run/,
# because the tests check the packaged data against the source it was built from.
CANARY_SOURCES = ROOT / 'forbric-loader' / 'run'
CANARIES = (
    # The NeoForge carrier last: the MinecraftForge canary asks NeoForge's loader whether a foreign mod is there, as
    # a multi-platform mod does. build-testmods.sh found that loader among a launcher's libraries.
    ('livemod-src', 'forge-runtime/forbriclive.jar', '17',
     ('forge-runtime/forge-runtime.jar', 'forge-patched/patched-mc-forge-26.2.jar', 'neoforge-runtime/neoforge-runtime.jar')),
    ('livemod-src-neoforge', 'neoforge-runtime/forbricneolive.jar', '21',
     ('neoforge-runtime/neoforge-runtime.jar', 'neoforge-patched/patched-mc-neoforge-26.2.jar')),
)
# What build-testmods.sh packages besides classes. The mixin config goes at the jar root: Forge drops a declared
# config whose entry is missing with only a warning, so a misplaced one makes the canary silently do nothing.
CANARY_RESOURCES = ('META-INF', 'forbriclive.mixins.json', 'data', 'assets')
CONSOLE_PINS = (
    ('jline-reader', '26333a275de502adf1dd9e6ea50aa0b4021412c71490df9ed5e88a648886ee89'),
    ('jline-terminal', 'c0f5d70901255da66a94e59778b265d19f9308342578e34c88fc92d1b0c65fef'),
    ('jline-terminal-jna', '58ca9d719c373206af15775ee3cd5f268136ea0d0c4e009c3e96a6d4612d5c66'),
)


@lru_cache(maxsize=1)
def download_context():
    context = ssl.create_default_context()
    # python.org macOS installs can lack the optional certificate bundle; use the OS PEM trust store.
    if not ssl.get_default_verify_paths().cafile and Path('/etc/ssl/cert.pem').is_file():
        context.load_verify_locations('/etc/ssl/cert.pem')
    return context


def system_name():
    return {'Darwin': 'osx', 'Windows': 'windows', 'Linux': 'linux'}[platform.system()]


def default_minecraft_dir(system=None, env=None, home=None):
    system, env, home = system or system_name(), os.environ if env is None else env, home or Path.home()
    if system == 'windows':
        return Path(env.get('APPDATA', str(home / 'AppData' / 'Roaming'))) / '.minecraft'
    return home / ('Library/Application Support/minecraft' if system == 'osx' else '.minecraft')


def applies(rules, system=None, arch=None):
    """Mojang rules: a nonempty list starts denied, then the last matching rule wins."""
    allowed = not rules
    system, arch = system or system_name(), arch or platform.machine()
    for rule in rules:
        match = rule.get('os', {})
        if match.get('name', system) != system:
            continue
        if 'arch' in match and not re.fullmatch(match['arch'], arch):
            continue
        if 'version' in match and not re.search(match['version'], platform.version()):
            continue
        if any(value for value in rule.get('features', {}).values()):
            continue  # dev launches have no demo/quick-play/custom-resolution features enabled
        allowed = rule['action'] == 'allow'
    return allowed


def digest(path, algorithm='sha256'):
    h = hashlib.new(algorithm)
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def fetch(url, target, expected=None, algorithm='sha1', size=None, cache=None):
    """Verify cached and downloaded bytes; replace atomically only after validation."""
    def valid(path):
        return path.is_file() and (size is None or path.stat().st_size == size) and (
            digest(path, algorithm) == expected if expected else path.stat().st_size > 0)
    if valid(target):
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    if cache and expected and valid(cache):
        with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as stream:
            temporary = Path(stream.name)
            with cache.open('rb') as source:
                shutil.copyfileobj(source, stream)
        if not valid(temporary):
            temporary.unlink()
            raise RuntimeError(f'local cache changed while copying: {cache}')
        temporary.replace(target)
        return
    request = urllib.request.Request(url, headers={'User-Agent': 'Forbric-dev/1.0'})
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=target.parent, delete=False) as stream:
            temporary = Path(stream.name)
            for attempt in range(3):
                try:
                    with urllib.request.urlopen(request, timeout=30, context=download_context()) as response:
                        stream.seek(0)
                        stream.truncate()
                        shutil.copyfileobj(response, stream)
                    break
                except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
                    if isinstance(error, urllib.error.HTTPError) and error.code not in (429, 500, 502, 503, 504):
                        raise
                    if attempt == 2:
                        raise
                    time.sleep(attempt + 1)
        if not valid(temporary):
            raise RuntimeError(f'download digest/size mismatch: {url}')
        temporary.replace(target)
    finally:
        if temporary and temporary.exists():
            temporary.unlink()


def confined(root, relative):
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()) or path == root.resolve():
        raise RuntimeError(f'unsafe artifact path: {relative}')
    return path


def libraries(metadata, system=None, arch=None):
    system, arch = system or system_name(), arch or platform.machine()
    arm = arch.lower() in ('arm64', 'aarch64')
    x86 = arch.lower() in ('x86', 'i386', 'i686')
    for lib in metadata.get('libraries', []):
        if not applies(lib.get('rules', []), system, arch):
            continue
        classifier_name = lib.get('name', '').split(':')[-1]
        if classifier_name.startswith('natives-'):
            if classifier_name.endswith('-arm64') != arm:
                continue
            if classifier_name.endswith('-x86') != x86:
                continue
        downloads = lib.get('downloads', {})
        if downloads.get('artifact'):
            yield downloads['artifact']
        classifier = lib.get('natives', {}).get(system)
        if classifier:
            classifier = classifier.replace('${arch}', '64' if sys.maxsize > 2**32 else '32')
            native = downloads.get('classifiers', {}).get(classifier)
            if native:
                yield native


def merge_libraries(metadata, root, system=None, arch=None):
    """The base version's libraries as forbric-loader/run/build-merged-base.sh hands them to the merge tools.

    Each jar libraries() picks for this platform -- the rule prepare() fetched by, so a directory prepared here, by
    the installer or by a launcher has every one -- at the path the version JSON gives it under root, in the JSON's
    order, and checked against the JSON's SHA-1. Exactly those: root also holds every other version any launcher
    ever fetched there, and when the tools were given the folder, the first jar in sorted path order answered for a
    class (guava-25.1-jre before 26.2's guava-33.6.0-jre). Nothing is downloaded.

    Returns (jars, problems); each problem names a jar that is not in place as the JSON describes it.
    """
    root = Path(os.path.normpath(root))
    jars, problems = [], []
    for entry in libraries(metadata, system, arch):
        relative = entry.get('path')
        if not isinstance(relative, str) or not relative.endswith('.jar'):
            continue
        jar = Path(os.path.normpath(root / relative))
        if jar == root or os.path.commonpath([root, jar]) != str(root):
            problems.append('leaves the libraries directory: ' + relative)
        elif not jar.is_file():
            problems.append('missing: ' + str(jar))
        elif isinstance(entry.get('sha1'), str) and digest(jar, 'sha1') != entry['sha1'].lower():
            problems.append('SHA-1 differs from the version JSON: ' + str(jar))
        elif not isinstance(entry.get('sha1'), str) and jar.stat().st_size == 0:
            problems.append('empty: ' + str(jar))
        else:
            jars.append(jar)
    return jars, problems


NO_ASSETS = '.no-assets'


def assets_skipped(mc):
    """True when this Minecraft directory was prepared with --no-assets (enough for tests and dedicated servers)."""
    return (mc / NO_ASSETS).is_file()


def stage_minecraft(mc, native_dir, arch=None, assets=True):
    metadata = json.loads((mc / f'versions/{MC_VERSION}/{MC_VERSION}.json').read_text())
    native_dir.mkdir(parents=True, exist_ok=True)
    entries = list(libraries(metadata, arch=arch))
    print(f'[dev] Resolving {len(entries)} platform libraries' + (' and Minecraft assets' if assets else ''), flush=True)
    for entry in entries:
        jar = confined(mc / 'libraries', entry['path'])
        fetch(entry['url'], jar, entry['sha1'], size=entry.get('size'),
              cache=confined(default_minecraft_dir() / 'libraries', entry['path']))
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.endswith(('.dll', '.so', '.dylib', '.jnilib')):
                    confined(native_dir, name)  # validate the archive path before flattening native filenames
                    destination = native_dir / Path(name).name
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(archive.read(name))
    if assets:
        stage_assets(mc, metadata)
        (mc / NO_ASSETS).unlink(missing_ok=True)
    else:
        # Unit tests and dedicated-server gates never read assets; only the client does. Leave a marker so a later
        # client launch knows to fetch them instead of reporting a broken install.
        (mc / NO_ASSETS).write_text('prepared with --no-assets\n')
        print('[dev] Assets skipped (--no-assets): enough for tests and dedicated servers, not for the client', flush=True)
    # Forge-family runtimes omit JLine as a game-provided library, but vanilla metadata does not list it.
    for name, sha in CONSOLE_PINS:
        relative = f'org/jline/{name}/3.25.1/{name}-3.25.1.jar'
        fetch('https://repo.maven.apache.org/maven2/' + relative, mc / 'libraries' / relative,
              sha, 'sha256', cache=default_minecraft_dir() / 'libraries' / relative)


def stage_assets(mc, metadata):
    index = metadata['assetIndex']
    path = mc / 'assets/indexes' / (index['id'] + '.json')
    fetch(index['url'], path, index['sha1'], size=index.get('size'),
          cache=default_minecraft_dir() / 'assets/indexes' / (index['id'] + '.json'))
    objects = json.loads(path.read_text())['objects']
    def asset(entry):
        sha1 = entry['hash']
        if not re.fullmatch(r'[0-9a-f]{40}', sha1):
            raise RuntimeError('invalid asset hash')
        relative = sha1[:2] + '/' + sha1
        fetch('https://resources.download.minecraft.net/' + relative, mc / 'assets/objects' / relative,
              sha1, size=entry.get('size'), cache=default_minecraft_dir() / 'assets/objects' / relative)
    with ThreadPoolExecutor(max_workers=8) as pool:
        for count, _ in enumerate(pool.map(asset, objects.values()), 1):
            if count % 1000 == 0:
                print(f'[dev] Assets: {count}/{len(objects)}', flush=True)
    print(f'[dev] {len(objects)} assets ready', flush=True)


def java_bin(requested=None):
    requested = requested or os.environ.get('FORBRIC_JAVA')
    if requested:
        path = Path(requested).expanduser()
        if path.is_dir():
            path /= 'bin/java.exe' if os.name == 'nt' else 'bin/java'
        return str(path.resolve()) if path.exists() else (shutil.which(requested) or requested)
    if os.environ.get('JAVA_HOME'):
        return str(Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java'))
    return shutil.which('java') or 'java'


def java_environment(java, minimum=25):
    result = subprocess.run([java, '-XshowSettings:properties', '-version'], capture_output=True, text=True, check=True)
    values = dict(re.findall(r'^\s*(java\.specification\.version|java\.home|os\.arch)\s*=\s*(.*?)\s*$',
                             result.stderr + result.stdout, re.M))
    feature = int(values['java.specification.version'])
    if feature < minimum:
        raise RuntimeError(f'JDK {minimum}+ required for this command; selected Java {feature}')
    home = Path(values['java.home'])
    if not (home / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')).is_file():
        raise RuntimeError(f'a full JDK is required, not a JRE: {home}')
    return dict(os.environ, JAVA_HOME=str(home), FORBRIC_DEV_ARCH=values['os.arch'])


def gradle(module, arguments, env, capture=False):
    wrapper = ROOT / module / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    command = [str(wrapper), '-p', str(wrapper.parent)] + list(arguments)
    # Explicit cmd invocation for .bat wrappers; subprocess still quotes paths with spaces.
    if os.name == 'nt':
        command = ['cmd', '/d', '/c'] + command
    result = subprocess.run(command, cwd=ROOT, env=env, check=True, stdout=subprocess.PIPE if capture else None, text=capture)
    return result.stdout if capture else None


def kernel_libraries(env, mc, stage):
    """sponge-mixin and ASM as the kernel resolves them, which a vanilla Minecraft tree does not have."""
    # The kernel's prepareDev task hands its classpath over rather than have this start a second build of itself.
    classpath = env.get('FORBRIC_KERNEL_CLASSPATH') or (gradle(
        'forbric-kernel', ['-q', 'printBootClasspath'] + build_properties(mc, stage), env, capture=True).strip().splitlines() or [''])[-1]
    jars = [Path(p) for p in classpath.split(os.pathsep) if p.endswith('.jar')]
    chosen = [p for p in jars if p.name.startswith(('sponge-mixin-', 'asm-'))]
    for prefix in ('sponge-mixin-', 'asm-tree-'):
        if not any(p.name.startswith(prefix) and p.is_file() for p in chosen):
            raise RuntimeError(f'the kernel classpath has no {prefix}*.jar for the canary mods')
    return chosen


def stage_canaries(stage, libraries, java_home, sources=CANARY_SOURCES):
    """Build CANARIES into the stage as build-testmods.sh does, replacing what an earlier prepare left."""
    suffix = '.exe' if os.name == 'nt' else ''
    javac, jar = (str(Path(java_home) / 'bin' / (tool + suffix)) for tool in ('javac', 'jar'))
    for source, output, release, game in CANARIES:
        tree = stage / source
        if tree.exists():
            shutil.rmtree(tree)
        shutil.copytree(sources / source, tree)
        target = stage / output
        target.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='forbric-canary-') as scratch:
            classes = Path(scratch) / 'classes'
            classpath = os.pathsep.join(str(p) for p in [stage / jar_path for jar_path in game] + list(libraries))
            subprocess.run([javac, '--release', release, '-proc:none', '-cp', classpath, '-d', str(classes)]
                           + sorted(str(p) for p in tree.rglob('*.java')), check=True)
            for name in CANARY_RESOURCES:
                resource = tree / name
                if resource.is_dir():
                    shutil.copytree(resource, classes / name, dirs_exist_ok=True)
                elif resource.is_file():
                    shutil.copy2(resource, classes / name)
            partial = target.with_name(target.name + '.part')
            subprocess.run([jar, '--create', '--file', str(partial), '-C', str(classes), '.'], check=True)
            partial.replace(target)
        print(f'[dev] Built {target.name} from {source}', flush=True)


def options(args, arch=None):
    mc = Path(args.mc_dir or os.environ.get('MC_DIR') or STATE / 'minecraft').expanduser().resolve()
    old = Path(args.staged or os.environ.get('FORBRIC_OLD') or STATE / 'staged').expanduser().resolve()
    instance = Path(args.instance or os.environ.get('RUNDIR') or STATE / ('client' if args.command == 'client' else 'server')).expanduser().resolve()
    arch = arch or platform.machine()
    arch = {'aarch64': 'arm64', 'amd64': 'x86_64'}.get(arch.lower(), arch.lower())
    natives = Path(args.natives or os.environ.get('NATIVES_DIR') or STATE / 'natives' /
                   (system_name() + '-' + arch)).expanduser().resolve()
    return mc, old / 'run', instance, natives


def build_properties(mc, stage):
    return [f'-Pforbric.stagedRoot={stage}', f'-Pforbric.mcLibraries={mc / "libraries"}',
            f'-Pforbric.fabricApi={STATE / "api" / API_PINS[0][0]}',
            f'-Pforbric.rebornEnergy={STATE / "api" / API_PINS[1][0]}']


def prepare(args, java, env):
    mc, stage, _, natives = options(args, env['FORBRIC_DEV_ARCH'])
    gradle('forbric-kernel-installer', ['devToolsJar'], env)
    tools = ROOT / 'forbric-kernel-installer/build/libs/forbric-dev-tools.jar'
    subprocess.run([java, '-jar', str(tools), str(mc), str(stage), java], cwd=ROOT, env=env, check=True)
    stage_minecraft(mc, natives, env['FORBRIC_DEV_ARCH'], assets=not getattr(args, 'no_assets', False))
    for name, url, sha in API_PINS:
        fetch(url, STATE / 'api' / name, sha, 'sha256')
    metadata = json.loads((mc / f'versions/{MC_VERSION}/{MC_VERSION}.json').read_text())
    game_libraries = [confined(mc / 'libraries', entry['path'])
                      for entry in libraries(metadata, arch=env['FORBRIC_DEV_ARCH']) if entry['path'].endswith('.jar')]
    stage_canaries(stage, game_libraries + kernel_libraries(env, mc, stage), env['JAVA_HOME'])
    print('[dev] Prepared. Launch with python3 tools/dev.py client (Windows: py tools/dev.py client).', flush=True)


def pins_stamp(pins_java=PINS_JAVA):
    """The installer's Pins.stamp(), read from Pins.java: 'mc=… forge=… neoforge=… nfrt=… result=…'."""
    text = pins_java.read_text(encoding='utf-8')
    value = {name: re.search(r'static final String ' + name + r' = "([^"]+)"', text).group(1)
             for name in ('MINECRAFT', 'FORGE', 'NEOFORGE', 'NFRT', 'NFRT_RESULT')}
    return (f"mc={value['MINECRAFT']} forge={value['FORGE']} neoforge={value['NEOFORGE']}"
            f" nfrt={value['NFRT']} result={value['NFRT_RESULT']}")


def stale_stamps(stage, stamp=None):
    """Staged build stamps that do not begin with the current pins (a missing stamp counts as stale)."""
    stamp = stamp or pins_stamp()
    stale = []
    for name in STAMPED_FILES:
        path = stage / name
        if not path.is_file() or not path.read_text(encoding='utf-8').strip().startswith(stamp):
            stale.append(name)
    return stale


def ready(mc, stage, natives, arch=None, assets=True):
    """Prepared for this use. assets=False is for tests and dedicated servers, which never read them."""
    if stale_stamps(stage):
        return False
    basic = (all((stage / p).is_file() for p in STAGED_FILES)
            and all((STATE / 'api' / name).is_file() and digest(STATE / 'api' / name) == sha
                    for name, _, sha in API_PINS)
            and (mc / f'versions/{MC_VERSION}/{MC_VERSION}.json').is_file()
            and (not assets or (mc / 'assets/indexes').is_dir()) and natives.is_dir())
    if not basic:
        return False
    metadata = json.loads((mc / f'versions/{MC_VERSION}/{MC_VERSION}.json').read_text())
    if any(not confined(mc / 'libraries', lib['path']).is_file() for lib in libraries(metadata, arch=arch)):
        return False
    if any(not (mc / f'libraries/org/jline/{name}/3.25.1/{name}-3.25.1.jar').is_file() for name, _ in CONSOLE_PINS):
        return False
    if not assets:
        return any(p.is_file() for p in natives.iterdir())
    index = confined(mc / 'assets/indexes', metadata['assetIndex']['id'] + '.json')
    if not index.is_file():
        return False
    for entry in json.loads(index.read_text())['objects'].values():
        sha = entry['hash']
        path = confined(mc / 'assets/objects', sha[:2] + '/' + sha)
        if not path.is_file() or path.stat().st_size != entry['size']:
            return False
    return any(p.is_file() for p in natives.iterdir())


def launch_carriers(stage):
    """The runtime jars a launch owns: the merge's MinecraftForge carrier while it is staged, then the NeoForge one."""
    return ([STAGED_FORGE_INTEROP] if (stage / STAGED_FORGE_INTEROP).is_file() else []) + [STAGED_NEO_CARRIER]


def launch_arguments(side, info, mc, stage, instance, natives, jvm=(), game=(), system=None, pathsep=None, arch=None):
    system, pathsep = system or system_name(), pathsep or os.pathsep
    metadata = json.loads((mc / f'versions/{MC_VERSION}/{MC_VERSION}.json').read_text())
    owned = [str(confined(mc / 'libraries', lib['path'])) for lib in libraries(metadata, system, arch)]
    missing = [p for p in owned if not Path(p).is_file()]
    if missing:
        raise RuntimeError('missing Minecraft libraries; run prepare: ' + ', '.join(missing[:3]))
    # The dedicated console uses jline, absent from some version metadata.
    owned += [str(p) for p in sorted((mc / 'libraries/org/jline').glob('**/jline-*-3.25.1.jar')) if str(p) not in owned]
    boot = os.environ.get('FORBRIC_BOOT_JAR', info['bootJar'])
    with zipfile.ZipFile(boot) as archive:
        if 'META-INF/jars/forbric-kernel-runtime.jar' not in archive.namelist():
            raise RuntimeError('refusing to launch a boot-only kernel jar; run prepare and rebuild')
    merged = stage / STAGED_BASE
    metadata_jar = merged.parent / 'forbric-game-metadata.jar'
    with zipfile.ZipFile(merged) as archive:
        version = archive.read('version.json')
    # System-classloader consumers need metadata, but must never see game bytecode on the parent classpath.
    with zipfile.ZipFile(metadata_jar, 'w') as archive:
        archive.writestr('version.json', version)
    cp = pathsep.join([boot] + info['dependencies'] + owned + [str(metadata_jar)])
    args = ([] if side == 'server' or system != 'osx' else ['-XstartOnFirstThread'])
    args += [f'-Djava.library.path={natives}',
             '-Djava.awt.headless=true'] if side == 'server' else [f'-Djava.library.path={natives}']
    args += [f'-Dforbric.compatibilityPolicy={os.environ.get("FORBRIC_COMPAT_POLICY", "strict")}',
             f'-Dforbric.dependencyDialog={os.environ.get("FORBRIC_DEP_DIALOG", "off")}']
    args += list(jvm) + ['-cp', cp, 'net.forbric.kernel.boot.Kernel' + side.title() + 'Launch',
                        '--gameJar', str(stage / STAGED_BASE),
                        '--runtimeJar', pathsep.join(str(stage / name) for name in launch_carriers(stage)),
                        '--libraryPath', pathsep.join(owned),
                        # Its own flag, never a second --gameJar: a launcher keeping the last value of a repeated flag
                        # would hand the kernel this resources-only jar as the game.
                        '--nativeReference', str(stage / VANILLA_REFERENCE),
                        '--', '--gameDir', str(instance)]
    if side == 'client':
        args += ['--version', '26.2-forbric-dev', '--assetsDir', str(mc / 'assets'),
                 '--assetIndex', metadata['assetIndex']['id'], '--accessToken', '0',
                 '--username', 'ForbricDev', '--uuid', '00000000000000000000000000000000',
                 '--userType', 'legacy', '--versionType', 'release']
    else:
        args += ['--nogui']
    return args + list(game)


def write_argument_file(path, arguments):
    # Java @argfiles avoid cmd.exe's 8191-character limit; quote every path/argument independently.
    path.write_text('\n'.join('"' + a.replace('\\', '\\\\').replace('"', '\\"')
                             .replace('\n', '\\n').replace('\r', '\\r') + '"' for a in arguments) + '\n', encoding='utf-8')



def java_command(java, argument_file, arguments, windows=None, launcher=None):
    windows = os.name == 'nt' if windows is None else windows
    if not windows or all(value.isascii() for value in [str(argument_file)] + list(arguments)):
        return [java, '@' + str(argument_file)]
    # Both argv and @files pass through Windows Java's native codepage. Keep its command ASCII:
    # URL-encoded manifest classpaths load the real jars; UTF-8 JSON carries application arguments.
    launcher = Path(launcher or ROOT / 'forbric-kernel-installer/build/libs/forbric-dev-tools.jar')
    if not launcher.exists():
        raise RuntimeError('Unicode Windows launch needs the development tools jar; run prepare first')
    cp_index = arguments.index('-cp')
    vm_flags, properties = [], {}
    for value in arguments[:cp_index]:
        if value.isascii():
            vm_flags.append(value)
        elif value.startswith('-Djava.library.path='):
            source = Path(value.split('=', 1)[1])
            destination = argument_file.parent / '.forbric-natives'
            shutil.copytree(source, destination, dirs_exist_ok=True)
            vm_flags.append('-Djava.library.path=.forbric-natives')
        elif value.startswith('-D') and '=' in value:
            key, setting = value[2:].split('=', 1)
            properties[key] = setting
        else:
            raise RuntimeError('Windows JVM options must be ASCII; use an ASCII output path for: ' + value)
    entries = [Path(value) for value in arguments[cp_index + 1].split(';')] + [launcher]
    urls = [entry.resolve().as_uri() + ('/' if entry.is_dir() else '') for entry in entries]
    attribute = 'Class-Path: ' + ' '.join(urls)
    lines = [attribute[:72]]
    attribute = attribute[72:]
    while attribute:
        lines.append(' ' + attribute[:71])
        attribute = attribute[71:]
    manifest = 'Manifest-Version: 1.0\r\n' + '\r\n'.join(lines) + '\r\n\r\n'
    classpath_jar = argument_file.parent / '.forbric-classpath.jar'
    with zipfile.ZipFile(classpath_jar, 'w') as archive:
        archive.writestr('META-INF/MANIFEST.MF', manifest)
    configuration = argument_file.parent / '.forbric-launch.json'
    configuration.write_text(json.dumps(dict(mainClass=arguments[cp_index + 2],
                                             arguments=list(arguments[cp_index + 3:]),
                                             properties=properties)), encoding='utf-8')
    return [java] + vm_flags + ['-cp', classpath_jar.name,
                              'net.forbric.installer.kernel.DevLaunch', configuration.name]


def launch(args, java, env):
    mc, stage, instance, natives = options(args, env['FORBRIC_DEV_ARCH'])
    if args.command == 'client' and args.no_assets:
        raise RuntimeError('the client needs Minecraft assets; drop --no-assets')
    need_assets = args.command == 'client' or not (args.no_assets or assets_skipped(mc))
    if not ready(mc, stage, natives, env['FORBRIC_DEV_ARCH'], assets=need_assets):
        if args.no_build:
            raise RuntimeError('development inputs missing; run prepareDev first')
        prepare(args, java, env)
    info_path = STATE / 'launch-build.json'
    if not args.no_build:
        gradle('forbric-kernel', ['jar', 'writeDevClasspath'] + build_properties(mc, stage), env)
    info = json.loads(info_path.read_text())
    instance.mkdir(parents=True, exist_ok=True)
    (instance / 'mods').mkdir(exist_ok=True)
    for name, _, _ in API_PINS:
        target = instance / 'mods' / name
        if not target.exists():
            shutil.copy2(STATE / 'api' / name, target)
    if args.command == 'server':
        properties = instance / 'server.properties'
        if not properties.exists():
            properties.write_text('server-ip=127.0.0.1\nonline-mode=false\nenforce-secure-profile=false\n')
        eula = instance / 'eula.txt'
        if not args.dry_run and (not eula.is_file() or not re.search(r'^\s*eula\s*=\s*true\s*$', eula.read_text(), re.M)):
            if not args.accept_eula:
                raise RuntimeError('server requires Minecraft EULA acceptance: use --accept-eula or set eula=true in ' + str(eula))
            eula.write_text('eula=true\n')
    argument_file = instance / '.forbric-java.args'
    command = launch_arguments(args.command, info, mc, stage, instance, natives, args.jvm, args.game,
                               arch=env['FORBRIC_DEV_ARCH'])
    write_argument_file(argument_file, command)
    if args.dry_run:
        print(json.dumps([java] + command, indent=2))
        return
    print(f'[dev] {args.command}: {instance}', flush=True)
    if os.name == 'nt' and any(not value.isascii() for value in [str(argument_file)] + command):
        gradle('forbric-kernel-installer', ['devToolsJar'], env)
    subprocess.run(java_command(java, argument_file, command), cwd=instance, env=env, check=True)


def doctor(args):
    mc, stage, instance, natives = options(args)
    failures, arch = [], None
    try:
        java = java_bin(args.java)
        arch = java_environment(java).get('FORBRIC_DEV_ARCH')
        mc, stage, instance, natives = options(args, arch)
        print('[dev] JDK: ' + java)
    except (OSError, subprocess.SubprocessError, RuntimeError, KeyError) as error:
        failures.append(str(error))
    for relative in STAGED_FILES:
        path = stage / relative
        if not path.is_file():
            failures.append('missing ' + str(path))
    for name, _, sha in API_PINS:
        path = STATE / 'api' / name
        if not path.is_file() or digest(path) != sha:
            failures.append('missing or incorrect API: ' + str(path))
    skipped = assets_skipped(mc)
    if not ready(mc, stage, natives, arch, assets=not skipped):
        failures.append('Minecraft libraries/assets/natives or staged APIs need preparation')
    print(f'[dev] Minecraft: {mc}\n[dev] Stage: {stage}\n[dev] Instance: {instance}')
    if skipped:
        print('[dev] Assets were deliberately not prepared (--no-assets): tests and servers only; '
              'run prepare without it before launching the client')
    for message in failures:
        print('[dev] NEEDS PREPARATION: ' + message)
    print('[dev] ' + ('run python3 tools/dev.py prepare' if failures else 'ready'))
    return 2 if failures else 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['doctor', 'prepare', 'client', 'server', 'test', 'integration', 'gate', 'tool-test'])
    parser.add_argument('--mc-dir', help='MC_DIR; default: isolated forbric-kernel/.dev/minecraft')
    parser.add_argument('--staged', help='FORBRIC_OLD; directory containing run/; default: .dev/staged')
    parser.add_argument('--instance', help='RUNDIR; default: .dev/client or .dev/server')
    parser.add_argument('--natives', help='NATIVES_DIR; default: .dev/natives/<platform>')
    parser.add_argument('--java', help='JDK home or java executable; FORBRIC_JAVA, JAVA_HOME, then PATH')
    parser.add_argument('--jvm', action='append', default=[], help='repeat --jvm=-Dkey=value')
    parser.add_argument('--accept-eula', action='store_true', help='accept https://aka.ms/MinecraftEULA for the dev server')
    parser.add_argument('--no-assets', action='store_true',
                        help='prepare: skip the ~1 GB of Minecraft assets; enough for tests and dedicated servers')
    parser.add_argument('--dry-run', action='store_true', help='build and print launch command without starting Minecraft')
    parser.add_argument('--no-build', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--gate', default='m0', help='gate name, e.g. m0 or m33-transfer')
    arguments = list(sys.argv[1:] if argv is None else argv)
    game = []
    if '--' in arguments:
        split = arguments.index('--')
        arguments, game = arguments[:split], arguments[split+1:]
    args = parser.parse_args(arguments)
    args.game = game
    if game and args.command not in ('client', 'server'):
        parser.error('extra game arguments apply only to client/server')
    try:
        if args.command == 'doctor':
            return doctor(args)
        if args.command == 'tool-test':
            subprocess.run([sys.executable, '-m', 'unittest', 'discover', '-s', str(ROOT / 'tools'), '-p', 'test_*.py'], check=True, cwd=ROOT)
            subprocess.run([sys.executable, '-m', 'unittest', 'discover', '-s', str(KERNEL / 'run/compat'), '-p', 'test_*.py'], check=True, cwd=KERNEL)
            # mac/ is not a package, so the call above never descends into it; its tools import each other flat.
            mac = KERNEL / 'run/compat/mac'
            subprocess.run([sys.executable, '-m', 'unittest', 'discover', '-s', str(mac), '-t', str(mac), '-p', 'test_*.py'], check=True, cwd=KERNEL)
            return 0
        java = java_bin(args.java)
        env = java_environment(java, minimum=21 if args.command == 'test' else 25)
        if args.command == 'prepare':
            prepare(args, java, env)
        elif args.command in ('client', 'server'):
            launch(args, java, env)
        elif args.command == 'test':
            gradle('forbric-kernel', ['check'], env)
        elif args.command == 'integration':
            mc, stage, _, natives = options(args, env['FORBRIC_DEV_ARCH'])
            if not ready(mc, stage, natives, env['FORBRIC_DEV_ARCH'], assets=not (args.no_assets or assets_skipped(mc))):
                prepare(args, java, env)
            gradle('forbric-kernel', ['cleanTest', 'cleanTransferTest', 'integrationTest'] + build_properties(mc, stage), env)
        elif args.command == 'gate':
            if not re.fullmatch(r'm[0-9]+[a-z]?(?:-[a-z0-9-]+)?', args.gate):
                raise RuntimeError('invalid gate name')
            gate = KERNEL / 'run' / ('gate-' + args.gate + '.sh')
            if not gate.is_file():
                raise RuntimeError('unknown gate: ' + args.gate)
            if not shutil.which('bash'):
                raise RuntimeError('legacy gates require Bash and their documented local fixtures')
            # Legacy gates keep their own staging defaults; do not silently point them at an empty dev fixture set.
            if args.staged:
                env['FORBRIC_OLD'] = str(Path(args.staged).resolve())
            if args.mc_dir:
                env['MC_DIR'] = str(Path(args.mc_dir).resolve())
            subprocess.run(['bash', str(gate)], cwd=ROOT, env=env, check=True)
        return 0
    except KeyboardInterrupt:
        return 130
    except (OSError, subprocess.SubprocessError, RuntimeError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print('[dev] ERROR: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
