#!/usr/bin/env python3
"""Resolve a reproducible Modrinth pack (stdlib only), optionally without downloading jars."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

LOADERS = ("fabric", "neoforge", "forge")


def safe_filename(name):
    # Windows forbids these characters even when the pack was assembled on another OS.
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', '_', name).rstrip(' .')
    if not name or name in ('.', '..'):
        raise ValueError('invalid empty filename')
    if name.split('.')[0].upper() in {'CON', 'PRN', 'AUX', 'NUL', *(f'COM{i}' for i in range(1, 10)), *(f'LPT{i}' for i in range(1, 10))}:
        name = '_' + name
    return name


class Modrinth:
    def __init__(self, api, mc):
        self.api, self.mc = api.rstrip('/'), mc

    def fetch(self, url):
        request = urllib.request.Request(url, headers={'User-Agent': 'Forbric-compat/1.0'})
        for attempt in range(3):
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    return response.read()
            except urllib.error.HTTPError as error:
                if error.code not in (429, 500, 502, 503, 504) or attempt == 2:
                    raise
            except urllib.error.URLError:
                if attempt == 2:
                    raise
            time.sleep(attempt + 1)

    def get(self, route, **query):
        url = self.api + route
        if query:
            url += '?' + urllib.parse.urlencode(query)
        return json.loads(self.fetch(url))

    def versions(self, project, loader=None):
        query = {'game_versions': json.dumps([self.mc])}
        if loader:
            query['loaders'] = json.dumps([loader])
        versions = self.get('/project/' + urllib.parse.quote(project, safe='') + '/version', **query)
        # Validate responses as well as requesting filters: never call a NeoForge jar a Forge build.
        return [v for v in versions if self.mc in v.get('game_versions', [])
                and (not loader or loader in v.get('loaders', [])) and primary(v)]

    def search(self, loader, offset=0, limit=100):
        facets = [[f'versions:{self.mc}'], ['project_type:mod'], [f'categories:{loader}']]
        return self.get('/search', facets=json.dumps(facets), offset=offset, limit=limit)


def digest(path):
    sha1 = hashlib.sha1()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            sha1.update(block)
    return sha1.hexdigest()


def primary(version):
    files = [f for f in version.get('files', []) if f['filename'].lower().endswith('.jar')]
    return next((f for f in files if f.get('primary')), files[0] if files else None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', nargs='?', type=Path, default=Path('mods'))
    parser.add_argument('--api', default=os.environ.get('MODRINTH_API', 'https://api.modrinth.com/v2'))
    parser.add_argument('--mc', default=os.environ.get('MC_VERSION', '26.2'))
    parser.add_argument('--seed', type=int, default=int(os.environ.get('SEED', '20260919')))
    parser.add_argument('--slugs', help='Explicit slug=loader pairs separated by commas; skips random selection')
    parser.add_argument('--resolve-only', action='store_true', help='Write manifest and report availability without downloading jars')
    parser.add_argument('--exclude-manifest', type=Path, default=os.environ.get('EXCLUDE_MANIFEST'))
    parser.add_argument('--manifest', type=Path, help='Default: output/../manifest.json')
    for loader, env, count in [('fabric', 'WANT_FABRIC', 26), ('neoforge', 'WANT_NEO', 26), ('forge', 'WANT_FORGE', 18)]:
        parser.add_argument('--want-' + loader, type=int, default=int(os.environ.get(env, str(count))))
    args = parser.parse_args()
    client, rng = Modrinth(args.api, args.mc), random.Random(args.seed)
    excludes = {s.strip().lower() for s in os.environ.get('EXCLUDE', '').split(',') if s.strip()}
    if args.exclude_manifest:
        previous = json.loads(args.exclude_manifest.read_text(encoding='utf-8'))
        excludes.update(row['slug'].lower() for row in previous)
    selected, failures = {}, []

    def add(slug, loader, version, kind):
        pid = version['project_id']
        if pid in selected:
            if selected[pid][2]['id'] != version['id']:
                raise ValueError(f'conflicting versions for {slug}: {selected[pid][2]["id"]} and {version["id"]}')
            return False
        selected[pid] = (slug, loader, version, kind)
        print(f'RESOLVED {kind} {slug}={loader} {version["version_number"]}')
        return True

    if args.slugs is not None:
        for pair in args.slugs.split(','):
            slug, separator, loader = pair.strip().rpartition('=')
            if not separator or not slug or loader not in LOADERS:
                parser.error('--slugs requires slug=fabric,slug=forge,slug=neoforge')
            if slug.lower() in excludes:
                print(f'EXCLUDED {slug}={loader}')
                continue
            try:
                candidates = client.versions(slug, loader)
            except urllib.error.HTTPError as error:
                if error.code != 404:
                    raise
                candidates = []
            if candidates:
                add(slug, loader, candidates[0], 'picked')
            else:
                failures.append(f'{slug}={loader}')
                print(f'UNAVAILABLE {slug}={loader} mc={args.mc}')
    else:
        for loader in LOADERS:
            want = getattr(args, 'want_' + loader)
            if want < 0:
                parser.error('wanted mod counts must be nonnegative')
            if not want:
                continue
            total = client.search(loader, limit=1)['total_hits']
            offsets = list(range(0, total, 100))
            rng.shuffle(offsets)
            pool = []
            for offset in offsets[:12]:
                pool.extend(client.search(loader, offset)['hits'])
            rng.shuffle(pool)
            seen, count = set(), 0
            for hit in pool:
                pid, slug = hit['project_id'], hit['slug']
                if count == want:
                    break
                if pid in selected or pid in seen or slug.lower() in excludes:
                    continue
                seen.add(pid)
                candidates = client.versions(pid, loader)
                if candidates:
                    count += add(slug, loader, candidates[0], 'picked')
            print(f'{loader}: wanted {want}, got {count} (pool {len(pool)} of {total})')
            if count != want:
                failures.append(f'{loader}: wanted {want}, got {count}')

    queue = list(selected.values())
    for slug, loader, version, _ in queue:
        for dependency in version.get('dependencies', []):
            if dependency.get('dependency_type') != 'required':
                continue
            pid, vid = dependency.get('project_id'), dependency.get('version_id')
            if pid in selected and not vid:
                continue
            if vid:
                dep = client.get('/version/' + urllib.parse.quote(vid, safe=''))
                if args.mc not in dep.get('game_versions', []) or not primary(dep):
                    raise ValueError(f'required dependency {vid} for {slug} has no {args.mc} jar')
                pid = dep['project_id']
            else:
                if not pid:
                    raise ValueError(f'required dependency of {slug} has neither project nor version id')
                versions = client.versions(pid, loader) or client.versions(pid)
                if not versions:
                    raise ValueError(f'UNAVAILABLE required dependency {pid} for {slug}')
                dep = versions[0]
            dslug = client.get('/project/' + urllib.parse.quote(pid, safe=''))['slug']
            if add(dslug, loader, dep, 'dep'):
                queue.append(selected[pid])

    manifest, filenames = [], {}
    for pid, (slug, loader, version, kind) in selected.items():
        artifact = primary(version)
        filename = safe_filename(artifact['filename'])
        if filename.casefold() in filenames:
            raise ValueError(f'filename collision: {filename} ({slug}, {filenames[filename.casefold()]})')
        filenames[filename.casefold()] = slug
        row = dict(kind=kind, slug=slug, project_id=pid, picked_loader=loader,
                   version=version['version_number'], version_id=version['id'], loaders=version['loaders'],
                   game_versions=version['game_versions'], filename=filename, source_filename=artifact['filename'],
                   url=artifact['url'], size=artifact['size'], sha1=artifact['hashes']['sha1'])
        if not args.resolve_only:
            args.output.mkdir(parents=True, exist_ok=True)
            target = args.output / filename
            if not target.is_file() or target.stat().st_size != row['size'] or digest(target) != row['sha1']:
                temporary = target.with_name(target.name + '.part')
                try:
                    request = urllib.request.Request(row['url'], headers={'User-Agent': 'Forbric-compat/1.0'})
                    with urllib.request.urlopen(request, timeout=60) as response, temporary.open('wb') as output:
                        for block in iter(lambda: response.read(1024 * 1024), b''):
                            output.write(block)
                    if temporary.stat().st_size != row['size'] or digest(temporary) != row['sha1']:
                        raise ValueError(f'hash/size mismatch for {filename}')
                    temporary.replace(target)
                finally:
                    if temporary.exists():
                        temporary.unlink()
            row['sha1_ok'] = True
            print(f'DL {filename}')
        manifest.append(row)
    manifest_path = args.manifest or args.output.parent / 'manifest.json'
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print(f'total {len(manifest)} jars; manifest={manifest_path}')
    return 2 if failures else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError) as error:
        print(f'ERROR: {error}', file=sys.stderr)
        sys.exit(2)
