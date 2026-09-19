#!/usr/bin/env python3
"""Generate and tick a world, save/stop cleanly, then stage that world for the client."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import threading
import time
from common import config, driver_command, finish, own_driver, parser, safe_filename, spawn


def main():
    argument_parser = parser(__doc__)
    argument_parser.add_argument('--boot-timeout', type=int, default=int(os.environ.get('BOOT_TIMEOUT', '900')))
    argument_parser.add_argument('--tick-seconds', type=int, default=int(os.environ.get('TICK_SECONDS', '90')))
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
        f'level-name={configuration["world"]}\nlevel-seed={args.seed}\nserver-port={args.port}\n'
        'online-mode=false\nview-distance=10\nsimulation-distance=10\nspawn-protection=0\n', encoding='utf-8')
    ready, failed = threading.Event(), threading.Event()
    log = directory / 'server-console.log'
    with own_driver(configuration), log.open('w', encoding='utf-8') as output:
        process = spawn(configuration, driver_command(configuration, 'forbric-server.py'), cwd=directory,
                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                        text=True, encoding='utf-8', errors='replace', bufsize=1)
        def pump():
            for line in process.stdout:
                output.write(line)
                output.flush()
                if 'Done (' in line and 'For help' in line:
                    ready.set()
                if any(marker in line for marker in ('Mod Loading has failed', 'Failed to start the minecraft server', 'Preparing crash report')):
                    failed.set()
        thread = threading.Thread(target=pump, daemon=True)
        thread.start()
        try:
            deadline = time.monotonic() + args.boot_timeout
            while not ready.is_set() and not failed.is_set() and process.poll() is None and time.monotonic() < deadline:
                time.sleep(1)
            if not ready.is_set() or failed.is_set() or process.poll() is not None:
                print('FAIL server did not become ready')
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
            print(f'PASS server saved world={target}')
            return 0
        finally:
            finish(configuration, process)
            thread.join(timeout=10)


if __name__ == '__main__':
    sys.exit(main())
