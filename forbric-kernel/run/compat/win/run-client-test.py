#!/usr/bin/env python3
"""Join the generated world; require both a clean smoke outcome and a drawn Minecraft screenshot."""
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
from common import (await_outcome, config, driver_command, finish, frame_verdict, fresh_shots, own_driver,
                    parser, screenshot_fallback, spawn)


def main():
    argument_parser = parser(__doc__)
    argument_parser.add_argument('--run-timeout', type=int, default=int(os.environ.get('RUN_TIMEOUT', '1200')))
    # The same trap as the server's, and twenty minutes deep: --run-timeout is the ceiling for a client that is
    # still working, and there was nothing at all for one that has stopped. A wedged client held it for the full
    # 1200s while its console log's last line sat minutes in the past.
    #
    # The margin here is deliberately larger than the server's. Across the recorded sweeps no client log ever
    # went quiet for more than 9 seconds — but not one of those runs ever reached a clean disconnect, so that
    # number describes failing clients only and is NOT evidence about how quiet a healthy one may go while it
    # loads a world. 300s is thirty times the largest silence actually observed, which is a bound this evidence
    # can carry; anything tighter would be a guess about a run nobody here has recorded.
    argument_parser.add_argument('--stall', type=int, default=int(os.environ.get('CLIENT_STALL', '300')))
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
    last_output = [time.monotonic()]
    started = time.time()
    with own_driver(configuration), (instance / 'client-console.log').open('w', encoding='utf-8') as output:
        process = spawn(configuration, command, cwd=instance, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                        text=True, encoding='utf-8', errors='replace', bufsize=1)
        def pump():
            for line in process.stdout:
                last_output[0] = time.monotonic()
                output.write(line)
                output.flush()
                if 'clean disconnect observed' in line:
                    outcome.set()
                if any(marker in line for marker in ('Game crashed', 'Preparing crash report', 'Mod Loading has failed', 'Failed to load level data')):
                    failed.set()
        thread = threading.Thread(target=pump, daemon=True)
        thread.start()
        try:
            verdict = await_outcome(ready=outcome, failed=failed, process=process, timeout=args.run_timeout,
                                    stall=args.stall, last_output=last_output)
            if verdict == 'stalled':
                # Fall through rather than return: the screenshot and frame verdict below are evidence about a
                # wedged client too, and finish() still has to stop the process it owns.
                print(f'FAIL client stopped producing output {int(time.monotonic() - last_output[0])}s ago '
                      f'without a clean disconnect; see client-console.log for its last line')
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
