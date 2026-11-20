#!/usr/bin/env python3
"""M34: freeze a per-nonce client installation and independently validate real simulation evidence.

Does not build or mutate a source world. Only the gate wrapper builds, before the immutable snapshot.
A CONTROL_PASS is deliberately not a release soak. Reachable retired servers require review.
"""
import argparse
import contextlib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import shlex
import shutil
import signal
import subprocess
import sys
import time
import uuid
import zipfile
sys.dont_write_bytecode = True
from evidence import source_record

MIN_SECONDS = 7200
# The game type the soak's player has, on the server and on the client, in every occupied row
# (ClientSoakController.preparePlayer says why: a soak drives the game as a survival player's client does). A run whose
# player was anything else is refused; the controller holds each row to the same rule as it writes it
# (net.forbric.kernel.soak.SoakPlayer, whose GAME_TYPE is this word; SoakPlayerTest keeps the two together).
SOAK_GAME_TYPE = 'survival'
NATIVE_RETENTION = Path(__file__).with_name('native-retention.json')


def stop_signals():
    """The signals that stop a running soak from outside, on this system: SIGTERM and SIGHUP where both exist (macOS and
    Linux, where the soak runs), SIGTERM and SIGBREAK on Windows.

    Windows has no SIGHUP at all, so naming it there is an AttributeError before anything is installed. What it has
    instead is SIGBREAK (Ctrl-Break, and CTRL_BREAK_EVENT sent to a process group) and a SIGTERM that only the process
    itself can raise: nothing outside can deliver one, because os.kill there is TerminateProcess. Each name is taken
    only where the signal module has it, so the POSIX pair is exactly what it was."""
    return tuple(getattr(signal, name) for name in ('SIGTERM', 'SIGHUP', 'SIGBREAK') if hasattr(signal, name))


# The stop signals the running stop_signals_interrupt block has seen so far; None while no such block runs.
STOPS_SEEN = None


@contextlib.contextmanager
def stop_signals_interrupt():
    """While the block runs, the stop signals (stop_signals: SIGTERM and SIGHUP) are the interruption Ctrl-C is
    (KeyboardInterrupt), once, and the handlers in force before are back afterwards.

    Everything the soak starts runs in a session of its own (the client, and through the reproduction its servers), so
    a signal to the soak's process group reaches none of it, and Python's default for SIGTERM ends the soak on the spot:
    the client then plays on for its two hours with nobody waiting for it, outside the locks the soak was started
    under. A gate started in the background cannot be stopped with Ctrl-C either (a non-interactive shell starts its
    background jobs with SIGINT ignored), so SIGTERM is the one way to stop it, and it has to stop what was started.

    Once: the interruption starts the stopping (the client's group told to stop, thirty seconds, killed; the
    measurement likewise), and a second signal used to interrupt the stopping itself. The soak then left with exit 130
    and no record while the client it had started was still running -- a second `kill`, a second Ctrl-C, or the two
    SIGHUPs of a closing terminal, against a client that had not gone yet. So only the first stop raises; later ones
    are counted and change nothing, since what they ask for is already under way and ends within its thirty seconds.
    Ctrl-C is in the same count where it would raise at all (Python's own handler in force; the ignored SIGINT of a
    background job stays ignored), and a block inside another (the measurement inside main's) is part of the outer
    one, so no mix of the three signals and no nesting gets a second interruption through."""
    global STOPS_SEEN
    if STOPS_SEEN is not None:
        yield
        return
    stops = []
    def interrupted(number, frame):
        # Whether this is the first is settled before it is counted, not read back from the count: two signals a few
        # microseconds apart run this handler inside itself (Python runs a handler between any two instructions of
        # the main thread, another handler's included), and a first call that found two entries after its own
        # append raised nothing at all -- the soak then carried on as if nobody had stopped it (measured: SIGHUP,
        # SIGTERM and SIGINT sent together). A call that starts before anything is counted is a first call; when a
        # second one starts inside it at that point, its interruption goes out through both.
        first = not stops
        stops.append(number)
        if first:
            raise KeyboardInterrupt(f'signal {number}')
    previous = {}
    try:
        taken = list(stop_signals())
        if signal.getsignal(signal.SIGINT) is signal.default_int_handler:
            taken.append(signal.SIGINT)
        for number in taken:
            previous[number] = signal.signal(number, interrupted)
    except ValueError:
        pass  # not the main thread: signals are the main thread's to turn into an interruption
    STOPS_SEEN = stops
    try:
        yield
    finally:
        STOPS_SEEN = None
        for number, handler in previous.items():
            signal.signal(number, handler)


def signal_owned_group(process, force=False):
    """Stop the client the soak started, and everything it started: told to (SIGTERM), or killed when `force`.

    The client runs in a session of its own, so its process group is exactly what the soak owns: os.killpg reaches all
    of it and nothing else. Windows has no process groups to signal and no SIGTERM or SIGKILL to send (os.killpg is not
    there to call), so the tree is ended there with `taskkill /F /T`, as tools/nightly/run_nightly.py's stop() and
    run/compat/win/common.py's finish() end theirs; there is no asking first on that system, so both calls do the same.
    Which path is taken is decided by whether this Python has os.killpg, not by the system's name. The taskkill path
    has run only with subprocess.run modelled (test_soak): the soak itself does not run on Windows, its self-tests do."""
    killpg = getattr(os, 'killpg', None)
    if killpg is None:
        subprocess.run(['taskkill', '/F', '/T', '/PID', str(process.pid)], capture_output=True)
        return
    # The owned child may exit between poll() and signaling. Preserve its real exit status.
    try:
        killpg(process.pid, signal.SIGKILL if force else signal.SIGTERM)
    except ProcessLookupError:
        pass


def wait_for_client(process, log, timeout):
    """Keep a reported game crash from leaving an unresponsive test window until the soak timeout."""
    began = time.monotonic()
    crash_seen = None
    offset, tail = 0, ''
    while process.poll() is None:
        with Path(log).open(errors='replace') as stream:
            stream.seek(offset); tail = (tail + stream.read())[-8192:]; offset = stream.tell()
        if crash_seen is None and '#@!@# Game crashed!' in tail:
            crash_seen = time.monotonic()
            signal_owned_group(process)
        if (crash_seen is not None and time.monotonic() - crash_seen > 5) or time.monotonic() - began > timeout:
            signal_owned_group(process, force=True)
            process.wait()
            return process.returncode
        time.sleep(.5)
    return process.returncode


def client_environment(caught):
    """The client JVM's environment: the caller's, with the kernel's caught-failure sink pointed at the run's evidence.

    The client and every integrated server it opens are one process, so this one file is everything the kernel caught
    and carried on from during the soak. It lives in the evidence directory so a retained run is complete without the
    gate that started it; forward_caught() then hands it to that gate.
    """
    environment = dict(os.environ)
    environment['FORBRIC_CAUGHT_FILE'] = str(caught)
    return environment


def forward_caught(caught, outer):
    """Append the client's caught file to the gate's (the inherited FORBRIC_CAUGHT_FILE) and summarise it.

    gate-m34 used to exec this script, which replaced the shell whose EXIT trap prints a gate's [kernel] CAUGHT line,
    and the client wrote to a file nobody reported. Now the gate waits for this script and reports its own file, and
    this is what puts the client's failures in it. Returns the evidence record: the file, its failure sites, its digest.
    """
    caught = Path(caught)
    text = caught.read_text(errors='replace') if caught.is_file() else ''
    sites = sorted({fields[2] for fields in (line.split('\t') for line in text.splitlines())
                    if len(fields) > 2 and fields[0] == 'FAILURE'})
    if outer and text and Path(outer).resolve() != caught.resolve():
        with Path(outer).open('a') as stream:
            stream.write(text if text.endswith('\n') else text + '\n')
    return {'file': str(caught), 'failureSites': sites, 'sha256': digest(caught) if caught.is_file() else None}


class RetentionReview(ValueError):
    """Activity can be proven while release acceptance remains refused."""
    def __init__(self, activity):
        super().__init__('retained old servers require evidence review; release acceptance remains refused')
        self.activity = activity


def validate_compatibility(path, started_ns):
    path = Path(path)
    if not path.is_file() or path.stat().st_mtime_ns < started_ns:
        raise ValueError('missing or stale final compatibility report')
    report = json.loads(path.read_text())
    required = [row for row in report['findings'] if row['confidence'] == 'CONFIRMED' and row['required']]
    if report['policy'] != 'STRICT' or report['confirmedRequired'] != len(required) or required:
        raise ValueError('final compatibility report is not strict with zero required losses')
    if any(row['status'] == 'FAILED' for row in report.get('catalogFailures', [])):
        raise ValueError('unclassified initialization failure in final compatibility report')
    return {'sha256': digest(path), 'policy': report['policy'], 'confirmedRequired': 0}


def digest(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def inventory(root):
    root = Path(root)
    if root.is_symlink():
        raise ValueError(f'symlinks are not permitted in copied inputs: {root}')
    result = {}
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise ValueError(f'symlinks are not permitted in copied inputs: {path}')
        if path.is_file():
            result[str(path.relative_to(root))] = digest(path)
    return result


def dump(path, value):
    Path(path).write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def copy_frozen(source, destination, records):
    source = Path(source)
    if source.is_symlink() or not source.is_file():
        raise ValueError(f'expected an actual input file: {source}')
    before = digest(source)
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)
    if before != digest(source) or before != digest(destination):
        raise ValueError(f'input changed while being copied: {source}')
    destination.chmod(0o444)
    records.append({'source': str(source.resolve()), 'snapshot': str(destination.resolve()),
                    'sha256': before, 'size': destination.stat().st_size})
    return destination


def copy_tree(source, destination):
    before = inventory(source)
    shutil.copytree(source, destination)
    if inventory(source) != before or inventory(destination) != before:
        raise ValueError(f'input tree changed while being copied: {source}')
    return before


def native_retention_roots(frozen_mod_hashes, registry=NATIVE_RETENTION):
    """Reviewed roots whose exact mod jar is in this run. Nothing else may be cut by the controller."""
    entries = json.loads(Path(registry).read_text())['roots']
    present = set(frozen_mod_hashes)
    return [entry for entry in entries if entry['modJarSha256'] in present]


def recorded_files(inputs):
    """Every file an arm's inputs.json names with the bytes it had: its mods, what booted them, its sources and, for the
    native arm, the installed image's record."""
    return [*inputs.get('modSet', []), *inputs.get('carriers', []), *inputs.get('sources', []),
            *([inputs['image']] if 'image' in inputs else [])]


def unreadable_evidence(path, failure):
    return f'unreadable: {path} is not a record this can read ({type(failure).__name__}: {failure})'


def native_evidence_problem(kernel, entry, launched=None):
    """Why the native reproduction `entry` names cannot back a release soak now, or None when it can.

    The evidence is a build output, so it is only as good as what it was measured on. It backs this soak when
      - it exists and says what the registry claims: identical mods on both loaders, the root reproduced by both arms,
        the registered jar in both mod sets;
      - nothing it was measured on has changed since: every file an arm recorded (recorded_files) is still there with
        the recorded bytes, the kernel jar at build/libs among them, so a rebuilt kernel makes it stale; and
      - its Forbric arm booted what this soak launches: `launched` maps a name to the SHA-256 of the soak's kernel jar,
        game base and carrier, and each must be among that arm's carriers.
      - it is this checkout's own measurement: each arm's record lies beside the comparison, where the reproduction
        writes it. A comparison copied in from another tree names that tree's files by absolute path and would be
        judged by their bytes, not by anything here; and
      - the native arm's record names the installed image it booted. An arm that does not say which installation it ran
        cannot be told from one whose installation was replaced since.
    Anything else is absent, stale or unreadable evidence, which ensure_native_evidence() replaces by measuring again
    and verify_native_evidence() refuses. A file that is JSON of another shape (a truncated or hand-edited comparison,
    a record without the keys read here) is unreadable evidence like one that is not JSON at all: it used to end the
    soak with a bare KeyError instead of being measured again."""
    path = Path(kernel) / entry['nativeEvidence']
    if not path.is_file():
        return f'absent: there is no {path}'
    try:
        return described_evidence_problem(path, json.loads(path.read_text()), entry, launched)
    except (ValueError, KeyError, TypeError, AttributeError) as failure:
        return unreadable_evidence(path, failure)


def described_evidence_problem(path, comparison, entry, launched):
    """native_evidence_problem() for a comparison that was read; a shape it cannot walk raises, and the caller names it."""
    arms = comparison.get('arms', [])
    if not comparison.get('sameModHashes') or {arm.get('engine') for arm in arms} != {'native', 'forbric'}:
        return 'it does not compare identical mods on both loaders'
    beside = path.resolve().parent
    for arm in arms:
        engine = arm.get('engine')
        if not arm.get('nativeRetentionReproduced') or arm.get('proof', {}).get('root') != entry['root']:
            return f"native retention of {entry['root']} is not reproduced by arm {engine}"
        recorded = Path(arm['inputs']['path'])
        if not recorded.resolve().is_relative_to(beside):
            return (f'stale: the record of what arm {engine} ran is not under {beside} ({recorded}), so the comparison '
                    'was not measured in this checkout')
        if not recorded.is_file():
            return f'stale: the record of what arm {engine} ran is gone ({recorded})'
        if digest(recorded) != arm['inputs']['sha256']:
            return f'stale: the record of what arm {engine} ran changed after the comparison ({recorded})'
        try:
            inputs = json.loads(recorded.read_text())
            mods = {mod['sha256'] for mod in inputs['modSet']}
            files = [(Path(item['path']), item['sha256']) for item in recorded_files(inputs)]
            ran = {item['sha256'] for item in inputs.get('carriers', [])}
        except (ValueError, KeyError, TypeError, AttributeError) as failure:
            return unreadable_evidence(recorded, failure)
        if entry['modJarSha256'] not in mods:
            return f"it did not use the registered {entry['mod']}"
        if engine == 'native' and 'image' not in inputs:
            return f'stale: the record of what arm {engine} ran does not name the installed image it booted ({recorded})'
        for file, recorded_digest in files:
            if not file.is_file():
                return f'stale: arm {engine} was measured on {file}, which is gone'
            if digest(file) != recorded_digest:
                return f'stale: arm {engine} was measured on {file}, which has other bytes now'
        if engine == 'forbric' and launched:
            for name, value in launched.items():
                if value not in ran:
                    return f'stale: its Forbric arm did not boot the {name} this soak launches (sha256 {value[:12]})'
    return None


def verify_native_evidence(kernel, entry, launched=None):
    """The registry is a claim; release acceptance re-reads the native reproduction it names, and refuses one that is
    absent or stale for this run (native_evidence_problem)."""
    problem = native_evidence_problem(kernel, entry, launched)
    if problem:
        raise ValueError(f"native retention evidence for {entry['root']} cannot be used: {problem} "
                         f"(it comes from: {entry['reproduce']})")
    path = Path(kernel) / entry['nativeEvidence']
    return {'root': entry['root'], 'evidence': str(path), 'sha256': digest(path)}


def reproduction_caught(log):
    """Where the kernel JVMs a reproduction starts say what they caught: a file of the measurement's own, beside its log."""
    return Path(log).with_name(Path(log).stem + '.caught.tsv')


def run_reproduction(kernel, entry, log):
    """Run the registry entry's own reproduction command in the kernel directory, its output kept in `log`.

    Its environment is the caller's (GATE_PORT, FORBRIC_OLD, FORBRIC_JVM, the prebuilt-jar variables), except for where
    the kernel writes what it caught: the reproduction's Forbric server is a kernel JVM of this gate, but not the soak's
    client, and writing into the gate's file directly put its failures in the gate's CAUGHT line and in no evidence
    record. It gets reproduction_caught(log); ensure_native_evidence() records that file and forwards it to the gate's.

    The command starts servers in sessions of their own, so it has to be told to stop, not killed: when this is
    interrupted (Ctrl-C, or SIGTERM/SIGHUP: stop_signals_interrupt) the command gets SIGTERM and thirty seconds to stop
    its server before it is killed."""
    command = shlex.split(entry['reproduce'])
    if command[0] in ('python3', 'python'):
        command[0] = sys.executable
    caught = reproduction_caught(log)
    caught.write_text('')
    environment = {**os.environ, 'FORBRIC_CAUGHT_FILE': str(caught)}
    with stop_signals_interrupt(), Path(log).open('w') as stream:
        process = subprocess.Popen(command, cwd=kernel, stdout=stream, stderr=subprocess.STDOUT, env=environment)
        try:
            return process.wait()
        except BaseException:
            process.terminate()
            try: process.wait(timeout=30)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
            raise


def ensure_native_evidence(kernel, entry, launched, log, reproduce=run_reproduction):
    """The native reproduction a release soak re-reads, measured now when there is none that describes this run.

    The evidence lives in gitignored build/, and nothing but a hand-run of the reproduction used to write it, so a fresh
    worktree or a cleaned build failed the soak in its first seconds (C6 ACCEPT finding F1). The soak now obtains its
    own prerequisite: current evidence is used as it is, absent or stale evidence is measured again with the registry's
    own command, and the result goes through the same check either way. Nothing is skipped and nothing stale is
    accepted: when the reproduction fails, or what it wrote still does not describe this run, the soak stops. Every
    step is printed, with the seconds the measurement cost."""
    root = entry['root']
    problem = native_evidence_problem(kernel, entry, launched)
    produced = None
    if problem is None:
        print(f"[M34] native retention evidence for {root} is current: {Path(kernel) / entry['nativeEvidence']}", flush=True)
    else:
        print(f'[M34] native retention evidence for {root} cannot be used as it is: {problem}', flush=True)
        print(f"[M34] measuring it now: {entry['reproduce']} (one native NeoForge server, then one Forbric server; "
              f'output in {log})', flush=True)
        began = time.monotonic()
        code = reproduce(kernel, entry, log)
        seconds = time.monotonic() - began
        print(f"[M34] {entry['reproduce']} took {seconds:.0f} s and exited {code}", flush=True)
        # What the measurement's kernel JVM caught and carried on from: recorded with the measurement, and handed to
        # the gate's file so its CAUGHT line covers every kernel JVM the gate started.
        caught = forward_caught(reproduction_caught(log), os.environ.get('FORBRIC_CAUGHT_FILE'))
        print(f"[M34] the measurement's Forbric server caught {len(caught['failureSites'])} failure site(s) and carried "
              f"on: {caught['file']}", flush=True)
        problem = native_evidence_problem(kernel, entry, launched)
        if code != 0 or problem:
            said = [line for line in Path(log).read_text(errors='replace').splitlines() if line.strip()] if Path(log).is_file() else []
            raise ValueError(f'native retention evidence for {root} could not be produced: the reproduction exited {code}'
                             + (f', and what it left cannot be used: {problem}' if problem else '')
                             + (f'; its last line: {said[-1][:600]}' if said else '') + f' (output: {log})')
        produced = {'command': entry['reproduce'], 'seconds': round(seconds, 1), 'exitCode': code, 'log': str(log),
                    'caught': caught}
    record = verify_native_evidence(kernel, entry, launched)
    record['measuredForThisRun'] = produced is not None
    if produced:
        record['measurement'] = produced
    return record


def validate_native_release(result, roots, serial):
    """Accept retention only when the controller's post-measurement cut of reviewed roots freed every server."""
    before = result['oldServers']
    after = result.get('oldServersAfterNativeRelease', before)
    released = result.get('nativeRetentionRelease', [])
    if sorted(server['server'] for server in after) != list(range(1, serial + 1)):
        raise ValueError('post-release observations do not cover every completed session')
    if any(before_row['server'] == after_row['server'] and not before_row['alive'] and after_row['alive']
           for before_row in before for after_row in after):
        raise ValueError('a collected server reappeared after the native release')
    allowed = {entry['root']: entry for entry in roots}
    for row in released:
        if row.get('root') not in allowed:
            raise ValueError('controller cut a root that is not reviewed for this run: ' + str(row.get('root')))
    if not any(server['alive'] for server in before):
        if released:
            raise ValueError('native roots were cut although no server was retained')
        return []
    if any(server['alive'] for server in after):
        return None
    return [{'root': row['root'], 'removedStoppedServerEntries': row['removedStoppedServerEntries'],
             'modJarSha256': allowed[row['root']]['modJarSha256']} for row in released]


HEAP_PATHS = Path(__file__).with_name('HeapPaths.java')


def mod_owned_retention(java, dump, mods, game_jars, output):
    """Ask HeapPaths whether every strong path to each retained server runs through state a mod keeps."""
    command = [java, '-Xmx6g', str(HEAP_PATHS), str(dump), 'net.minecraft.client.server.IntegratedServer', '3',
               '--mod-owned', str(mods), *map(str, game_jars)]
    with Path(output).open('w') as log:
        code = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT).returncode
    verdicts = [line.split()[1:] for line in Path(output).read_text().splitlines() if line.startswith('VERDICT ')]
    return {'exitCode': code, 'reachable': sum(v[1] == 'REACHABLE' for v in verdicts),
            'unreachable': sum(v[1] == 'UNREACHABLE' for v in verdicts), 'analysis': str(output), 'sha256': digest(output)}


def require_soak_player(row):
    """An occupied row must come from the soak's own kind of player: a survival player on the server and on the client.

    The controller makes the player one while a session joins, writes the join once the client agrees, and fails the
    soak at the first later row that says otherwise (net.forbric.kernel.soak.SoakPlayer; this is the same rule, applied
    again to the evidence). A row that says otherwise, or says nothing because an older controller wrote it, is a run
    that did not drive the game the way the soak is defined, whatever else it measured. The server's game type decides
    what the world does to the player and the client's what the client draws and runs for it, so both are held.
    One exception, and only for a sample: a row that says the client had left the world (clientInWorld false) has no
    client game type to hold; a sample taken just before a disconnect can be written just after it."""
    if not row['occupied']:
        return
    if row.get('gameType') != SOAK_GAME_TYPE:
        raise ValueError(f"the soak player was not a {SOAK_GAME_TYPE} player in telemetry row {row['sequence']} "
                         f"(gameType {row.get('gameType')!r}); such a run is not a soak")
    left = row['type'] == 'sample' and row.get('clientInWorld') is False
    if not left and row.get('clientGameType') != SOAK_GAME_TYPE:
        raise ValueError(f"the soak player was not a {SOAK_GAME_TYPE} player on the client in telemetry row "
                         f"{row['sequence']} (clientGameType {row.get('clientGameType')!r}); such a run is not a soak")


CONDITION_KEYS = ('sequence', 'utc', 'server', 'gameTime', 'dimension', 'gameType', 'clientGameType', 'clientInWorld',
                  'flying', 'health', 'raining', 'thundering', 'rainingAtPlayer')


def conditions(rows):
    """What the telemetry says about the player and the weather, for whoever has to explain a verdict.

    Not a check: it never fails a run. It goes into acceptance.json for every run that wrote telemetry, a crashed one
    included, so that "when did it start to rain, and was the player under it" is answered by the evidence. The m34 run
    of 2026-11-20 died 4610 s in, in the second rain reached the player, and nothing in its evidence said so.

    rainingAtPlayer is null in a row whose player stood in a chunk that was not loaded yet (the controller does not
    load a chunk to answer it); such rows are counted in rainingAtPlayerUnknownRows, not as dry ones."""
    seen = [row for row in rows if row.get('type') in ('join', 'sample') and 'raining' in row]
    def brief(row):
        return {key: row.get(key) for key in CONDITION_KEYS}
    def first(key):
        return next((brief(row) for row in seen if row.get(key)), None)
    health = [row['health'] for row in seen if isinstance(row.get('health'), (int, float))]
    return {'rows': len(seen),
            'gameTypes': sorted({str(row.get('gameType')) for row in seen if row.get('occupied')}),
            'rainingRows': sum(bool(row.get('raining')) for row in seen),
            'rainingAtPlayerRows': sum(bool(row.get('rainingAtPlayer')) for row in seen),
            'rainingAtPlayerUnknownRows': sum(row.get('occupied') is True and row.get('rainingAtPlayer') is None for row in seen),
            'thunderingRows': sum(bool(row.get('thundering')) for row in seen),
            'firstRaining': first('raining'), 'firstRainingAtPlayer': first('rainingAtPlayer'),
            'joinsInRain': [brief(row) for row in seen if row['type'] == 'join' and row.get('rainingAtPlayer')],
            'lowestHealth': min(health) if health else None,
            'last': brief(seen[-1]) if seen else None}


def read_telemetry(path):
    """The telemetry rows that are whole. A client that died mid-write leaves a cut last line; it is dropped here and
    only here: release validation reads the file strictly."""
    rows = []
    for line in Path(path).read_text(errors='replace').splitlines():
        try:
            rows.append(json.loads(line))
        except ValueError:
            break
    return rows


def validate_trace(rows, result, nonce, seconds, control, process_seconds, min_sessions=3, native_roots=(), mod_owned=None):
    if not control and seconds < MIN_SECONDS:
        raise ValueError('release acceptance requires at least 7200 seconds')
    if not rows or rows[0]['type'] != 'start' or rows[-1]['type'] != 'finish':
        raise ValueError('missing complete start/finish telemetry')
    if result['nonce'] != nonce or result['pid'] != rows[0]['pid']:
        raise ValueError('foreign or stale result')
    if rows[0]['requiredSeconds'] != seconds or rows[0]['releaseEligible'] != (not control):
        raise ValueError('controller configuration does not match launcher')
    pid, sequence, previous, serial, sessions = rows[0]['pid'], 0, None, 0, 0
    ticks = active = opens = 0
    visits, unloads, reloads = [0] * 6, [0] * 6, [0] * 6
    seen, loaded, unloaded = [False] * 6, [False] * 6, [False] * 6
    desired = None
    close_requested = False
    for row in rows:
        if row['nonce'] != nonce or row['pid'] != pid or row['sequence'] != sequence + 1:
            raise ValueError('noncontiguous telemetry or changed process identity')
        sequence += 1
        kind = row['type']
        if kind == 'open':
            if previous is not None or sessions != serial:
                raise ValueError('reopen before completed normal disconnect')
            opens += 1
        elif kind == 'join':
            if previous is not None or row['server'] != serial + 1 or not row['occupied']:
                raise ValueError('invalid integrated-server join')
            require_soak_player(row)
            serial += 1
            previous = row
            seen, loaded, unloaded = [False] * 6, [False] * 6, [False] * 6
            desired = None
        elif kind == 'move':
            if previous is None or row['server'] != serial or desired is not None:
                raise ValueError('movement outside occupied session or preceding arrival missing')
            desired = row['point']
            if desired not in range(6):
                raise ValueError('unexpected chunk probe')
        elif kind == 'sample':
            if previous is None or row['server'] != serial:
                raise ValueError('sample outside joined server')
            require_soak_player(row)
            dt = row['tick'] - previous['tick']
            dw = row['gameTime'] - previous['gameTime']
            dn = row['sampleNano'] - previous['sampleNano']
            if min(dt, dw, dn) < 0:
                raise ValueError('simulation counters regressed')
            actual = min(dt, dw)
            if row['occupied'] and previous['occupied'] and not row['paused'] and not previous['paused']:
                ticks += actual
                active += min(dn, actual * 50_000_000)
            if len(row['loaded']) != 6 or len(row['chunks']) != 3 or min(row['chunks']) < 0:
                raise ValueError('missing three-dimensional chunk measurements')
            for i, present in enumerate(row['loaded']):
                if present:
                    if unloaded[i] and not loaded[i]:
                        reloads[i] += 1
                    seen[i] = True
                elif seen[i] and loaded[i]:
                    unloaded[i] = True
                    unloads[i] += 1
                loaded[i] = present
            if desired is not None and row['point'] == desired and row['loaded'][desired]:
                visits[desired] += 1
                desired = None
            previous = row
        elif kind == 'save-and-disconnect':
            if previous is None or desired is not None:
                raise ValueError('normal disconnect before final probe arrived')
            close_requested = True
        elif kind == 'disconnect':
            if previous is None or not close_requested or row['server'] != serial or not row['stopped'] or not row['normalSaveRequested']:
                raise ValueError('disconnect lacks native stop/save evidence')
            sessions += 1
            previous, close_requested = None, False
    if previous is not None or sessions != serial or opens != serial - 1 or sessions < min_sessions:
        raise ValueError('insufficient same-JVM normal save and reopen cycles')
    expected = 'CONTROL_PASS' if control else 'RELEASE_PASS'
    if result['status'] not in (expected, 'REVIEW_REQUIRED'):
        raise ValueError('controller did not pass: ' + result['status'] + '; retained-server evidence is reviewable, not proof of a leak')
    if ticks != result['actualTicks'] or active != result['activeNanos']:
        raise ValueError('reported activity differs from independently counted simulation')
    if active < seconds * 1_000_000_000 or ticks < seconds * 20 or process_seconds < seconds:
        raise ValueError('insufficient real occupied simulation; idle wall time does not count')
    if any(n < 2 for n in visits) or any(n == 0 for n in unloads + reloads):
        raise ValueError('all six chunk probes must unload and reload observably')
    for name, measured in [('visits', visits), ('unloads', unloads), ('reloads', reloads)]:
        if measured != result[name]:
            raise ValueError('coverage counter mismatch: ' + name)
    if sorted(server['server'] for server in result['oldServers']) != list(range(1, serial + 1)):
        raise ValueError('retired-server observations do not cover every completed session')
    if any(not server['stopped'] for server in result['oldServers']):
        raise ValueError('a retired server was not stopped normally')
    activity = {'activityVerified': True, 'actualTicks': ticks,
            'activeSeconds': active / 1_000_000_000, 'sessions': sessions,
            'visits': visits, 'unloads': unloads, 'reloads': reloads}
    attributed = validate_native_release(result, native_roots, serial)
    if attributed is None:
        residual = sum(server['alive'] for server in result.get('oldServersAfterNativeRelease', result['oldServers']))
        # Residual servers pass only when every strong path to each of them runs through a field a mod keeps (none
        # through the game, a carrier or Forbric alone), and when most sessions' servers were collected, so that
        # what is held is a mod's last-value state and not a per-session accumulation.
        if (not mod_owned or mod_owned['exitCode'] != 0 or mod_owned['reachable'] != 0
                or mod_owned['unreachable'] != residual or residual * 2 >= sessions):
            raise RetentionReview(activity)
        activity['modOwnedRetention'] = {'retained': residual, 'sessions': sessions,
                                         'analysis': mod_owned['analysis'], 'sha256': mod_owned['sha256']}
        attributed = []
    if result['status'] == 'REVIEW_REQUIRED' and not any(server['alive'] for server in result['oldServers']):
        raise ValueError('controller requested retention review without a retained-server witness')
    if result['status'] != 'REVIEW_REQUIRED' and any(server['alive'] for server in
                                                    result.get('oldServersAfterNativeRelease', result['oldServers'])):
        raise ValueError('controller reported a pass while a retired server is still reachable')
    if attributed:
        activity['nativeRetentionAttributed'] = attributed
    return {'status': expected, 'releaseAccepted': not control, **activity}


def saved_weather(world):
    """What the world's own save says the weather is when the first session joins it, for the manifest and one line of
    output; never a check.

    The first join meets the weather the source world was saved with, and the soak takes that world as it is (it is the
    fixture's, shared and not tracked by git). A join into rain and a join into clear weather that turns to rain later
    are different runs for anything that reacts to rain, so the evidence says which this was before the client starts:
    the telemetry says it only once a join row exists. 26.2 keeps the weather in data/minecraft/weather.dat."""
    path = Path(world) / 'data/minecraft/weather.dat'
    if not path.is_file():
        return {'file': str(path), 'problem': 'no such file in this world'}
    try:
        spec = importlib.util.spec_from_file_location('world_snapshot', Path(__file__).with_name('world-snapshot.py'))
        reader = importlib.util.module_from_spec(spec); spec.loader.exec_module(reader)
        data = reader.read_nbt_file(path)[1]['data'][1]
        value = lambda name: data[name][1] if name in data else None
        return {'file': str(path), 'sha256': digest(path), 'raining': bool(value('raining')),
                'thundering': bool(value('thundering')), 'rainTime': value('rain_time'),
                'thunderTime': value('thunder_time'), 'clearWeatherTime': value('clear_weather_time')}
    except Exception as failure:
        return {'file': str(path), 'problem': f'not readable as saved weather ({type(failure).__name__}: {failure})'}


def describe_saved_weather(weather):
    if 'problem' in weather:
        return f"the copied world's saved weather is not known: {weather['problem']} ({weather['file']})"
    now = ('thunder' if weather['thundering'] else 'rain') if weather['raining'] else 'clear'
    turn = (f"; its rain timer stands at {weather['rainTime']} tick(s)" if weather['rainTime'] is not None else '')
    return (f"the copied world was saved in {now} weather{turn}, so the first session joins into "
            f"{'rain' if weather['raining'] else 'clear weather'}")


def vanilla_reference(kernel, base, mc):
    """run/vanilla-reference-jar.sh's sidecar for base — staged by prepare or built once into build/, and only one that
    records this base. When it can provide none the soak stops: NeoForge's patched game embeds no vanilla index of its
    own, so a client launched without --nativeReference has no FABRIC references at all, and two hours of it would soak a
    kernel no player runs (every launcher hands the sidecar over). --native-reference names one explicitly."""
    result = subprocess.run(['bash', str(kernel / 'run/vanilla-reference-jar.sh'), str(base)], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env={**os.environ, 'MC_DIR': str(mc)})
    named = result.stdout.strip().splitlines()
    if result.returncode == 6:
        raise SystemExit(f'[M34] FATAL: the vanilla reference was refused: {result.stderr.strip()[-500:]}')
    if result.returncode or not named:
        raise SystemExit(f'[M34] FATAL: no vanilla reference for {base}, and a soak without --nativeReference runs with no '
                         f'FABRIC references: name one with --native-reference (exit {result.returncode}): '
                         f'{result.stderr.strip()[-500:]}')
    return Path(named[-1])


def launch(args):
    if args.seconds < 1 or (not args.control and args.seconds < MIN_SECONDS):
        raise ValueError('release soak requires >=7200 seconds; --control is explicitly non-release')
    if not args.control and args.policy != 'strict':
        raise ValueError('release acceptance always requires strict compatibility policy')
    if args.sessions < (2 if args.control else 3):
        raise ValueError('at least three release sessions (two control sessions) are required')
    kernel, staged, fixture = Path(args.kernel).resolve(), Path(args.staged).resolve(), Path(args.fixture).resolve()
    source_before = source_record(kernel.parent, set())
    if not args.control and source_before['dirty']:
        raise ValueError('release soak requires committed source changes')
    mc = Path(args.minecraft).resolve()
    nonce = str(uuid.uuid4())
    run = kernel / 'build' / 'soak-runs' / nonce
    run.mkdir(parents=True, exist_ok=False)
    frozen, evidence = run / 'snapshot', run / 'evidence'
    frozen.mkdir(); evidence.mkdir()
    (run / '.forbric-soak-owner').write_text(nonce + '\n')
    world_source = Path(args.world_source).resolve() if args.world_source else fixture / 'saves' / 'ForbricTest'
    if not (world_source / 'level.dat').is_file():
        raise ValueError(f'no existing test world at {world_source}')
    world_name = 'ForbricSoak_' + nonce
    world = run / 'saves' / world_name
    world.parent.mkdir()
    source_world_hashes = copy_tree(world_source, world)
    (world / '.forbric-soak-world').write_text(nonce + '\n')
    weather = saved_weather(world)
    print('[M34] ' + describe_saved_weather(weather), flush=True)
    configuration = {}
    for name in ('config', 'resourcepacks', 'shaderpacks'):
        if (fixture / name).is_dir():
            configuration[name] = copy_tree(fixture / name, run / name)
    for name in ('options.txt', 'servers.dat'):
        if (fixture / name).is_file():
            if (fixture / name).is_symlink(): raise ValueError('symlink input ' + name)
            shutil.copy2(fixture / name, run / name)
            configuration[name] = digest(run / name)
    records = []
    (run / 'mods').mkdir()
    mods_source = fixture / 'mods'
    if not mods_source.is_dir(): raise ValueError('fixture mods directory missing')
    for path in sorted(mods_source.iterdir()):
        if path.is_file() and path.suffix == '.jar': copy_frozen(path, run / 'mods' / path.name, records)
    if not any((run / 'mods').iterdir()): raise ValueError('soak requires the actual mod pack, not an empty client')
    native_roots = native_retention_roots([record['sha256'] for record in records])
    boot = copy_frozen(kernel / 'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar', frozen / 'boot.jar', records)
    runtime = copy_frozen(kernel / 'build/libs/forbric-kernel-runtime-0.1.0-SNAPSHOT.jar', frozen / 'runtime.jar', records)
    with zipfile.ZipFile(boot) as jar:
        embedded = [name for name in jar.namelist() if name.endswith('forbric-kernel-runtime.jar')]
        if len(embedded) != 1 or hashlib.sha256(jar.read(embedded[0])).hexdigest() != digest(runtime):
            raise ValueError('boot embedded runtime is missing or differs from frozen runtime jar')
    # The game base (NeoForge's patched game) and its one carrier, as launch-kernel-client.sh launches them.
    base_source = Path(args.base) if args.base else staged / 'neoforge-patched/patched-mc-neoforge-26.2.jar'
    base = copy_frozen(base_source, frozen / 'base.jar', records)
    neo = copy_frozen(Path(args.neo) if args.neo else staged / 'neoforge-runtime/neoforge-runtime.jar', frozen / 'neo.jar', records)
    # The native reproduction of every reviewed root in this pack, which only a release soak leans on: used when it
    # describes the jars just frozen, measured again here when it is absent or stale (ensure_native_evidence).
    launched = {'kernel jar': digest(boot), 'game base': digest(base), 'NeoForge carrier': digest(neo)}
    native_evidence = [] if args.control else [
        ensure_native_evidence(kernel, entry, launched, evidence / f'native-retention-{index}.log')
        for index, entry in enumerate(native_roots)]
    # The kernel's FABRIC native references (--nativeReference), handed over as on every other launch path and frozen
    # with the rest: the sidecar recording this base. The base embeds no index, so there is no soak without one
    # (vanilla_reference stops the run when it can provide none).
    reference_source = (Path(args.native_reference) if args.native_reference
                        else vanilla_reference(kernel, base_source, mc))
    native_reference = copy_frozen(reference_source, frozen / 'vanilla-reference.jar', records)
    parent = [boot]
    cp_raw = Path(args.boot_classpath).read_text().strip().split(os.pathsep)
    for index, item in enumerate(cp_raw):
        path = Path(item)
        if path.is_dir() and path.resolve().is_relative_to(kernel / 'build'):
            # main output is already in the boot jar; adding live build directories would unfreeze the run.
            continue
        if not path.is_file() or path.suffix != '.jar': raise ValueError(f'unfreezable boot classpath: {path}')
        parent.append(copy_frozen(path, frozen / 'boot-libs' / f'{index:03d}-{path.name}', records))
    metadata = copy_frozen(mc / 'versions/26.2/26.2.json', frozen / '26.2.json', records)
    version = json.loads(metadata.read_text())
    game_libraries = []
    for index, lib in enumerate(version['libraries']):
        parts = lib['name'].split(':')
        artifact = lib.get('downloads', {}).get('artifact', {}).get('path')
        if artifact is None and len(parts) >= 3:
            group, name, ver = parts[:3]
            classifier = '-' + parts[3] if len(parts) > 3 else ''
            artifact = f"{group.replace('.', '/')}/{name}/{ver}/{name}-{ver}{classifier}.jar"
        if artifact and (mc / 'libraries' / artifact).is_file():
            source = mc / 'libraries' / artifact
            game_libraries.append(copy_frozen(source, frozen / 'mc-libs' / f'{index:03d}-{source.name}', records))
    parent += game_libraries
    native_source = Path(args.natives).resolve() if args.natives else mc / 'versions/26.2/26.2-natives'
    native_hashes = inventory(native_source)
    for name in native_hashes: copy_frozen(native_source / name, frozen / 'natives' / name, records)
    with zipfile.ZipFile(base) as jar, zipfile.ZipFile(frozen / 'game-metadata.jar', 'w') as output:
        output.writestr('version.json', jar.read('version.json'))
    parent.append(frozen / 'game-metadata.jar')
    records.append({'source': 'generated from frozen base.jar/version.json', 'snapshot': str(parent[-1]), 'sha256': digest(parent[-1]), 'size': parent[-1].stat().st_size})
    asset_index = mc / 'assets/indexes' / (version['assetIndex']['id'] + '.json')
    copy_frozen(asset_index, frozen / 'asset-index.json', records)
    # The launcher never regenerates these binaries after this manifest has been sealed.
    manifest = {'nonce': nonce, 'control': args.control, 'seconds': args.seconds, 'minSessions': args.sessions,
                'createdUtc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                'run': str(run), 'fixture': str(fixture), 'worldSource': str(world_source), 'sourceWorld': source_world_hashes,
                'sourceWorldWeather': weather,
                'initialConfiguration': configuration, 'artifacts': records, 'policy': args.policy,
                'assetsReadOnlySource': str(mc / 'assets'), 'source': source_before,
                'nativeRetentionRoots': native_roots, 'nativeRetentionEvidence': native_evidence}
    dump(evidence / 'manifest.json', manifest)
    (evidence / 'manifest.sha256').write_text(digest(evidence / 'manifest.json') + '\n')
    java = args.java
    properties = {'forbric.clientSoak': 'true', 'forbric.soakNonce': nonce, 'forbric.soakRun': str(run),
                  'forbric.soak.seconds': str(args.seconds), 'forbric.soakControl': str(args.control).lower(),
                  'forbric.soak.dwellTicks': str(args.dwell_ticks), 'forbric.soak.sessions': str(args.sessions),
                  'forbric.soak.routes': '2', 'forbric.soak.betweenSeconds': str(args.between_seconds),
                  'forbric.soak.settleSeconds': str(args.settle_seconds), 'forbric.compatibilityPolicy': args.policy,
                  'forbric.dependencyDialog': 'off', 'java.library.path': str(frozen / 'natives')}
    if native_roots: properties['forbric.soak.nativeRetentionRoots'] = ','.join(entry['root'] for entry in native_roots)
    if getattr(args, 'heap_dump_on_retention', False) or not args.control: properties['forbric.soak.heapDumpOnRetention'] = 'true'
    command = [java] + (['-XstartOnFirstThread'] if platform.system() == 'Darwin' else []) + ['-Xmx' + args.heap]
    command += [f'-D{key}={value}' for key, value in properties.items()]
    command += ['-cp', os.pathsep.join(map(str, parent)), 'net.forbric.kernel.boot.KernelClientLaunch', '--gameJar', str(base),
                '--runtimeJar', str(neo), '--libraryPath', os.pathsep.join(map(str, game_libraries)),
                # Its own flag, never a second --gameJar: the resources-only sidecar is not a game.
                *['--nativeReference', str(native_reference)],
                '--', '--version', version['id'] + '-forbric-kernel', '--gameDir', str(run), '--assetsDir', str(mc / 'assets'),
                '--assetIndex', version['assetIndex']['id'], '--accessToken', '0', '--username', 'ForbricKernel',
                '--uuid', '00000000000000000000000000000000', '--userType', 'legacy', '--versionType', 'release',
                '--quickPlayPath', str(run / 'quickPlay/log.json'), '--quickPlaySingleplayer', world_name]
    dump(evidence / 'command.json', command)
    print(f'[M34] {"CONTROL ONLY" if args.control else "RELEASE SOAK"} nonce={nonce} evidence={evidence}', flush=True)
    began = time.monotonic()
    started_ns = time.time_ns()
    caught = evidence / 'caught.tsv'
    caught.touch()
    outer_caught = os.environ.get('FORBRIC_CAUGHT_FILE')
    try:
        with (evidence / 'client.log').open('w') as log:
            process = subprocess.Popen(command, cwd=run, stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
                                       env=client_environment(caught))
            try:
                dump(evidence / 'process.json', {'pid': process.pid, 'nonce': nonce})
                code = wait_for_client(process, evidence / 'client.log', args.timeout or max(args.seconds * 2, args.seconds + 1800))
            except KeyboardInterrupt:
                # Ctrl-C, or SIGTERM/SIGHUP (main's stop_signals_interrupt): the client is in its own session and is
                # stopped here; the run is then recorded as the failed run it is. A further stop signal does not
                # raise again (that block interrupts once), so the thirty seconds and the kill after them always run.
                signal_owned_group(process)
                try: process.wait(timeout=30)
                except subprocess.TimeoutExpired: signal_owned_group(process, force=True); process.wait()
                code = -1
    finally:
        caught_record = forward_caught(caught, outer_caught)
    print(f"[M34] the client's kernel caught {len(caught_record['failureSites'])} failure site(s) and carried on: {caught}", flush=True)
    elapsed = time.monotonic() - began
    validation = {'status': 'FAIL', 'releaseAccepted': False, 'nonce': nonce, 'pid': process.pid, 'exitCode': code, 'processSeconds': elapsed,
                  'caught': caught_record}
    try:
        if code != 0:
            stated = evidence / 'controller-result.json'
            reason = json.loads(stated.read_text()).get('failure') if stated.is_file() else None
            raise ValueError(f'client exited abnormally: {code}' + (f' ({reason})' if reason else ''))
        if source_record(kernel.parent, set()) != source_before: raise ValueError('source changed during the measured run')
        if any(digest(item['snapshot']) != item['sha256'] for item in records): raise ValueError('frozen artifacts changed during run')
        if inventory(world_source) != source_world_hashes: raise ValueError('source world changed during soak; cannot attest untouched original')
        if not args.control:
            validation['compatibility'] = validate_compatibility(run / '.forbric-kernel/compatibility-report.json', started_ns)
        rows = [json.loads(line) for line in (evidence / 'telemetry.jsonl').read_text().splitlines()]
        result = json.loads((evidence / 'controller-result.json').read_text())
        if rows[0]['pid'] != process.pid: raise ValueError('telemetry is not from the launched child JVM')
        heap_dump = evidence / 'retained-after-native-release.hprof'
        mod_owned = None
        if heap_dump.is_file() and any(server['alive'] for server in result.get('oldServersAfterNativeRelease', result['oldServers'])):
            mod_owned = mod_owned_retention(java, heap_dump, run / 'mods', [base, neo], evidence / 'retention-paths.txt')
            validation['modOwnedAnalysis'] = mod_owned
        validation.update(validate_trace(rows, result, nonce, args.seconds, args.control, elapsed, args.sessions, native_roots, mod_owned))
    except RetentionReview as review:
        validation.update(review.activity)
        validation.update(status='REVIEW_REQUIRED', releaseAccepted=False, detail=str(review))
    except Exception as failure:
        validation['detail'] = str(failure)
    validation['finalWorld'] = inventory(world)
    validation['artifactHashesAfter'] = {item['snapshot']: digest(item['snapshot']) for item in records}
    validation['telemetrySha256'] = digest(evidence / 'telemetry.jsonl') if (evidence / 'telemetry.jsonl').is_file() else None
    # Whatever the verdict: what the rows say of the player and the weather (conditions), so a FAIL can be explained.
    validation['conditions'] = conditions(read_telemetry(evidence / 'telemetry.jsonl')) if (evidence / 'telemetry.jsonl').is_file() else None
    if validation['conditions']:
        told = validation['conditions']
        print(f"[M34] player {'/'.join(told['gameTypes']) or 'never present'}; rain in {told['rainingRows']} of {told['rows']} "
              f"row(s), on the player in {told['rainingAtPlayerRows']} (not known in {told['rainingAtPlayerUnknownRows']}), first on the player at "
              f"{(told['firstRainingAtPlayer'] or {}).get('utc', 'no row')}; {len(told['joinsInRain'])} join(s) into rain", flush=True)
    dump(evidence / 'acceptance.json', validation)
    latest = kernel / 'build/verification/m34-soak'
    latest.mkdir(parents=True, exist_ok=True)
    dump(latest / ('last-control.json' if args.control else 'last-release.json'), {'nonce': nonce, 'evidence': str(evidence), 'acceptance': validation})
    print(f"[M34] {validation['status']} releaseAccepted={validation['releaseAccepted']} evidence={evidence}", flush=True)
    return 0 if validation['status'] in ('CONTROL_PASS', 'RELEASE_PASS') else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kernel', required=True)
    parser.add_argument('--staged', required=True)
    parser.add_argument('--fixture', required=True)
    parser.add_argument('--boot-classpath', required=True)
    parser.add_argument('--minecraft', default=str(Path.home() / 'Library/Application Support/minecraft'))
    parser.add_argument('--world-source')
    parser.add_argument('--base', help="the game jar, NeoForge's patched game (default: the stage's neoforge-patched/)")
    parser.add_argument('--neo'); parser.add_argument('--natives')
    parser.add_argument('--native-reference', help='the vanilla-reference sidecar for the base (default: run/vanilla-reference-jar.sh)')
    parser.add_argument('--seconds', type=int, default=7200)
    parser.add_argument('--control', action='store_true')
    parser.add_argument('--sessions', type=int, default=3)
    parser.add_argument('--dwell-ticks', type=int, default=600)
    parser.add_argument('--between-seconds', type=int, default=10)
    parser.add_argument('--settle-seconds', type=int, default=60)
    parser.add_argument('--timeout', type=int)
    parser.add_argument('--policy', choices=('strict', 'continue'), default='strict')
    parser.add_argument('--heap', default='4G')
    parser.add_argument('--heap-dump-on-retention', action='store_true',
                        help='write a live heap dump after the native cut when a retired server is still reachable')
    parser.add_argument('--java', default='java')
    args = parser.parse_args()
    with stop_signals_interrupt():
        return launch(args)


if __name__ == '__main__':
    try: sys.exit(main())
    except KeyboardInterrupt as stopped:
        # Outside the client's own wait (which stops the client and records the interruption as a failed run): while
        # freezing inputs, measuring the native evidence, or validating. Said in the gate's words instead of a
        # traceback; 130 as a shell reports it.
        print(f'[M34] FAIL stopped outside the client\'s run ({stopped or "interrupted"}); nothing was accepted', file=sys.stderr)
        sys.exit(130)
    except Exception as failure:
        print('[M34] FAIL ' + str(failure), file=sys.stderr)
        sys.exit(1)
