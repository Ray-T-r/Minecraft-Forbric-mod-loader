#!/usr/bin/env python3
"""Stage and observe one Windows compatibility run using the configured transports."""
import argparse
import datetime
import hashlib
import json
import ntpath
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import zipfile

HERE = Path(__file__).resolve().parent
KERNEL = HERE.parent.parent
STAGED = Path(os.environ.get('FORBRIC_OLD', KERNEL.parent / 'forbric-loader')) / 'run'
sys.path.insert(0, str(HERE / 'win'))
from common import LANG_LINE, SWEEP_RECORD, client_language, safe_filename

# Explicit children only. Never delete the instance directory or a launcher-owned file.
#
# replay_recordings is a previous run's output, never an input, and a killed client leaves its recording unfinished.
# At startup ReplayMod 2.6.27 (ReplayFilesService) moves recording/ into the replay folder and opens RestoreReplayGui
# over the title screen for every unfinished recording it finds, and quick-play waits behind that screen: in a Mac run
# behind sweep90-win-r7c's diagnosis it logged `Found partially saved replay, offering recovery` for an earlier run's
# recording as the title screen came up, and the world started loading only after `Attempting recovery`, 72 s later.
CLEAN = ('config', 'mods', 'saves', 'logs', '.forbric-kernel', '.mixin.out', '.fabric',
         'crash-reports', 'screenshots', 'server-gen', 'quickPlay', 'resourcepacks',
         'defaultconfigs', '.cache', '.physics_mod_cache', 'replay_recordings', 'client-console.log',
         'server-console.log', 'bisect-console.log')
ARTIFACTS = {
    'net.forbric:forbric-kernel': KERNEL / 'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar',
    'net.forbric:patched-mc-merged': STAGED / 'merged-base/patched-mc-merged-26.2.jar',
    'net.forbric:forge-runtime': (STAGED / 'merged-base/forge-runtime-interop.jar'
                                if (STAGED / 'merged-base/forge-runtime-interop.jar').is_file()
                                else STAGED / 'forge-runtime/forge-runtime.jar'),
    'net.forbric:neoforge-runtime': STAGED / 'neoforge-runtime/neoforge-runtime.jar',
}


def ps(value):
    return "'" + str(value).replace("'", "''") + "'"


def transport(function, *arguments):
    result = subprocess.run(['bash', '-c', '. "$1"; shift; "$@"', 'compat',
                             str(HERE / 'lib-compat.sh'), function, *map(str, arguments)],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=2 * int(os.environ.get('COMPAT_CALL_TIMEOUT', '240')) + 10)
    if result.returncode:
        raise RuntimeError(f'{function} failed ({result.returncode}): {result.stdout}')
    return result.stdout.strip()


def remote(command):
    return transport('remote_ps', command)


def put(local, target):
    transport('remote_put', local, target)


def get(source, local):
    local = Path(local)
    local.parent.mkdir(parents=True, exist_ok=True)
    transport('remote_get', source, local)
    if not local.is_file():
        raise RuntimeError('download did not produce ' + str(local))


def stop_command(instance):
    # Only PIDs recorded by these drivers/gates, never a process-name kill. The recorded PIDs include the client and
    # bisect drivers themselves, so the kill also skips their own restore of the player's options.txt: the stop notes
    # before its kill whether the sweep that owns the file is alive, and does that restore itself once every killed
    # process has let go of the file (sweep_record_commands).
    files = [ntpath.join(instance, name) for name in ('.forbric-sweep.pid', '.forbric-gate.pid')]
    files.append(ntpath.join(instance, 'server-gen', '.forbric-gate.pid'))
    before, after = sweep_record_commands(instance)
    return (before +
            "foreach ($file in @(" + ','.join(map(ps, files)) + ")) { "
            "if (Test-Path -LiteralPath $file) { foreach ($line in (Get-Content -LiteralPath $file)) { "
            "if ($line -match '^\\d+$' -and [int]$line -gt 0) { "
            "$owned = Get-Process -Id ([int]$line) -ErrorAction SilentlyContinue; "
            "if ($owned) { & taskkill /T /F /PID $line | Out-Null; "
            "if ($LASTEXITCODE -ne 0 -and (Get-Process -Id ([int]$line) -ErrorAction SilentlyContinue)) "
            "{ throw ('could not stop recorded PID ' + $line) }; "
            "Wait-Process -Id ([int]$line) -Timeout 10 -ErrorAction SilentlyContinue } } }; "
            "Remove-Item -LiteralPath $file -Force } }; " + after)


def sweep_record_commands(instance):
    """The stop's part in win/common.py's SWEEP_RECORD, as PowerShell to run before its kill and after it.

    Before the kill it reads the record and notes whether the process that wrote it is alive: its pid together with
    its start time, the identity observe_command uses, so a pid Windows has handed on after a reboot is not taken for
    it. After the kill:
      - the writer was alive and this stop killed it: its own finally never ran, so the stop does what it would have,
        and the player's file goes back whole from the record, or the one the client wrote goes when the player had
        none. The lang line alone would leave Minecraft's own rewrite behind, startedCleanly:false above all.
      - the writer was already gone before the stop (a reboot): only the lang line goes back, and only while it still
        names the sweep's language, since the player may have changed settings since. A file the client created where
        the player had none stays.
      - the writer still runs: it is not one this stop killed, and its own finally restores the file.
    A record nobody can read is acted on by nobody: the stop kills first, then fails naming the file to delete.
    Python twin: note_sweep_writer / restore_after_stop, which the tests run; PowerShell cannot run here. Bytes are
    read as Latin-1, as the record's strings are, so every byte survives the round trip."""
    options, record, temporary = (ps(ntpath.join(instance, name))
                                  for name in ('options.txt', SWEEP_RECORD, 'options.txt.forbric-tmp'))
    lang_line = "'(?m)" + LANG_LINE.pattern.decode('ascii') + "'"
    path = ntpath.join(instance, SWEEP_RECORD)
    unreadable = ps(f'{path} is not a sweep record, so nothing acted on it and options.txt was left as it is; '
                    f'check options.txt (its lang: line above all), then delete {path}')
    # One step, like os.replace on the Python side: Move-Item -Force onto an existing file deletes it first, and a kill
    # in between would leave no options.txt. A plain $null reaches Replace as an empty backup name, which it refuses.
    # Replace needs a file to replace; one the player removed meanwhile gets a plain move.
    replace = (f"if (Test-Path -LiteralPath {options}) {{ [IO.File]::Replace({temporary}, {options}, [NullString]::Value) }} "
               f"else {{ [IO.File]::Move({temporary}, {options}) }}")
    functions = (
        "function Read-SweepRecord { try { "
        f"$r = Get-Content -Raw -LiteralPath {record} | ConvertFrom-Json; "
        "if (-not (@('lang', 'was', 'absent', 'pid', 'started', 'original') "
        "| Where-Object { $r.PSObject.Properties.Name -notcontains $_ }) "
        "-and ($null -eq $r.lang -or $r.lang -is [string]) -and ($null -eq $r.was -or $r.was -is [string]) "
        "-and $r.absent -is [bool] -and ($r.pid -is [int] -or $r.pid -is [long]) -and $r.pid -ge 0 "
        "-and ($r.started -is [int] -or $r.started -is [long]) -and $r.started -ge 0 "
        "-and ($r.absent -or $null -ne [Convert]::FromBase64String($r.original))) { return $r } } catch { }; "
        "return $null }; "
        "function Test-SweepWriter($r) { try { $p = Get-Process -Id ([int]$r.pid) -ErrorAction SilentlyContinue; "
        "return [bool]($p -and $p.StartTime.ToUniversalTime().Ticks -eq [long]$r.started) } catch { return $false } }; ")
    before = (functions +
              "$sweepWriter = $null; $sweepUnreadable = $false; "
              f"if (Test-Path -LiteralPath {record}) {{ $r = Read-SweepRecord; "
              "if ($null -eq $r) { $sweepUnreadable = $true } "
              "elseif (Test-SweepWriter $r) { $sweepWriter = [string]$r.pid + ':' + [string]$r.started; "
              "$sweepWriterPid = [int]$r.pid } }; ")
    # The kill loop waits only for the PIDs it finds alive, and taskkill /T has already ended the driver as a child of
    # its job: wait for the writer itself, so a driver still being torn down is not taken for one this stop missed.
    after = (f"if ($sweepUnreadable) {{ throw {unreadable} }}; "
             "if ($sweepWriter) { Wait-Process -Id $sweepWriterPid -Timeout 10 -ErrorAction SilentlyContinue }; "
             f"if (Test-Path -LiteralPath {record}) {{ $r = Read-SweepRecord; "
             f"if ($null -eq $r) {{ throw {unreadable} }}; "
             "if (-not (Test-SweepWriter $r)) { "
             "if ($sweepWriter -eq ([string]$r.pid + ':' + [string]$r.started)) { "
             f"if ($r.absent) {{ if (Test-Path -LiteralPath {options}) {{ Remove-Item -LiteralPath {options} -Force }} }} "
             f"else {{ [IO.File]::WriteAllBytes({temporary}, [Convert]::FromBase64String($r.original)); {replace} }} }} "
             f"elseif (-not $r.absent -and $null -ne $r.lang -and (Test-Path -LiteralPath {options})) {{ "
             "$latin1 = [Text.Encoding]::GetEncoding(28591); "
             f"$text = $latin1.GetString([IO.File]::ReadAllBytes({options})); "
             f"$langs = @([regex]::Matches($text, {lang_line}) | ForEach-Object {{ $_.Groups[1].Value }}); "
             "if ($langs.Count -gt 0 -and @($langs | Where-Object { $_ -cne $r.lang }).Count -eq 0) { "
             "if ($null -eq $r.was) { $text = [regex]::Replace($text, '(?m)^lang:[^\\r\\n]*(\\r?\\n)?', '') } "
             "else { $text = [regex]::Replace($text, '(?m)^lang:[^\\r\\n]*', ('lang:' + $r.was).Replace('$', '$$')) }; "
             f"[IO.File]::WriteAllBytes({temporary}, $latin1.GetBytes($text)); {replace} }} }}; "
             f"Remove-Item -LiteralPath {record} -Force -ErrorAction SilentlyContinue }} }}")
    return before, after


def clean_command(instance):
    return '; '.join("if (Test-Path -LiteralPath " + ps(ntpath.join(instance, child)) +
                     ') { Remove-Item -LiteralPath ' + ps(ntpath.join(instance, child)) +
                     ' -Force -Recurse }' for child in CLEAN)


def resolve_artifacts(profile, overrides):
    result = dict(ARTIFACTS)
    for item in overrides:
        coordinate, local = item.split('=', 1)
        if coordinate not in result:
            raise ValueError('unsupported artifact: ' + coordinate)
        result[coordinate] = Path(local).resolve()
    entries = {}
    for entry in profile.get('libraries', []):
        coordinate = ':'.join(entry['name'].split(':')[:2])
        if coordinate in result:
            path = entry.get('downloads', {}).get('artifact', {}).get('path')
            if not path or ntpath.isabs(path) or '..' in Path(path.replace('\\', '/')).parts:
                raise ValueError('unsafe or missing library path: ' + coordinate)
            if coordinate in entries:
                raise ValueError('duplicate artifact: ' + coordinate)
            entries[coordinate] = (result[coordinate], path)
    if entries.keys() != result.keys():
        raise ValueError('profile is missing artifacts: ' + ', '.join(result.keys() - entries.keys()))
    return entries


def mod_files(directory):
    files, seen = [], set()
    for jar in sorted(directory.glob('*.jar')):
        name = safe_filename(jar.name)
        if name.casefold() in seen:
            raise ValueError('sanitized filename collision: ' + name)
        seen.add(name.casefold())
        files.append((jar, name))
    if not files:
        raise ValueError('mod directory has no jars: ' + str(directory))
    return files


# The long process owns its status file. Polling never starts a replacement job. It also owns its two log files:
# Start-Process with -RedirectStandard* creates the child with inherited handles, so the job held the remote shell's
# own output pipe and the start command could not return until the game exited — past the shell server's 300 s
# limit for every client run. Started without redirection, nothing of the shell is inherited.
JOB = '''import json, os, pathlib, subprocess, sys, time, traceback
from common import own_driver
status, instance, out_log, err_log, *command = sys.argv[1:]
sys.stdout = open(out_log, 'w', encoding='utf-8', buffering=1)
sys.stderr = open(err_log, 'w', encoding='utf-8', buffering=1)
path = pathlib.Path(status)
started_ns = time.time_ns()
def publish(state, **fields):
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(dict(state=state, pid=os.getpid(), started_ns=started_ns, **fields)), encoding='utf-8')
    temp.replace(path)
with own_driver(dict(pid_file=str(pathlib.Path(instance) / '.forbric-sweep.pid'))):
    publish('running')
    try:
        result = subprocess.run([sys.executable, *command], stdin=subprocess.DEVNULL, stdout=sys.stdout, stderr=sys.stderr)
        publish('done', returncode=result.returncode)
    except BaseException:
        publish('done', returncode=2, error=traceback.format_exc())
'''


class NotOurJob(RuntimeError):
    """The status file was published by a process this run did not start.

    Never transport flakiness, so it is never retried. The one way it happens is a launcher shim: the command
    named by --python re-executes a different interpreter, the driver publishes THAT process's pid, and the pid
    this run is allowed to stop belongs to a wrapper that has already exited. Retrying it burned a whole 97-jar
    sweep and reported a server test that had returned 0 as a FAIL.
    """


def observe_command(pid, start_ticks, status):
    # A status file alone is never evidence that a job is still alive. StartTime also rejects PID reuse.
    return (f'$process = Get-Process -Id {pid} -ErrorAction SilentlyContinue; '
            f'$alive = $null -ne $process -and $process.StartTime.ToUniversalTime().Ticks -eq {start_ticks}; '
            '$result = $null; '
            f'if (Test-Path -LiteralPath {ps(status)}) {{ $result = Get-Content -Raw -LiteralPath {ps(status)} | ConvertFrom-Json }}; '
            '@{alive=[bool]$alive; result=$result} | ConvertTo-Json -Depth 8 -Compress')


def run_job(args, stage, remote_tools, output, driver, extra=()):
    run_dir = ntpath.join(args.instance, '.forbric-compat', args.label)
    status = ntpath.join(run_dir, stage + '-status.json')
    command = [ntpath.join(remote_tools, 'job.py'), status, args.instance,
               ntpath.join(run_dir, stage + '.log'), ntpath.join(run_dir, stage + '-stderr.log'),
               ntpath.join(remote_tools, driver), '--mc', args.mc, '--version', args.version,
               '--instance', args.instance, '--world', args.world]
    if getattr(args, 'java', None):
        command += ['--java', args.java]
    command += ['--jvm=' + value for value in getattr(args, 'jvm', [])]
    command += list(extra)
    argument_line = subprocess.list2cmdline(command)
    start = (f'Remove-Item -LiteralPath {ps(status)} -Force -ErrorAction SilentlyContinue; '
             f'$job = Start-Process -FilePath {ps(args.python)} -ArgumentList {ps(argument_line)} '
             f'-WorkingDirectory {ps(remote_tools)} -PassThru -WindowStyle Hidden; '
             "Write-Output ('FORBRIC_PID=' + $job.Id); "
             "Write-Output ('FORBRIC_STARTED=' + $job.StartTime.ToUniversalTime().Ticks)")
    handle = dict(status=status, driver=driver)
    result_file = output / (stage + '-result.json')
    try:
        result = remote(start)
        match = re.search(r'^FORBRIC_PID=(\d+)$', result, re.M)
        birth = re.search(r'^FORBRIC_STARTED=(\d+)$', result, re.M)
        if match:
            handle['pid'] = int(match[1])
        if birth:
            handle['start_ticks'] = int(birth[1])
        (output / (stage + '-handle.json')).write_text(json.dumps(handle, indent=2))
        if not match or not birth:
            raise RuntimeError('Start-Process returned no verifiable process handle: ' + result)
        pid, start_ticks = handle['pid'], handle['start_ticks']
        print(f'{stage}: started owned job {pid}', flush=True)
        deadline, misses = time.monotonic() + args.timeout, 0
        while time.monotonic() < deadline:
            try:
                observation = json.loads(remote(observe_command(pid, start_ticks, status)))
                if not isinstance(observation.get('alive'), bool):
                    raise ValueError('process liveness missing from observation')
                state = observation.get('result')
                if state is not None and not isinstance(state, dict):
                    raise ValueError('status is not an object: ' + str(state))
                if state is not None and state.get('pid') != pid:
                    raise NotOurJob(
                        f'{args.python} started process {pid}, but the job published pid {state.get("pid")} — '
                        'that command is a launcher shim, not an interpreter, so this run owns a wrapper it '
                        'cannot stop and cannot vouch for the job that did the work. Point FORBRIC_PYTHON at '
                        'the real interpreter (a Python Manager shim names it in <command>.__target__) and run '
                        f'again; the job itself published {state}')
                misses = 0
            except NotOurJob:
                raise
            except (RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
                misses += 1
                print(f'{stage}: observation failed; retaining job {pid}: {error}', flush=True)
                if misses >= 3:
                    raise RuntimeError(f'cannot observe job {pid}; handle saved, do not restart it') from error
                time.sleep(10)
                continue
            if state and state.get('state') == 'done' and isinstance(state.get('returncode'), int):
                result_file.write_text(json.dumps(state, indent=2))
                return state['returncode']
            if not observation['alive']:
                raise RuntimeError(f'owned job {pid} ended before publishing a result; status={state}')
            if state and state.get('state') != 'running':
                raise RuntimeError('unrecognized job status: ' + str(state))
            time.sleep(10)
        raise RuntimeError(f'job {pid} is still unproven after {args.timeout}s; inspect its saved handle')
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        result_file.write_text(json.dumps(dict(state='unproven', returncode=2, error=str(error), **handle), indent=2))
        raise


def collect(args, remote_tools, output):
    remote(f'New-Item -ItemType Directory -Force -Path {ps(remote_tools)} | Out-Null')
    script = output / 'collect.py'
    script.write_text(r'''import json, pathlib, sys, zipfile
root, tools, target = map(pathlib.Path, sys.argv[1:])
metadata = []
with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as z:
    for base in (root, tools):
        for p in base.rglob('*'):
            if not p.is_file(): continue
            rel = p.relative_to(base)
            if p.suffix.lower() in ('.log', '.png', '.mca') or p.name in ('load-report.txt', 'compatibility-report.json') or p.name.endswith('-status.json'):
                if base == root and any(x in ('mods', 'mods-all', 'quarantine', '.forbric-compat') for x in rel.parts): continue
                name = str(pathlib.Path('instance' if base == root else 'driver') / rel).replace('\\', '/')
                info = p.stat()
                z.write(p, name)
                metadata.append(dict(name=name, mtime_ns=info.st_mtime_ns, size=info.st_size))
    z.writestr('files.json', json.dumps(metadata))
''')
    remote_script = ntpath.join(remote_tools, 'collect.py')
    archive = ntpath.join(remote_tools, 'evidence.zip')
    put(script, remote_script)
    remote(f'& {ps(args.python)} {ps(remote_script)} {ps(args.instance)} {ps(remote_tools)} {ps(archive)}; '
           "if ($LASTEXITCODE -ne 0) { throw 'evidence collection failed' }")
    get(archive, output / 'evidence.zip')
    target = output / 'artifacts'
    with zipfile.ZipFile(output / 'evidence.zip') as bundle:
        for name in bundle.namelist():
            dest = (target / name).resolve()
            if not dest.is_relative_to(target.resolve()):
                raise ValueError('unsafe evidence archive entry: ' + name)
        bundle.extractall(target)
    return target


def check(command, destination):
    with destination.open('w') as output:
        try:
            result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=240)
            return result.returncode == 0
        except (OSError, subprocess.SubprocessError) as error:
            output.write('FAIL diagnostic could not run: ' + str(error) + '\n')
            return False


# Where each side of a sweep leaves its machine report, inside the collected evidence.
COMPATIBILITY_REPORTS = (('server', 'instance/server-gen/.forbric-kernel/compatibility-report.json'),
                         ('client', 'instance/.forbric-kernel/compatibility-report.json'))


def compatibility(output, artifacts, records):
    """Acceptance is strict: each side must leave a report written by this run, decided under STRICT, with no
    confirmed required loss and no unclassified FAILED mod. A player's Continue changes none of that, so a run
    somebody clicked through, a report left over from an earlier run and a missing report all fail alike."""
    lines, accepted = [], True
    for stage, relative in COMPATIBILITY_REPORTS:
        result = output / (stage + '-result.json')
        started = json.loads(result.read_text()).get('started_ns') if result.is_file() else None
        record = next((item for item in records if item['name'] == relative), None)
        path = artifacts / relative
        problem = None
        if record is None or not path.is_file():
            problem = 'missing'
        elif started is None or record['mtime_ns'] <= started:
            problem = 'not written by this run'
        else:
            try:
                data = json.loads(path.read_text(encoding='utf-8'))
            except ValueError:
                data, problem = {}, 'unreadable'
            required = [row for row in data.get('findings', []) if row.get('confidence') == 'CONFIRMED' and row.get('required')]
            if problem:
                pass
            elif data.get('policy') != 'STRICT':
                problem = 'decided under ' + str(data.get('policy')) + ', not STRICT'
            elif required or data.get('confirmedRequired') != len(required):
                problem = f'{len(required)} confirmed required loss(es): ' + ', '.join(
                    str(row.get('modId')) + ':' + str(row.get('id')) for row in required)
            elif any(row.get('status') == 'FAILED' for row in data.get('catalogFailures', [])):
                problem = 'unclassified FAILED mod'
        lines.append(f'{stage}: ' + (problem or 'STRICT, 0 confirmed required losses'))
        accepted = accepted and problem is None
    (output / 'compatibility.txt').write_text('\n'.join(lines) + '\n')
    return accepted


def played_language(args, artifacts):
    """The driver's own `client language ...` line (win/common.py `describe_language`): what the client played and what
    the player's file names. A language decides which assets every mod loads, so a verdict is only comparable with
    another played in the same one, and --client-lang alone says what was asked for, not what ran."""
    log = artifacts / 'driver' / ('bisect.log' if args.bisect else 'client.log')
    text = log.read_text(encoding='utf-8', errors='replace') if log.is_file() else ''
    found = re.search(r'^client language (.+)$', text, re.M)
    return found[1].strip() if found else f'asked for {args.client_lang}; no client driver reported one'


def report(args, output, artifacts, server, client, started, errors=()):
    errors = list(errors)
    logs = list((artifacts / 'instance').glob('bisect-console.log' if args.bisect else 'client-console.log'))
    log = logs[0] if logs else artifacts / 'missing.log'
    assertions = check(['bash', str(HERE / 'assert.sh'), str(log)], output / 'assertions.txt')
    metadata = artifacts / 'files.json'
    records = json.loads(metadata.read_text()) if metadata.is_file() else []
    client_result = output / ('bisect-result.json' if args.bisect else 'client-result.json')
    state = json.loads(client_result.read_text()) if client_result.is_file() else {}
    started_ns = state.get('started_ns')
    shots = sorted((item for item in records if item['name'].startswith('instance/screenshots/')
                    and item['name'].lower().endswith('.png') and item['size'] > 0
                    and started_ns is not None and item['mtime_ns'] > started_ns),
                   key=lambda item: (item['mtime_ns'], item['name']))
    frame = bool(shots) and check([sys.executable, str(HERE / 'frame-verdict.py'),
                                  str(artifacts / shots[-1]['name'])], output / 'frame.txt')
    if not shots:
        (output / 'frame.txt').write_text('verdict=UNSUPPORTED reason=no-fresh-screenshot\n')
    regions = artifacts / 'instance/server-gen' / args.world / 'dimensions/minecraft/overworld/region'
    # Ores, not dungeons, are what this world can prove. A sweep's server never has a player on it, so it
    # generates only the spawn area: every run kept in build/compat/ writes 529 chunk entries of which exactly
    # 25 carry terrain, and that is a property of the fixed seed, not of the build. Dungeons are about one per
    # 177 chunks — measured on run/client-merged-pack's real world, 106 of them across 18,749 chunks — so 25
    # chunks expect 0.14 of one, and `dungeons: [1-9]` was a coincidence the sweep could not produce. It had
    # therefore been red in EVERY run ever kept here, which is worse than useless: the overall verdict was FAIL
    # no matter what the client did, so a genuine client failure and a clean run reported the same word.
    #
    # Ore is the same claim without the coincidence. It is placed in the feature stage, exactly like a dungeon,
    # so a world that reached it reached the stage the check exists to prove — and 11 coal, 10 iron and 18
    # copper chunks out of those 25 is a floor, not a sample. The dungeon counts stay in region.txt as evidence.
    ORES = ('minecraft:coal_ore', 'minecraft:iron_ore', 'minecraft:copper_ore')
    region = check([sys.executable, str(HERE / 'region-probe.py'), str(regions), *ORES, '--dungeons'],
                   output / 'region.txt')
    region_text = (output / 'region.txt').read_text()
    featured = any(re.search(re.escape(ore) + r': [1-9]', region_text) for ore in ORES)
    region = region and bool(re.search(r'unreadable: 0\b', region_text)) and featured
    findings = []
    for path in artifacts.rglob('load-report.txt'):
        # The kernel localizes this report, and the mod name is on a separate line from its reason.
        # Keep the complete text: filtering English status words lost every name, and all Chinese failures.
        findings.append(str(path.relative_to(artifacts)) + '\n' + path.read_text(errors='replace'))
    (output / 'degraded.txt').write_text('\n\n'.join(findings) or 'No load-report.txt was produced.\n')
    strict = None if args.bisect else compatibility(output, artifacts, records)
    passed = client == 0 and frame if args.bisect else server == client == 0 and assertions and frame and region and strict
    passed = passed and not errors
    try:
        commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=KERNEL, text=True, timeout=10).strip()
    except (OSError, subprocess.SubprocessError):
        commit = 'unavailable'
    values = dict(label=args.label, commit=commit, manifest=str(args.manifest or args.mods),
                  version=args.version, started=started, server=server, client=client,
                  language=played_language(args, artifacts),
                  assertions='PASS' if assertions else 'FAIL', frame='DREW' if frame else 'FAIL',
                  region='PASS' if region else 'FAIL', degraded=f'{len(findings)} report(s), see degraded.txt',
                  compatibility='not judged (bisect)' if strict is None else 'PASS' if strict else 'FAIL, see compatibility.txt',
                  verdict='PASS' if passed else 'FAIL', errors='; '.join(map(str, errors)) or 'none')
    (output / 'report.md').write_text((HERE / 'report-template.md').read_text().format(**values))
    print((output / 'report.md').read_text(), flush=True)
    return 0 if passed else 1


def stage_tools(args, output):
    tools_dir = ntpath.join(args.instance, '.forbric-compat', 'tools')
    run_dir = ntpath.join(args.instance, '.forbric-compat', args.label)
    remote(f'New-Item -ItemType Directory -Force -Path {ps(tools_dir)}, {ps(run_dir)} | Out-Null')
    for script in sorted((HERE / 'win').glob('*.py')):
        put(script, ntpath.join(tools_dir, script.name))
    put(HERE / 'frame-verdict.py', ntpath.join(args.instance, '.forbric-compat', 'frame-verdict.py'))
    job = output / 'job.py'
    job.write_text(JOB)
    put(job, ntpath.join(tools_dir, 'job.py'))
    return tools_dir


def finish_run(args, output, remote_tools, server, client, started, errors):
    try:
        artifacts = collect(args, remote_tools, output)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        errors.append('evidence collection failed: ' + str(error))
        artifacts = output / 'artifacts'
        artifacts.mkdir(exist_ok=True)
    (output / 'errors.json').write_text(json.dumps(errors, indent=2))
    return report(args, output, artifacts, server, client, started, errors)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--label', required=True)
    parser.add_argument('--mc', default=os.environ.get('FORBRIC_MC'))
    parser.add_argument('--version', default=os.environ.get('FORBRIC_VERSION'))
    parser.add_argument('--instance', default=os.environ.get('FORBRIC_INSTANCE'))
    parser.add_argument('--world', default=os.environ.get('FORBRIC_WORLD', 'compat-world'))
    parser.add_argument('--python', default=os.environ.get('FORBRIC_PYTHON', 'python'))
    parser.add_argument('--java', default=os.environ.get('FORBRIC_JAVA'))
    parser.add_argument('--jvm', action='append', default=[])
    parser.add_argument('--mods', type=Path)
    parser.add_argument('--manifest', type=Path)
    parser.add_argument('--version-json', type=Path)
    parser.add_argument('--artifact', action='append', default=[])
    parser.add_argument('--output', type=Path)
    parser.add_argument('--timeout', type=int, default=2400)
    parser.add_argument('--dry-run', action='store_true')
    # The pack crosses the transport as one zip by default. Through a relayed tunnel that throttles after tens of
    # megabytes, every later remote call then stalls for minutes; with this, only the manifest crosses and the
    # Windows side fetches each jar from its own URL, verified against the manifest's SHA-1 (win/fetch-mods.py).
    parser.add_argument('--remote-mods', action='store_true')
    # The kernel jar is built from the working tree before it is staged. Without this a run shipped whatever jar
    # build/libs last held while report.md named the current commit: sweep90-win-r5 reported 4f229131 and ran a
    # jar from before 8d0fb2ee, so the very fix it was meant to prove looked like it had failed.
    parser.add_argument('--no-build', action='store_true', help='stage build/libs as it is, without building it')
    # The Windows job starts from the remote shell's environment, not this one, so the client's language crosses as an
    # argument; otherwise only a FORBRIC_LANG exported on the Windows side could change it, invisibly to this run and its
    # report. `player` plays the player's own options.txt as it is.
    parser.add_argument('--client-lang', type=client_language, default=os.environ.get('FORBRIC_LANG', ''),
                        help="language the sweep's client plays (win/common.py sweep_language); default en_us, also "
                             "for an empty FORBRIC_LANG; 'player' plays the player's options.txt as it is")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--bisect', type=Path, metavar='SUBSET_TXT')
    modes.add_argument('--quarantine', metavar='JAR')
    args = parser.parse_args()
    if not args.mc or not args.version:
        parser.error('FORBRIC_MC and FORBRIC_VERSION are required')
    for name in ('label', 'version', 'world'):
        value = getattr(args, name)
        if not value or value in ('.', '..') or safe_filename(value) != value:
            parser.error(name + ' must be a safe single directory name')
    args.mc = ntpath.normpath(args.mc)
    args.instance = ntpath.normpath(args.instance or ntpath.join(args.mc, 'versions', args.version))
    root = ntpath.normcase(args.instance)
    mc = ntpath.normcase(args.mc)
    if (not ntpath.isabs(args.mc) or not ntpath.splitdrive(args.mc)[0] or
            not ntpath.isabs(args.instance) or not ntpath.splitdrive(args.instance)[0] or
            root == ntpath.dirname(root) or mc == root or mc.startswith(root.rstrip('\\') + '\\')):
        parser.error('instance must be an absolute, dedicated instance directory, not the installation or its ancestor')
    if args.timeout <= 0:
        parser.error('--timeout must be positive')
    output = args.output or KERNEL / 'build/compat' / args.label
    remote_tools = ntpath.join(args.instance, '.forbric-compat', args.label)
    if args.quarantine:
        if ntpath.basename(args.quarantine) != args.quarantine or not args.quarantine.lower().endswith('.jar'):
            parser.error('--quarantine requires a jar basename')
        name = safe_filename(args.quarantine)
        command = (f'New-Item -ItemType Directory -Force -Path {ps(ntpath.join(args.instance, "quarantine"))} | Out-Null; '
                   f'Move-Item -LiteralPath {ps(ntpath.join(args.instance, "mods", name))} '
                   f'-Destination {ps(ntpath.join(args.instance, "quarantine", name))}')
        if args.dry_run:
            print('STOP_BY_PID ' + stop_command(args.instance)); print('QUARANTINE ' + command)
        else:
            remote(stop_command(args.instance)); remote(command)
        return 0
    if args.bisect:
        if args.dry_run:
            print('BISECT frame-verdict.py; subset=' + str(args.bisect) + '; --lang ' + args.client_lang); return 0
        if output.exists() and any(output.iterdir()):
            parser.error('output already contains evidence; choose a new --label or --output')
        if not args.bisect.is_file():
            parser.error('subset file does not exist')
        output.mkdir(parents=True, exist_ok=True)
        started = datetime.datetime.now(datetime.timezone.utc).isoformat()
        errors, code = [], -1
        try:
            remote(stop_command(args.instance))
            tools_dir = stage_tools(args, output)
            subset = ntpath.join(remote_tools, 'subset.txt')
            put(args.bisect, subset)
            code = run_job(args, 'bisect', tools_dir, output, 'bisect.py', ['--subset', subset, '--lang', args.client_lang])
        except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
            errors.append(str(error))
        return finish_run(args, output, remote_tools, 'reused world', code, started, errors)
    if args.mods is None:
        parser.error('--mods is required for a new run')
    if args.remote_mods and not args.manifest:
        parser.error('--remote-mods fetches from the manifest, so --manifest is required')
    files = mod_files(args.mods)
    if args.dry_run and not args.version_json:
        parser.error('--dry-run needs --version-json so all four artifact destinations can be verified')
    if not args.dry_run:
        if output.exists() and any(output.iterdir()):
            parser.error('output already contains evidence; choose a new --label or --output')
        if not args.no_build:
            build = subprocess.run([str(KERNEL / 'gradlew'), '--offline', '-q', 'jar'], cwd=KERNEL, text=True,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            if build.returncode:
                parser.error('building the kernel jar failed; nothing was staged:\n' + build.stdout[-4000:])
        output.mkdir(parents=True, exist_ok=True)
        profile_path = output / 'version.json'
        if args.version_json:
            shutil.copy2(args.version_json, profile_path)
        else:
            get(ntpath.join(args.mc, 'versions', args.version, args.version + '.json'), profile_path)
    else:
        profile_path = args.version_json
    profile = json.loads(profile_path.read_text(encoding='utf-8-sig'))
    entries = resolve_artifacts(profile, args.artifact)
    if args.dry_run:
        print('STOP_BY_PID ' + stop_command(args.instance))
        print('CLEAN ' + json.dumps(CLEAN))
        for coordinate, (local, path) in entries.items():
            print('PUT_ARTIFACT ' + coordinate + ' ' + str(local) + ' -> ' + ntpath.join(args.mc, 'libraries', path))
        print('SYNC_VERSION_JSON by group:artifact; preserve other libraries')
        for local, name in files:
            print('MOD ' + local.name + ' -> ' + name)
        print('START_PROCESS server-gen -> client-join --lang ' + args.client_lang + '; POLL same PID/status; call timeout=240s')
        print('COLLECT logs/screenshots/region/load-report/compatibility-report; ASSERT; FRAME; REGION; STRICT REPORTS; REPORT report.md')
        return 0
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    if args.manifest:
        shutil.copy2(args.manifest, output / 'manifest.json')
    local_manifest = [dict(filename=name, sha1=hashlib.sha1(path.read_bytes()).hexdigest(), size=path.stat().st_size)
                      for path, name in files]
    (output / 'staged-mods.json').write_text(json.dumps(local_manifest, indent=2))
    # Validate all local inputs before stopping or changing anything remotely.
    sync = [sys.executable, str(HERE / 'version-json-sync.py'), str(profile_path)]
    for coordinate, (local, _) in entries.items():
        sync += ['--artifact', coordinate + '=' + str(local)]
    subprocess.run(sync, check=True)
    bundle = output / 'mods.zip'
    if not args.remote_mods:
        with zipfile.ZipFile(bundle, 'w', zipfile.ZIP_DEFLATED) as archive:
            for local, name in files:
                archive.write(local, 'mods/' + name)
    server, client, errors = -1, -1, []
    try:
        remote(stop_command(args.instance))
        # Keep prior logs/worlds until the four verified binary transfers and profile synchronization succeed.
        for coordinate, (local, path) in entries.items():
            destination = ntpath.join(args.mc, 'libraries', path.replace('/', '\\'))
            remote(f'New-Item -ItemType Directory -Force -Path {ps(ntpath.dirname(destination))} | Out-Null')
            put(local, destination)
        put(profile_path, ntpath.join(args.mc, 'versions', args.version, args.version + '.json'))
        remote(clean_command(args.instance))
        tools_dir = stage_tools(args, output)
        if args.remote_mods:
            remote_manifest = ntpath.join(remote_tools, 'mods-manifest.json')
            put(args.manifest, remote_manifest)
            if run_job(args, 'fetch', tools_dir, output, 'fetch-mods.py', ['--manifest', remote_manifest]) != 0:
                raise RuntimeError('the Windows side could not fetch and verify every jar of the manifest; see fetch.log')
            unpack = ''
        else:
            remote_bundle = ntpath.join(remote_tools, 'mods.zip')
            put(bundle, remote_bundle)
            unpack = f'Expand-Archive -LiteralPath {ps(remote_bundle)} -DestinationPath {ps(args.instance)} -Force; '
        remote(unpack +
               f'if (Test-Path -LiteralPath {ps(ntpath.join(args.instance, "mods-all"))}) {{ '
               f'Remove-Item -LiteralPath {ps(ntpath.join(args.instance, "mods-all"))} -Recurse -Force }}; '
               f'Copy-Item -LiteralPath {ps(ntpath.join(args.instance, "mods"))} -Destination {ps(ntpath.join(args.instance, "mods-all"))} -Recurse')
        server = run_job(args, 'server', tools_dir, output, 'run-server-test.py')
        client = (run_job(args, 'client', tools_dir, output, 'run-client-test.py', ['--lang', args.client_lang])
                  if server == 0 else -1)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        errors.append(str(error))
    return finish_run(args, output, remote_tools, server, client, started, errors)



if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print('compat run failed: ' + str(error), file=sys.stderr)
        sys.exit(2)
