#!/usr/bin/env python3
"""Generate and tick a world, save/stop cleanly, then stage that world for the client."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import threading
import time
from common import (await_outcome, config, driver_command, finish, own_driver, parser, prepare_world,
                    safe_filename, server_properties, spawn)


def main():
    argument_parser = parser(__doc__)
    argument_parser.add_argument('--boot-timeout', type=int, default=int(os.environ.get('BOOT_TIMEOUT', '900')))
    # A BOOT THAT HAS STOPPED TALKING IS NOT A SLOW BOOT. --boot-timeout is the ceiling for a server that is
    # still working; this is the one for a server that has stopped. Measured over the sixteen sweeps kept in
    # build/compat/: every boot that reached Done did so in 31-38 seconds and never went quiet for more than
    # EIGHT of them, while all three that never reached Done fell silent 13-17 seconds in and stayed silent,
    # alive, until the full 900s ceiling expired. Two of those cost 820s and 1615s of wall clock to learn
    # nothing that the first two minutes had not already settled.
    #
    # 120s is fifteen times the largest silence a healthy boot has ever produced here, so a slow machine still
    # gets to finish. It applies ONLY before Done: after that the server is idle by design and the tick soak
    # below is legitimately quiet for a minute at a stretch ("Server empty for 60 seconds, pausing").
    argument_parser.add_argument('--boot-stall', type=int, default=int(os.environ.get('BOOT_STALL', '120')))
    # 60, not the 90 it was. The last thirty of those ninety were never simulation: the server paused itself
    # sixty seconds after Done and sat there. See common.server_properties, which now disables that pause — so
    # this is the same amount of REAL ticking as before, and raising this flag now actually buys more of it.
    argument_parser.add_argument('--tick-seconds', type=int, default=int(os.environ.get('TICK_SECONDS', '60')))
    argument_parser.add_argument('--stop-timeout', type=int, default=int(os.environ.get('STOP_TIMEOUT', '240')))
    argument_parser.add_argument('--seed', default=os.environ.get('WORLD_SEED', '20260919'))
    argument_parser.add_argument('--port', type=int, default=int(os.environ.get('GATE_PORT', '25599')))
    args = argument_parser.parse_args()
    configuration = config(args, argument_parser)
    if not configuration:
        return 0
    instance, directory = Path(configuration['instance']), Path(configuration['server_dir'])
    mods = directory / 'mods'
    mods.mkdir(parents=True, exist_ok=True)
    for jar in mods.glob('*.jar'):
        jar.unlink()
    names = set()
    for jar in sorted((instance / 'mods').glob('*.jar')):
        name = safe_filename(jar.name)
        if name.casefold() in names:
            raise ValueError('sanitized mod filename collision: ' + name)
        names.add(name.casefold())
        shutil.copy2(jar, mods / name)
    (directory / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
    (directory / 'server.properties').write_text(
        server_properties(configuration['world'], args.seed, args.port), encoding='utf-8')
    ready, failed = threading.Event(), threading.Event()
    last_output = [time.monotonic()]
    log = directory / 'server-console.log'
    with own_driver(configuration), log.open('w', encoding='utf-8') as output:
        process = spawn(configuration, driver_command(configuration, 'forbric-server.py'), cwd=directory,
                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                        text=True, encoding='utf-8', errors='replace', bufsize=1)
        def pump():
            for line in process.stdout:
                last_output[0] = time.monotonic()
                output.write(line)
                output.flush()
                if 'Done (' in line and 'For help' in line:
                    ready.set()
                if any(marker in line for marker in ('Mod Loading has failed', 'Failed to start the minecraft server', 'Preparing crash report')):
                    failed.set()
        thread = threading.Thread(target=pump, daemon=True)
        thread.start()
        try:
            verdict = await_outcome(ready=ready, failed=failed, process=process, timeout=args.boot_timeout,
                                    stall=args.boot_stall, last_output=last_output)
            if verdict == 'stalled':
                # Name it. "did not become ready" covers a crash, a slow machine and a deadlock alike, and the
                # three want different next steps.
                quiet = int(time.monotonic() - last_output[0])
                print(f'FAIL server stopped producing output {quiet}s ago and had not reached Done; '
                      'see server-console.log for its last line')
                return 1
            if verdict != 'ready':
                print(f'FAIL server did not become ready ({verdict})')
                return 1
            deadline = time.monotonic() + args.tick_seconds
            while process.poll() is None and time.monotonic() < deadline:
                time.sleep(1)
            if process.poll() is not None:
                print('FAIL server exited during tick interval')
                return 1
            process.stdin.write('save-all flush\nstop\n')
            process.stdin.flush()
            try:
                code = process.wait(timeout=args.stop_timeout)
            except subprocess.TimeoutExpired:
                print('FAIL server stop timed out')
                return 1
            thread.join(timeout=10)
            world = directory / configuration['world']
            if code or failed.is_set() or not (world / 'level.dat').is_file():
                print(f'FAIL server exit={code} level.dat={(world / "level.dat").is_file()}')
                return 1
            target = instance / 'saves' / configuration['world']
            if target.exists():
                shutil.rmtree(target)
            shutil.copytree(world, target)
            prepare_world(configuration, target)
            print(f'PASS server saved world={target}')
            return 0
        finally:
            finish(configuration, process)
            thread.join(timeout=10)


if __name__ == '__main__':
    sys.exit(main())
