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
from common import safe_filename

# Explicit children only. Never delete the instance directory or a launcher-owned file.
CLEAN = ('config', 'mods', 'saves', 'logs', '.forbric-kernel', '.mixin.out', '.fabric',
         'crash-reports', 'screenshots', 'server-gen', 'quickPlay', 'resourcepacks',
         'defaultconfigs', '.cache', '.physics_mod_cache', 'client-console.log',
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
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=490)
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
    # Only PIDs recorded by these drivers/gates, never a process-name kill.
    files = [ntpath.join(instance, name) for name in ('.forbric-sweep.pid', '.forbric-gate.pid')]
    files.append(ntpath.join(instance, 'server-gen', '.forbric-gate.pid'))
    return ("foreach ($file in @(" + ','.join(map(ps, files)) + ")) { "
            "if (Test-Path -LiteralPath $file) { foreach ($line in (Get-Content -LiteralPath $file)) { "
            "if ($line -match '^\\d+$' -and [int]$line -gt 0) { "
            "$owned = Get-Process -Id ([int]$line) -ErrorAction SilentlyContinue; "
            "if ($owned) { & taskkill /T /F /PID $line | Out-Null; "
            "if ($LASTEXITCODE -ne 0 -and (Get-Process -Id ([int]$line) -ErrorAction SilentlyContinue)) "
            "{ throw ('could not stop recorded PID ' + $line) } } } }; "
            "Remove-Item -LiteralPath $file -Force } }")


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


# The long process owns its status file. Polling never starts a replacement job.
JOB = '''import json, os, pathlib, subprocess, sys, time, traceback
from common import own_driver
status, instance, *command = sys.argv[1:]
path = pathlib.Path(status)
started_ns = time.time_ns()
def publish(state, **fields):
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(dict(state=state, pid=os.getpid(), started_ns=started_ns, **fields)), encoding='utf-8')
    temp.replace(path)
with own_driver(dict(pid_file=str(pathlib.Path(instance) / '.forbric-sweep.pid'))):
    publish('running')
    try:
        result = subprocess.run([sys.executable, *command])
        publish('done', returncode=result.returncode)
    except BaseException:
        publish('done', returncode=2, error=traceback.format_exc())
'''


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
               ntpath.join(remote_tools, driver), '--mc', args.mc, '--version', args.version,
               '--instance', args.instance, '--world', args.world]
    if getattr(args, 'java', None):
        command += ['--java', args.java]
    command += ['--jvm=' + value for value in getattr(args, 'jvm', [])]
    command += list(extra)
    argument_line = subprocess.list2cmdline(command)
    start = (f'Remove-Item -LiteralPath {ps(status)} -Force -ErrorAction SilentlyContinue; '
             f'$job = Start-Process -FilePath {ps(args.python)} -ArgumentList {ps(argument_line)} '
             f'-WorkingDirectory {ps(remote_tools)} -PassThru '
             f'-RedirectStandardOutput {ps(ntpath.join(run_dir, stage + ".log"))} '
             f'-RedirectStandardError {ps(ntpath.join(run_dir, stage + "-stderr.log"))}; '
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
                if state is not None and (not isinstance(state, dict) or state.get('pid') != pid):
                    raise ValueError('status belongs to a different process')
                misses = 0
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
            if p.suffix.lower() in ('.log', '.png', '.mca') or p.name == 'load-report.txt' or p.name.endswith('-status.json'):
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
    region = check([sys.executable, str(HERE / 'region-probe.py'), str(regions), '--dungeons'], output / 'region.txt')
    region_text = (output / 'region.txt').read_text()
    region = region and bool(re.search(r'unreadable: 0\b', region_text)) and bool(re.search(r'dungeons: [1-9]', region_text))
    findings = []
    for path in artifacts.rglob('load-report.txt'):
        # The kernel localizes this report, and the mod name is on a separate line from its reason.
        # Keep the complete text: filtering English status words lost every name, and all Chinese failures.
        findings.append(str(path.relative_to(artifacts)) + '\n' + path.read_text(errors='replace'))
    (output / 'degraded.txt').write_text('\n\n'.join(findings) or 'No load-report.txt was produced.\n')
    passed = client == 0 and frame if args.bisect else server == client == 0 and assertions and frame and region
    passed = passed and not errors
    try:
        commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=KERNEL, text=True, timeout=10).strip()
    except (OSError, subprocess.SubprocessError):
        commit = 'unavailable'
    values = dict(label=args.label, commit=commit, manifest=str(args.manifest or args.mods),
                  version=args.version, started=started, server=server, client=client,
                  assertions='PASS' if assertions else 'FAIL', frame='DREW' if frame else 'FAIL',
                  region='PASS' if region else 'FAIL', degraded=f'{len(findings)} report(s), see degraded.txt',
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
            print('BISECT frame-verdict.py; subset=' + str(args.bisect)); return 0
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
            code = run_job(args, 'bisect', tools_dir, output, 'bisect.py', ['--subset', subset])
        except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
            errors.append(str(error))
        return finish_run(args, output, remote_tools, 'reused world', code, started, errors)
    if args.mods is None:
        parser.error('--mods is required for a new run')
    files = mod_files(args.mods)
    if args.dry_run and not args.version_json:
        parser.error('--dry-run needs --version-json so all four artifact destinations can be verified')
    if not args.dry_run:
        if output.exists() and any(output.iterdir()):
            parser.error('output already contains evidence; choose a new --label or --output')
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
        print('START_PROCESS server-gen -> client-join; POLL same PID/status; call timeout=240s')
        print('COLLECT logs/screenshots/region/load-report; ASSERT; FRAME; REGION; REPORT report.md')
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
        remote_bundle = ntpath.join(remote_tools, 'mods.zip')
        put(bundle, remote_bundle)
        remote(f'Expand-Archive -LiteralPath {ps(remote_bundle)} -DestinationPath {ps(args.instance)} -Force; '
               f'if (Test-Path -LiteralPath {ps(ntpath.join(args.instance, "mods-all"))}) {{ '
               f'Remove-Item -LiteralPath {ps(ntpath.join(args.instance, "mods-all"))} -Recurse -Force }}; '
               f'Copy-Item -LiteralPath {ps(ntpath.join(args.instance, "mods"))} -Destination {ps(ntpath.join(args.instance, "mods-all"))} -Recurse')
        server = run_job(args, 'server', tools_dir, output, 'run-server-test.py')
        client = run_job(args, 'client', tools_dir, output, 'run-client-test.py') if server == 0 else -1
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        errors.append(str(error))
    return finish_run(args, output, remote_tools, server, client, started, errors)



if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print('compat run failed: ' + str(error), file=sys.stderr)
        sys.exit(2)
