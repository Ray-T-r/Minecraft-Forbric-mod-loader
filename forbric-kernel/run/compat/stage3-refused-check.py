#!/usr/bin/env python3
"""Evidence for the hand check of every jar stage3-sweep.py says the kernel refuses.

For each refused row of stage3-sweep.tsv it opens the jar (never the kernel's code: an independent reading) and
prints what a reviewer needs to decide whether the refusal is right:
  - the loader manifests it carries and the mod ids they declare,
  - its @Mod classes, MinecraftForge's and NeoForge's (found by the annotation descriptor in the class bytes),
  - its Fabric entrypoints and mixin configs, and whether each named class / config is in the jar,
  - how many of its classes name net/minecraftforge, net/neoforged, net/fabricmc,
  - the jars nested in it,
  - and whether Modrinth has a NeoForge or Fabric build of the same mod for 26.2: by the jar's SHA-1 first
    (POST /v2/version_files), else by a search on the mod's display name / id.

Usage: python3 run/compat/stage3-refused-check.py <stage3-sweep.tsv> <out.tsv> [--offline]
"""

import csv
import io
import json
import re
import sys
import time
import urllib.parse
import subprocess
import zipfile

API = 'https://api.modrinth.com/v2'
AGENT = 'Forbric-stage3-sweep (github.com/Ray-T-r/Minecraft-Forbric-mod-loader)'
FORGE_MOD = b'Lnet/minecraftforge/fml/common/Mod;'
NEO_MOD = b'Lnet/neoforged/fml/common/Mod;'
MC = '26.2'


def api(path, body=None):
    """GET/POST the Modrinth API through curl: the python.org interpreters ship without a CA bundle, and a request
    that cannot verify must fail loudly, not read as "not on Modrinth"."""
    command = ['curl', '-sS', '--fail-with-body', '-m', '30', '--retry', '3', '-A', AGENT, API + path]
    if body is not None:
        command += ['-H', 'Content-Type: application/json', '--data', json.dumps(body)]
    for attempt in range(4):
        result = subprocess.run(command, capture_output=True, text=True)
        if result.returncode == 0:
            return json.loads(result.stdout)
        if '404' in result.stderr:
            return None
        if '429' in result.stderr:
            time.sleep(10)
            continue
        raise SystemExit(f'Modrinth request {path} failed: {result.stderr.strip()}')
    raise SystemExit(f'Modrinth request {path} was rate-limited four times')


def lenient_json(text):
    try:
        return json.loads(text)
    except ValueError:
        text = re.sub(r'/\*.*?\*/', '', text, flags=re.S)
        text = re.sub(r'(?m)^\s*//.*$', '', text)
        return json.loads(re.sub(r',(\s*[}\]])', r'\1', text), strict=False)


def class_name(entry):
    return entry[:-6].replace('/', '.')


def inspect(path):
    facts = {}
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        files = set(names)
        manifests = []
        for manifest in ('fabric.mod.json', 'META-INF/neoforge.mods.toml', 'META-INF/mods.toml'):
            if manifest in files:
                text = jar.read(manifest).decode('utf-8', 'replace')
                if manifest.endswith('.json'):
                    try:
                        meta = lenient_json(text)
                        ids = [meta.get('id')]
                        entry = meta.get('entrypoints') or {}
                        points = []
                        for key, values in entry.items():
                            for value in values if isinstance(values, list) else [values]:
                                value = value.get('value') if isinstance(value, dict) else value
                                cls = str(value).split('::')[0].replace('.', '/') + '.class'
                                points.append(f"{key}={value}{'' if cls in files else ' (ABSENT)'}")
                        facts['fabric_entrypoints'] = ' '.join(points) or 'none (library)'
                        mixins = [m if isinstance(m, str) else m.get('config') for m in meta.get('mixins') or []]
                        facts['fabric_mixins'] = ' '.join(f"{m}{'' if m in files else ' (ABSENT)'}" for m in mixins) or '-'
                    except ValueError as error:
                        ids = [f'UNREADABLE: {error}']
                else:
                    ids = re.findall(r'(?m)^\s*modId\s*=\s*"([^"]+)"', text)
                    configs = re.findall(r'(?m)^\s*config\s*=\s*"([^"]+)"', text)
                    if configs:
                        key = 'neo_mixins' if 'neoforge' in manifest else 'forge_mixins'
                        facts[key] = ' '.join(f"{c}{'' if c in files else ' (ABSENT)'}" for c in configs)
                manifests.append(f"{manifest.split('/')[-1]}[{','.join(map(str, ids))}]")
        facts['manifests'] = ' '.join(manifests)
        forge_mods, neo_mods, counts = [], [], {'classes': 0, 'forge': 0, 'neo': 0, 'fabric': 0}
        for name in names:
            if not name.endswith('.class') or name.startswith('META-INF/versions/'):
                continue
            data = jar.read(name)
            counts['classes'] += 1
            counts['forge'] += b'net/minecraftforge/' in data
            counts['neo'] += b'net/neoforged/' in data
            counts['fabric'] += b'net/fabricmc/' in data
            if FORGE_MOD in data:
                forge_mods.append(class_name(name))
            if NEO_MOD in data:
                neo_mods.append(class_name(name))
        facts['forge_mod_classes'] = ' '.join(forge_mods) or '-'
        facts['neo_mod_classes'] = ' '.join(neo_mods) or '-'
        facts['class_refs'] = (f"{counts['classes']} classes: {counts['forge']} name net.minecraftforge, "
                               f"{counts['neo']} net.neoforged, {counts['fabric']} net.fabricmc")
        facts['nested'] = ' '.join(n.split('/')[-1] for n in names if n.endswith('.jar')
                                   and (n.startswith('META-INF/jars/') or n.startswith('META-INF/jarjar/'))) or '-'
        facts['data_or_assets'] = 'data/' if any(n.startswith('data/') for n in names) else '-'
        facts['manifest_ids'] = re.findall(r'\[([^\]]*)\]', facts['manifests'])
        mods = jar.read('META-INF/mods.toml').decode('utf-8', 'replace') if 'META-INF/mods.toml' in files else ''
        names_found = re.findall(r'(?m)^\s*displayName\s*=\s*"([^"]+)"', mods)
        facts['display_name'] = names_found[0] if names_found else ''
    return facts


def modrinth_builds(project):
    versions = api(f"/project/{project}/version?game_versions=" + urllib.parse.quote(json.dumps([MC]))) or []
    loaders = sorted({loader for v in versions for loader in v.get('loaders', [])})
    return loaders


def main():
    source, out = sys.argv[1], sys.argv[2]
    offline = '--offline' in sys.argv
    csv.field_size_limit(1 << 30)
    with open(source, encoding='utf-8') as stream:
        rows = [r for r in csv.DictReader(stream, delimiter='\t') if r['after'] == 'refused']
    by_hash = {}
    if not offline:
        hashes = [r['sha1'] for r in rows]
        by_hash = api('/version_files', {'hashes': hashes, 'algorithm': 'sha1'}) or {}
    columns = ['jar', 'sha1', 'kind', 'manifests', 'forge_mod_classes', 'neo_mod_classes', 'fabric_entrypoints',
               'fabric_mixins', 'neo_mixins', 'forge_mixins', 'class_refs', 'nested', 'data_or_assets',
               'modrinth_project', 'modrinth_match', 'modrinth_26.2_loaders', 'packs']
    with open(out, 'w', encoding='utf-8') as stream:
        stream.write('\t'.join(columns) + '\n')
        for row in rows:
            facts = inspect(row['path'])
            facts.update({k: row[k] for k in ('jar', 'sha1', 'kind', 'packs')})
            project, match = '-', '-'
            if not offline and row['sha1'] in by_hash:
                project, match = by_hash[row['sha1']]['project_id'], 'sha1'
            elif not offline:
                query = facts['display_name'] or (facts['manifest_ids'][-1] if facts['manifest_ids'] else row['jar'])
                found = api('/search?limit=3&query=' + urllib.parse.quote(query)) or {}
                hits = found.get('hits') or []
                if hits:
                    project, match = hits[0]['project_id'], f"search '{query}' -> {hits[0]['slug']}"
            if project != '-':
                info = api(f'/project/{project}') or {}
                facts['modrinth_project'] = info.get('slug', project)
                facts['modrinth_26.2_loaders'] = ','.join(modrinth_builds(project)) or 'none for 26.2'
            else:
                facts['modrinth_project'] = '-'
                facts['modrinth_26.2_loaders'] = '-'
            facts['modrinth_match'] = match
            stream.write('\t'.join(str(facts.get(c, '-')).replace('\t', ' ') for c in columns) + '\n')
            stream.flush()
            print(row['jar'], '|', facts['modrinth_project'], '|', facts.get('modrinth_26.2_loaders'), flush=True)


if __name__ == '__main__':
    main()
