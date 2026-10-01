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
STAGED_FILES = ('merged-base/patched-mc-merged-26.2.jar', 'forge-runtime/forge-runtime.jar',
                'merged-base/forge-runtime-interop.jar', 'neoforge-runtime/neoforge-runtime.jar')
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


def stage_minecraft(mc, native_dir, arch=None):
    metadata = json.loads((mc / f'versions/{MC_VERSION}/{MC_VERSION}.json').read_text())
    native_dir.mkdir(parents=True, exist_ok=True)
    entries = list(libraries(metadata, arch=arch))
    print(f'[dev] Resolving {len(entries)} platform libraries and Minecraft assets', flush=True)
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
    # Forge-family runtimes omit JLine as a game-provided library, but vanilla metadata does not list it.
    for name, sha in CONSOLE_PINS:
        relative = f'org/jline/{name}/3.25.1/{name}-3.25.1.jar'
        fetch('https://repo.maven.apache.org/maven2/' + relative, mc / 'libraries' / relative,
              sha, 'sha256', cache=default_minecraft_dir() / 'libraries' / relative)


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


def gradle(module, arguments, env):
    wrapper = ROOT / module / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    command = [str(wrapper), '-p', str(wrapper.parent)] + list(arguments)
    # Explicit cmd invocation for .bat wrappers; subprocess still quotes paths with spaces.
    if os.name == 'nt':
        command = ['cmd', '/d', '/c'] + command
    subprocess.run(command, cwd=ROOT, env=env, check=True)


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
    stage_minecraft(mc, natives, env['FORBRIC_DEV_ARCH'])
    for name, url, sha in API_PINS:
        fetch(url, STATE / 'api' / name, sha, 'sha256')
    print('[dev] Prepared. Launch with python3 tools/dev.py client (Windows: py tools/dev.py client).', flush=True)


def ready(mc, stage, natives, arch=None):
    basic = (all((stage / p).is_file() for p in STAGED_FILES)
            and all((STATE / 'api' / name).is_file() and digest(STATE / 'api' / name) == sha
                    for name, _, sha in API_PINS)
            and (mc / f'versions/{MC_VERSION}/{MC_VERSION}.json').is_file()
            and (mc / 'assets/indexes').is_dir() and natives.is_dir())
    if not basic:
        return False
    metadata = json.loads((mc / f'versions/{MC_VERSION}/{MC_VERSION}.json').read_text())
    if any(not confined(mc / 'libraries', lib['path']).is_file() for lib in libraries(metadata, arch=arch)):
        return False
    if any(not (mc / f'libraries/org/jline/{name}/3.25.1/{name}-3.25.1.jar').is_file() for name, _ in CONSOLE_PINS):
        return False
    index = confined(mc / 'assets/indexes', metadata['assetIndex']['id'] + '.json')
    if not index.is_file():
        return False
    for entry in json.loads(index.read_text())['objects'].values():
        sha = entry['hash']
        path = confined(mc / 'assets/objects', sha[:2] + '/' + sha)
        if not path.is_file() or path.stat().st_size != entry['size']:
            return False
    return any(p.is_file() for p in natives.iterdir())


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
    merged = stage / STAGED_FILES[0]
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
                        '--gameJar', str(stage / STAGED_FILES[0]),
                        '--runtimeJar', pathsep.join([str(stage / STAGED_FILES[2]), str(stage / STAGED_FILES[3])]),
                        '--libraryPath', pathsep.join(owned), '--', '--gameDir', str(instance)]
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


def launch(args, java, env):
    mc, stage, instance, natives = options(args, env['FORBRIC_DEV_ARCH'])
    if not ready(mc, stage, natives, env['FORBRIC_DEV_ARCH']):
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
    subprocess.run([java, '@' + str(argument_file)], cwd=instance, env=env, check=True)


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
    if not ready(mc, stage, natives, arch):
        failures.append('Minecraft libraries/assets/natives or staged APIs need preparation')
    print(f'[dev] Minecraft: {mc}\n[dev] Stage: {stage}\n[dev] Instance: {instance}')
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
            if not ready(mc, stage, natives, env['FORBRIC_DEV_ARCH']):
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
