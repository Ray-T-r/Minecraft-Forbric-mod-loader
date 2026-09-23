#!/usr/bin/env python3
"""M34: freeze a per-nonce client installation and independently validate real simulation evidence.

Does not build or mutate a source world. Only the gate wrapper builds, before the immutable snapshot.
A CONTROL_PASS is deliberately not a release soak. Reachable retired servers require review.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
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


def signal_owned_group(process, value):
    # The owned child may exit between poll() and signaling. Preserve its real exit status.
    try:
        os.killpg(process.pid, value)
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
            signal_owned_group(process, signal.SIGTERM)
        if (crash_seen is not None and time.monotonic() - crash_seen > 5) or time.monotonic() - began > timeout:
            signal_owned_group(process, signal.SIGKILL)
            process.wait()
            return process.returncode
        time.sleep(.5)
    return process.returncode


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


def validate_trace(rows, result, nonce, seconds, control, process_seconds, min_sessions=3):
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
    if any(server['alive'] for server in result['oldServers']):
        raise RetentionReview(activity)
    if result['status'] == 'REVIEW_REQUIRED':
        raise ValueError('controller requested retention review without a retained-server witness')
    return {'status': expected, 'releaseAccepted': not control, **activity}


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
    boot = copy_frozen(kernel / 'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar', frozen / 'boot.jar', records)
    runtime = copy_frozen(kernel / 'build/libs/forbric-kernel-runtime-0.1.0-SNAPSHOT.jar', frozen / 'runtime.jar', records)
    with zipfile.ZipFile(boot) as jar:
        embedded = [name for name in jar.namelist() if name.endswith('forbric-kernel-runtime.jar')]
        if len(embedded) != 1 or hashlib.sha256(jar.read(embedded[0])).hexdigest() != digest(runtime):
            raise ValueError('boot embedded runtime is missing or differs from frozen runtime jar')
    merged = copy_frozen(Path(args.merged) if args.merged else staged / 'merged-base/patched-mc-merged-26.2.jar', frozen / 'merged.jar', records)
    forge_source = Path(args.forge) if args.forge else staged / 'merged-base/forge-runtime-interop.jar'
    if not forge_source.is_file(): forge_source = staged / 'forge-runtime/forge-runtime.jar'
    forge = copy_frozen(forge_source, frozen / 'forge.jar', records)
    neo = copy_frozen(Path(args.neo) if args.neo else staged / 'neoforge-runtime/neoforge-runtime.jar', frozen / 'neo.jar', records)
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
    with zipfile.ZipFile(merged) as jar, zipfile.ZipFile(frozen / 'game-metadata.jar', 'w') as output:
        output.writestr('version.json', jar.read('version.json'))
    parent.append(frozen / 'game-metadata.jar')
    records.append({'source': 'generated from frozen merged.jar/version.json', 'snapshot': str(parent[-1]), 'sha256': digest(parent[-1]), 'size': parent[-1].stat().st_size})
    asset_index = mc / 'assets/indexes' / (version['assetIndex']['id'] + '.json')
    copy_frozen(asset_index, frozen / 'asset-index.json', records)
    # The launcher never regenerates these binaries after this manifest has been sealed.
    manifest = {'nonce': nonce, 'control': args.control, 'seconds': args.seconds, 'minSessions': args.sessions,
                'createdUtc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                'run': str(run), 'fixture': str(fixture), 'worldSource': str(world_source), 'sourceWorld': source_world_hashes,
                'initialConfiguration': configuration, 'artifacts': records, 'policy': args.policy,
                'assetsReadOnlySource': str(mc / 'assets'), 'source': source_before}
    dump(evidence / 'manifest.json', manifest)
    (evidence / 'manifest.sha256').write_text(digest(evidence / 'manifest.json') + '\n')
    java = args.java
    properties = {'forbric.clientSoak': 'true', 'forbric.soakNonce': nonce, 'forbric.soakRun': str(run),
                  'forbric.soak.seconds': str(args.seconds), 'forbric.soakControl': str(args.control).lower(),
                  'forbric.soak.dwellTicks': str(args.dwell_ticks), 'forbric.soak.sessions': str(args.sessions),
                  'forbric.soak.routes': '2', 'forbric.soak.betweenSeconds': str(args.between_seconds),
                  'forbric.soak.settleSeconds': str(args.settle_seconds), 'forbric.compatibilityPolicy': args.policy,
                  'forbric.dependencyDialog': 'off', 'java.library.path': str(frozen / 'natives')}
    command = [java] + (['-XstartOnFirstThread'] if platform.system() == 'Darwin' else []) + ['-Xmx' + args.heap]
    command += [f'-D{key}={value}' for key, value in properties.items()]
    command += ['-cp', os.pathsep.join(map(str, parent)), 'net.forbric.kernel.boot.KernelClientLaunch', '--gameJar', str(merged),
                '--runtimeJar', str(forge), '--runtimeJar', str(neo), '--libraryPath', os.pathsep.join(map(str, game_libraries)),
                '--', '--version', version['id'] + '-forbric-kernel', '--gameDir', str(run), '--assetsDir', str(mc / 'assets'),
                '--assetIndex', version['assetIndex']['id'], '--accessToken', '0', '--username', 'ForbricKernel',
                '--uuid', '00000000000000000000000000000000', '--userType', 'legacy', '--versionType', 'release',
                '--quickPlayPath', str(run / 'quickPlay/log.json'), '--quickPlaySingleplayer', world_name]
    dump(evidence / 'command.json', command)
    print(f'[M34] {"CONTROL ONLY" if args.control else "RELEASE SOAK"} nonce={nonce} evidence={evidence}', flush=True)
    began = time.monotonic()
    started_ns = time.time_ns()
    with (evidence / 'client.log').open('w') as log:
        process = subprocess.Popen(command, cwd=run, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        dump(evidence / 'process.json', {'pid': process.pid, 'nonce': nonce})
        try:
            code = wait_for_client(process, evidence / 'client.log', args.timeout or max(args.seconds * 2, args.seconds + 1800))
        except KeyboardInterrupt:
            signal_owned_group(process, signal.SIGTERM)
            try: process.wait(timeout=30)
            except subprocess.TimeoutExpired: signal_owned_group(process, signal.SIGKILL); process.wait()
            code = -1
    elapsed = time.monotonic() - began
    validation = {'status': 'FAIL', 'releaseAccepted': False, 'nonce': nonce, 'pid': process.pid, 'exitCode': code, 'processSeconds': elapsed}
    try:
        if code != 0: raise ValueError(f'client exited abnormally: {code}')
        if source_record(kernel.parent, set()) != source_before: raise ValueError('source changed during the measured run')
        if any(digest(item['snapshot']) != item['sha256'] for item in records): raise ValueError('frozen artifacts changed during run')
        if inventory(world_source) != source_world_hashes: raise ValueError('source world changed during soak; cannot attest untouched original')
        if not args.control:
            validation['compatibility'] = validate_compatibility(run / '.forbric-kernel/compatibility-report.json', started_ns)
        rows = [json.loads(line) for line in (evidence / 'telemetry.jsonl').read_text().splitlines()]
        result = json.loads((evidence / 'controller-result.json').read_text())
        if rows[0]['pid'] != process.pid: raise ValueError('telemetry is not from the launched child JVM')
        validation.update(validate_trace(rows, result, nonce, args.seconds, args.control, elapsed, args.sessions))
    except RetentionReview as review:
        validation.update(review.activity)
        validation.update(status='REVIEW_REQUIRED', releaseAccepted=False, detail=str(review))
    except Exception as failure:
        validation['detail'] = str(failure)
    validation['finalWorld'] = inventory(world)
    validation['artifactHashesAfter'] = {item['snapshot']: digest(item['snapshot']) for item in records}
    validation['telemetrySha256'] = digest(evidence / 'telemetry.jsonl') if (evidence / 'telemetry.jsonl').is_file() else None
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
    parser.add_argument('--merged'); parser.add_argument('--forge'); parser.add_argument('--neo'); parser.add_argument('--natives')
    parser.add_argument('--seconds', type=int, default=7200)
    parser.add_argument('--control', action='store_true')
    parser.add_argument('--sessions', type=int, default=3)
    parser.add_argument('--dwell-ticks', type=int, default=600)
    parser.add_argument('--between-seconds', type=int, default=10)
    parser.add_argument('--settle-seconds', type=int, default=60)
    parser.add_argument('--timeout', type=int)
    parser.add_argument('--policy', choices=('strict', 'continue'), default='strict')
    parser.add_argument('--heap', default='4G')
    parser.add_argument('--java', default='java')
    return launch(parser.parse_args())


if __name__ == '__main__':
    try: sys.exit(main())
    except Exception as failure:
        print('[M34] FAIL ' + str(failure), file=sys.stderr)
        sys.exit(1)
