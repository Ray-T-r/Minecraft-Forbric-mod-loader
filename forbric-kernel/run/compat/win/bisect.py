#!/usr/bin/env python3
"""Run one named mod subset; the verdict always comes from a fresh Minecraft frame."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from common import (acknowledge_first_run, config, driver_command, finish, frame_verdict, fresh_shots, own_driver,
                    parser, safe_filename, screenshot_fallback, spawn)


def main():
    argument_parser = parser(__doc__)
    argument_parser.add_argument('--subset', type=Path, help='UTF-8 jar names, one per line; default INSTANCE/mods-subset.txt')
    argument_parser.add_argument('--all-mods', type=Path, help='Default INSTANCE/mods-all')
    argument_parser.add_argument('--wait', type=int, default=int(os.environ.get('BISECT_WAIT', '300')))
    args = argument_parser.parse_args()
    configuration = config(args, argument_parser)
    if not configuration:
        return 0
    instance = Path(configuration['instance'])
    source = args.all_mods or instance / 'mods-all'
    subset = args.subset or instance / 'mods-subset.txt'
    names = [line.strip() for line in subset.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
    copies, targets = [], set()
    for name in names:
        if Path(name).name != name or '/' in name or '\\' in name or not name.lower().endswith('.jar'):
            argument_parser.error('subset entries must be jar basenames: ' + name)
        target = safe_filename(name)
        if target.casefold() in targets:
            argument_parser.error('sanitized filename collision: ' + target)
        targets.add(target.casefold())
        jar = source / target
        if not jar.is_file():
            jar = source / name
        if not jar.is_file():
            argument_parser.error('missing subset jar: ' + name)
        copies.append((jar, target))
    mods = instance / 'mods'
    mods.mkdir(parents=True, exist_ok=True)
    for jar in mods.glob('*.jar'):
        jar.unlink()
    for jar, target in copies:
        shutil.copy2(jar, mods / target)
    flags = ['-Dforbric.clientSmoke=true', '-Dforbric.clientSmokeWorld=' + configuration['world'],
             '-Dforbric.clientSmokeReadyTicks=60', '-Dforbric.clientSmokeScreenshots=100',
             '-Dforbric.clientSmokeDisconnectTicks=200']
    acknowledge_first_run(instance)
    command = driver_command(configuration, 'forbric-launch.py') + ['--jvm=' + flag for flag in flags]
    started = time.time()
    with own_driver(configuration), (instance / 'bisect-console.log').open('w', encoding='utf-8') as output:
        process = spawn(configuration, command, cwd=instance, stdout=output, stderr=subprocess.STDOUT)
        print(f'mods={len(copies)} pid={process.pid}', flush=True)
        try:
            deadline = time.monotonic() + args.wait
            while process.poll() is None and time.monotonic() < deadline and not fresh_shots(configuration, started):
                time.sleep(1)
            shots = fresh_shots(configuration, started)
            if not shots and process.poll() is None:
                screenshot_fallback(configuration)
                shots = fresh_shots(configuration, started)
            if not shots:
                print('verdict=UNSUPPORTED reason=no-fresh-screenshot')
                return 2
            return 0 if frame_verdict(shots[-1]) else 1
        finally:
            finish(configuration, process)


if __name__ == '__main__':
    sys.exit(main())
