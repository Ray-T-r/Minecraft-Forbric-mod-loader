#!/usr/bin/env python3
"""Shared portable configuration and owned-process handling for the Windows drivers."""
import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import time
import zipfile


def parser(description):
    result = argparse.ArgumentParser(description=description)
    result.add_argument('--mc', default=os.environ.get('FORBRIC_MC'), help='Minecraft installation root; FORBRIC_MC')
    result.add_argument('--version', default=os.environ.get('FORBRIC_VERSION'), help='Installed version id; FORBRIC_VERSION')
    result.add_argument('--instance', default=os.environ.get('FORBRIC_INSTANCE'), help='Default: MC/versions/VERSION')
    result.add_argument('--world', default=os.environ.get('FORBRIC_WORLD', 'compat-world'))
    result.add_argument('--java', default=os.environ.get('FORBRIC_JAVA'))
    result.add_argument('--jvm', action='append', default=[], help='Extra JVM argument; use --jvm=-Dkey=value')
    result.add_argument('--print-config', action='store_true', help='Print configuration without files, processes or Windows APIs')
    return result


def config(args, argument_parser):
    if not args.mc or not args.version:
        argument_parser.error('set FORBRIC_MC and FORBRIC_VERSION, or --mc and --version')
    if Path(args.version).name != args.version or re.search(r'[<>:"/\\|?*]', args.version):
        argument_parser.error('version must be a single directory name')
    if args.world in ('.', '..') or re.search(r'[<>:"/\\|?*]', args.world):
        argument_parser.error('world must be a single directory name')
    mc = Path(args.mc).absolute()
    instance = Path(args.instance).absolute() if args.instance else mc / 'versions' / args.version
    result = dict(mc=str(mc), version=args.version, instance=str(instance), world=args.world,
                  java=args.java, version_json=str(mc / 'versions' / args.version / (args.version + '.json')),
                  pid_file=str(instance / '.forbric-sweep.pid'), server_dir=str(instance / 'server-gen'),
                  screenshots=str(instance / 'screenshots'), jvm=args.jvm,
                  screenshot_flag='-Dforbric.clientSmokeScreenshots=100')
    if args.print_config:
        print(json.dumps(result, indent=2))
        return None
    if os.name != 'nt':
        argument_parser.error('launching requires Windows; --print-config works on any platform')
    return result


def safe_filename(name):
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', '_', name).rstrip(' .')
    if not name or name in ('.', '..'):
        raise ValueError('empty filename')
    if name.split('.')[0].upper() in {'CON', 'PRN', 'AUX', 'NUL', *(f'COM{i}' for i in range(1, 10)), *(f'LPT{i}' for i in range(1, 10))}:
        name = '_' + name
    return name


@contextmanager
def pid_lock(configuration):
    path = Path(configuration['pid_file'])
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.with_suffix('.pid.lock').open('a+b') as lock:
        if lock.tell() == 0:
            lock.write(b'0')
            lock.flush()
        lock.seek(0)
        if os.name == 'nt':
            import msvcrt
            msvcrt.locking(lock.fileno(), msvcrt.LK_LOCK, 1)
        else:
            import fcntl
            fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            yield path
        finally:
            lock.seek(0)
            if os.name == 'nt':
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(lock, fcntl.LOCK_UN)


def record_pid(configuration, pid, remove=False):
    with pid_lock(configuration) as path:
        values = path.read_text(encoding='ascii').splitlines() if path.exists() else []
        values = [value for value in values if value != str(pid)]
        if not remove:
            values.append(str(pid))
        temporary = path.with_suffix('.pid.tmp')
        temporary.write_text(''.join(value + '\n' for value in values), encoding='ascii')
        temporary.replace(path)


@contextmanager
def own_driver(configuration):
    record_pid(configuration, os.getpid())
    try:
        yield
    finally:
        record_pid(configuration, os.getpid(), remove=True)


def spawn(configuration, command, **kwargs):
    process = subprocess.Popen(command, **kwargs)
    record_pid(configuration, process.pid)
    return process


def await_outcome(*, ready, failed, process, timeout, stall, last_output,
                  now=time.monotonic, sleep=time.sleep, tick=1.0):
    """Wait for a spawned game to announce itself, and say WHY the wait ended.

    The verdict matters as much as the waiting. `timeout` is the ceiling for a process that is still working;
    `stall` is the one for a process that has stopped, measured from the last line it printed, which the caller
    keeps in the one-element list `last_output` from inside its output pump.

    Without the second one there is only the first, and the difference is fifteen minutes. Across the sixteen
    sweeps in build/compat/, every server boot that reached Done did so in 31-38 seconds and never went quiet
    for more than 8; all three that never reached Done fell silent 13-17 seconds in and then sat there, alive
    and mute, until the 900s ceiling expired. Two of those cost 820s and 1615s to report a failure that was
    already decided before the second minute.

    Returns 'failed', 'exited', 'ready', 'stalled' or 'timeout'. The first three are checked in that order
    after the loop, which is the order the drivers' own conditions used to resolve them in: a process that has
    announced failure, or died, has not become ready however many events are set.
    """
    deadline = now() + timeout
    while now() < deadline:
        if failed.is_set() or ready.is_set() or process.poll() is not None:
            break
        if now() - last_output[0] > stall:
            return 'stalled'
        sleep(tick)
    if failed.is_set():
        return 'failed'
    if process.poll() is not None:
        return 'exited'
    if ready.is_set():
        return 'ready'
    return 'timeout'


def finish(configuration, process):
    if process.poll() is None:
        if os.name == 'nt':
            subprocess.run(['taskkill', '/T', '/F', '/PID', str(process.pid)], capture_output=True, timeout=30)
        else:
            process.terminate()
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)
    record_pid(configuration, process.pid, remove=True)


def driver_command(configuration, filename):
    command = [sys.executable, str(Path(__file__).with_name(filename)), '--mc', configuration['mc'],
               '--version', configuration['version'], '--instance', configuration['instance'], '--world', configuration['world']]
    if configuration['java']:
        command += ['--java', configuration['java']]
    command += ['--jvm=' + value for value in configuration['jvm']]
    return command


def rules_allow(entry):
    if not entry.get('rules'):
        return True
    allowed = False
    architecture = 'arm64' if platform.machine().lower() in ('arm64', 'aarch64') else 'x86_64'
    for rule in entry['rules']:
        if any(value for value in rule.get('features', {}).values()):
            continue
        spec = rule.get('os', {})
        if spec.get('name', 'windows') != 'windows':
            continue
        if 'arch' in spec and not re.fullmatch(spec['arch'], architecture):
            continue
        if 'version' in spec and not re.search(spec['version'], platform.version()):
            continue
        allowed = rule['action'] == 'allow'
    return allowed


def profile_chain(configuration):
    profiles, seen = [], set()
    current = configuration['version']
    while current:
        if current in seen:
            raise ValueError('version inheritance cycle: ' + current)
        seen.add(current)
        path = Path(configuration['mc']) / 'versions' / current / (current + '.json')
        profile = json.loads(path.read_text(encoding='utf-8-sig'))
        profiles.insert(0, profile)
        current = profile.get('inheritsFrom')
    return profiles


def launch_command(configuration, server=False):
    mc, instance = Path(configuration['mc']), Path(configuration['instance'])
    profiles = profile_chain(configuration)
    child, vanilla = profiles[-1], profiles[0]
    libraries = {}
    for profile in profiles:
        for library in profile.get('libraries', []):
            if rules_allow(library):
                parts = library['name'].split(':')
                # Child versions supersede inherited versions of the same group/artifact/classifier.
                libraries[(parts[0], parts[1], tuple(parts[3:]))] = library
    classpath = []
    for library in libraries.values():
        path = library.get('downloads', {}).get('artifact', {}).get('path')
        if not path:
            group, artifact, version, *classifier = library['name'].split(':')
            suffix = '-' + classifier[0] if classifier else ''
            path = f'{group.replace(".", "/")}/{artifact}/{version}/{artifact}-{version}{suffix}.jar'
        jar = mc / 'libraries' / Path(path)
        if not jar.is_file():
            raise ValueError('missing installed library: ' + str(jar))
        if str(jar) not in classpath:
            classpath.append(str(jar))
    jline = sorted((mc / 'libraries' / 'org' / 'jline').rglob('*.jar')) if server else []
    classpath.extend(str(jar) for jar in jline if str(jar) not in classpath)
    # Only metadata belongs on the parent classpath: vanilla classes must remain game-loader owned.
    vanilla_jar = mc / 'versions' / vanilla['id'] / (vanilla['id'] + '.jar')
    if vanilla_jar.is_file():
        metadata = instance / 'game-metadata.jar'
        instance.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(vanilla_jar) as source, zipfile.ZipFile(metadata, 'w') as target:
            target.writestr('version.json', source.read('version.json'))
        classpath.append(str(metadata))
    natives = Path(os.environ.get('FORBRIC_NATIVES', str(instance / (configuration['version'] + '-natives'))))
    if not server and not natives.is_dir():
        natives = instance / 'natives'
    if not server and not natives.is_dir():
        natives = mc / 'versions' / vanilla['id'] / (vanilla['id'] + '-natives')
    if not server and not natives.is_dir():
        raise ValueError('missing native libraries; set FORBRIC_NATIVES')
    game_dir = Path(configuration['server_dir']) if server else instance
    replacements = dict(library_directory=str(mc / 'libraries'), classpath_separator=os.pathsep,
                        classpath=os.pathsep.join(classpath), natives_directory=str(natives),
                        launcher_name='Forbric-compat', launcher_version='1', version_name=configuration['version'],
                        game_directory=str(game_dir), assets_root=str(mc / 'assets'),
                        assets_index_name=vanilla.get('assetIndex', {}).get('id', vanilla['id']),
                        auth_player_name=os.environ.get('FORBRIC_PLAYER', 'CompatPlayer'),
                        auth_uuid='00000000000000000000000000000000', auth_access_token='0',
                        clientid='0', auth_xuid='0', user_type='legacy', version_type='release', user_properties='{}')

    def expand(value):
        for key, replacement in replacements.items():
            value = value.replace('${' + key + '}', replacement)
        if '${' in value:
            raise ValueError('unresolved launch placeholder: ' + value)
        return value

    def arguments(profile, kind):
        result = []
        for value in profile.get('arguments', {}).get(kind, []):
            if isinstance(value, str):
                result.append(expand(value))
            elif rules_allow(value):
                values = value['value']
                result.extend(expand(item) for item in ([values] if isinstance(values, str) else values))
        return result

    java = configuration['java']
    if not java:
        component = vanilla.get('javaVersion', {}).get('component')
        runtime = mc / 'runtime'
        preferred = sorted((runtime / component).rglob('java.exe')) if component else []
        candidates = preferred or sorted(runtime.rglob('bin/java.exe'))
        java = str(candidates[-1]) if candidates else shutil.which('java')
    if not java:
        raise ValueError('no Java found; set FORBRIC_JAVA or --java')
    kernel_args = arguments(child, 'game')
    if server and jline:
        index = kernel_args.index('--libraryPath') + 1
        kernel_args[index] += ''.join(os.pathsep + str(jar) for jar in jline)
    jvm = ['-Xmx4G', '-Dfile.encoding=UTF-8', '-Dforbric.dependencyDialog=off']
    if server:
        jvm += ['-Djava.awt.headless=true', '-cp', os.pathsep.join(classpath)]
        game_args = ['--gameDir', str(game_dir), '--nogui']
        main = 'net.forbric.kernel.boot.KernelServerLaunch'
    else:
        for profile in profiles:
            jvm += arguments(profile, 'jvm')
        if '-cp' not in jvm and '-classpath' not in jvm:
            jvm += ['-cp', os.pathsep.join(classpath)]
        jvm += ['-Djava.library.path=' + str(natives)]
        game_args = []
        for profile in profiles[:-1]:
            game_args += arguments(profile, 'game')
        game_args += ['--quickPlayPath', str(instance / 'quickPlay' / 'log.json'),
                      '--quickPlaySingleplayer', configuration['world']]
        (instance / 'quickPlay').mkdir(parents=True, exist_ok=True)
        main = child['mainClass']
    jvm += configuration['jvm']
    return [java] + jvm + [main] + kernel_args + ['--'] + game_args


def run_java(configuration, server=False):
    game_dir = Path(configuration['server_dir'] if server else configuration['instance'])
    game_dir.mkdir(parents=True, exist_ok=True)
    command = launch_command(configuration, server)
    argfile = game_dir / ('server.args' if server else 'client.args')
    argfile.write_text(''.join('"' + argument.replace('\\', '\\\\').replace('"', '\\"') + '"\n'
                               for argument in command[1:]), encoding='utf-8')
    with own_driver(configuration):
        process = spawn(configuration, [command[0], '@' + str(argfile)], cwd=game_dir)
        print(f'java pid={process.pid} args={argfile}', flush=True)
        try:
            return process.wait()
        finally:
            finish(configuration, process)


def prepare_world(configuration, world):
    """Acknowledge only the copied test save, after the dedicated server has exited."""
    command = [sys.executable, str(Path(__file__).with_name('prepare-world.py')), str(Path(world) / 'level.dat')]
    process = spawn(configuration, command)
    try:
        code = process.wait(timeout=30)
        if code:
            raise RuntimeError('test world acknowledgement failed: exit ' + str(code))
    finally:
        finish(configuration, process)


def fresh_shots(configuration, started):
    # Minecraft writes screenshots asynchronously. Wait for IEND before a directory entry can end a bisect.
    complete = []
    for path in Path(configuration['screenshots']).glob('*.png'):
        try:
            metadata = path.stat()
            if metadata.st_mtime <= started or metadata.st_size < 20:
                continue
            with path.open('rb') as image:
                if image.read(8) != b'\x89PNG\r\n\x1a\n':
                    continue
                image.seek(-12, 2)
                if image.read() != b'\x00\x00\x00\x00IEND\xaeB`\x82':
                    continue
            complete.append((metadata.st_mtime_ns, path.name, path))
        except OSError:
            # A writer, cleanup, or rename can change a screenshot between stat and read.
            continue
    return [path for _, _, path in sorted(complete)]


def screenshot_fallback(configuration):
    if os.name != 'nt':
        return False
    import ctypes
    from ctypes import wintypes
    with pid_lock(configuration) as path:
        owned = {int(value) for value in path.read_text(encoding='ascii').splitlines()}
    user32 = ctypes.windll.user32
    callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    user32.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
    user32.GetWindowThreadProcessId.restype = wintypes.DWORD
    user32.IsWindowVisible.argtypes = [wintypes.HWND]
    user32.IsWindowVisible.restype = wintypes.BOOL
    user32.ShowWindow.argtypes = [wintypes.HWND, ctypes.c_int]
    user32.SetForegroundWindow.argtypes = [wintypes.HWND]
    user32.SetForegroundWindow.restype = wintypes.BOOL
    user32.GetForegroundWindow.restype = wintypes.HWND
    user32.EnumWindows.argtypes = [callback_type, wintypes.LPARAM]
    handles = []
    @callback_type
    def collect(handle, _):
        pid = wintypes.DWORD()
        user32.GetWindowThreadProcessId(handle, ctypes.byref(pid))
        if pid.value in owned and user32.IsWindowVisible(handle):
            handles.append(handle)
        return True
    user32.EnumWindows(collect, 0)
    if not handles:
        return False
    user32.ShowWindow(handles[-1], 9)
    if not user32.SetForegroundWindow(handles[-1]):
        return False
    if user32.GetForegroundWindow() != handles[-1]:
        return False
    # F2 is a fallback only when clientSmoke has not produced a fresh screenshot.
    subprocess.run(['powershell', '-NoProfile', '-Command',
                    'Add-Type -AssemblyName System.Windows.Forms;'
                    '[System.Windows.Forms.SendKeys]::SendWait("{F2}")'], check=True, timeout=15,
                   creationflags=subprocess.CREATE_NO_WINDOW)
    time.sleep(3)
    return True


def frame_verdict(shot):
    probe = Path(__file__).resolve().parent.parent / 'frame-verdict.py'
    result = subprocess.run([sys.executable, str(probe), str(shot)], capture_output=True, text=True, timeout=30)
    print(result.stdout, end='', flush=True)
    if result.stderr:
        print(result.stderr, end='', file=sys.stderr)
    return result.returncode == 0 and 'verdict=DREW' in result.stdout


if __name__ == '__main__':
    command_parser = parser(__doc__)
    config(command_parser.parse_args(), command_parser)
