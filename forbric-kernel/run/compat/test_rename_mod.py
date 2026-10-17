"""rename-mod.py: a synthetic mod (Fabric and NeoForge metadata, mixins, refmap, access widener, services, an own
nested module, a nested library, a dependent probe) relocated, read back, run, and compared run against run."""
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile

_spec = importlib.util.spec_from_file_location("rename_mod", Path(__file__).with_name("rename-mod.py"))
rm = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(rm)

STUBS = {
    'org/spongepowered/asm/mixin/Mixin.java': 'package org.spongepowered.asm.mixin; import java.lang.annotation.*; '
    '@Retention(RetentionPolicy.CLASS) @Target(ElementType.TYPE) public @interface Mixin { Class<?>[] value() default {};'
    ' String[] targets() default {}; }',
    'org/spongepowered/asm/mixin/injection/At.java': 'package org.spongepowered.asm.mixin.injection; '
    'public @interface At { String value(); String target() default ""; }',
    'org/spongepowered/asm/mixin/injection/Inject.java': 'package org.spongepowered.asm.mixin.injection; '
    'import java.lang.annotation.*; @Retention(RetentionPolicy.CLASS) public @interface Inject { String[] method(); At[] at(); }',
    'org/spongepowered/asm/mixin/transformer/meta/MixinMerged.java': 'package org.spongepowered.asm.mixin.transformer.meta; '
    'import java.lang.annotation.*; @Retention(RetentionPolicy.CLASS) public @interface MixinMerged { String mixin(); '
    'int priority(); String sessionId(); }',
    'net/fabricmc/loader/api/FabricLoader.java': 'package net.fabricmc.loader.api; public interface FabricLoader { '
    'static FabricLoader getInstance() { return null; } boolean isModLoaded(String id); }',
    'net/neoforged/fml/common/Mod.java': 'package net.neoforged.fml.common; import java.lang.annotation.*; '
    '@Retention(RetentionPolicy.RUNTIME) public @interface Mod { String value(); }',
    'org/spongepowered/asm/mixin/extensibility/IMixinConfigPlugin.java': 'package org.spongepowered.asm.mixin.extensibility; '
    'public interface IMixinConfigPlugin { java.util.List<String> getMixins(); }',
}

MOD = {
    'org/example/samplemod/SampleMod.java': '''package org.example.samplemod;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ServiceLoader;
import net.fabricmc.loader.api.FabricLoader;
import org.example.samplemod.api.Greeter;
import org.example.samplemod.util.Helper;
import org.example.samplemod.util.KeepMeHelper;
@net.neoforged.fml.common.Mod("samplemod")
public class SampleMod implements Runnable {
    public static final String MOD_ID = "samplemod";
    public void run() { }
    public static boolean loaded() { return FabricLoader.getInstance().isModLoaded("samplemod"); }
    static class Inner { }
    public static String describe() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(Class.forName("org.example.samplemod.util.Helper").getName()).append(';');
        sb.append(new Helper().help()).append(';');
        try (InputStream in = SampleMod.class.getResourceAsStream("/org/example/samplemod/util/data.txt")) {
            sb.append(new String(in.readAllBytes(), StandardCharsets.UTF_8).trim()).append(';');
        }
        for (Greeter g : ServiceLoader.load(Greeter.class, SampleMod.class.getClassLoader())) sb.append(g.getClass().getName()).append(';');
        sb.append(KeepMeHelper.class.getSimpleName().contains("KeepMe")).append(';');
        sb.append(MOD_ID).append(';');
        sb.append(new Inner().getClass().getName()).append(';');
        Runnable local = new Runnable() { public void run() { } };
        sb.append(local.getClass().getName());
        return sb.toString();
    }
}
''',
    'org/example/samplemod/api/Greeter.java': 'package org.example.samplemod.api; public interface Greeter { String greet(); }',
    'org/example/samplemod/util/HelloGreeter.java': 'package org.example.samplemod.util; '
    'public class HelloGreeter implements org.example.samplemod.api.Greeter { public String greet() { return "hi"; } }',
    'org/example/samplemod/util/Helper.java': 'package org.example.samplemod.util; public class Helper { '
    'void inner() { } public String help() { inner(); return "help"; } }',
    'org/example/samplemod/util/KeepMeHelper.java': 'package org.example.samplemod.util; public class KeepMeHelper { }',
    'org/example/samplemod/mixin/TargetMixin.java': '''package org.example.samplemod.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
@Mixin(targets = "org.example.samplemod.util.Helper")
public class TargetMixin {
    @Inject(method = "help", at = @At(value = "INVOKE", target = "Lorg/example/samplemod/util/Helper;inner()V"))
    private void onHelp() { }
}
''',
    'org/example/samplemod/mixin/GameMixin.java': '''package org.example.samplemod.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
@Mixin(targets = "net.minecraft.server.MinecraftServer")
public class GameMixin {
    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;tickChildren()V"))
    private void onTick() { }
}
''',
    'org/example/samplemod/mixin/client/ClientMixin.java': 'package org.example.samplemod.mixin.client; '
    '@org.spongepowered.asm.mixin.Mixin(targets = "net.minecraft.client.Minecraft") public class ClientMixin { }',
    'org/example/samplemod/mixin/SamplePlugin.java': 'package org.example.samplemod.mixin; public class SamplePlugin { '
    'public boolean shouldApply(String mixin) { return mixin.startsWith("org.example.samplemod.mixin."); } }',
    'org/example/samplemod/mixin/ListingPlugin.java': 'package org.example.samplemod.mixin; public class ListingPlugin '
    'implements org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin { '
    'public java.util.List<String> getMixins() { return java.util.List.of("ListedMixin"); } }',
    'org/example/samplemod/stash/Stashed.java': 'package org.example.samplemod.stash; public class Stashed { '
    'public static String shortName(String name) { return name.replaceAll(".+\\\\.stash\\\\.", ""); } }',
    'org/example/samplemod/mixin/ListedMixin.java': 'package org.example.samplemod.mixin; '
    '@org.spongepowered.asm.mixin.Mixin(targets = "net.minecraft.client.Minecraft") public class ListedMixin { }',
}

EXTRA = {'org/example/samplemod/extra/Extra.java': 'package org.example.samplemod.extra; public class Extra { '
         'public String run() { return new org.example.samplemod.util.Helper().help(); } }'}
LIB = {'com/other/lib/Lib.java': 'package com.other.lib; public class Lib { public static String name() { return "lib"; } }'}
PROBE = {'probe/Probe.java': 'package probe; public class Probe { public static String run() throws Exception { '
         'return Class.forName("org.example.samplemod.SampleMod").getName() + "/" + new org.example.samplemod.util.Helper().help(); } }'}

RUN = '''import java.net.URL; import java.net.URLClassLoader; import java.nio.file.Path;
public class Run { public static void main(String[] a) throws Exception {
    URLClassLoader l = new URLClassLoader(new URL[] { Path.of(a[0]).toUri().toURL() }, Run.class.getClassLoader());
    System.out.println(l.loadClass(a[1]).getMethod(a[2]).invoke(null)); } }
'''


def jdk_tool(name):
    tool = Path(rm.java()).with_name(name + ('.exe' if os.name == 'nt' else ''))
    return str(tool) if tool.is_file() else shutil.which(name)


def compile_tree(sources, out, classpath=()):
    src = out.parent / (out.name + '-src')
    for rel, text in sources.items():
        (src / rel).parent.mkdir(parents=True, exist_ok=True)
        (src / rel).write_text(text, encoding='utf-8')
    out.mkdir(parents=True, exist_ok=True)
    cp = os.pathsep.join(str(c) for c in classpath)
    subprocess.run([jdk_tool('javac'), '-proc:none', '--release', '17', '-d', str(out)] + (['-cp', cp] if cp else [])
                   + [str(src / rel) for rel in sources], check=True)
    return out


def jar_bytes(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as zf:
        for name, data in entries.items():
            zf.writestr(name, data)
    return buffer.getvalue()


def classes_of(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(root.rglob('*.class'))}


def read_jar(data):
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        return {i.filename: zf.read(i) for i in zf.infolist() if not i.is_dir()}


class RenameModTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='rename-mod-test-')
        t = cls.t = Path(cls.temp.name)
        stubs = compile_tree(STUBS, t / 'stubs')
        mod = compile_tree(MOD, t / 'mod', [stubs])
        extra = compile_tree(EXTRA, t / 'extra', [mod])
        lib = compile_tree(LIB, t / 'lib')
        probe = compile_tree(PROBE, t / 'probe', [mod])
        cls.lib_jar = jar_bytes({'fabric.mod.json': json.dumps({'schemaVersion': 1, 'id': 'somelib', 'version': '1.0'}),
                                 **classes_of(lib)})
        extra_jar = jar_bytes({'fabric.mod.json': json.dumps({'schemaVersion': 1, 'id': 'samplemod-extra', 'version': '1.0',
                                                               'depends': {'samplemod': '*'}}), **classes_of(extra)})
        neo_jar = jar_bytes({'META-INF/neoforge.mods.toml': 'modLoader = "javafml"\n[[mods]]\nmodId = "samplemod_neo"\n'
                             'version = "1.0"\n[[dependencies.samplemod_neo]]\nmodId = "samplemod"\n',
                             **{k.replace('extra/Extra', 'extra/NeoExtra'): v for k, v in classes_of(extra).items()}})
        jarjar = {'jars': [{'identifier': {'group': 'org.example', 'artifact': 'samplemod_neo'},
                            'version': {'range': '[1.0,)', 'artifactVersion': '1.0'},
                            'path': 'META-INF/jarjar/samplemod_neo-1.0.jar', 'isObfuscated': False}]}
        mixin_config = {'required': True, 'package': 'org.example.samplemod.mixin',
                        'plugin': 'org.example.samplemod.mixin.SamplePlugin', 'refmap': 'samplemod-refmap.json',
                        'mixins': ['TargetMixin', 'GameMixin'], 'client': ['client.ClientMixin']}
        refmap = {'mappings': {'org/example/samplemod/mixin/TargetMixin': {
            'Lorg/example/samplemod/util/Helper;inner()V': 'Lorg/example/samplemod/util/Helper;inner()V'}}}
        fabric = {'schemaVersion': 1, 'id': 'samplemod', 'version': '2.0',
                  'entrypoints': {'main': ['org.example.samplemod.SampleMod'], 'client': ['org.example.samplemod.SampleMod::run']},
                  'mixins': ['samplemod.mixins.json'], 'accessWidener': 'samplemod.accesswidener',
                  'jars': [{'file': 'META-INF/jars/samplemod-extra-1.0.jar'}, {'file': 'META-INF/jars/somelib-1.0.jar'}],
                  'depends': {'fabricloader': '*'}, 'custom': {'modmenu': {'parent': 'samplemod'}}}
        toml = ('modLoader = "javafml"\n[[mods]]\nmodId = "samplemod" # the id\nversion = "2.0"\n'
                '[[dependencies.samplemod]]\nmodId = "minecraft"\n[[mixins]]\nconfig = "samplemod.mixins.json"\n'
                '[modproperties.samplemod]\n"x:entry" = "org.example.samplemod.SampleMod"\n')
        entries = {'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\nMixinConfigs: samplemod.mixins.json\n'
                   'Automatic-Module-Name: org.example.samplemod\n\n',
                   'fabric.mod.json': json.dumps(fabric), 'META-INF/neoforge.mods.toml': toml,
                   'samplemod.mixins.json': json.dumps(mixin_config), 'samplemod-refmap.json': json.dumps(refmap),
                   'samplemod.accesswidener': 'accessWidener v2 named\naccessible class net/minecraft/Foo\n'
                   'accessible method net/minecraft/Foo bar (Lorg/example/samplemod/util/Helper;)V\n',
                   'META-INF/services/org.example.samplemod.api.Greeter': 'org.example.samplemod.util.HelloGreeter # impl\n',
                   'org/example/samplemod/util/data.txt': 'payload\n',
                   'assets/samplemod/lang/en_us.json': json.dumps({'item.samplemod.thing': 'Thing'}),
                   'META-INF/jars/samplemod-extra-1.0.jar': extra_jar, 'META-INF/jars/somelib-1.0.jar': cls.lib_jar,
                   'META-INF/jarjar/metadata.json': json.dumps(jarjar), 'META-INF/jarjar/samplemod_neo-1.0.jar': neo_jar,
                   **classes_of(mod)}
        cls.source = t / 'samplemod-2.0.jar'
        cls.source.write_bytes(jar_bytes(entries))
        cls.probe = t / 'probe.jar'
        cls.probe.write_bytes(jar_bytes({'fabric.mod.json': json.dumps({'schemaVersion': 1, 'id': 'probe', 'version': '1',
                                                                         'depends': {'samplemod': '*'}}),
                                         **classes_of(probe)}))
        cls.out = t / 'out'
        rm.main(['rename', str(cls.source), '--out-dir', str(cls.out), '--dependent', str(cls.probe), '--seed', 'test'])
        cls.mapping_file = next(cls.out.glob('*.rename.json'))
        cls.mapping = json.loads(cls.mapping_file.read_text(encoding='utf-8'))
        cls.renamed = cls.out / cls.mapping['output']
        cls.files = read_jar(cls.renamed.read_bytes())
        cls.new_id = cls.mapping['modIds']['samplemod']

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def cls_name(self, old):
        return self.mapping['classes'][old]

    def test_a_mixin_a_config_plugin_lists_by_name_keeps_that_name_in_the_moved_package(self):
        # Mixin loads what getMixins() returns relative to the config's package: the name must still be there.
        listed = self.cls_name('org/example/samplemod/mixin/ListedMixin')
        self.assertTrue(listed.endswith('/ListedMixin'), listed)
        self.assertEqual(listed.rsplit('/', 1)[0], self.cls_name('org/example/samplemod/mixin/TargetMixin').rsplit('/', 1)[0])
        self.assertFalse(listed.startswith('org/example/'), listed)
        self.assertIn('ListedMixin', self.mapping['keptNames'])

    def test_a_package_segment_a_regex_strips_names_against_keeps_its_text(self):
        # Stashed.shortName strips everything up to ".stash.": the segment must still read "stash" once moved.
        stashed = self.cls_name('org/example/samplemod/stash/Stashed')
        self.assertIn('/stash/', stashed)
        self.assertFalse(stashed.startswith('org/example/'), stashed)

    def test_no_name_of_the_mod_is_left_in_entry_names(self):
        self.assertNotIn('samplemod', self.renamed.name)
        leaked = [n for n in self.files if ('samplemod' in n or 'org/example' in n) and not n.startswith('assets/samplemod/')]
        self.assertEqual([], leaked, 'only the resource namespace keeps the old id')
        self.assertIn('assets/samplemod/lang/en_us.json', self.files, 'resource namespaces stay')
        self.assertTrue(re.fullmatch(r's[a-z]{8}', self.new_id), 'an id of letters only: Forge Mixin drops digits from ids')

    def test_metadata_follows_the_classes(self):
        fabric = json.loads(self.files['fabric.mod.json'])
        self.assertEqual(self.new_id, fabric['id'])
        main = fabric['entrypoints']['main'][0]
        self.assertIn(main.replace('.', '/') + '.class', self.files)
        self.assertTrue(fabric['entrypoints']['client'][0].endswith('::run'))
        self.assertEqual(self.new_id, fabric['custom']['modmenu']['parent'], 'a custom value naming the id follows it')
        config_name = fabric['mixins'][0]
        config = json.loads(self.files[config_name])
        package = config['package']
        for entry in config['mixins'] + config['client']:
            self.assertIn(f'{package}.{entry}'.replace('.', '/') + '.class', self.files, entry)
        self.assertIn(config['plugin'].replace('.', '/') + '.class', self.files)
        refmap = json.loads(self.files[config['refmap']])
        target_mixin = self.cls_name('org/example/samplemod/mixin/TargetMixin')
        helper = self.cls_name('org/example/samplemod/util/Helper')
        self.assertEqual({f'L{helper};inner()V': f'L{helper};inner()V'}, refmap['mappings'][target_mixin])
        widener = self.files[fabric['accessWidener']].decode()
        self.assertIn(f'(L{helper};)V', widener)
        self.assertIn('net/minecraft/Foo', widener, 'game names stay')
        toml = self.files['META-INF/neoforge.mods.toml'].decode()
        self.assertIn(f'modId = "{self.new_id}" # the id', toml)
        self.assertIn(f'[[dependencies.{self.new_id}]]', toml)
        self.assertIn(f'[modproperties.{self.new_id}]', toml)
        self.assertIn('modId = "minecraft"', toml)
        self.assertIn(f'config = "{config_name}"', toml)
        self.assertIn('"' + main + '"', toml, 'a class name in a TOML value follows the class')
        manifest = self.files['META-INF/MANIFEST.MF'].decode()
        self.assertIn(f'MixinConfigs: {config_name}', manifest)
        self.assertNotIn('org.example', manifest)
        greeter = self.cls_name('org/example/samplemod/api/Greeter').replace('/', '.')
        service = self.files['META-INF/services/' + greeter].decode()
        self.assertIn(self.cls_name('org/example/samplemod/util/HelloGreeter').replace('/', '.') + ' # impl', service)

    def test_strings_follow_the_classes_and_ids_only_into_lookups(self):
        log = (self.out / (self.mapping['output'] + '.strings.tsv')).read_text(encoding='utf-8')
        helper = self.cls_name('org/example/samplemod/util/Helper')
        self.assertIn('org.example.samplemod.util.Helper\t' + helper.replace('/', '.'), log, 'Class.forName name')
        self.assertIn('/org/example/samplemod/util/data.txt\t/' + self.mapping['resources']['org/example/samplemod/util/data.txt'], log)
        self.assertIn('org.example.samplemod.mixin.\t' + self.mapping['packages']['org/example/samplemod/mixin'].replace('/', '.') + '.', log)
        self.assertIn('org.example.samplemod.util.Helper\t' + helper.replace('/', '.'), log, '@Mixin(targets)')
        self.assertIn(f'Lorg/example/samplemod/util/Helper;inner()V\tL{helper};inner()V', log, '@At(target)')
        self.assertRegex(log, r'LOOKUP-ID\t[^\t]*SampleMod.class\t[^\t]*loaded\(\)Z\tsamplemod\t' + self.new_id)
        self.assertRegex(log, r'ANNOTATION-ID\t[^\t]*SampleMod.class\t[^\t]*Mod;\tsamplemod\t' + self.new_id)
        self.assertNotIn('MinecraftServer', log, 'a game class named by a mixin keeps its name')
        self.assertIn('KeepMeHelper', self.cls_name('org/example/samplemod/util/KeepMeHelper'),
                      'a name the code tests a string against keeps that text')

    def test_nested_own_module_moves_and_a_nested_library_is_untouched(self):
        nested = {k.split('!', 1)[1]: v for k, v in self.mapping['nested'].items()}
        new_extra = nested['META-INF/jars/samplemod-extra-1.0.jar']
        self.assertNotIn('samplemod', new_extra)
        extra = read_jar(self.files[new_extra])
        meta = json.loads(extra['fabric.mod.json'])
        self.assertEqual({self.new_id: '*'}, meta['depends'])
        self.assertTrue(meta['id'].startswith(self.new_id))
        self.assertIn(self.cls_name('org/example/samplemod/extra/Extra') + '.class', extra)
        self.assertEqual(self.lib_jar, self.files['META-INF/jars/somelib-1.0.jar'], 'another library: byte for byte')
        jars = [j['file'] for j in json.loads(self.files['fabric.mod.json'])['jars']]
        self.assertEqual([new_extra, 'META-INF/jars/somelib-1.0.jar'], jars)

    def test_a_neoforge_jar_in_jar_module_moves_with_its_metadata(self):
        jarjar = json.loads(self.files['META-INF/jarjar/metadata.json'])['jars'][0]
        self.assertNotIn('samplemod', jarjar['path'])
        self.assertNotIn('samplemod', jarjar['identifier']['artifact'])
        neo = read_jar(self.files[jarjar['path']])
        toml = neo['META-INF/neoforge.mods.toml'].decode()
        new_neo = self.mapping['modIds']['samplemod_neo']
        self.assertTrue(re.fullmatch(r'[a-z][a-z0-9_]+', new_neo), 'a NeoForge id has no hyphen')
        self.assertIn(f'modId = "{new_neo}"', toml)
        self.assertIn(f'[[dependencies.{new_neo}]]\nmodId = "{self.new_id}"', toml)

    def test_verify_catches_a_class_that_is_not_the_original(self):
        helper = self.cls_name('org/example/samplemod/util/Helper') + '.class'
        greeter = self.cls_name('org/example/samplemod/util/HelloGreeter') + '.class'
        with zipfile.ZipFile(self.renamed) as zf:
            entries = {i.filename: zf.read(i) for i in zf.infolist()}
        entries[helper] = entries[greeter]
        broken = self.t / 'broken.jar'
        broken.write_bytes(jar_bytes(entries))
        out = io.StringIO()
        import contextlib
        with contextlib.redirect_stdout(out):
            code = rm.main(['verify', '--map', str(self.mapping_file), str(self.source), str(broken)])
        self.assertEqual(1, code)
        self.assertIn('DIFFERENT :org/example/samplemod/util/Helper.class', out.getvalue())

    def test_every_class_reads_back_as_the_original(self):
        out = io.StringIO()
        import contextlib
        with contextlib.redirect_stdout(out):
            code = rm.main(['verify', '--map', str(self.mapping_file), str(self.source), str(self.renamed)])
        self.assertEqual(0, code, out.getvalue())
        self.assertRegex(out.getvalue(), r'verify: \d+ classes compared, 0 problems')

    def test_the_renamed_mod_and_its_dependent_run_as_the_original(self):
        run = compile_tree({'Run.java': RUN}, self.t / 'runner')
        java = rm.java()

        def call(jar, cls, method):
            return subprocess.run([java, '-cp', str(run), 'Run', str(jar), cls, method], check=True, capture_output=True,
                                  text=True).stdout.strip()
        apply = rm.reverse_map(self.mapping)
        original = call(self.source, 'org.example.samplemod.SampleMod', 'describe')
        renamed = call(self.renamed, self.cls_name('org/example/samplemod/SampleMod').replace('/', '.'), 'describe')
        self.assertNotEqual(original, renamed)
        self.assertEqual(original, apply(renamed))
        self.assertIn(';payload;', original)
        self.assertIn(';true;samplemod;', original, 'the name test still holds and the namespace string stays')
        both = self.t / 'with-probe'
        both.mkdir(exist_ok=True)
        probe_out = self.out / 'probe.jar'
        merged = both / 'merged.jar'
        merged.write_bytes(jar_bytes({**{k: v for k, v in self.files.items() if k.endswith('.class')},
                                      **{k: v for k, v in read_jar(probe_out.read_bytes()).items() if k.endswith('.class')}}))
        self.assertEqual('org.example.samplemod.SampleMod/help', apply(call(merged, 'probe.Probe', 'run')))
        probe_meta = json.loads(read_jar(probe_out.read_bytes())['fabric.mod.json'])
        self.assertEqual({'probe': 'probe', 'deps': {self.new_id: '*'}}, {'probe': probe_meta['id'], 'deps': probe_meta['depends']})

    def test_merged_member_numbers_that_follow_names_compare_alike(self):
        same = [
            ('fd1a2b3c4$field', 'fd9f8e7d6$field'),
            ('md1a2b3c$x$lambda$init$0$3', 'md9f8e7d$x$lambda$init$0$7'),
            ('wrapOperation$abc000$m$hookDamage$mixinextras$bridge$187', 'wrapOperation$xyz111$m$hookDamage$mixinextras$bridge$190'),
            ('init$forbricresource$a36a0dd', 'init$forbricresource$1b2c3d4'),
            ('md1a2b3c$m$init$forbriccontinuation$a36a0dd$1', 'md4d5e6f$m$init$forbriccontinuation$0f1e2d3$1'),
        ]
        for a, b in same:
            self.assertEqual(rm.normalize_merged(a), rm.normalize_merged(b), (a, b))
        self.assertNotEqual(rm.normalize_merged('init$forbricresource$a36a0dd'), rm.normalize_merged('init$forbriccontinuation$a36a0dd'))
        self.assertNotEqual(rm.normalize_merged('m$mixinextras$bridge$1'), rm.normalize_merged('m$mixinextras$wrapped$1'))

    def test_compare_puts_names_back_and_still_sees_a_changed_count(self):
        stubs = self.t / 'stubs'
        handler_old = 'handler$zza000$samplemod$onHelp'
        handler_new = f'handler$zzb000${self.new_id}$onHelp'
        target_mixin = 'org.example.samplemod.mixin.TargetMixin'
        new_mixin = self.cls_name('org/example/samplemod/mixin/TargetMixin').replace('/', '.')

        def run_dir(name, jar, mixin, handler, calls, config):
            game = self.t / 'runs' / name
            (game / 'mods').mkdir(parents=True)
            shutil.copy2(jar, game / 'mods' / jar.name)
            body = ' '.join(f'{handler}();' for _ in range(calls))
            source = ('package net.minecraft; public class Foo { public void tick() { ' + body + ' } '
                      f'@org.spongepowered.asm.mixin.transformer.meta.MixinMerged(mixin = "{mixin}", priority = 1000, '
                      f'sessionId = "s") private void {handler}() {{ }} }}')
            compile_tree({'net/minecraft/Foo.java': source}, game / '.mixin.out' / 'class', [stubs])
            shutil.rmtree(game / '.mixin.out' / 'class-src')
            (game / 'logs').mkdir()
            simple = mixin.rsplit('.', 1)[1]
            (game / 'logs' / 'latest.log').write_text(
                f'[10:00:00] [main/INFO]: Mixing {simple} from {config} into net.minecraft.Foo\n', encoding='utf-8')
            return game
        config_new = json.loads(self.files['fabric.mod.json'])['mixins'][0]
        a = run_dir('a', self.source, target_mixin, handler_old, 1, 'samplemod.mixins.json')
        b = run_dir('b', self.renamed, new_mixin, handler_new, 1, config_new)
        c = run_dir('c', self.renamed, new_mixin, handler_new, 2, config_new)
        out = io.StringIO()
        import contextlib
        with contextlib.redirect_stdout(out):
            same = rm.main(['compare', '--map', str(self.mapping_file), str(a), str(b)])
        self.assertEqual(0, same, out.getvalue())
        self.assertIn('Mixin census (merged members, call sites, callers): 1 same, 0 only', out.getvalue())
        self.assertIn('Mixin applications (Mixing ... into ...): 1 same', out.getvalue())
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            different = rm.main(['compare', '--map', str(self.mapping_file), str(a), str(c)])
        self.assertEqual(1, different, out.getvalue())
        self.assertIn('0 same, 1 only in the original run, 1 only in the renamed run', out.getvalue())


if __name__ == '__main__':
    unittest.main()
