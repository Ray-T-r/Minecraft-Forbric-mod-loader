#!/usr/bin/env python3
"""Join the generated world; require both a clean smoke outcome and a drawn Minecraft screenshot."""
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
from common import (config, driver_command, finish, frame_verdict, fresh_shots, own_driver,
                    parser, screenshot_fallback, spawn)


def main():
    argument_parser = parser(__doc__)
    argument_parser.add_argument('--run-timeout', type=int, default=int(os.environ.get('RUN_TIMEOUT', '1200')))
    argument_parser.add_argument('--grace', type=int, default=int(os.environ.get('GRACE', '45')))
    args = argument_parser.parse_args()
    configuration = config(args, argument_parser)
    if not configuration:
        return 0
    instance = Path(configuration['instance'])
    if not (instance / 'saves' / configuration['world'] / 'level.dat').is_file():
        argument_parser.error('world missing; run run-server-test.py first')
    flags = ['-Dforbric.clientSmoke=true', '-Dforbric.clientSmokeWorld=' + configuration['world'],
             '-Dforbric.clientSmokeReadyTicks=80', '-Dforbric.clientSmokeModsScreen=100',
             '-Dforbric.clientSmokeScreenshots=100', '-Dforbric.clientSmokeDisconnectTicks=200']
    command = driver_command(configuration, 'forbric-launch.py') + ['--jvm=' + flag for flag in flags]
    outcome, failed = threading.Event(), threading.Event()
    started = time.time()
    with own_driver(configuration), (instance / 'client-console.log').open('w', encoding='utf-8') as output:
        process = spawn(configuration, command, cwd=instance, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                        text=True, encoding='utf-8', errors='replace', bufsize=1)
        def pump():
            for line in process.stdout:
                output.write(line)
                output.flush()
                if 'clean disconnect observed' in line:
                    outcome.set()
                if any(marker in line for marker in ('Game crashed', 'Preparing crash report', 'Mod Loading has failed', 'Failed to load level data')):
                    failed.set()
        thread = threading.Thread(target=pump, daemon=True)
        thread.start()
        try:
            deadline = time.monotonic() + args.run_timeout
            while not outcome.is_set() and not failed.is_set() and process.poll() is None and time.monotonic() < deadline:
                time.sleep(1)
            if not fresh_shots(configuration, started) and process.poll() is None:
                screenshot_fallback(configuration)
            # Give the post-main watchdog time to report a leaked non-daemon mod thread.
            try:
                process.wait(timeout=args.grace)
            except subprocess.TimeoutExpired:
                print('FAIL client remained alive after grace')
                return 1
            thread.join(timeout=10)
            shots = fresh_shots(configuration, started)
            drew = bool(shots) and frame_verdict(shots[-1])
            passed = outcome.is_set() and not failed.is_set() and process.returncode == 0 and drew
            print(f'{"PASS" if passed else "FAIL"} client exit={process.returncode} joined={outcome.is_set()} drew={drew}')
            return 0 if passed else 1
        finally:
            finish(configuration, process)
            thread.join(timeout=10)


if __name__ == '__main__':
    sys.exit(main())
