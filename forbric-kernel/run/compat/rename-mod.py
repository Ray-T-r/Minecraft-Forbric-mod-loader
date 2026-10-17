#!/usr/bin/env python3
"""Relocate a mod jar to unrelated names, for the rename experiment (CLAUDE.md paragraph 2).

    rename-mod.py rename MOD.jar --out-dir DIR [--modid NEW] [--seed S] [--root PKG ...] [--keep PKG ...]
                  [--modid-strings meta|lookups|all] [--rename-tested] [--dependent JAR ...] [--output NAME.jar]
    rename-mod.py reverse --map DIR/NEW.jar.rename.json [FILE ...]
    rename-mod.py census EXPORT_DIR [JAR ...]
    rename-mod.py verify --map DIR/NEW.jar.rename.json MOD.jar DIR/NEW.jar
    rename-mod.py compare --map DIR/NEW.jar.rename.json ORIGINAL_RUN RENAMED_RUN [--probe GLOB ...] [--out FILE]

rename writes a jar that does what MOD.jar does under other names: every class of the mod moves to another package
and class name (references, descriptors, signatures, annotations, Mixin targets, MixinExtras definitions, refmap
entries and class-name strings follow), its mixin configs, refmaps and access widener get other file names, and its
mod ids (Fabric, NeoForge, MinecraftForge; nested jars of its own too) become other ids wherever the metadata, the
manifest, service files, jar-in-jar metadata and its loader annotations name them. Minecraft, the loaders, Mixin,
MixinExtras, other mods and the libraries the mod shades keep their names: only classes under the mod's root
packages move. Resource namespaces (assets/<id>, data/<id>) are kept, and so are mod-id strings in code, except where
the code hands one straight to a loader's mod lookup (--modid-strings lookups, the default): the code goes on
registering under its namespace, and still finds itself. A dependent jar (--dependent: a probe or addon compiled
against the mod) gets its references and dependency ids moved too, keeping its own names.

The roots are found from the mod's own declarations (entrypoints, @Mod classes, mixin packages and plugins, service
providers; also those of a declared jar-in-jar mod whose packages share the mod's): their longest common package, or
each of them when they share none. --root replaces that guess and --keep exempts a package. A nested jar is the mod's
own when it has classes under a root; any other nested jar is copied unchanged unless it refers to the mod. A name
segment the code compares strings against (s.contains("X"), "X".equals(s), as whole words) keeps its text, since the
mod would otherwise behave differently; --rename-tested renames those too. New mod ids are letters only, because
Forge's Mixin drops the digits of a mod id where it names merged members.

verify reads every class of the renamed jar back with javap, puts the old names back, and compares it with the
original class (constant pool order, instruction offsets and frame encodings aside): 0 problems means the rename is
a pure renaming.

Next to the jar it writes NEW.jar.rename.json (every old -> new name), NEW.jar.strings.tsv (each string and mod-id
constant the rewrite changed, where) and NEW.jar.left.tsv (constants that still mention an old package or id: what
was deliberately kept, for review).

reverse prints FILE (or stdin) with every new name put back to its old one. census prints the Mixin export census of
a -Dmixin.debug.export=true run (one row per merged member). compare puts a run of the renamed jar beside a run of
the original: the census, the "Mixing ... into ..." lines, the kernel's [Forbric/...] lines and compatibility
report, and any probe files, each with the renamed run's names put back, and lists every difference.

ASM is found through FORBRIC_ASM_CLASSPATH, FORBRIC_KERNEL_CLASSPATH, the Gradle cache, or the kernel's
printBootClasspath task, in that order; Java through FORBRIC_JAVA, JAVA_HOME or PATH.
"""
import argparse
import base64
import collections
import functools
import glob
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import tomllib
import zipfile

HERE = Path(__file__).resolve().parent
KERNEL = HERE.parents[1]
JAVA_TOOL = HERE / 'ModRename.java'
MOD_ANNOTATIONS = (b'Lnet/neoforged/fml/common/Mod;', b'Lnet/minecraftforge/fml/common/Mod;')
TOML_FILES = ('META-INF/neoforge.mods.toml', 'META-INF/mods.toml')
SIGNATURE = re.compile(r'META-INF/[^/]+\.(SF|RSA|DSA|EC)$', re.I)
CLASS_ATTRIBUTES = ('Main-Class', 'Premain-Class', 'Agent-Class', 'Launcher-Agent-Class', 'MixinConnector', 'TweakClass',
                    'FMLCorePlugin')
FABRIC_DEPENDENCY_KEYS = ('depends', 'recommends', 'suggests', 'conflicts', 'breaks')


# ---------------------------------------------------------------- tools

def java():
    if os.environ.get('FORBRIC_JAVA'):
        return os.environ['FORBRIC_JAVA']
    if os.environ.get('JAVA_HOME'):
        candidate = Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        if candidate.is_file():
            return str(candidate)
    found = shutil.which('java')
    if not found:
        raise SystemExit('rename-mod: no java (set FORBRIC_JAVA or JAVA_HOME)')
    return found


@functools.lru_cache(maxsize=None)
def asm_classpath():
    """asm, asm-tree and asm-commons of one version."""
    if os.environ.get('FORBRIC_ASM_CLASSPATH'):
        return os.environ['FORBRIC_ASM_CLASSPATH']
    wanted = ('asm', 'asm-tree', 'asm-commons')

    def pick(jars):
        by = {}
        for jar in jars:
            m = re.match(r'(asm(?:-tree|-commons)?)-(\d+(?:\.\d+)*)\.jar$', Path(jar).name)
            if m and m.group(1) in wanted:
                by.setdefault(m.group(2), {})[m.group(1)] = jar
        complete = [v for v in by if len(by[v]) == len(wanted)]
        if not complete:
            return None
        best = max(complete, key=lambda v: tuple(int(x) for x in v.split('.')))
        return os.pathsep.join(str(by[best][w]) for w in wanted)

    for variable in ('FORBRIC_KERNEL_CLASSPATH',):
        if os.environ.get(variable):
            chosen = pick(os.environ[variable].split(os.pathsep))
            if chosen:
                return chosen
    home = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle'))
    cache = home / 'caches/modules-2/files-2.1/org.ow2.asm'
    chosen = pick(glob.glob(str(cache / '*' / '*' / '*' / '*.jar')))
    if chosen:
        return chosen
    wrapper = KERNEL / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    command = [str(wrapper), '-q', '-p', str(KERNEL), 'printBootClasspath']
    if os.name == 'nt':
        command = ['cmd', '/d', '/c'] + command
    output = subprocess.run(command, check=True, capture_output=True, text=True).stdout.strip().splitlines()
    chosen = pick(output[-1].split(os.pathsep)) if output else None
    if not chosen:
        raise SystemExit('rename-mod: no ASM (asm, asm-tree, asm-commons) found; set FORBRIC_ASM_CLASSPATH')
    return chosen


def run_java(*arguments, capture=False):
    command = [java(), '-cp', asm_classpath(), str(JAVA_TOOL)] + [str(a) for a in arguments]
    result = subprocess.run(command, check=True, capture_output=capture, text=capture)
    return result.stdout if capture else None


# ---------------------------------------------------------------- names

class Namer:
    """Deterministic unrelated names: the same seed and old name always give the same new name."""

    def __init__(self, seed):
        self.seed = seed
        self.owner = {}

    def letters(self, kind, key, length=8):
        """Lowercase letters only: Forge's Mixin drops the digits of a mod id where it names merged members."""
        digest = hashlib.sha256(f'{self.seed}\0{kind}\0{key}'.encode()).digest()
        number = int.from_bytes(digest, 'big')
        for size in range(length, 40):
            text, value = '', number
            for _ in range(size):
                value, digit = divmod(value, 26)
                text += chr(ord('a') + digit)
            if self.owner.setdefault((kind, text), key) == key:
                return text
        raise RuntimeError('name space exhausted')

    def token(self, kind, key, length=7):
        digest = hashlib.sha256(f'{self.seed}\0{kind}\0{key}'.encode()).digest()
        text = base64.b32encode(digest).decode().lower().rstrip('=')
        for size in range(length, len(text)):
            candidate = text[:size]
            if self.owner.setdefault((kind, candidate), key) == key:
                return candidate
        raise RuntimeError('name space exhausted')


def lenient_json(data):
    text = data.decode('utf-8-sig') if isinstance(data, bytes) else data
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        stripped = re.sub(r'^\s*//.*$', '', text, flags=re.M)
        stripped = re.sub(r'/\*.*?\*/', '', stripped, flags=re.S)
        return json.loads(stripped)


def unescape(text):
    return re.sub(r'\\(.)', lambda m: {'t': '\t', 'n': '\n', 'r': '\r'}.get(m.group(1), m.group(1)), text)


def word_bounds(name):
    """Positions where a word of an identifier starts or ends: VKOnly_InitKeys -> 0, 2, 6, 7, 11, 15."""
    bounds = {0, len(name)}
    for i in range(1, len(name)):
        a, b = name[i - 1], name[i]
        nxt = name[i + 1] if i + 1 < len(name) else ''
        if b in '_$' or a in '_$' or (a.islower() and b.isupper()) or (a.isdigit() != b.isdigit()) \
                or (a.isupper() and b.isupper() and nxt.islower()):
            bounds.add(i)
            if a in '_$':
                bounds.add(i - 1)
    return bounds


def package_of(internal):
    return internal.rsplit('/', 1)[0] if '/' in internal else ''


def under(name, roots):
    return any(name == r or name.startswith(r + '/') for r in roots)


# ---------------------------------------------------------------- jar model

class Jar:
    def __init__(self, data, path='', parent=None, label=None):
        self.data = data
        self.path = path
        self.parent = parent
        self.label = label or path
        with zipfile.ZipFile(io.BytesIO(data)) as zf:
            self.infos = zf.infolist()
            self.files = {}
            for info in self.infos:
                if not info.is_dir():
                    self.files[info.filename] = zf.read(info)
        self.children = {}
        for name, content in self.files.items():
            if name.lower().endswith('.jar') and content[:2] == b'PK':
                try:
                    self.children[name] = Jar(content, name, self, f'{self.label}!{name}')
                except zipfile.BadZipFile:
                    pass
        self.own = False
        self.out = {}          # entry -> new bytes
        self.rename = {}       # entry -> new entry name
        self.changed = False
        self.result = None

    def walk(self):
        yield self
        for child in self.children.values():
            yield from child.walk()

    def classes(self):
        """(entry, internal name) of every class file, multi-release copies included."""
        for name in self.files:
            if not name.endswith('.class'):
                continue
            internal = name[:-6]
            m = re.match(r'META-INF/versions/\d+/(.+)$', internal)
            if m:
                internal = m.group(1)
            elif internal.startswith('META-INF/'):
                continue
            yield name, internal

    def fabric(self):
        raw = self.files.get('fabric.mod.json')
        return lenient_json(raw) if raw else None

    def tomls(self):
        out = {}
        for name in TOML_FILES:
            if name in self.files:
                try:
                    out[name] = tomllib.loads(self.files[name].decode('utf-8-sig'))
                except tomllib.TOMLDecodeError:
                    out[name] = {}
        return out

    def manifest(self):
        return parse_manifest(self.files.get('META-INF/MANIFEST.MF', b''))

    def mod_ids(self):
        ids = []
        meta = self.fabric()
        if meta and isinstance(meta.get('id'), str):
            ids.append(meta['id'])
            provides = meta.get('provides') or []
            ids += [p for p in provides if isinstance(p, str)]
        for toml in self.tomls().values():
            for mod in toml.get('mods', []) or []:
                if isinstance(mod, dict) and isinstance(mod.get('modId'), str):
                    ids.append(mod['modId'])
        return list(dict.fromkeys(ids))

    def declared_mixin_configs(self):
        configs = []
        meta = self.fabric()
        if meta:
            for entry in meta.get('mixins', []) or []:
                name = entry.get('config') if isinstance(entry, dict) else entry
                if isinstance(name, str):
                    configs.append(name)
        for toml in self.tomls().values():
            for mixin in toml.get('mixins', []) or []:
                if isinstance(mixin, dict) and isinstance(mixin.get('config'), str):
                    configs.append(mixin['config'])
        for name in self.manifest()[0].get('MixinConfigs', '').split(','):
            if name.strip():
                configs.append(name.strip())
        return list(dict.fromkeys(configs))

    def declared_mods(self):
        """Nested jars this jar's metadata declares (Fabric jars, jar-in-jar metadata) that are mods, recursively."""
        paths = []
        meta = self.fabric()
        if meta:
            paths += [j.get('file') for j in meta.get('jars', []) or [] if isinstance(j, dict)]
        if 'META-INF/jarjar/metadata.json' in self.files:
            try:
                paths += [j.get('path') for j in lenient_json(self.files['META-INF/jarjar/metadata.json']).get('jars', [])]
            except (ValueError, AttributeError):
                pass
        for path in paths:
            child = self.children.get(path)
            if child is None:
                continue
            if child.fabric() or child.tomls():
                yield child
            yield from child.declared_mods()

    def version(self):
        meta = self.fabric()
        if meta and isinstance(meta.get('version'), str) and '$' not in meta['version']:
            return meta['version']
        for toml in self.tomls().values():
            for mod in toml.get('mods', []) or []:
                if isinstance(mod, dict) and isinstance(mod.get('version'), str) and '$' not in mod['version']:
                    return mod['version']
        return None

    def mixin_configs(self):
        """Declared mixin configs the jar ships, and any other top-level JSON shaped like one (unused dev configs)."""
        found = [c for c in self.declared_mixin_configs() if c in self.files]
        for name, content in self.files.items():
            if '/' in name or not name.endswith('.json') or name in found or name == 'fabric.mod.json':
                continue
            try:
                data = lenient_json(content)
            except (ValueError, UnicodeDecodeError):
                continue
            if isinstance(data, dict) and isinstance(data.get('package'), str) and \
                    any(isinstance(data.get(k), list) for k in ('mixins', 'client', 'server')):
                found.append(name)
        return found

    def anchors(self):
        """Packages (internal form) the mod declares itself through."""
        found = set()
        meta = self.fabric()
        if meta:
            for values in (meta.get('entrypoints') or {}).values():
                for value in values if isinstance(values, list) else [values]:
                    text = value.get('value') if isinstance(value, dict) else value
                    if isinstance(text, str):
                        found.add(package_of(text.split('::')[0].replace('.', '/')))
            for value in (meta.get('languageAdapters') or {}).values():
                if isinstance(value, str):
                    found.add(package_of(value.replace('.', '/')))
        for config in self.declared_mixin_configs():
            if config not in self.files:
                continue
            try:
                data = lenient_json(self.files[config])
            except (ValueError, UnicodeDecodeError):
                continue
            if isinstance(data.get('package'), str):
                found.add(data['package'].replace('.', '/'))
            if isinstance(data.get('plugin'), str):
                found.add(package_of(data['plugin'].replace('.', '/')))
        for entry, internal in self.classes():
            if any(a in self.files[entry] for a in MOD_ANNOTATIONS):
                found.add(package_of(internal))
        for name, content in self.files.items():
            if name.startswith('META-INF/services/') and not name.endswith('/'):
                for line in content.decode('utf-8', 'replace').splitlines():
                    line = line.split('#')[0].strip()
                    if line:
                        found.add(package_of(line.replace('.', '/')))
        class_packages = {package_of(i) for _, i in self.classes()}
        return {p for p in found if p and any(under(c, [p]) for c in class_packages)}


def parse_manifest(data):
    """(main attributes, raw text of the per-entry sections)."""
    text = data.decode('utf-8', 'replace').replace('\r\n', '\n').replace('\r', '\n')
    sections = text.split('\n\n', 1)
    main = collections.OrderedDict()
    current = None
    for line in sections[0].split('\n'):
        if line.startswith(' ') and current:
            main[current] += line[1:]
        elif ':' in line:
            key, value = line.split(':', 1)
            current = key.strip()
            main[current] = value.strip()
    return main, sections[1] if len(sections) > 1 else ''


def write_manifest(main):
    lines = []
    for key, value in main.items():
        line = f'{key}: {value}'.encode()
        first = True
        while line:
            limit = 72 if first else 71
            cut = limit
            while cut < len(line) and (line[cut] & 0xC0) == 0x80:
                cut -= 1
            lines.append((b'' if first else b' ') + line[:cut])
            line = line[cut:]
            first = False
    return b'\r\n'.join(lines) + b'\r\n\r\n'


# ---------------------------------------------------------------- planning

def same_family(a, b):
    """Two packages of one mod: they share three segments, or all of the shorter one."""
    x, y = a.split('/'), b.split('/')
    shared = 0
    for p, q in zip(x, y):
        if p != q:
            break
        shared += 1
    return shared >= min(3, len(x), len(y))


def find_roots(top, override, keep):
    if override:
        return sorted({r.replace('.', '/').strip('/') for r in override})
    declared = list(top.declared_mods())
    own = set(top.anchors())
    if own:
        # A jar-in-jar module of the same mod (its packages share the mod's) roots the rename too; an embedded
        # other mod (Flywheel in Create) does not.
        for child in declared:
            own |= {a for a in child.anchors() if any(same_family(a, t) for t in top.anchors())}
    else:
        nested = declared or [j for j in top.walk() if j is not top and (j.fabric() or j.tomls())]
        own = {a for unit in nested for a in unit.anchors()}
    anchors = sorted(own)
    if not anchors:
        raise SystemExit('rename-mod: found no entrypoint, @Mod class, mixin package or service to root the rename at;'
                         ' pass --root')
    split = [a.split('/') for a in anchors]
    common = []
    for parts in zip(*split):
        if len(set(parts)) != 1:
            break
        common.append(parts[0])
    if len(common) >= 2 or (len(common) == 1 and any(len(s) == 1 for s in split)):
        roots = ['/'.join(common)]
    else:
        roots = []
        for parts in sorted(split, key=len):
            candidate = '/'.join(parts[:3])
            if not under(candidate, roots):
                roots.append(candidate)
    keep = [k.replace('.', '/').strip('/') for k in keep or []]
    return [r for r in roots if not under(r, keep)]


class Plan:
    def __init__(self, top, dependents, roots, keep, namer, new_id, modid_strings):
        self.top = top
        self.dependents = dependents
        self.roots = roots
        self.keep = [k.replace('.', '/').strip('/') for k in keep or []]
        self.namer = namer
        self.modid_strings = modid_strings
        self.classes = {}
        self.packages = {}
        self.resources = {}
        self.ids = {}
        self.files = {}        # (jar label, entry) -> new entry, for renamed config/refmap/AW/nested files
        self.nested = {}       # jar label -> new entry name in its parent
        self.new_root = {r: 'p' + namer.token('root', r) for r in roots}
        self.kept_classes = []
        self.test_fragments = set()
        self.test_equal = set()
        self.mark_own()
        self.plan_ids(new_id)
        self.plan_classes()
        self.plan_files()

    def owned(self, internal):
        return under(internal, self.roots) and not under(internal, self.keep)

    def mark_own(self):
        for unit in self.top.walk():
            unit.own = any(self.owned(i) for _, i in unit.classes())
        self.top.own = True

    def plan_ids(self, new_id):
        own = [u for u in self.top.walk() if u.own]
        top_ids = self.top.mod_ids() or next((u.mod_ids() for u in own if u.mod_ids()), [])
        primary = top_ids[0] if top_ids else None
        if primary:
            self.ids[primary] = new_id or primary[0] + self.namer.letters('id', primary)
        for unit in own:
            hyphen_ok = not unit.tomls()
            for old in unit.mod_ids():
                if old not in self.ids:
                    if primary and old.startswith(primary):
                        self.ids[old] = self.ids[primary] + old[len(primary):]
                    else:
                        self.ids[old] = old[0] + self.namer.letters('id', old)
                pattern = r'[a-z][a-z0-9_\-]{1,63}' if hyphen_ok else r'[a-z][a-z0-9_]{1,63}'
                if not re.fullmatch(pattern, self.ids[old]):
                    raise SystemExit(f'rename-mod: {self.ids[old]!r} is not a valid id for {unit.label}')

    def new_package(self, package):
        for root in sorted(self.roots, key=len, reverse=True):
            if package == root or package.startswith(root + '/'):
                rest = package[len(root):].strip('/')
                parts = rest.split('/') if rest else []
                out = [self.new_root[root]]
                walked = root
                for part in parts:
                    walked += '/' + part
                    out.append(part if self.kept_segment(part) else 'p' + self.namer.token('package', walked, 6))
                return '/'.join(out)
        return None

    def new_class(self, internal):
        package = package_of(internal)
        simple = internal[len(package) + 1:] if package else internal
        if simple in ('package-info', 'module-info'):
            return self.new_package(package) + '/' + simple
        segments = simple.split('$')
        out = []
        walked = package
        for index, segment in enumerate(segments):
            walked += ('/' if index == 0 else '$') + segment
            if segment == '' or segment.isdigit():
                out.append(segment)
                continue
            if self.kept_segment(segment):
                out.append(segment)
                continue
            digits = re.match(r'\d*', segment).group(0)
            out.append(digits + 'C' + self.namer.token('class', walked, 7))
        return self.new_package(package) + '/' + '$'.join(out)

    def plan_classes(self):
        for unit in self.top.walk():
            if not unit.own:
                continue
            for _, internal in unit.classes():
                if self.owned(internal):
                    self.classes.setdefault(internal, self.new_class(internal))
                    package = package_of(internal)
                    while package and self.owned(package) and package not in self.packages:
                        self.packages[package] = self.new_package(package)
                        package = package_of(package)
                else:
                    self.kept_classes.append(f'{unit.label}:{internal}')
            for name in unit.files:
                if name.endswith('.class') or name.endswith('/') or name.lower().endswith('.jar'):
                    continue
                directory = package_of(name)
                if directory and self.owned(directory):
                    self.resources[name] = self.new_package(directory) + '/' + name.rsplit('/', 1)[1]
                    package = directory
                    while package and self.owned(package) and package not in self.packages:
                        self.packages[package] = self.new_package(package)
                        package = package_of(package)

    def renamed_file(self, path):
        directory, _, base = path.rpartition('/')
        new = base
        for old in sorted(self.ids, key=len, reverse=True):
            new = new.replace(old, self.ids[old])
        if new == base:
            first = next(iter(self.ids.values()), 'renamed')
            new = f'{first}_{base}'
        return f'{directory}/{new}' if directory else new

    def renamed_jar(self, unit, path):
        """A jar's new file name: its first mod's new id and version, nothing of the old name."""
        directory, _, base = path.rpartition('/')
        ids = unit.mod_ids()
        new_id = self.ids.get(ids[0]) if ids else None
        if not new_id:
            return self.renamed_file(path)
        version = unit.version()
        name = f'{new_id}-{version}.jar' if version else f'{new_id}.jar'
        name = re.sub(r'[^\w.+\-]', '_', name)
        return f'{directory}/{name}' if directory else name

    def plan_files(self):
        for unit in self.top.walk():
            if not unit.own:
                continue
            meta = unit.fabric()
            targets = list(unit.mixin_configs()) + list(unit.declared_mixin_configs())
            for config in unit.mixin_configs():
                try:
                    data = lenient_json(unit.files[config])
                except ValueError:
                    continue
                if isinstance(data.get('refmap'), str) and data['refmap'] in unit.files:
                    targets.append(data['refmap'])
            if meta and isinstance(meta.get('accessWidener'), str) and meta['accessWidener'] in unit.files:
                targets.append(meta['accessWidener'])
            for toml in unit.tomls().values():
                for at in toml.get('accessTransformers', []) or []:
                    if isinstance(at, dict) and isinstance(at.get('file'), str) and at['file'] in unit.files:
                        targets.append(at['file'])
            for target in dict.fromkeys(targets):
                self.files[(unit.label, target)] = self.renamed_file(target)
            for name, child in unit.children.items():
                if child.own:
                    self.nested[child.label] = self.renamed_jar(child, name)

    def name_tests(self, tests):
        """Keep the name segments the code tests strings against (substring or equality), then plan again."""
        fragments, equal = set(), set()
        for kind, constant in tests:
            internal = constant.rstrip('./').replace('.', '/') if '/' not in constant else constant.rstrip('/')
            if internal in self.classes or internal in self.packages:
                continue  # a whole planned name: rewritten together with the classes it names
            if kind == 'equal':
                equal.add(constant)
            else:
                fragments |= set(re.findall(r'[A-Za-z_][A-Za-z0-9_]{2,}', constant))
        self.test_fragments, self.test_equal = fragments, equal
        if fragments or equal:
            self.classes.clear()
            self.packages.clear()
            self.resources.clear()
            self.kept_classes.clear()
            self.plan_classes()

    def kept_segment(self, segment):
        """A segment equal to a tested constant, or holding a substring-tested fragment as whole words."""
        if segment in self.test_equal:
            return True
        bounds = word_bounds(segment)
        for fragment in self.test_fragments:
            start = segment.find(fragment)
            while start >= 0:
                if start in bounds and start + len(fragment) in bounds:
                    return True
                start = segment.find(fragment, start + 1)
        return False

    def relocate(self, internal):
        """Plan a class under a root that no jar ships (a dead reference stays dead under its new name)."""
        internal = internal.replace('.', '/') if '/' not in internal else internal
        if internal in self.classes or not self.owned(internal) or internal in self.packages:
            return self.classes.get(internal, internal)
        self.classes[internal] = self.new_class(internal)
        self.relocate_package(package_of(internal))
        return self.classes[internal]

    def relocate_package(self, package):
        package = package.strip('/')
        walk = package
        while walk and self.owned(walk) and walk not in self.packages:
            self.packages[walk] = self.new_package(walk)
            walk = package_of(walk)
        return self.packages.get(package, package)

    def declared_class(self, name):
        """A name metadata declares as a class (entrypoint, plugin, provider): relocated when under a root."""
        if not isinstance(name, str) or not re.fullmatch(r'[\w$]+([./][\w$]+)+', name):
            return name
        dotted = '/' not in name
        mapped = self.relocate(name.replace('.', '/') if dotted else name)
        return mapped.replace('/', '.') if dotted else mapped

    def write(self, path):
        lines = ['# ModRename plan v1', f'M\tmodidStrings\t{self.modid_strings}']
        lines += [f'C\t{k}\t{v}' for k, v in sorted(self.classes.items())]
        lines += [f'P\t{k}\t{v}' for k, v in sorted(self.packages.items())]
        lines += [f'R\t{k}\t{v}' for k, v in sorted(self.resources.items())]
        lines += [f'I\t{k}\t{v}' for k, v in sorted(self.ids.items())]
        Path(path).write_text('\n'.join(lines) + '\n', encoding='utf-8')

    # -------- text rewrites shared by every jar

    def dotted(self, name):
        mapped = self.classes.get(name.replace('.', '/'))
        return mapped.replace('/', '.') if mapped else name

    def descriptor_tokens(self, text):
        return re.sub(r'L([A-Za-z_$][\w$]*(?:/[\w$]+)*);',
                      lambda m: 'L' + self.classes.get(m.group(1), m.group(1)) + ';', text)

    def class_token(self, token):
        if token in self.classes:
            return self.classes[token]
        if '.' in token and '/' not in token and token.replace('.', '/') in self.classes:
            return self.dotted(token)
        return self.descriptor_tokens(token)


# ---------------------------------------------------------------- rewriting

def rewrite_fabric(unit, plan):
    raw = unit.files.get('fabric.mod.json')
    if not raw:
        return
    meta = lenient_json(raw)
    before = json.dumps(meta, sort_keys=True)
    ids = plan.ids

    def deep(value):
        if isinstance(value, str):
            if value in ids:
                return ids[value]
            if re.fullmatch(r'[\w$]+(/[\w$]+)+', value):
                return plan.classes.get(value, value)
            return plan.dotted(value) if re.fullmatch(r'[\w$]+(\.[\w$]+)+', value) else value
        if isinstance(value, list):
            return [deep(v) for v in value]
        if isinstance(value, dict):
            return {k: deep(v) for k, v in value.items()}
        return value

    for key in FABRIC_DEPENDENCY_KEYS:
        if isinstance(meta.get(key), dict):
            meta[key] = {ids.get(k, k): v for k, v in meta[key].items()}
    if isinstance(meta.get('custom'), dict):
        meta['custom'] = deep(meta['custom'])
    if unit.own:
        if isinstance(meta.get('id'), str):
            meta['id'] = ids.get(meta['id'], meta['id'])
        if isinstance(meta.get('provides'), list):
            meta['provides'] = [ids.get(p, p) for p in meta['provides']]
        entrypoints = meta.get('entrypoints')
        if isinstance(entrypoints, dict):
            def entry(value):
                if isinstance(value, dict):
                    return dict(value, value=entry(value.get('value')))
                if not isinstance(value, str):
                    return value
                head, sep, member = value.partition('::')
                return plan.declared_class(head) + sep + member
            meta['entrypoints'] = {k: [entry(v) for v in (vs if isinstance(vs, list) else [vs])]
                                   for k, vs in entrypoints.items()}
        if isinstance(meta.get('languageAdapters'), dict):
            meta['languageAdapters'] = {k: plan.declared_class(v) if isinstance(v, str) else v
                                        for k, v in meta['languageAdapters'].items()}
        files = {old: new for (label, old), new in plan.files.items() if label == unit.label}
        if isinstance(meta.get('mixins'), list):
            meta['mixins'] = [dict(m, config=files.get(m.get('config'), m.get('config'))) if isinstance(m, dict)
                              else files.get(m, m) for m in meta['mixins']]
        if isinstance(meta.get('accessWidener'), str):
            meta['accessWidener'] = files.get(meta['accessWidener'], meta['accessWidener'])
        if isinstance(meta.get('jars'), list):
            nested = {child_name: plan.nested[child.label] for child_name, child in unit.children.items()
                      if child.label in plan.nested}
            meta['jars'] = [dict(j, file=nested.get(j.get('file'), j.get('file'))) if isinstance(j, dict) else j
                            for j in meta['jars']]
    if json.dumps(meta, sort_keys=True) != before:
        unit.out['fabric.mod.json'] = (json.dumps(meta, indent=2, ensure_ascii=False) + '\n').encode()


TOML_STRING = re.compile(r'"((?:[^"\\\n]|\\.)*)"|\'([^\'\n]*)\'')


def rewrite_toml(unit, plan):
    files = {old: new for (label, old), new in plan.files.items() if label == unit.label}
    for name in TOML_FILES:
        if name not in unit.files:
            continue
        text = unit.files[name].decode('utf-8-sig')
        out = []
        for line in text.splitlines(keepends=True):
            header = re.match(r'(\s*\[\[?)([^\]]+)(\]\]?.*)$', line, re.S)
            if header and not line.lstrip().startswith('#'):
                keys = [ids_key(k, plan.ids) for k in header.group(2).split('.')]
                line = header.group(1) + '.'.join(keys) + header.group(3)
            else:
                def value(m):
                    s = m.group(1) if m.group(1) is not None else m.group(2)
                    quote = '"' if m.group(1) is not None else "'"
                    if s in plan.ids:
                        return quote + plan.ids[s] + quote
                    if s in files:
                        return quote + files[s] + quote
                    if plan.dotted(s) != s:
                        return quote + plan.dotted(s) + quote
                    return m.group(0)
                code, hash_, comment = split_comment(line)
                line = TOML_STRING.sub(value, code) + hash_ + comment
            out.append(line)
        new = ''.join(out)
        if new != text:
            unit.out[name] = new.encode()


def ids_key(key, ids):
    stripped = key.strip()
    quote = ''
    if len(stripped) > 1 and stripped[0] in '"\'' and stripped[-1] == stripped[0]:
        quote, stripped = stripped[0], stripped[1:-1]
    if stripped in ids:
        return key.replace(quote + stripped + quote, quote + ids[stripped] + quote)
    return key


def split_comment(line):
    """(code, '#', comment) outside quoted strings; ('line', '', '') without a comment."""
    quote = None
    for index, ch in enumerate(line):
        if quote:
            if ch == '\\' and quote == '"':
                continue
            if ch == quote:
                quote = None
        elif ch in '"\'':
            quote = ch
        elif ch == '#':
            return line[:index], '#', line[index + 1:]
    return line, '', ''


def rewrite_manifest(unit, plan):
    raw = unit.files.get('META-INF/MANIFEST.MF')
    if raw is None:
        return
    main, sections = parse_manifest(raw)
    original = dict(main)
    files = {old: new for (label, old), new in plan.files.items() if label == unit.label}
    if 'MixinConfigs' in main:
        main['MixinConfigs'] = ','.join(files.get(c.strip(), c.strip()) for c in main['MixinConfigs'].split(','))
    for key in CLASS_ATTRIBUTES:
        if key in main:
            main[key] = plan.declared_class(main[key])
    if unit.own and 'Automatic-Module-Name' in main:
        ids = unit.mod_ids()
        module = plan.ids.get(ids[0]) if ids else None
        main['Automatic-Module-Name'] = (module or 'm' + plan.namer.token('module', main['Automatic-Module-Name'])).replace('-', '_')
    # Per-entry digests no longer match a rewritten jar, and its signature files are dropped.
    if dict(main) != original or (sections.strip() and unit.changed):
        unit.out['META-INF/MANIFEST.MF'] = write_manifest(main)


def rewrite_services(unit, plan):
    for name in list(unit.files):
        if not name.startswith('META-INF/services/') or name.endswith('/'):
            continue
        service = name[len('META-INF/services/'):]
        lines = unit.files[name].decode('utf-8', 'replace').splitlines()
        new_lines = []
        for line in lines:
            code, hash_, comment = line.partition('#')
            stripped = code.strip()
            new_lines.append(code.replace(stripped, plan.declared_class(stripped)) + hash_ + comment if stripped else line)
        new_service = plan.declared_class(service)
        if new_service != service:
            unit.rename[name] = 'META-INF/services/' + new_service
        if new_lines != lines:
            unit.out[name] = ('\n'.join(new_lines) + '\n').encode()


def rewrite_mixin_configs(unit, plan, refmaps):
    for config in unit.mixin_configs():
        try:
            data = lenient_json(unit.files[config])
        except (ValueError, UnicodeDecodeError):
            continue
        before = json.dumps(data, sort_keys=True)
        package = data.get('package') if isinstance(data.get('package'), str) else None
        new_package = package
        if package and plan.owned(package.replace('.', '/')):
            new_package = plan.relocate_package(package.replace('.', '/')).replace('/', '.')
            data['package'] = new_package
        for key in ('mixins', 'client', 'server'):
            if not isinstance(data.get(key), list) or not package:
                continue
            renamed = []
            for entry in data[key]:
                if not isinstance(entry, str):
                    renamed.append(entry)
                    continue
                full = f'{package}.{entry}'.replace('.', '/')
                mapped = plan.classes.get(full)
                renamed.append(mapped[len(new_package) + 1:].replace('/', '.') if mapped else entry)
            data[key] = renamed
        if isinstance(data.get('plugin'), str):
            data['plugin'] = plan.declared_class(data['plugin'])
        if isinstance(data.get('refmap'), str):
            refmaps.add(data['refmap'])
            plan.files.setdefault((unit.label, data['refmap']), plan.renamed_file(data['refmap']))
            data['refmap'] = plan.files[(unit.label, data['refmap'])]
        if json.dumps(data, sort_keys=True) != before:
            unit.out[config] = (json.dumps(data, indent=2, ensure_ascii=False) + '\n').encode()


def rewrite_access(unit, plan):
    for name, content in unit.files.items():
        widener = name.endswith('.accesswidener') or name.endswith('.classtweaker')
        transformer = name.endswith('accesstransformer.cfg') or (name.startswith('META-INF/') and name.endswith('_at.cfg'))
        if not (widener or transformer):
            continue
        text = content.decode('utf-8', 'replace')
        out = []
        for line in text.splitlines(keepends=True):
            code, hash_, comment = line.partition('#')
            tokens = re.split(r'(\s+)', code)
            out.append(''.join(plan.class_token(t) if t.strip() else t for t in tokens) + hash_ + comment)
        new = ''.join(out)
        if new != text:
            unit.out[name] = new.encode()


def rewrite_jarjar(unit, plan):
    name = 'META-INF/jarjar/metadata.json'
    if name not in unit.files or not unit.own:
        return
    data = lenient_json(unit.files[name])
    before = json.dumps(data, sort_keys=True)
    for jar in data.get('jars', []) or []:
        child = unit.children.get(jar.get('path'))
        if child is None or child.label not in plan.nested:
            continue
        jar['path'] = plan.nested[child.label]
        ident = jar.get('identifier') or {}
        if isinstance(ident.get('group'), str) and ident['group'].replace('.', '/') in plan.packages:
            ident['group'] = plan.packages[ident['group'].replace('.', '/')].replace('/', '.')
        if isinstance(ident.get('artifact'), str):
            ident['artifact'] = plan.renamed_file(ident['artifact'])
    if json.dumps(data, sort_keys=True) != before:
        unit.out[name] = (json.dumps(data, indent=2) + '\n').encode()


# ---------------------------------------------------------------- assembly

def assemble(unit, plan, class_out):
    """New bytes of the unit (children first); the original bytes when nothing in it changed."""
    for child in unit.children.values():
        assemble(child, plan, class_out)
    children_changed = any(c.changed for c in unit.children.values())
    classes_changed = any(class_out.get((unit.label, e), (None, False))[1] for e, _ in unit.classes())
    unit.changed = unit.own or children_changed or classes_changed or bool(unit.out) or bool(unit.rename)
    rewrite_manifest(unit, plan)
    unit.changed = unit.changed or 'META-INF/MANIFEST.MF' in unit.out
    if not unit.changed:
        unit.result = unit.data
        return
    files = {old: new for (label, old), new in plan.files.items() if label == unit.label}
    had_dirs = any(i.is_dir() for i in unit.infos)
    buffer = io.BytesIO()
    written = set()
    with zipfile.ZipFile(buffer, 'w') as zf:
        def put(info, name, data):
            if name in written:
                return
            written.add(name)
            clone = zipfile.ZipInfo(name, info.date_time)
            clone.compress_type = info.compress_type
            clone.external_attr = info.external_attr
            clone.create_system = info.create_system
            clone.comment = info.comment
            zf.writestr(clone, data)

        for info in unit.infos:
            name = info.filename
            if SIGNATURE.match(name):
                continue
            if info.is_dir():
                # A moved package's directory entry is written again below, under its new name.
                if not (unit.own and plan.owned(name.rstrip('/'))):
                    put(info, name, b'')
                continue
            if name.endswith('.class'):
                path, _ = class_out.get((unit.label, name), (None, False))
                version = re.match(r'(META-INF/versions/\d+/)(.+)$', name[:-6])
                prefix, bare = (version.group(1), version.group(2)) if version else ('', name[:-6])
                new_name = prefix + plan.classes[bare] + '.class' if unit.own and bare in plan.classes else name
                put(info, new_name, Path(path).read_bytes() if path else unit.files[name])
                continue
            if name in unit.children:
                child = unit.children[name]
                put(info, plan.nested.get(child.label, name), child.result)
                continue
            new_name = unit.rename.get(name, files.get(name, name))
            if unit.own and name in plan.resources:
                new_name = plan.resources[name]
            put(info, new_name, unit.out.get(name, unit.files[name]))
        if had_dirs:
            missing = set()
            for name in list(written):
                parent = name.rstrip('/').rpartition('/')[0]
                while parent and parent + '/' not in written:
                    missing.add(parent + '/')
                    parent = parent.rpartition('/')[0]
            template = next(i for i in unit.infos if i.is_dir())
            for directory in sorted(missing):
                put(template, directory, b'')
    unit.result = buffer.getvalue()


# ---------------------------------------------------------------- commands

def rename(args):
    source = Path(args.jar).resolve()
    out_dir = Path(args.out_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    top = Jar(source.read_bytes(), '', None, source.name)
    dependent_paths = [Path(d).resolve() for d in args.dependent or []]
    dependents = [Jar(d.read_bytes(), '', None, d.name) for d in dependent_paths]
    roots = find_roots(top, args.root, args.keep)
    plan = Plan(top, dependents, roots, args.keep, Namer(args.seed), args.modid, args.modid_strings)
    if not plan.classes:
        raise SystemExit(f'rename-mod: no class under {roots}')

    output = Path(args.output) if args.output else out_dir / plan.renamed_jar(top, source.name)
    if not output.is_absolute():
        output = out_dir / output
    for dep_path in dependent_paths + [source]:
        if (out_dir / dep_path.name).resolve() == dep_path or output.resolve() == dep_path:
            raise SystemExit(f'rename-mod: {dep_path.name} would overwrite its input; use another --out-dir')
    work = Path(tempfile.mkdtemp(prefix='rename-mod-', dir=out_dir))
    try:
        plan_file = work / 'plan.tsv'
        jobs = []
        class_out = {}
        refmaps = {}
        all_units = [u for top_unit in [top] + dependents for u in top_unit.walk()]
        for index, unit in enumerate(all_units):
            for entry, internal in unit.classes():
                src = work / 'in' / str(index) / entry
                dst = work / 'out' / str(index) / entry
                src.parent.mkdir(parents=True, exist_ok=True)
                src.write_bytes(unit.files[entry])
                role = 'own' if unit.own else 'other'
                jobs.append(f'class\t{role}\t{src}\t{dst}\t{unit.label}:{entry}')
                class_out[(unit.label, entry)] = (dst, None)
        # Classes under a root that the code names but the jar does not ship move too, so they stay as absent.
        (work / 'jobs.tsv').write_text('\n'.join(jobs) + '\n', encoding='utf-8')
        (work / 'roots.txt').write_text('\n'.join(roots) + '\n', encoding='utf-8')
        refs = run_java('refs', work / 'roots.txt', work / 'jobs.tsv', capture=True).splitlines()
        if not args.rename_tested:
            plan.name_tests([tuple(unescape(part) for part in line.split('\t')[1:3])
                             for line in refs if line.startswith('TEST\t')])
        for line in refs:
            if line.startswith('REF\t'):
                plan.relocate(line[4:])
        for index, unit in enumerate(all_units):
            rewrite_fabric(unit, plan)
            rewrite_toml(unit, plan)
            rewrite_services(unit, plan)
            unit_refmaps = set()
            if unit.own:
                rewrite_mixin_configs(unit, plan, unit_refmaps)
            else:
                for config in unit.mixin_configs():
                    try:
                        ref = lenient_json(unit.files[config]).get('refmap')
                    except ValueError:
                        ref = None
                    if isinstance(ref, str):
                        unit_refmaps.add(ref)
            rewrite_access(unit, plan)
            rewrite_jarjar(unit, plan)
            handled = {'fabric.mod.json', 'META-INF/jarjar/metadata.json'} | set(unit.mixin_configs())
            texts = set(unit_refmaps)
            if unit.own:
                # Other JSON outside assets/ and data/ (Loom's interface injections, coremod lists): class names in it.
                texts |= {n for n in unit.files if n.endswith('.json') and not n.startswith(('assets/', 'data/'))
                          and n not in handled}
            for ref in sorted(texts):
                if ref not in unit.files:
                    continue
                src = work / 'in' / str(index) / ref
                dst = work / 'out' / str(index) / ref
                src.parent.mkdir(parents=True, exist_ok=True)
                src.write_bytes(unit.files[ref])
                jobs.append(f'json\t-\t{src}\t{dst}\t{unit.label}:{ref}')
                refmaps[(unit.label, ref)] = dst
        plan.write(plan_file)
        (work / 'jobs.tsv').write_text('\n'.join(jobs) + '\n', encoding='utf-8')
        log = work / 'log.tsv'
        run_java('remap', plan_file, work / 'jobs.tsv', log)
        status = {}
        string_lines = []
        for line in log.read_text(encoding='utf-8').splitlines():
            kind, rest = line.split('\t', 1)
            if kind in ('CHANGED', 'SAME'):
                status[rest] = kind == 'CHANGED'
            else:
                string_lines.append(line)
        for key, (dst, _) in list(class_out.items()):
            class_out[key] = (dst, status.get(f'{key[0]}:{key[1]}', False))
        for (label, ref), dst in refmaps.items():
            if status.get(f'{label}:{ref}'):
                unit = next(u for u in all_units if u.label == label)
                unit.out[ref] = Path(dst).read_bytes()
        for top_unit in [top] + dependents:
            assemble(top_unit, plan, class_out)
        output.write_bytes(top.result)
        for dep in dependents:
            (out_dir / dep.label).write_bytes(dep.result)
        mapping = {
            'tool': 'rename-mod.py', 'source': source.name, 'output': output.name, 'seed': args.seed,
            'roots': {r: plan.new_root[r] for r in roots}, 'modIds': plan.ids,
            'files': {f'{label}:{old}': new for (label, old), new in sorted(plan.files.items())},
            'nested': plan.nested, 'modidStrings': args.modid_strings,
            'dependents': [d.label for d in dependents],
            'ownUnits': [u.label for u in top.walk() if u.own],
            'keptUnits': [u.label for u in top.walk() if not u.own],
            'keptClasses': plan.kept_classes,
            'testedFragments': sorted(plan.test_fragments), 'testedEqual': sorted(plan.test_equal),
            'keptNames': sorted({seg for name in plan.classes for seg in re.split(r'[/$]', name)
                                 if plan.kept_segment(seg)}),
            'classes': dict(sorted(plan.classes.items())), 'packages': dict(sorted(plan.packages.items())),
            'resources': dict(sorted(plan.resources.items())),
        }
        mapping_file = output.with_name(output.name + '.rename.json')
        mapping_file.write_text(json.dumps(mapping, indent=1) + '\n', encoding='utf-8')
        output.with_name(output.name + '.strings.tsv').write_text(
            'kind\tfile\twhere\told\tnew\n' + '\n'.join(string_lines) + '\n', encoding='utf-8')
        scan_jobs = [j for j in jobs if j.startswith('class\town')]
        (work / 'scan.tsv').write_text('\n'.join(scan_jobs) + '\n', encoding='utf-8')
        left = run_java('scan', plan_file, work / 'scan.tsv', capture=True)
        output.with_name(output.name + '.left.tsv').write_text('kind\tclass\tconstant\n' + '\n'.join(
            line.split('\t', 1)[1] for line in left.splitlines() if line.startswith('LEFT\t')) + '\n', encoding='utf-8')
        kinds = collections.Counter(line.split('\t', 1)[0] for line in string_lines)
        print(f'rename-mod: {source.name} -> {output}')
        print(f'  roots: ' + ', '.join(f'{r} -> {plan.new_root[r]}' for r in roots))
        print(f'  mod ids: ' + ', '.join(f'{k} -> {v}' for k, v in plan.ids.items()))
        print(f'  classes relocated: {len(plan.classes)}; kept (outside the roots): {len(plan.kept_classes)};'
              f' resources moved: {len(plan.resources)}')
        print(f'  own jars: {len(mapping["ownUnits"])}; kept jars: {len(mapping["keptUnits"])}; files renamed: '
              + ', '.join(f'{k.split(":", 1)[1]} -> {v}' for k, v in mapping['files'].items()))
        print(f'  string rewrites: {dict(kinds)}; constants left for review: {left.count(chr(10))}')
        if mapping['keptNames']:
            print(f'  names kept because the code tests strings against them: {", ".join(mapping["keptNames"])}')
        for dep in dependents:
            print(f'  dependent: {dep.label} -> {out_dir / dep.label} ({"changed" if dep.changed else "unchanged"})')
    finally:
        if not args.keep_work:
            shutil.rmtree(work, ignore_errors=True)


def reverse_map(mapping):
    pairs = {}
    for old, new in mapping['classes'].items():
        pairs[new] = old
        pairs[new.replace('/', '.')] = old.replace('/', '.')
    for old, new in mapping['packages'].items():
        pairs.setdefault(new, old)
        pairs.setdefault(new.replace('/', '.'), old.replace('/', '.'))
    for old, new in mapping['resources'].items():
        pairs.setdefault(new, old)
    for key, new in mapping['files'].items():
        old = key.split(':', 1)[1]
        pairs.setdefault(new, old)
        pairs.setdefault(new.rsplit('/', 1)[-1], old.rsplit('/', 1)[-1])
    for label, new in mapping['nested'].items():
        old = label.rsplit('!', 1)[-1]
        pairs.setdefault(new, old)
        pairs.setdefault(new.rsplit('/', 1)[-1], old.rsplit('/', 1)[-1])
    pairs.setdefault(mapping['output'], mapping['source'])
    pairs.setdefault(mapping['output'].removesuffix('.jar'), mapping['source'].removesuffix('.jar'))
    for label, new in mapping['nested'].items():
        old = label.rsplit('!', 1)[-1].rsplit('/', 1)[-1]
        pairs.setdefault(new.rsplit('/', 1)[-1].removesuffix('.jar'), old.removesuffix('.jar'))
    for old, new in mapping['modIds'].items():
        pairs.setdefault(new, old)
    for old, new in mapping['roots'].items():
        pairs.setdefault(new, old)
        pairs.setdefault(new.replace('/', '.'), old.replace('/', '.'))
    # Mixin names a mixin relative to its config's package ("sub.Name"): put back each generated segment too.
    for old, new in list(mapping['classes'].items()) + list(mapping['packages'].items()):
        old_parts = re.split(r'([/$])', old)
        new_parts = re.split(r'([/$])', new)
        # Below the root the two names have the same shape; the root itself may have had more segments.
        for o, n in zip(reversed(old_parts), reversed(new_parts)):
            if n != o and re.fullmatch(r'\d*[Cp][a-z2-7]{6,}', n):
                pairs.setdefault(n, o)
                # A local class's simple name (InnerClasses, getSimpleName) is the segment without its digits.
                pairs.setdefault(n.lstrip('0123456789'), o.lstrip('0123456789'))
    keys = sorted(pairs, key=len, reverse=True)
    pattern = re.compile('|'.join(re.escape(k) for k in keys)) if keys else None

    def apply(text):
        return pattern.sub(lambda m: pairs[m.group(0)], text) if pattern else text
    return apply


def reverse(args):
    apply = reverse_map(json.loads(Path(args.map).read_text(encoding='utf-8')))
    if not args.files:
        sys.stdout.write(apply(sys.stdin.read()))
    for name in args.files:
        sys.stdout.write(apply(Path(name).read_text(encoding='utf-8', errors='replace')))


def census(args):
    sys.stdout.write(run_java('census', args.export, *args.jars, capture=True))


# ---------------------------------------------------------------- verify

# ASM drops an empty LocalVariableTable (header only), so the headers are not compared; their rows are.
JAVAP_DROP = re.compile(r'^\s*(Classfile |Last modified |SHA-256 checksum|MD5 checksum|Compiled from |SourceFile: |'
                        r'minor version|major version|LocalVariableTable:|LocalVariableTypeTable:|'
                        r'Start\s+Length\s+Slot\s+Name\s+Signature|\s*$)')


JAVAP_OFFSETS = [
    # A rewritten constant pool can push a constant past index 255 (ldc becomes ldc_w) and move every later offset.
    (re.compile(r'#\d+'), '#'),
    (re.compile(r'^(-?\d+|default): \d+$'), r'\1: @'),
    (re.compile(r'^\d+: '), ''),
    (re.compile(r'\buninitialized \d+'), 'uninitialized @'),
    (re.compile(r'\bldc_w\b'), 'ldc'),
    (re.compile(r'\b(goto|jsr)_w\b'), r'\1'),
    (re.compile(r'^(if\w*|goto|jsr) \d+'), r'\1 @'),
    (re.compile(r'^line (\d+): \d+$'), r'line \1: @'),
    (re.compile(r'^\d+ \d+ \d+ (Class \S+|any)$'), r'@ @ @ \1'),
    (re.compile(r'^\d+ \d+ (\d+ \S+ \S+)$'), r'@ @ \1'),
    (re.compile(r'(frame_type|offset_delta) = \d+'), r'\1 = @'),
    (re.compile(r'start_pc=\d+, length=\d+'), 'start_pc=@, length=@'),
    (re.compile(r'\boffset=\d+'), 'offset=@'),
    (re.compile(r'/\* (same|same_locals_1_stack_item)(_frame)?_extended \*/'), r'/* \1 */'),
    (re.compile(r'/\* same_locals_1_stack_item_frame \*/'), '/* same_locals_1_stack_item */'),
]


def javap_line(line):
    line = re.sub(r'\s+', ' ', line).strip()
    for pattern, repl in JAVAP_OFFSETS:
        line = pattern.sub(repl, line)
    return line


def javap_classes(root, entries):
    """javap -v -p -c of each class file, constant pool and indices dropped: {entry: text}."""
    javap = str(Path(java()).with_name('javap.exe' if os.name == 'nt' else 'javap'))
    out = {}
    for start in range(0, len(entries), 200):
        chunk = entries[start:start + 200]
        text = subprocess.run([javap, '-v', '-p', '-c'] + [str(root / e) for e in chunk], check=True,
                              capture_output=True, text=True).stdout
        parts = re.split(r'^Classfile (.*)$', text, flags=re.M)
        if (len(parts) - 1) // 2 != len(chunk):
            raise RuntimeError(f'javap printed {(len(parts) - 1) // 2} classes for {len(chunk)} files')
        # javap prints the classes in argument order; the path it echoes may be spelled differently (8.3 names).
        for entry, index in zip(chunk, range(1, len(parts), 2)):
            lines, in_pool, bootstrap, methods = [], False, False, []
            for line in parts[index + 1].splitlines():
                if line.startswith('Constant pool:'):
                    in_pool = True
                    continue
                if in_pool:
                    if line.startswith('{'):
                        in_pool = False
                    else:
                        continue
                if line.startswith('BootstrapMethods:'):
                    bootstrap = True
                    continue
                if bootstrap:
                    # ASM writes each distinct bootstrap entry once; javac may have repeated one.
                    if not line.startswith(' '):
                        bootstrap = False
                    elif re.match(r'\s+\d+: #', line):
                        methods.append([])
                        continue
                    else:
                        methods[-1].append(javap_line(line))
                        continue
                if JAVAP_DROP.match(line) or line.strip().startswith('offset_delta = '):
                    continue
                lines.append(javap_line(line))
            lines += ['BootstrapMethod ' + ' | '.join(e) for e in sorted({tuple(e) for e in methods})]
            out[entry] = '\n'.join(lines)
    return out


def verify(args):
    """Every class of the mod, read back from the renamed jar with old names put back, is the original class."""
    mapping = json.loads(Path(args.map).read_text(encoding='utf-8'))
    apply = reverse_map(mapping)
    original, renamed = Jar(Path(args.original).read_bytes()), Jar(Path(args.renamed).read_bytes())

    def units(jar, nested):
        found = {'': jar}
        for name, child in jar.children.items():
            found.update({f'{name}!{k}' if k else name: v for k, v in units(child, nested).items()})
        return found
    old_units = units(original, mapping['nested'])
    new_units = units(renamed, mapping['nested'])
    own = {label.split('!', 1)[1] if '!' in label else '' for label in mapping['ownUnits']}
    nested_by_old = {label.split('!', 1)[1]: new for label, new in mapping['nested'].items()}
    problems, checked = [], 0
    with tempfile.TemporaryDirectory(prefix='rename-verify-') as scratch:
        for key in sorted(own):
            new_key = '!'.join(nested_by_old.get('!'.join(key.split('!')[:i + 1]), part)
                               for i, part in enumerate(key.split('!'))) if key else ''
            old_unit, new_unit = old_units.get(key), new_units.get(new_key)
            if new_unit is None:
                problems.append(f'MISSING-JAR {key} (expected {new_key})')
                continue
            pairs = []
            for entry, internal in old_unit.classes():
                prefix = entry[:len(entry) - len(internal) - 6]
                new_entry = prefix + mapping['classes'].get(internal, internal) + '.class'
                if new_entry not in new_unit.files:
                    problems.append(f'MISSING-CLASS {key}:{entry} (expected {new_entry})')
                    continue
                pairs.append((entry, new_entry))
            a_root, b_root = Path(scratch) / 'a' / str(len(problems) + checked), Path(scratch) / 'b' / str(len(problems) + checked)
            for entry, new_entry in pairs:
                (a_root / entry).parent.mkdir(parents=True, exist_ok=True)
                (a_root / entry).write_bytes(old_unit.files[entry])
                (b_root / new_entry).parent.mkdir(parents=True, exist_ok=True)
                (b_root / new_entry).write_bytes(new_unit.files[new_entry])
            a_text = javap_classes(a_root, [e for e, _ in pairs])
            b_text = javap_classes(b_root, [n for _, n in pairs])
            for entry, new_entry in pairs:
                checked += 1
                # ASM may write a method's attributes in another order than javac did: compare the lines as a multiset.
                a = collections.Counter(a_text.get(entry, '').splitlines())
                b = collections.Counter(apply(b_text.get(new_entry, '')).splitlines())
                if a != b:
                    diff = [f'- {line}' for line in sorted(a - b)] + [f'+ {line}' for line in sorted(b - a)]
                    problems.append(f'DIFFERENT {key}:{entry}\n      ' + '\n      '.join(diff[:args.limit]))
    print(f'rename-mod verify: {checked} classes compared, {len(problems)} problems')
    for problem in problems[:args.limit * 4]:
        print('  ' + problem)
    return 0 if not problems else 1


# ---------------------------------------------------------------- compare

def game_dir(run):
    run = Path(run)
    for candidate in (run, run / 'mc', run / 'game'):
        if (candidate / 'mods').is_dir():
            return candidate
    return run


def export_dir(run):
    for candidate in (game_dir(run) / '.mixin.out', Path(run) / '.mixin.out'):
        if candidate.is_dir():
            return candidate
    return None


def logs_of(run):
    """The run's logs, its own directory written as <RUN> so two runs' lines compare."""
    game = game_dir(run)
    texts = []
    for name in ('logs/latest.log', 'logs/debug.log'):
        if (game / name).is_file():
            texts.append((game / name).read_text(encoding='utf-8', errors='replace'))
            break
    for name in ('console.log', 'stdout.log'):
        for base in {Path(run), game}:
            if (base / name).is_file():
                texts.append((base / name).read_text(encoding='utf-8', errors='replace'))
    text = '\n'.join(texts)
    for base in sorted({str(Path(run).resolve()), str(game.resolve()), str(Path(run)), str(game)}, key=len, reverse=True):
        text = text.replace(base, '<RUN>')
    return text


MERGED_NAME = [
    (re.compile(r'\$md\$([^$\s]+)\$[0-9a-f]+\$\d+'), r'$md$\1$#'),
    (re.compile(r'\$md\$[0-9a-f]{6,}\$\d+'), r'$md$#'),
    (re.compile(r'\bmd[0-9a-f]{6,8}\$'), 'md#$'),
    # Mixin's unique-field prefix and its per-target counter on a renamed lambda depend on the order configs apply in,
    # which follows their (renamed) names: the same member either way.
    (re.compile(r'\bfd[0-9a-f]{6,8}\$'), 'fd#$'),
    (re.compile(r'(\$lambda\$[\w]+\$\d+)\$\d+\b'), r'\1$#'),
    # MixinExtras numbers its bridge and wrapped methods per target, and the kernel suffixes the members it generates with
    # a hash of the mixin's (renamed) name: both follow the names, not the member.
    (re.compile(r'(\$mixinextras\$(?:bridge|wrapped))\$\d+\b'), r'\1$#'),
    (re.compile(r'(\$forbric[a-z]+)\$[0-9a-f]{6,7}\b'), r'\1$#hash'),
    (re.compile(r'\b([A-Za-z]+)\$[a-z]{3}\d{3}\$'), r'\1$###$'),
    (re.compile(r'\$[0-9a-f]{8,}\b'), '$#hash'),
]


def normalize_merged(text):
    for pattern, repl in MERGED_NAME:
        text = pattern.sub(repl, text)
    return text


def census_rows(run, apply=None):
    export = export_dir(run)
    if not export:
        return None
    jars = sorted(str(p) for p in (game_dir(run) / 'mods').glob('*.jar'))
    text = run_java('census', export, *jars, capture=True)
    if apply:
        text = apply(text)
    rows = collections.Counter()
    for line in text.splitlines()[1:]:
        f = line.split('\t')
        if len(f) < 8:
            continue
        # Mixin drops a handler's "<mod id>_" or "<mod id>$" prefix from the merged name, so a renamed id keeps that prefix there;
        # the resolved original name only explains the row, the merged name identifies it.
        rows[tuple([f[0], f[1], mixin_prefix(normalize_merged(f[3])), f[4], f[5], mixin_prefix(normalize_merged(f[6])),
                    f[7]])] += 1
    return rows


LOG_NOISE = [
    (re.compile(r'^\d{4}-\d\d-\d\dT[0-9:.]+Z? ?'), ''),
    (re.compile(r'\b(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun) \w{3} +\d+ [0-9:]+ \w+ \d{4}\b'), '#date'),
    (re.compile(r'\b(ms|after ms|took)=\d+'), r'\1=#'),
    (re.compile(r'^\[[0-9:. ]+\] ?'), ''),
    (re.compile(r'^\[\d{1,2}\w{3}\d{4} [0-9:.]+\] ?'), ''),
    (re.compile(r'\bpid=\d+'), 'pid=#'),
    (re.compile(r'\b\d+ \(\d+(\.\d+)?%\)'), '# (#%)'),
    (re.compile(r'^\[[^\]]*?/(INFO|WARN|ERROR|DEBUG|FATAL)\] ?'), r'[\1] '),
    (re.compile(r'\[[^\]]*?(thread|Thread|pool|Worker|main)[^\]]*?/(INFO|WARN|ERROR|DEBUG|FATAL)\]'), r'[\2]'),
    (re.compile(r'\b\d+(\.\d+)? ?(ms|s|seconds)\b'), '#\\2'),
    (re.compile(r'\b[0-9a-f]{12,}\b'), '#hex'),
    (re.compile(r'@[0-9a-f]{5,}\b'), '@#'),
    (re.compile(r'\b0x[0-9a-f]{6,}\b'), '0x#'),
    (re.compile(r'#\d{6,}\b'), '#id'),
]


def normalize_line(line):
    for pattern, repl in LOG_NOISE:
        line = pattern.sub(repl, line)
    return mixin_prefix(normalize_merged(line.strip()))


def mixin_prefix(text):
    """Mixin drops a handler's "<mod id>_" / "<mod id>$" prefix from merged names; a renamed id keeps it."""
    text = re.sub(r'\$([a-z][a-z0-9_\-]*)\$\1[_$]', r'$\1$', text)
    return re.sub(r'(^|[|(:\s])([a-z][a-z0-9_\-]*)[$_]([^|(\s]*\$\2_\$md\$)', r'\1\3', text)


def mixing_lines(text):
    return collections.Counter(normalize_line(m.group(0)) for m in re.finditer(r'Mixing \S+ from \S+ into \S+', text))


def kernel_lines(text):
    return collections.Counter(normalize_line(line) for line in text.splitlines() if '[Forbric/' in line)


def warning_lines(text, names):
    out = collections.Counter()
    for line in text.splitlines():
        if re.search(r'/(WARN|ERROR|FATAL)\]', line) and any(n in line for n in names) and '[Forbric/' not in line:
            out[normalize_line(line)] += 1
    return out


def report_findings(run, apply=None):
    for base in (game_dir(run), Path(run)):
        path = base / '.forbric-kernel' / 'compatibility-report.json'
        if path.is_file():
            text = path.read_text(encoding='utf-8')
            data = json.loads(apply(text) if apply else text)
            return collections.Counter(normalize_line(f"{f.get('confidence')} {'required ' if f.get('required') else ''}"
                                                      f"{f.get('id')} :: {f.get('detail')}")
                                       for f in data.get('findings', []))
    return None


def unordered(row):
    if not isinstance(row, str):
        return row
    return re.sub(r'\[([^\[\]]*,[^\[\]]*)\]', lambda m: '[' + ', '.join(sorted(x.strip() for x in m.group(1).split(','))) + ']', row)


def diff_counters(title, a, b, out, limit):
    if a is None and b is None:
        out.append(f'== {title}: absent in both runs')
        return 0
    if a is None or b is None:
        out.append(f'== {title}: present only in the {"renamed" if a is None else "original"} run')
        return 1
    only_a = a - b
    only_b = b - a
    same = sum((a & b).values())
    # Lines that differ only in the order of a bracketed list: shown, not counted (an order can differ run to run).
    sorted_a = collections.Counter({unordered(k): v for k, v in only_a.items()}) if only_a else collections.Counter()
    sorted_b = collections.Counter({unordered(k): v for k, v in only_b.items()}) if only_b else collections.Counter()
    order_only = sorted_a & sorted_b
    if order_only:
        only_a = collections.Counter({k: v for k, v in only_a.items() if unordered(k) not in order_only})
        only_b = collections.Counter({k: v for k, v in only_b.items() if unordered(k) not in order_only})
    out.append(f'== {title}: {same} same, {sum(only_a.values())} only in the original run, '
               f'{sum(only_b.values())} only in the renamed run'
               + (f', {sum(order_only.values())} differing only in the order of a [list]' if order_only else ''))
    for row in sorted(order_only)[:limit]:
        out.append(f'   ~ {row}')
    for label, side in (('-', only_a), ('+', only_b)):
        for index, (row, count) in enumerate(sorted(side.items())):
            if index >= limit:
                out.append(f'   {label} ... {len(side) - limit} more')
                break
            text = '\t'.join(row) if isinstance(row, tuple) else row
            out.append(f'   {label} {text}' + (f'   (x{count})' if count > 1 else ''))
    return sum(only_a.values()) + sum(only_b.values())


def image_difference(path_a, path_b):
    from PIL import Image, ImageChops  # only the image check needs Pillow
    a, b = Image.open(path_a).convert('RGB'), Image.open(path_b).convert('RGB')
    if a.size != b.size:
        return 1.0
    diff = ImageChops.difference(a, b)
    pixels = list(diff.get_flattened_data() if hasattr(diff, 'get_flattened_data') else diff.getdata())
    return sum(1 for p in pixels if max(p) > 16) / len(pixels)


def compare(args):
    mapping = json.loads(Path(args.map).read_text(encoding='utf-8'))
    apply = reverse_map(mapping)
    original, renamed = Path(args.original), Path(args.renamed)
    for run in (original, renamed):
        if not run.is_dir():
            raise SystemExit(f'rename-mod: no run directory {run}')
    out = [f'rename-mod compare: original {original}', f'                   renamed  {renamed} (names put back by {args.map})']
    differences = 0
    differences += diff_counters('Mixin census (merged members, call sites, callers)', census_rows(original),
                                 census_rows(renamed, apply), out, args.limit)
    text_a, text_b = logs_of(original), apply(logs_of(renamed))
    differences += diff_counters('Mixin applications (Mixing ... into ...)', mixing_lines(text_a) or None,
                                 mixing_lines(text_b) or None, out, args.limit)
    names = set(mapping['modIds']) | {r.replace('/', '.') for r in mapping['roots']} | set(mapping['roots'])
    names |= {k.split(':', 1)[1] for k in mapping['files']}
    differences += diff_counters('warnings/errors naming the mod', warning_lines(text_a, names) or None,
                                 warning_lines(text_b, names) or None, out, args.limit)
    differences += diff_counters('kernel lines [Forbric/...]', kernel_lines(text_a) or None, kernel_lines(text_b) or None,
                                 out, args.limit)
    differences += diff_counters('compatibility report findings', report_findings(original),
                                 report_findings(renamed, apply), out, args.limit)
    for pattern in args.probe or []:
        found_a = sorted(glob.glob(str(game_dir(original) / pattern), recursive=True))
        for path_a in found_a:
            relative = Path(path_a).relative_to(game_dir(original))
            path_b = game_dir(renamed) / relative
            def lines(text, run):
                for base in sorted({str(Path(run).resolve()), str(game_dir(run).resolve())}, key=len, reverse=True):
                    text = text.replace(base, '<RUN>')
                try:  # a JSON probe file: its key order may differ run to run (Map.of), its content may not
                    text = json.dumps(json.loads(text), sort_keys=True, indent=1)
                except ValueError:
                    pass
                return collections.Counter(normalize_line(line) for line in text.splitlines())
            a = lines(Path(path_a).read_text(encoding='utf-8', errors='replace'), original)
            b = lines(apply(path_b.read_text(encoding='utf-8', errors='replace')), renamed) if path_b.is_file() else None
            differences += diff_counters(f'probe {relative}', a, b, out, args.limit)
        if not found_a:
            out.append(f'== probe {pattern}: no file in the original run')
    for pattern in args.image or []:
        for path_a in sorted(glob.glob(str(game_dir(original) / pattern), recursive=True)):
            relative = Path(path_a).relative_to(game_dir(original))
            path_b = game_dir(renamed) / relative
            if not path_b.is_file():
                out.append(f'== image {relative}: missing in the renamed run')
                differences += 1
                continue
            fraction = image_difference(path_a, path_b)
            verdict = 'same' if fraction <= args.image_threshold else 'DIFFERENT'
            out.append(f'== image {relative}: {fraction:.4%} of pixels differ by more than 16 ({verdict},'
                       f' threshold {args.image_threshold:.2%})')
            differences += verdict != 'same'
    compared = sum(1 for line in out if line.startswith('== ') and 'absent in both' not in line
                   and 'no file in the original run' not in line)
    if compared == 0:
        out.append('RESULT NOTHING-COMPARED (neither run has an export, a log or a probe file this tool reads)')
        differences = -1
    else:
        out.append(f'RESULT {"SAME" if differences == 0 else "DIFFERENT"} ({differences} differing rows)')
    text = '\n'.join(out) + '\n'
    if args.out:
        Path(args.out).write_text(text, encoding='utf-8')
    sys.stdout.write(text)
    return 0 if differences == 0 else (2 if differences < 0 else 1)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('rename', help='write the relocated jar')
    p.add_argument('jar')
    p.add_argument('--out-dir', required=True)
    p.add_argument('--output', help='file name of the renamed jar (default: old ids in the name replaced)')
    p.add_argument('--modid', help='the new id of the jar\'s first mod (default: derived from the seed)')
    p.add_argument('--seed', default='forbric-rename-1')
    p.add_argument('--root', action='append', help='a package to relocate (default: found from the mod\'s declarations)')
    p.add_argument('--keep', action='append', help='a package under a root to leave in place')
    p.add_argument('--modid-strings', choices=('meta', 'lookups', 'all'), default='lookups',
                   help='mod-id strings in code: none, those passed straight to a loader lookup (default), or all '
                        '(all also changes the namespace the code registers under; resources keep theirs)')
    p.add_argument('--rename-tested', action='store_true',
                   help='rename even the name segments the code tests strings against (may change behaviour)')
    p.add_argument('--dependent', action='append', help='a jar compiled against the mod: references follow the rename')
    p.add_argument('--keep-work', action='store_true')
    p.set_defaults(func=rename)
    p = sub.add_parser('reverse', help='put old names back into text')
    p.add_argument('--map', required=True)
    p.add_argument('files', nargs='*')
    p.set_defaults(func=reverse)
    p = sub.add_parser('census', help='Mixin export census')
    p.add_argument('export')
    p.add_argument('jars', nargs='*')
    p.set_defaults(func=census)
    p = sub.add_parser('verify', help='read every renamed class back with old names: is it the original class?')
    p.add_argument('--map', required=True)
    p.add_argument('original')
    p.add_argument('renamed')
    p.add_argument('--limit', type=int, default=20)
    p.set_defaults(func=verify)
    p = sub.add_parser('compare', help='a run of the renamed jar against a run of the original')
    p.add_argument('--map', required=True)
    p.add_argument('original')
    p.add_argument('renamed')
    p.add_argument('--probe', action='append', help='a probe output file (glob, relative to the game dir) to compare')
    p.add_argument('--image', action='append', help='screenshots (glob) to compare pixel by pixel (needs Pillow)')
    p.add_argument('--image-threshold', type=float, default=0.005)
    p.add_argument('--limit', type=int, default=60)
    p.add_argument('--out')
    p.set_defaults(func=compare)
    args = parser.parse_args(argv)
    return args.func(args) or 0


if __name__ == '__main__':
    sys.exit(main())
