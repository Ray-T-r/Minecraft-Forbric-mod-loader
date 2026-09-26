#!/usr/bin/env python3
"""Download a pick_mods manifest's jars into the instance's mods directory, on the Windows side, from their own URLs.

push-and-run's default ships the whole pack through the remote file transport as one zip. A 150 MB pack is fine on a
LAN; through a relayed tunnel that throttles after tens of megabytes it stalls every remote call for minutes, and a
sweep dies on a timed-out upload of a 2 KB script. The manifest already carries each jar's URL, size and SHA-1, so
the machine that runs the game can fetch them itself and only the manifest crosses the transport.

Every jar is verified against the manifest's SHA-1 and size before it is written; an existing jar that already
matches is kept. Exit status 0 only when every row is present and verified.
"""
import hashlib
import json
from pathlib import Path
import sys
import time
# This directory's own bisect.py shadows the standard module urllib reaches through random; import urllib with the
# script directory off the path, then put it back for common.
_HERE = sys.path.pop(0)
import urllib.request  # noqa: E402
sys.path.insert(0, _HERE)
from common import config, parser, safe_filename  # noqa: E402


def fetch(url, attempts=4):
    last = None
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(url, headers={'User-Agent': 'Forbric-compat/1.0'})
            with urllib.request.urlopen(request, timeout=120) as response:
                return response.read()
        except OSError as error:
            last = error
            time.sleep(2 * (attempt + 1))
    raise OSError(f'{url}: {last}')


def main():
    argument_parser = parser(__doc__)
    argument_parser.add_argument('--manifest', required=True)
    args = argument_parser.parse_args()
    configuration = config(args, argument_parser)
    if not configuration:
        return 0
    mods = Path(configuration['instance']) / 'mods'
    mods.mkdir(parents=True, exist_ok=True)
    rows = json.loads(Path(args.manifest).read_text(encoding='utf-8'))
    failed = []
    for row in rows:
        target = mods / safe_filename(row['filename'])
        if target.is_file() and target.stat().st_size == row['size'] \
                and hashlib.sha1(target.read_bytes()).hexdigest() == row['sha1']:
            print('KEEP', target.name, flush=True)
            continue
        try:
            data = fetch(row['url'])
            if len(data) != row['size'] or hashlib.sha1(data).hexdigest() != row['sha1']:
                raise ValueError('size/SHA-1 mismatch')
            temporary = target.with_name(target.name + '.part')
            temporary.write_bytes(data)
            temporary.replace(target)
            print('GOT', target.name, len(data), flush=True)
        except (OSError, ValueError) as error:
            failed.append(row['filename'])
            print('FAIL', row['filename'], error, flush=True)
    print(f'{len(rows) - len(failed)}/{len(rows)} jar(s) verified in {mods}', flush=True)
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
