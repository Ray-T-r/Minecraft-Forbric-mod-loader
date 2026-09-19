#!/usr/bin/env python3
"""Update sha1 and size of four named artifacts in a launcher version JSON, atomically."""
import argparse
import hashlib
import json
from pathlib import Path
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version_json', type=Path)
    parser.add_argument('--artifact', action='append', required=True, metavar='GROUP:ARTIFACT=JAR',
                        help='Repeat exactly four times; coordinates ignore version/classifier')
    parser.add_argument('--output', type=Path, help='Write here instead of updating version_json in place')
    args = parser.parse_args()
    artifacts = {}
    for spec in args.artifact:
        coordinate, separator, path = spec.partition('=')
        if not separator or len(coordinate.split(':')) != 2 or not all(coordinate.split(':')):
            parser.error('--artifact requires group:artifact=path')
        if coordinate in artifacts:
            parser.error('duplicate artifact: ' + coordinate)
        data = Path(path).read_bytes()
        artifacts[coordinate] = (hashlib.sha1(data).hexdigest(), len(data))
    if len(artifacts) != 4:
        parser.error('exactly four artifacts are required')
    document = json.loads(args.version_json.read_text(encoding='utf-8-sig'))
    found = set()
    for library in document.get('libraries', []):
        coordinate = ':'.join(library.get('name', '').split(':')[:2])
        if coordinate in artifacts:
            artifact = library.get('downloads', {}).get('artifact')
            if artifact is None:
                raise ValueError('missing downloads.artifact for ' + coordinate)
            artifact['sha1'], artifact['size'] = artifacts[coordinate]
            found.add(coordinate)
    if found != artifacts.keys():
        raise ValueError('missing library name(s): ' + ', '.join(sorted(artifacts.keys() - found)))
    output = args.output or args.version_json
    temporary = output.with_name(output.name + '.tmp')
    temporary.write_text(json.dumps(document, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(output)
    print(f'synced {len(found)} artifacts: {output}')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError) as error:
        print(f'ERROR: {error}', file=sys.stderr)
        sys.exit(2)
