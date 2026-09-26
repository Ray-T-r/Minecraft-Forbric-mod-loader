#!/usr/bin/env python3
"""Shared portable configuration and owned-process handling for the Windows drivers."""
import argparse
import base64
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


# A mod's first-run screen joins vanilla's initial-screen chain (Gui.buildInitialScreens), and vanilla runs quick-play
# only as that chain's LAST link — so a first launch sits on the mod's welcome screen until a player clicks through,
# on a native loader exactly as on Forbric. sweep90-win-r6 drew wover-ui's BetterX welcome for five minutes and never
# joined the world. A sweep plays the player who has already dismissed it: each row is the mod's own "seen" flag,
# merged into its config before the client starts; every other setting stays the mod's default.
FIRST_RUN_SEEN = (
    ('config/wover/client.json', ('internal', 'did_present_welcome_screen'), True),
)


def acknowledge_first_run(instance, rows=FIRST_RUN_SEEN):
    for relative, keys, value in rows:
        target = Path(instance) / relative
        try:
            data = json.loads(target.read_text(encoding='utf-8')) if target.is_file() else {}
        except ValueError:
            data = {}
        if not isinstance(data, dict):
            data = {}
        node = data
        for key in keys[:-1]:
            if not isinstance(node.get(key), dict):
                node[key] = {}
            node = node[key]
        node[keys[-1]] = value
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2), encoding='utf-8')


# A sweep's client plays one language whoever runs it. options.txt stays the player's, but its `lang:` line decides
# which assets every mod loads, so it is an input to the verdict like the jars and the seed — and the only one nobody
# pinned: every Mac gate and local sweep instance plays en_us, the Windows profile plays its player's zh_cn.
# sweep90-win-r7c died at world join on that alone. Axiom 6.1.3 builds its Dear ImGui font atlas on the first frame
# it draws, for zh_cn from two Noto CJK fonts (7.7 + 11.5 MB) read into byte[]s it does not keep. Its bundled
# imgui-java (imgui.moulberry92, Dear ImGui 1.92.7) pins each array only for the add call, while since 1.92 the atlas
# keeps that pointer and reads it again at build(); a GC in between moves or frees the array, stb_truetype reads a
# garbage cmap format (imstb_truetype.h:1590) and the binding's assert handler calls System.exit(1). The CJK reads
# are humongous allocations, which start exactly such a GC once a full pack has filled the heap. It is not Forbric's:
# native Fabric 0.19.5 with only Axiom and fabric-api asserts on the same line in zh_cn once a GC lands between the
# add and the build — forced, or under -XX:+UseSerialGC -Xmn16m; under default G1 that minimal native pack did not
# crash in the runs recorded. en_us narrows the race rather than closing it — four fonts, ~570 KB, no humongous read:
# under that same young generation native Fabric does not assert, and this pack joins and leaves cleanly on Forbric.
SWEEP_LANGUAGE = 'en_us'
# `--lang player` pins nothing and plays whatever the player's options.txt names. It is how the player's real language,
# and every mod's assets for it, goes back under test on purpose — a zh_cn run with Axiom left out, say.
PLAYER_LANGUAGE = 'player'
# Minecraft reads options.txt top to bottom into one map, so when a file somehow holds two lang lines the last wins.
LANG_LINE = re.compile(rb'^lang:([^\r\n]*)', re.M)
# The lang line is not all a run changes: the client rewrites the whole file itself. Minecraft.<init> sets
# startedCleanly to false and saves, and only onGameLoadFinished saves it true again — a client killed while it loads
# leaves false, and the player's next start resets their fullscreen mode. So for as long as a sweep's block runs, this
# record keeps the player's file as it was (`original`, base64; `absent` when there was none), what the sweep changed
# in it (`lang` it wrote, None under `player` and when there was no file; `was`, the line it replaced), and who wrote
# it (`pid` and `started`).
# It outlives the block only when its writer dies before its own restore:
#   - push-and-run's stop kills the driver along with the client. Before its kill it notes that the record's writer is
#     alive; after the kill it puts `original` back whole, as the writer's own finally would have
#     (push-and-run.py `stop_command`, whose Python twin is note_sweep_writer / restore_after_stop).
#   - anything else — a reboot, the driver dying on its own — leaves it for whoever comes next, the stop or a client or
#     bisect driver, and they find a writer that is gone. By then the player may have played and changed settings, so
#     only the lang line goes back, and only while it still names the sweep's language (restore_player_language).
# The writer is a pid together with its process's start time, because after a reboot Windows gives the pid to some
# other process. lang and was are the line's bytes read as Latin-1, so both sides compare the same bytes.
SWEEP_RECORD = 'options.txt.forbric-sweep'
# PowerShell's (Get-Process).StartTime.ToUniversalTime().Ticks counts from 0001-01-01; Windows' FILETIME, which
# GetProcessTimes gives, from 1601-01-01. This is the gap, in the same 100 ns ticks (.NET's DateTime.FileTimeOffset).
DOTNET_TICKS_AT_1601 = 504911232000000000


def client_language(value):
    """--lang / --client-lang: a Minecraft language code, or `player`. Empty — an exported but blank FORBRIC_LANG — is
    the default, as an unset one is, rather than an error that stops the client driver before it starts."""
    value = (value or '').strip() or SWEEP_LANGUAGE
    if value != PLAYER_LANGUAGE and not re.fullmatch(r'[a-z0-9_]{2,16}', value):
        raise argparse.ArgumentTypeError(f'not a Minecraft language code or {PLAYER_LANGUAGE!r}: {value!r}')
    return value


def language_argument(argument_parser):
    argument_parser.add_argument(
        '--lang', type=client_language, default=os.environ.get('FORBRIC_LANG', ''),
        help=f"language the client plays; default {SWEEP_LANGUAGE}, also for an empty FORBRIC_LANG; "
             f"'{PLAYER_LANGUAGE}' plays the player's options.txt as it is. Either way the player's options.txt is put "
             f"back as it was afterwards")


def describe_language(code, player, absent=False):
    """The driver's `client language ...` line, which push-and-run quotes in report.md: what the client played, and
    what the player's own file names."""
    if absent:
        played = SWEEP_LANGUAGE if code == PLAYER_LANGUAGE else code
        return f"{played}; the player has no options.txt (vanilla plays en_us), and the one the client writes is removed after the run"
    if code == PLAYER_LANGUAGE:
        return f"{player or 'en_us'}; the player's options.txt, played as it is and restored after the run"
    named = player or 'no language (vanilla plays en_us)'
    return f"{code}; the player's options.txt names {named}, restored after the run"


def replace_bytes(path, data):
    # Whole or not at all: a sweep killed mid-write must not leave the player half an options.txt.
    temporary = path.with_name(path.name + '.forbric-tmp')
    temporary.write_bytes(data)
    temporary.replace(path)


def process_started(pid):
    """When the running process `pid` started, as PowerShell's (Get-Process -Id pid).StartTime.ToUniversalTime().Ticks
    reads it — the identity observe_command in push-and-run.py uses too — or None when no such process runs.

    Off Windows, where only the tests run, a process's start time is not at hand; a live one reads 0."""
    if os.name != 'nt':
        try:
            os.kill(pid, 0)
        except (ProcessLookupError, PermissionError):
            return None
        return 0
    import ctypes
    from ctypes import wintypes
    kernel32 = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel32.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
    kernel32.OpenProcess.restype = wintypes.HANDLE
    kernel32.GetExitCodeProcess.argtypes = (wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD))
    kernel32.GetExitCodeProcess.restype = wintypes.BOOL
    kernel32.GetProcessTimes.argtypes = (wintypes.HANDLE,) + (ctypes.POINTER(wintypes.FILETIME),) * 4
    kernel32.GetProcessTimes.restype = wintypes.BOOL
    kernel32.CloseHandle.argtypes = (wintypes.HANDLE,)
    kernel32.CloseHandle.restype = wintypes.BOOL
    # PROCESS_QUERY_LIMITED_INFORMATION. It fails for a pid nobody holds, and for another user's process, which a
    # sweep's driver never is: the stop and the drivers run as the same account.
    handle = kernel32.OpenProcess(0x1000, False, pid)
    if not handle:
        return None
    try:
        code, times = wintypes.DWORD(), [wintypes.FILETIME() for _ in range(4)]
        if not kernel32.GetExitCodeProcess(handle, ctypes.byref(code)) or code.value != 259:  # STILL_ACTIVE
            return None
        if not kernel32.GetProcessTimes(handle, *(ctypes.byref(value) for value in times)):
            raise ctypes.WinError(ctypes.get_last_error())
        return (times[0].dwHighDateTime << 32 | times[0].dwLowDateTime) + DOTNET_TICKS_AT_1601
    finally:
        kernel32.CloseHandle(handle)


def read_sweep_record(path):
    """The record at `path`. One that cannot be read is acted on by nobody — not a driver, not the stop — so it raises
    ValueError naming the file to delete once a person has looked at options.txt."""
    try:
        record, problem = json.loads(path.read_bytes().decode('ascii')), None
    except ValueError as error:
        record, problem = None, str(error)
    def text(value):
        return value is None or isinstance(value, str)
    def count(value):
        return isinstance(value, int) and not isinstance(value, bool) and value >= 0
    def base64_text(value):
        try:
            base64.b64decode(value, validate=True)
            return isinstance(value, str)
        except (TypeError, ValueError):
            return False
    if (isinstance(record, dict) and {'lang', 'was', 'absent', 'pid', 'started', 'original'} <= record.keys()
            and text(record['lang']) and text(record['was']) and isinstance(record['absent'], bool)
            and count(record['pid']) and count(record['started'])
            and (record['absent'] or base64_text(record['original']))):
        return record
    raise ValueError(f"{path} is not a sweep record, so nothing acted on it and options.txt was left as it is; check "
                     f"options.txt (its lang: line above all), then delete {path}: {problem or repr(record)}")


def sweep_writer_alive(record):
    started = process_started(record['pid'])
    return started is not None and started == record['started']


def writer_identity(record):
    return f"{record['pid']}:{record['started']}"


def undo_sweep(options, record, whole):
    """Put options.txt back from `record`, and say what was done.

    `whole`: the block ended with its writer — the stop killed it — and nobody else wrote options.txt in it, the same
    assumption the writer's own finally rests on, so the player's bytes go back whole, over the client's rewrite too;
    when the player had no options.txt, the one the client wrote goes. Otherwise only the lang line goes back to the
    player's, and only while every lang line still names what the sweep wrote: a player who has picked a language
    since keeps it, and every other line stays as the file now has it, as does a file where the player had none."""
    if whole:
        if record['absent']:
            if options.is_file():
                options.unlink()
                return 'removed the options.txt its client wrote; the player had none'
            return 'the player had no options.txt, and none is left'
        replace_bytes(options, base64.b64decode(record['original']))
        return "put the player's file back byte for byte"
    wrote, was = record['lang'], record['was']
    if record['absent']:
        return ("the player had no options.txt and it wrote none; whatever options.txt is there now stays, since the "
                "player may have played since")
    if wrote is None:
        return "it played the player's language and changed no line of its own; nothing to undo"
    current = options.read_bytes() if options.is_file() else None
    values = LANG_LINE.findall(current) if current is not None else []
    if values and all(value == wrote.encode('latin-1') for value in values):
        if was is None:
            replace_bytes(options, re.sub(rb'(?m)^lang:[^\r\n]*(?:\r?\n)?', b'', current))
            done = f'removed the lang:{wrote} line it had added'
        else:
            replace_bytes(options, LANG_LINE.sub(lambda _: b'lang:' + was.encode('latin-1'), current))
            done = f"put the player's lang:{was} back over its lang:{wrote}"
    elif current is None:
        done = f'it had set lang:{wrote}, and options.txt is gone; nothing to undo'
    elif values:
        done = f"it had set lang:{wrote}; kept lang:{values[-1].decode('latin-1')}, which the player has picked since"
    else:
        done = f'it had set lang:{wrote}, and the file has no lang line now; nothing to undo'
    return done + '; every other line stays as the file has it'


def restore_player_language(instance):
    """At the start of a client or bisect run: undo what a sweep that died before its own restore left in options.txt,
    its lang line only (undo_sweep); returns what was done, or None when no sweep left a record.

    A record whose writer still runs is a sweep in progress, and options.txt is its until its block ends — a second
    driver on the same instance stops here rather than pin over the first one's pin."""
    options = Path(instance) / 'options.txt'
    path = options.with_name(SWEEP_RECORD)
    if not path.is_file():
        return None
    record = read_sweep_record(path)
    if sweep_writer_alive(record):
        raise RuntimeError(f"{path}: the sweep driver that wrote it, pid {record['pid']}, is still running, and "
                           f"options.txt is its until that run ends; let it finish or stop it (push-and-run's stop "
                           f"does), then start this one again")
    done = undo_sweep(options, record, whole=False)
    path.unlink(missing_ok=True)
    return 'options.txt: a sweep ended before its own restore; ' + done


def note_sweep_writer(instance):
    """Python twin of the first half of push-and-run's stop (`stop_command`), which the tests run because PowerShell
    cannot run here: before the kill, the identity of the live process that wrote the record, or None."""
    path = Path(instance) / SWEEP_RECORD
    if not path.is_file():
        return None
    try:
        record = read_sweep_record(path)
    except ValueError:
        return None  # the stop kills first and names the file after
    return writer_identity(record) if sweep_writer_alive(record) else None


def restore_after_stop(instance, writer):
    """Python twin of the second half of push-and-run's stop, after its kill; `writer` is what note_sweep_writer
    returned before it. Returns what was done, or None when there was nothing to do.

    The writer this stop saw alive and has now killed never reached its own finally, so the stop runs it: the player's
    file goes back whole. A record whose writer was already gone before the stop — a reboot — gets only its lang line
    undone. A writer still alive after the kill is not one this stop killed, and its own finally restores the file."""
    options = Path(instance) / 'options.txt'
    path = options.with_name(SWEEP_RECORD)
    if not path.is_file():
        return None
    record = read_sweep_record(path)
    if sweep_writer_alive(record):
        return None
    done = undo_sweep(options, record, whole=writer == writer_identity(record))
    path.unlink(missing_ok=True)
    return done


@contextmanager
def sweep_language(instance, code=SWEEP_LANGUAGE):
    """Set options.txt's `lang:` to `code` for the block, then put the player's file back byte for byte — or remove
    the one the client wrote when the player had none. `player` pins nothing but still puts the file back, over the
    client's own rewrite.

    Yields the driver's `client language` line (describe_language). Before anything is written, SWEEP_RECORD keeps
    what the block needs to be undone by someone else if this process dies inside it; a record another sweep left is
    undone first, and one whose writer still runs stops this one (restore_player_language)."""
    if code != PLAYER_LANGUAGE and not re.fullmatch(r'[a-z0-9_]{2,16}', code):
        raise ValueError('not a Minecraft language code: ' + repr(code))
    options = Path(instance) / 'options.txt'
    record = options.with_name(SWEEP_RECORD)
    started = process_started(os.getpid())
    if started is None:
        raise RuntimeError(f'cannot read the start time of this driver, pid {os.getpid()}; options.txt left as it is')
    # The pid file's lock: two drivers starting at once cannot both find no record and both write one.
    with pid_lock(dict(pid_file=str(Path(instance) / '.forbric-sweep.pid'))):
        undone = restore_player_language(instance)
        if undone:
            print(undone, flush=True)
        absent = not options.is_file()
        if absent and code not in (SWEEP_LANGUAGE, PLAYER_LANGUAGE):
            raise ValueError('no options.txt to set ' + code + ' in; vanilla would play en_us')
        original = None if absent else options.read_bytes()
        values = LANG_LINE.findall(original) if original else []
        pinned = None
        if not absent and code != PLAYER_LANGUAGE:
            value = b'lang:' + code.encode('ascii')
            if values:
                pinned = LANG_LINE.sub(lambda _: value, original)
            else:
                newline = b'\r\n' if b'\r\n' in original else b'\n'
                pinned = original + (b'' if not original or original.endswith(b'\n') else newline) + value + newline
        replace_bytes(record, json.dumps(dict(
            lang=None if pinned is None else code, was=values[-1].decode('latin-1') if values else None,
            absent=absent, pid=os.getpid(), started=started,
            original=None if absent else base64.b64encode(original).decode('ascii'))).encode('ascii'))
    try:
        if pinned is not None:
            replace_bytes(options, pinned)
        yield describe_language(code, values[-1].decode('utf-8', 'replace') if values else None, absent)
    finally:
        # The block was the sweep's own: nobody else wrote options.txt in it, so the player's bytes go back whole,
        # over whatever the sweep's client saved into it as well.
        if absent:
            options.unlink(missing_ok=True)
        else:
            replace_bytes(options, original)
        record.unlink(missing_ok=True)


def spawn(configuration, command, **kwargs):
    process = subprocess.Popen(command, **kwargs)
    record_pid(configuration, process.pid)
    return process


def server_properties(world, seed, port):
    """The dedicated server's properties for a sweep, with the empty-server pause DISABLED.

    pause-when-empty-seconds is the load-bearing line and it is easy to lose. Vanilla defaults it to 60
    (DedicatedServerProperties.<init>, `ldc "pause-when-empty-seconds"; bipush 60`), a sweep's server never has
    a player on it, and MinecraftServer.tickServer reads

        i = pauseWhenEmptySeconds() * 20
        if (i <= 0) goto normal_tick
        ...
        if (emptyTicks >= i) { ...log once, autoSave once...; tickConnection(); return; }

    with that `return` landing BEFORE tickCount++ and before EventHooks.fireServerTickPre. So without this line
    the server stops ticking sixty seconds after Done and every further second of the soak is a paused JVM: no
    tick, no mod tick event, no log line. In build/compat/mix80-verify4 that is visible as `Server empty for 60
    seconds, pausing` at Done+60 with nothing after it until the stop at Done+90 — thirty seconds of every
    sweep spent proving nothing.

    Zero DISABLES the pause (the `i <= 0` branch above); it does not mean pause immediately. With it, the whole
    of --tick-seconds is real simulation, and raising that flag buys what it looks like it buys.
    """
    return (f'level-name={world}\nlevel-seed={seed}\nserver-port={port}\n'
            'online-mode=false\nview-distance=10\nsimulation-distance=10\nspawn-protection=0\n'
            'pause-when-empty-seconds=0\n')


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
    # Acceptance is strict: a confirmed required loss stops the run instead of waiting on a window nobody at the
    # sweep can answer, and an operator clicking Continue cannot turn it into a pass. After the profile's own
    # arguments, because the last -D wins and an installed profile keeps the product's ask default; before the
    # operator's --jvm, so a deliberate negative canary can still ask for continue (and the verdict, which reads
    # the policy back from the compatibility report, then refuses it).
    jvm += ['-Dforbric.compatibilityPolicy=strict']
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
