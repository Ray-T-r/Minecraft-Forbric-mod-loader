#!/usr/bin/env python3
"""Stage 3 sweep: how the tri-eco kernel loaded every mod jar we have, and how this tree's kernel loads it now.

Plan stage 3 (MinecraftForge refused, zero deletion) requires zero false refusals over every local jar. This tool
collects every mod jar under the given roots (one row per distinct SHA-1, every directory it sits in listed as a
pack), and asks two kernels' OWN arbitration how they load it, through Stage3Sweep.java:

  before  the tri-eco-26.2-final versions of MultiLoaderArbiter, ForbricModDiscoverer and MetadataFailures, compiled
          from that tag's sources (git show) against this tree's classes and put first on the class path. Nothing
          else between the tag and this tree touches how a jar's owner is chosen (the only other change under
          discovery/ is LoaderHalfJudge, which the old classes never call).
  after   this tree's kernel classes as they are.

It writes the TSV (jar, sha1, packs, before, after, per-half reasons, ...) and prints the summary tables: the counts,
every transition, every refused jar, and every jar that now loads as Fabric with a mandatory dependency that is not
supplied in a pack it appears in.

Usage (from forbric-kernel/):
  ./gradlew -q compileJava printBootClasspath $(cat <props>) | tail -1 > /tmp/cp.txt
  python3 run/compat/stage3-sweep.py --classpath "$(cat /tmp/cp.txt)" --out <dir> [--root DIR ...]

Without --root it sweeps ../forbric-kernel/run, ../forbric-kernel/build and ../forbric-loader/run of the main
checkout (FORBRIC_SWEEP_HOME, default /Users/jerry/Documents/Forbric). libraries/, versions/ and .gradle/ are
skipped, as are the staged game and carrier jars and jars with no loader manifest at their root (counted).
"""

import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

HERE = Path(__file__).resolve().parent
KERNEL = HERE.parent.parent
TAG = 'tri-eco-26.2-final'
BEFORE_SOURCES = [
    'forbric-kernel/src/main/java/net/forbric/kernel/boot/MultiLoaderArbiter.java',
    'forbric-kernel/src/main/java/net/forbric/kernel/discovery/ForbricModDiscoverer.java',
    'forbric-kernel/src/main/java/net/forbric/kernel/discovery/MetadataFailures.java',
]
MANIFESTS = ('fabric.mod.json', 'META-INF/neoforge.mods.toml', 'META-INF/mods.toml')
SKIP_DIRS = {'libraries', 'versions', '.gradle'}
# The game and carrier jars a launch stages, and the kernel's own jars: not mods.
STAGED = re.compile(r'^(patched-mc-.*|forge-runtime.*|neoforge-runtime.*|server|client|minecraft-.*|'
                    r'forbric-kernel.*|forbric-installer.*|vanilla-reference.*)\.jar$')
# What the kernel supplies itself (net.forbric.kernel.util.PlatformIds) plus fabric-loader's own provides.
PLATFORM = {'minecraft', 'java', 'fabricloader', 'mixinextras', 'forge', 'neoforge', 'fml', 'fabric-loader'}
COLUMNS = ['jar', 'sha1', 'kind', 'packs', 'declared', 'before', 'after', 'halves', 'before_owner', 'after_owner',
           'mods', 'mandatory_deps', 'path']


def sha1(path):
    digest = hashlib.sha1()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def collect(roots, home):
    """sha1 -> [paths]; plus how many jars were set aside and why."""
    by_sha = collections.OrderedDict()
    skipped = collections.Counter()
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
                if not name.endswith('.jar'):
                    continue
                path = os.path.join(directory, name)
                if STAGED.match(name):
                    skipped['staged game/carrier/kernel jar'] += 1
                    continue
                try:
                    with zipfile.ZipFile(path) as jar:
                        names = set(jar.namelist())
                except (zipfile.BadZipFile, OSError):
                    skipped['not a readable zip'] += 1
                    continue
                if not any(m in names for m in MANIFESTS):
                    skipped['no loader manifest at its root'] += 1
                    continue
                by_sha.setdefault(sha1(path), []).append(path)
    return by_sha, skipped


def extract_nested(by_sha, into):
    """Adds every jar nested in a collected jar (META-INF/jars, META-INF/jarjar, recursively) that carries a loader
    manifest: the kernel arbitrates those too (NestedCandidateInventory asks MultiLoaderArbiter about the extracted
    file). Returns sha1 -> names of the jars it is nested in; a nested copy of a top-level jar only gains that note."""
    import io
    into.mkdir(parents=True, exist_ok=True)
    nested_in = collections.defaultdict(set)
    queue = [(digest, paths[0], 0) for digest, paths in list(by_sha.items())]
    while queue:
        digest, path, depth = queue.pop()
        if depth >= 4:
            continue
        try:
            with zipfile.ZipFile(path) as jar:
                children = [(n, jar.read(n)) for n in jar.namelist() if n.endswith('.jar')
                            and (n.startswith('META-INF/jars/') or n.startswith('META-INF/jarjar/'))]
        except (zipfile.BadZipFile, OSError):
            continue
        for name, data in children:
            try:
                with zipfile.ZipFile(io.BytesIO(data)) as child:
                    names = set(child.namelist())
            except zipfile.BadZipFile:
                continue
            if not any(m in names for m in MANIFESTS):
                continue
            child_digest = hashlib.sha1(data).hexdigest()
            nested_in[child_digest].add(os.path.basename(path))
            if child_digest not in by_sha:
                target = into / child_digest / os.path.basename(name)
                target.parent.mkdir(exist_ok=True)
                target.write_bytes(data)
                by_sha[child_digest] = [str(target)]
                queue.append((child_digest, str(target), depth + 1))
    return nested_in


def pack_of(path, home):
    parent = Path(path).parent
    try:
        rel = parent.relative_to(home)
    except ValueError:
        rel = parent
    text = str(rel)
    return text[:-5] if text.endswith('/mods') else text


def kernel_run(classpath, jars, out, log):
    command = ['java', '-cp', classpath, str(HERE / 'Stage3Sweep.java'), str(jars), str(out)]
    with open(log, 'w') as stream:
        subprocess.run(command, check=True, stdout=stream, stderr=subprocess.STDOUT)
    rows = {}
    for line in Path(out).read_text(encoding='utf-8').splitlines():
        cells = line.split('\t')
        rows[cells[0]] = dict(zip(['path', 'declared', 'owner', 'failed', 'refused', 'verdict', 'halves', 'mods',
                                   'deps', 'aliases'], cells))
    return rows


def before_classes(classpath, work):
    sources = work / 'before-src'
    classes = work / 'before-classes'
    sources.mkdir(parents=True, exist_ok=True)
    classes.mkdir(parents=True, exist_ok=True)
    files = []
    for source in BEFORE_SOURCES:
        text = subprocess.run(['git', '-C', str(KERNEL.parent), 'show', f'{TAG}:{source}'], check=True,
                              capture_output=True, text=True).stdout
        target = sources / Path(source).name
        target.write_text(text, encoding='utf-8')
        files.append(str(target))
    subprocess.run(['javac', '-nowarn', '-proc:none', '-d', str(classes), '-cp', classpath] + files, check=True)
    return classes


def lenient_json(text):
    try:
        return json.loads(text)
    except ValueError:
        text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
        text = re.sub(r'(?m)^\s*//.*$', '', text)
        text = re.sub(r',(\s*[}\]])', r'\1', text)
        try:
            return json.loads(text, strict=False)
        except ValueError:
            return {}


def nested_ids(data, depth=0):
    """Fabric ids + provides and NeoForge ids of every jar nested in `data` (bytes), recursively."""
    ids = set()
    if depth > 4:
        return ids
    try:
        import io
        with zipfile.ZipFile(io.BytesIO(data)) as jar:
            for name in jar.namelist():
                if name.endswith('.jar') and (name.startswith('META-INF/jars/') or name.startswith('META-INF/jarjar/')):
                    inner = jar.read(name)
                    ids |= own_ids(inner)
                    ids |= nested_ids(inner, depth + 1)
    except (zipfile.BadZipFile, OSError):
        pass
    return ids


def own_ids(data):
    import io
    ids = set()
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as jar:
            names = set(jar.namelist())
            if 'fabric.mod.json' in names:
                meta = lenient_json(jar.read('fabric.mod.json').decode('utf-8', 'replace'))
                if isinstance(meta, dict):
                    if isinstance(meta.get('id'), str):
                        ids.add(meta['id'])
                    for alias in meta.get('provides') or []:
                        if isinstance(alias, str):
                            ids.add(alias)
            for manifest in ('META-INF/neoforge.mods.toml',):
                if manifest in names:
                    for match in re.finditer(r'(?m)^\s*modId\s*=\s*"([^"]+)"', jar.read(manifest).decode('utf-8', 'replace')):
                        ids.add(match.group(1))
    except (zipfile.BadZipFile, OSError):
        pass
    return ids


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--classpath', required=True, help="this tree's boot classpath (gradlew printBootClasspath)")
    parser.add_argument('--out', required=True, type=Path, help='directory for stage3-sweep.tsv and work files')
    parser.add_argument('--root', action='append', help='a directory to sweep (repeatable)')
    parser.add_argument('--after-first', help='classes put before --classpath for the "after" run only: a red '
                        'control, e.g. a LoaderHalfJudge compiled with a rule deliberately broken')
    args = parser.parse_args()

    home = Path(os.environ.get('FORBRIC_SWEEP_HOME', '/Users/jerry/Documents/Forbric'))
    roots = args.root or [str(home / 'forbric-kernel/run'), str(home / 'forbric-kernel/build'),
                          str(home / 'forbric-loader/run')]
    args.out.mkdir(parents=True, exist_ok=True)
    work = args.out / 'work'
    work.mkdir(exist_ok=True)

    by_sha, skipped = collect(roots, home)
    nested_in = extract_nested(by_sha, work / 'nested')
    representative = {digest: paths[0] for digest, paths in by_sha.items()}
    (work / 'jars.txt').write_text('\n'.join(representative.values()) + '\n', encoding='utf-8')

    old = before_classes(args.classpath, work)
    before = kernel_run(f'{old}{os.pathsep}{args.classpath}', work / 'jars.txt', work / 'before.tsv',
                        work / 'before.log')
    after_classpath = args.classpath if not args.after_first else f'{args.after_first}{os.pathsep}{args.classpath}'
    after = kernel_run(after_classpath, work / 'jars.txt', work / 'after.tsv', work / 'after.log')

    rows = []
    for digest, paths in by_sha.items():
        path = representative[digest]
        b, a = before.get(path, {}), after.get(path, {})
        packs = sorted({pack_of(p, home) for p in paths if not p.startswith(str(work))}
                       | {'nested:' + parent for parent in nested_in.get(digest, ())})
        rows.append({
            'jar': os.path.basename(path), 'sha1': digest, 'packs': ';'.join(packs),
            'declared': a.get('declared', '?'), 'before': b.get('verdict', '?'), 'after': a.get('verdict', '?'),
            'halves': a.get('halves', '?'), 'before_owner': b.get('owner', '?'), 'after_owner': a.get('owner', '?'),
            'mods': a.get('mods', ''), 'mandatory_deps': a.get('deps', ''), 'path': path,
            '_aliases': a.get('aliases', ''), '_paths': paths,
        })
    rows.sort(key=lambda r: (r['after'] != 'refused', r['before'] == r['after'], r['jar'].lower()))

    # ---- summary -------------------------------------------------------------------------------------------------
    print(f'jars: {sum(len(p) for p in by_sha.values())} mod jar files, {len(by_sha)} distinct by SHA-1; '
          f'set aside: {dict(skipped)}')
    # The old loader's .forbric/cache holds jars IT rewrote (a Forge mod given a facade fabric.mod.json, "X (Forge
    # via Forbric)"): never a player's input, counted apart so they do not blur the real transitions.
    def kind(row):
        if all('/.forbric/cache/' in p for p in row['_paths']):
            return 'old-loader cache'
        return 'nested only' if all(p.startswith(str(work)) for p in row['_paths']) else 'mod jar'
    for row in rows:
        row['kind'] = kind(row)
    with open(args.out / 'stage3-sweep.tsv', 'w', encoding='utf-8') as stream:
        stream.write('\t'.join(COLUMNS) + '\n')
        for row in rows:
            stream.write('\t'.join(str(row[c]) for c in COLUMNS) + '\n')
    print('before -> after, by kind:')
    for (k, b, a), n in sorted(collections.Counter((r['kind'], r['before'], r['after']) for r in rows).items()):
        print(f'  {k:<16} {b:>16} -> {a:<16} {n}')
    # The mechanical half of the zero-false-refusal check: a refused jar that the tri-eco kernel loaded through its
    # Fabric or NeoForge half had a half that worked, so it is a false refusal unless proven otherwise. A refused jar
    # the tri-eco kernel loaded as FORGE still needs the hand check (stage3-refused-check.py): this cannot see a usable
    # half the old arbitration passed over in favour of MinecraftForge.
    regressions = [r for r in rows if r['after'] == 'refused' and r['before'] in ('FABRIC', 'NEOFORGE')]
    print(f'\nrefused although the tri-eco kernel loaded them as Fabric or NeoForge: {len(regressions)}')
    for row in regressions:
        print(f"  FALSE REFUSAL? {row['jar']} ({row['before']} before)  [{row['packs']}]  {row['halves']}")
    print('\nrefused:')
    for row in rows:
        if row['after'] == 'refused':
            print(f"  {row['jar']}  [{row['packs']}]  {row['halves']}")
    print('\nchanged (not refused):')
    for row in rows:
        if row['before'] != row['after'] and row['after'] != 'refused':
            print(f"  {row['jar']}: {row['before']} -> {row['after']}  [{row['packs']}]")

    # Dependencies of every jar that now loads through another family than before, checked pack by pack against what the
    # other jars in the same directory supply (their discovered ids and provides of the family they load as, and
    # every nested jar's ids), plus the platform ids. Presence only; version ranges are not compared.
    supplied_by_dir = collections.defaultdict(set)
    for row in rows:
        loads = row['after']
        if loads in ('refused', 'library') or loads.startswith('lost') or loads in ('error', '?'):
            continue
        ids = {m.split(':', 1)[1] for m in row['mods'].split(',') if m.startswith(loads + ':')}
        ids |= {m.split(':', 1)[1] for m in row['_aliases'].split(',') if m.startswith(loads + ':')}
        ids |= nested_ids(Path(row['_paths'][0]).read_bytes())
        for path in row['_paths']:
            supplied_by_dir[str(Path(path).parent)] |= ids
    print('\nnow loaded by another family, mandatory dependencies of the family it loads as, per pack:')
    for row in rows:
        if row['after'] not in ('FABRIC', 'NEOFORGE') or row['before'] == row['after']:
            continue
        family = row['after'] + ':'
        needs = sorted({d.split('>', 1)[1] for d in row['mandatory_deps'].split(',') if d.startswith(family)})
        for path in row['_paths']:
            if path.startswith(str(work)):
                continue  # nested: supplied by its parent, whose own dependencies are checked where it sits
            have = supplied_by_dir[str(Path(path).parent)] | PLATFORM
            missing = [d for d in needs if d not in have]
            print(f"  {row['kind']:<16} {row['jar']} as {row['after']}  [{pack_of(path, home)}]  needs {needs or '-'}  "
                  f"missing {missing or '-'}")


    # What the refusals cost the jars that still load: a mandatory dependency, in the family a jar loads as, on an id
    # that in its pack only a refused jar supplied. Those packs need the NeoForge or Fabric build of that dependency.
    refused_ids_by_dir = collections.defaultdict(set)
    for row in rows:
        if row['after'] != 'refused':
            continue
        # A refused jar discovers nothing now; its ids are the ones its manifests declare (the before run saw them).
        ids = {m.split(':', 1)[1] for m in before.get(row['path'], {}).get('mods', '').split(',') if ':' in m}
        for path in row['_paths']:
            refused_ids_by_dir[str(Path(path).parent)] |= ids
    print('\nloaded jars whose mandatory dependency, in their pack, only a refused jar supplied:')
    for row in rows:
        loads = row['after']
        if loads not in ('FABRIC', 'NEOFORGE'):
            continue
        needs = {d.split('>', 1)[1] for d in row['mandatory_deps'].split(',') if d.startswith(loads + ':')}
        for path in row['_paths']:
            if path.startswith(str(work)):
                continue
            directory = str(Path(path).parent)
            lost = sorted(d for d in needs if d in refused_ids_by_dir[directory]
                          and d not in supplied_by_dir[directory] | PLATFORM)
            if lost:
                print(f"  {row['jar']} as {loads}  [{pack_of(path, home)}]  needs {lost}")

if __name__ == '__main__':
    main()
