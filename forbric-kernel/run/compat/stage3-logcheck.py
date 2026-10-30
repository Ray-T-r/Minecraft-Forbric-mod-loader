#!/usr/bin/env python3
"""Cross-check stage3-sweep.tsv's "before" column against what real boots logged for multi-manifest jars.

The tri-eco kernel logs one line per jar that declares more than one loader:

  [Forbric/MultiLoader] <jar> declares <n> loaders — loading it as <ECOSYSTEM> only, suppressing ...

This walks every *.log / *.txt / *.out under the main checkout's forbric-kernel/run and forbric-kernel/build
(libraries/, versions/, .gradle/ and assets/ skipped; symlinks followed, each directory once), collects every such
line with the file's modification time, and compares each logged owner with the sweep's "before" column for the jar of
the same FILE NAME (the logs carry no hash, so two different jars with one name would be conflated).

Not every log was written by the kernel the "before" column stands for. The rule that picks a universal jar's loader
from its real initialization code arrived in 29043460 (2026-09-20 10:31:58 +0800); before that, and in runs of the
v0.2.0 release kernel (tagged 2026-09-14), a three-manifest jar could legitimately be logged as NEOFORGE where the
tri-eco kernel picks FORGE. So the comparison is printed twice: over every log, and over logs modified at or after
--since (default: that commit). Every disagreeing log line is listed with its date either way, so a reader can see
which kernel it came from. The logs carry no kernel version, so the date alone cannot tell a log that an older
kernel wrote after the cutoff (a release instance rerun later): such a log counts as current and shows up as a
disagreement. --exclude SUBSTRING (repeatable) leaves out logs whose path contains it, for run directories shown by
other evidence to use another kernel (for example build/sweep80-mac/per-mod-v020-*, whose instance mc-v020 installs
net.forbric:forbric-kernel:0.2.0); excluded lines are still listed, marked as such.

Exit status 1 when any log at or after --since, not excluded, disagrees: that is the check that can fail.

Usage: python3 run/compat/stage3-logcheck.py <stage3-sweep.tsv> [--since '2026-09-20 10:31:58 +0800']
       [--exclude SUBSTRING ...] [--root DIR ...]
"""

import argparse
import collections
import csv
import datetime
import os
from pathlib import Path
import re
import subprocess
import sys

HERE = Path(__file__).resolve().parent
LINE = re.compile(r'\[Forbric/MultiLoader\] (\S+\.jar) declares \d+ loaders — loading it as ([A-Z]+)')
SKIP_DIRS = {'libraries', 'versions', '.gradle', 'assets'}
SUFFIXES = ('.log', '.txt', '.out')


def main_checkout():
    if os.environ.get('FORBRIC_SWEEP_HOME'):
        return Path(os.environ['FORBRIC_SWEEP_HOME'])
    common = subprocess.run(['git', '-C', str(HERE), 'rev-parse', '--path-format=absolute', '--git-common-dir'],
                            check=True, capture_output=True, text=True).stdout.strip()
    return Path(common).parent


def logged(roots):
    """[(jar name, owner, log path, mtime)] for every MultiLoader line in every log under `roots`."""
    found = []
    seen_dirs = set()
    for root in roots:
        for directory, dirs, files in os.walk(root, followlinks=True):
            real = os.path.realpath(directory)
            if real in seen_dirs:
                dirs[:] = []
                continue
            seen_dirs.add(real)
            dirs[:] = sorted(d for d in dirs if d not in SKIP_DIRS)
            for name in sorted(files):
                if not name.endswith(SUFFIXES):
                    continue
                path = os.path.join(directory, name)
                try:
                    with open(path, 'rb') as stream:
                        text = stream.read().decode('utf-8', 'replace')
                    mtime = os.path.getmtime(path)
                except OSError:
                    continue
                if 'Forbric/MultiLoader' not in text:
                    continue
                for jar, owner in LINE.findall(text):
                    found.append((jar, owner, path, mtime))
    return found


def compare(lines, before, label):
    """Prints, for the jars in the sweep, how many were logged only with the "before" owner; returns the
    disagreeing lines."""
    by_jar = collections.defaultdict(list)
    for line in lines:
        by_jar[line[0]].append(line)
    in_sweep = [jar for jar in by_jar if jar in before]
    disagreeing = [line for jar in in_sweep for line in by_jar[jar] if line[1] not in before[jar]]
    bad_jars = sorted({line[0] for line in disagreeing})
    print(f'{label}: {len({l[2] for l in lines})} logs, {len(by_jar)} jar names logged, {len(in_sweep)} of them in '
          f'the sweep; {len(in_sweep) - len(bad_jars)} logged only with the "before" owner, {len(bad_jars)} logged '
          f'at least once with another')
    return disagreeing


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('sweep', type=Path)
    parser.add_argument('--since', default='2026-09-20 10:31:58 +0800')
    parser.add_argument('--root', action='append')
    parser.add_argument('--exclude', action='append', default=[])
    args = parser.parse_args()
    since = datetime.datetime.strptime(args.since, '%Y-%m-%d %H:%M:%S %z').timestamp()
    home = main_checkout()
    roots = args.root or [str(home / 'forbric-kernel/run'), str(home / 'forbric-kernel/build')]

    csv.field_size_limit(1 << 30)
    before = collections.defaultdict(set)
    with open(args.sweep, encoding='utf-8') as stream:
        for row in csv.DictReader(stream, delimiter='\t'):
            before[row['jar']].add(row['before'])

    lines = logged(roots)
    every = compare(lines, before, 'every log')
    def excluded(path):
        return any(part in path for part in args.exclude)

    label = f'logs modified at or after {args.since}' + (f", not under {args.exclude}" if args.exclude else '')
    current = compare([l for l in lines if l[3] >= since and not excluded(l[2])], before, label)

    def stamp(t):
        return datetime.datetime.fromtimestamp(t).astimezone().strftime('%Y-%m-%d %H:%M')

    def where(path):
        try:
            return str(Path(path).relative_to(home))
        except ValueError:
            return path

    print('\ndisagreeing log lines (jar, logged owner, sweep before, log date, log):')
    for jar, owner, path, mtime in sorted(every, key=lambda l: (l[0], l[3])):
        mark = 'older  ' if mtime < since else 'EXCLUDE' if excluded(path) else 'CURRENT'
        print(f"  {mark} {jar}: logged {owner}, before {'/'.join(sorted(before[jar]))}, {stamp(mtime)}, {where(path)}")
    return 1 if current else 0


if __name__ == '__main__':
    sys.exit(main())
