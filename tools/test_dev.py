import base64
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.error
import unittest
from unittest.mock import patch
import zipfile

import dev


class DevelopmentWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='forbric dev paths ')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_platform_defaults(self):
        self.assertEqual(dev.default_minecraft_dir('windows', {'APPDATA': 'C:/Users/A B/Roaming'}, self.root),
                         Path('C:/Users/A B/Roaming/.minecraft'))
        self.assertEqual(dev.default_minecraft_dir('linux', {}, self.root), self.root / '.minecraft')
        self.assertEqual(dev.default_minecraft_dir('osx', {}, self.root), self.root / 'Library/Application Support/minecraft')

    def test_rule_order_and_platform_exclusions(self):
        rules = [{'action': 'allow'}, {'action': 'disallow', 'os': {'name': 'windows'}}]
        self.assertFalse(dev.applies(rules, 'windows', 'amd64'))
        self.assertTrue(dev.applies(rules, 'linux', 'amd64'))
        self.assertFalse(dev.applies([{'action': 'allow', 'os': {'name': 'osx'}}], 'linux', 'amd64'))
        self.assertFalse(dev.applies([{'action': 'allow', 'features': {'is_demo_user': True}}], 'osx', 'aarch64'))

    def test_native_architecture_selection(self):
        metadata = {'libraries': [{'name': 'org.lwjgl:lwjgl:3:natives-macos' + suffix,
                                  'rules': [{'action': 'allow', 'os': {'name': 'osx'}}],
                                  'downloads': {'artifact': {'path': suffix or 'intel'}}}
                                 for suffix in ('', '-arm64')]}
        self.assertEqual([p['path'] for p in dev.libraries(metadata, 'osx', 'aarch64')], ['-arm64'])
        self.assertEqual([p['path'] for p in dev.libraries(metadata, 'osx', 'x86_64')], ['intel'])
        self.assertEqual(list(dev.libraries(metadata, 'windows', 'amd64')), [])

    def test_merge_libraries_are_exactly_the_jars_the_version_json_names(self):
        # build-merged-base.sh hands the merge tools this list. The folder also holds an older guava, sorting first:
        # the tools once walked the folder and the first jar in path order answered for a class.
        libraries = self.root / 'libraries'
        listed = {'org/zlib/zlib/1/zlib-1.jar': b'zlib', 'com/google/guava/guava/33/guava-33.jar': b'guava 33',
                  'org/lwjgl/lwjgl/3/lwjgl-3-natives-macos-arm64.jar': b'arm natives'}
        for relative, content in listed.items():
            (libraries / relative).parent.mkdir(parents=True, exist_ok=True)
            (libraries / relative).write_bytes(content)
        stale = libraries / 'com/google/guava/guava/25/guava-25.jar'
        stale.parent.mkdir(parents=True)
        stale.write_bytes(b'guava 25')

        def library(relative, name=None, rules=None):
            artifact = {'path': relative, 'url': 'https://fixture.invalid/' + relative,
                        'sha1': hashlib.sha1(listed.get(relative, b'')).hexdigest()}
            entry = {'name': name or relative, 'downloads': {'artifact': artifact}}
            if rules:
                entry['rules'] = rules
            return entry
        on_osx = [{'action': 'allow', 'os': {'name': 'osx'}}]
        # Out of path order, so a list that sorts shows; the Intel natives are not in the folder, as in a directory
        # tools/dev.py prepared on Apple silicon, and a Windows-only library is not either.
        metadata = {'libraries': [
            library('org/zlib/zlib/1/zlib-1.jar'),
            library('com/google/guava/guava/33/guava-33.jar'),
            library('org/lwjgl/lwjgl/3/lwjgl-3-natives-macos.jar', 'org.lwjgl:lwjgl:3:natives-macos', on_osx),
            library('org/lwjgl/lwjgl/3/lwjgl-3-natives-macos-arm64.jar', 'org.lwjgl:lwjgl:3:natives-macos-arm64', on_osx),
            library('org/win/win/1/win-1.jar', rules=[{'action': 'allow', 'os': {'name': 'windows'}}])]}

        jars, problems = dev.merge_libraries(metadata, libraries, 'osx', 'aarch64')
        self.assertEqual(problems, [])
        self.assertEqual(jars, [libraries / 'org/zlib/zlib/1/zlib-1.jar', libraries / 'com/google/guava/guava/33/guava-33.jar',
                                libraries / 'org/lwjgl/lwjgl/3/lwjgl-3-natives-macos-arm64.jar'])
        self.assertNotIn(stale, jars)

        # A jar the JSON names that is not what it says, or not there, or outside the folder, is refused by name.
        (libraries / 'com/google/guava/guava/33/guava-33.jar').write_bytes(b'GUAVA 33')
        metadata['libraries'].append(library('../escape/1/escape-1.jar'))
        jars, problems = dev.merge_libraries(metadata, libraries, 'osx', 'x86_64')
        self.assertEqual(jars, [libraries / 'org/zlib/zlib/1/zlib-1.jar'])
        self.assertEqual(problems, ['SHA-1 differs from the version JSON: ' + str(libraries / 'com/google/guava/guava/33/guava-33.jar'),
                                    'missing: ' + str(libraries / 'org/lwjgl/lwjgl/3/lwjgl-3-natives-macos.jar'),
                                    'leaves the libraries directory: ../escape/1/escape-1.jar'])

    def test_native_caches_are_separate_for_jvms_with_different_architectures(self):
        args = argparse.Namespace(command='client', mc_dir=None, staged=None, instance=None, natives=None)
        with patch.dict(os.environ, {}, clear=True), patch.object(dev, 'system_name', return_value='osx'):
            arm = dev.options(args, 'aarch64')[-1]
            intel = dev.options(args, 'amd64')[-1]
        self.assertEqual(arm.name, 'osx-arm64')
        self.assertEqual(intel.name, 'osx-x86_64')
        self.assertNotEqual(arm, intel)

    def test_bad_download_preserves_existing_file_and_removes_scratch(self):
        target = self.root / 'artifact.jar'
        target.write_bytes(b'old file')
        with patch.object(dev.urllib.request, 'urlopen', return_value=io.BytesIO(b'corrupt download')):
            with self.assertRaisesRegex(RuntimeError, 'digest/size mismatch'):
                dev.fetch('https://fixture.invalid/data', target, hashlib.sha1(b'good').hexdigest())
        self.assertEqual(target.read_bytes(), b'old file')
        self.assertEqual(list(self.root.iterdir()), [target])

    def test_verified_cache_does_not_touch_network(self):
        target = self.root / 'artifact.jar'
        target.write_bytes(b'cached')
        with patch.object(dev.urllib.request, 'urlopen') as request:
            dev.fetch('https://fixture.invalid/data', target, hashlib.sha1(b'cached').hexdigest(), size=6)
        request.assert_not_called()

    def test_no_assets_prepares_libraries_without_fetching_any_asset(self):
        mc, natives = self.root / 'mc', self.root / 'natives'
        version = mc / f'versions/{dev.MC_VERSION}/{dev.MC_VERSION}.json'
        version.parent.mkdir(parents=True)
        version.write_text(json.dumps({'libraries': [], 'assetIndex': {
            'id': '30', 'url': 'https://piston-meta.mojang.com/index.json', 'sha1': '0' * 40, 'size': 2}}))
        fetched = []

        def fake_fetch(url, target, *rest, **options):
            fetched.append(url)
            target = Path(target)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps({'objects': {'a': {'hash': 'ab' + '0' * 38, 'size': 1}}}) if 'index' in url else 'x')

        with patch.object(dev, 'fetch', side_effect=fake_fetch):
            dev.stage_minecraft(mc, natives, 'x86_64', assets=False)
        self.assertFalse([u for u in fetched if 'resources.download.minecraft.net' in u or 'index' in u], fetched)
        self.assertTrue(dev.assets_skipped(mc))
        self.assertTrue(any('jline' in u for u in fetched), 'the console libraries are still needed: ' + str(fetched))

        fetched.clear()
        with patch.object(dev, 'fetch', side_effect=fake_fetch):
            dev.stage_minecraft(mc, natives, 'x86_64', assets=True)
        self.assertTrue(any('index' in u for u in fetched) and any('resources.download.minecraft.net' in u for u in fetched), fetched)
        self.assertFalse(dev.assets_skipped(mc), 'a full prepare removes the marker')

    def test_verified_download_replaces_bad_cache(self):
        target = self.root / 'artifact.jar'
        target.write_bytes(b'bad')
        with patch.object(dev.urllib.request, 'urlopen', return_value=io.BytesIO(b'good')):
            dev.fetch('https://fixture.invalid/data', target, hashlib.sha1(b'good').hexdigest(), size=4)
        self.assertEqual(target.read_bytes(), b'good')

    def test_local_official_cache_is_verified_before_reuse(self):
        source, target = self.root / 'cache.jar', self.root / 'artifact.jar'
        source.write_bytes(b'good')
        with patch.object(dev.urllib.request, 'urlopen') as request:
            dev.fetch('https://fixture.invalid/data', target, hashlib.sha1(b'good').hexdigest(), cache=source)
        request.assert_not_called()
        self.assertEqual(target.read_bytes(), b'good')
        source.write_bytes(b'corrupt')
        target.unlink()
        with patch.object(dev.urllib.request, 'urlopen', return_value=io.BytesIO(b'good')) as request:
            dev.fetch('https://fixture.invalid/data', target, hashlib.sha1(b'good').hexdigest(), cache=source)
        request.assert_called_once()

    def test_transient_download_failure_retries_without_accepting_partial_bytes(self):
        target = self.root / 'artifact.jar'
        with patch.object(dev.urllib.request, 'urlopen', side_effect=[urllib.error.URLError('timeout'), io.BytesIO(b'good')]), \
                patch.object(dev.time, 'sleep'):
            dev.fetch('https://fixture.invalid/data', target, hashlib.sha1(b'good').hexdigest())
        self.assertEqual(target.read_bytes(), b'good')

    def test_missing_artifact_is_not_retried(self):
        with patch.object(dev.urllib.request, 'urlopen', side_effect=urllib.error.HTTPError('url', 404, 'missing', {}, None)) as request:
            with self.assertRaises(urllib.error.HTTPError):
                dev.fetch('https://fixture.invalid/data', self.root / 'artifact.jar')
        request.assert_called_once()
        self.assertEqual(list(self.root.iterdir()), [])

    def test_paths_cannot_escape_download_directory(self):
        for relative in ('../outside.jar', str(self.root.parent / 'outside.jar')):
            with self.assertRaisesRegex(RuntimeError, 'unsafe artifact path'):
                dev.confined(self.root, relative)

    def launch_fixture(self, with_runtime=True):
        mc, stage = self.root / 'Minecraft With Spaces', self.root / 'stage/run'
        version = mc / 'versions/26.2/26.2.json'
        version.parent.mkdir(parents=True)
        version.write_text(json.dumps({'assetIndex': {'id': '32'}, 'libraries': []}))
        boot = self.root / 'kernel.jar'
        with zipfile.ZipFile(boot, 'w') as archive:
            if with_runtime:
                archive.writestr('META-INF/jars/forbric-kernel-runtime.jar', b'runtime')
        merged = stage / dev.STAGED_FILES[0]
        merged.parent.mkdir(parents=True)
        with zipfile.ZipFile(merged, 'w') as archive:
            archive.writestr('version.json', '{"protocol_version":776}')
            archive.writestr('net/minecraft/NotParentLoaded.class', b'bytecode')
        return {'bootJar': str(boot), 'dependencies': ['dependency.jar']}, mc, stage

    def test_windows_classpaths_and_mac_only_flag(self):
        info, mc, stage = self.launch_fixture()
        with patch.dict(os.environ, {}, clear=True):
            win = dev.launch_arguments('client', info, mc, stage, self.root, self.root, system='windows', pathsep=';')
            mac = dev.launch_arguments('client', info, mc, stage, self.root, self.root, system='osx', pathsep=':')
            linux = dev.launch_arguments('client', info, mc, stage, self.root, self.root, system='linux', pathsep=':')
        self.assertIn(';dependency.jar;', win[win.index('-cp')+1])
        self.assertIn(';', win[win.index('--runtimeJar')+1])
        self.assertNotIn('-XstartOnFirstThread', win)
        self.assertNotIn('-XstartOnFirstThread', linux)
        self.assertIn('-XstartOnFirstThread', mac)
        self.assertIn('Minecraft With Spaces', win[win.index('--assetsDir')+1])
        with zipfile.ZipFile(stage / 'merged-base/forbric-game-metadata.jar') as archive:
            self.assertEqual(archive.namelist(), ['version.json'])

    def test_boot_only_jar_is_refused(self):
        info, mc, stage = self.launch_fixture(False)
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(RuntimeError, 'boot-only'):
                dev.launch_arguments('client', info, mc, stage, self.root, self.root)

    def test_missing_library_is_named_before_launch(self):
        info, mc, stage = self.launch_fixture()
        version = mc / 'versions/26.2/26.2.json'
        metadata = json.loads(version.read_text())
        metadata['libraries'] = [{'downloads': {'artifact': {'path': 'missing/library.jar'}}}]
        version.write_text(json.dumps(metadata))
        with self.assertRaisesRegex(RuntimeError, 'missing Minecraft libraries; run prepare'):
            dev.launch_arguments('server', info, mc, stage, self.root, self.root)

    def test_java_launch_preserves_spaces_quotes_backslashes_and_unicode(self):
        # Compile the actual UTF-8 bridge and exercise Java's manifest classpath resolution.
        source = self.root / 'EchoArgs.java'
        source.write_text('public class EchoArgs { public static void main(String[] a) { for (String s:a) '
                          'System.out.println(java.util.Base64.getEncoder().encodeToString('
                          's.getBytes(java.nio.charset.StandardCharsets.UTF_8))); '
                          'if (System.getProperty("forbric.test") != null) System.out.println('
                          'java.util.Base64.getEncoder().encodeToString(System.getProperty("forbric.test")'
                          '.getBytes(java.nio.charset.StandardCharsets.UTF_8))); } }')
        classes, helper = self.root / 'classes', self.root / 'helper'
        classes.mkdir()
        helper.mkdir()
        java = dev.java_bin()
        env = dev.java_environment(java, minimum=21)
        javac = str(Path(env['JAVA_HOME']) / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac'))
        subprocess.run([javac, '-d', str(classes), str(source)], check=True)
        package = dev.ROOT / 'forbric-kernel-installer/src/main/java/net/forbric/installer/kernel'
        subprocess.run([javac, '--release', '17', '-d', str(helper),
                        str(package / 'Json.java'), str(package / 'DevLaunch.java')], check=True)
        # Relocate after javac so the test does not itself depend on native compiler Unicode argv support.
        unicode_classes = self.root / '中文目录' / 'classes'
        unicode_classes.parent.mkdir()
        classes.rename(unicode_classes)
        arguments = ['C:\\Users\\A B\\.minecraft', '中文路径', 'a"quoted"value', 'literal;classpath', '#leading-comment', '']
        path = self.root / 'command.args'
        for windows in (False, True):
            if os.name == 'nt' and not windows:
                continue  # UTF-8 native @file parsing is not available on every Windows system codepage.
            command = ['-cp', str(unicode_classes), 'EchoArgs'] + arguments
            dev.write_argument_file(path, command)
            output = subprocess.check_output(dev.java_command(java, path, command, windows=windows, launcher=helper),
                                             cwd=self.root, text=True)
            self.assertEqual([base64.b64decode(line).decode('utf-8') for line in output.splitlines()], arguments)
        command = ['-Dforbric.test=中文值', '-cp', str(unicode_classes), 'EchoArgs']
        output = subprocess.check_output(dev.java_command(java, path, command, windows=True, launcher=helper),
                                         cwd=self.root, text=True)
        self.assertEqual(base64.b64decode(output.strip()).decode('utf-8'), '中文值')

    def test_canaries_compile_against_the_staged_game_and_package_their_resources(self):
        env = dev.java_environment(dev.java_bin(), minimum=21)
        bin_dir = Path(env['JAVA_HOME']) / 'bin'
        suffix = '.exe' if os.name == 'nt' else ''
        stage, sources, work = self.root / 'stage', self.root / 'sources', self.root / 'work'

        def library(jar, name):
            # One class per jar, so each compile fails unless its exact jar is on the classpath.
            package, simple = name.rsplit('.', 1)
            java = work / jar.stem / (simple + '.java')
            java.parent.mkdir(parents=True)
            java.write_text(f'package {package}; public class {simple} {{}}')
            subprocess.run([str(bin_dir / ('javac' + suffix)), '-d', str(java.parent / 'classes'), str(java)], check=True)
            jar.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run([str(bin_dir / ('jar' + suffix)), '--create', '--file', str(jar), '-C',
                            str(java.parent / 'classes'), '.'], check=True)

        library(stage / 'forge-patched/patched-mc-forge-26.2.jar', 'net.minecraftforge.Game')
        library(stage / 'neoforge-runtime/neoforge-runtime.jar', 'net.neoforged.Carrier')
        mixin = work / 'sponge-mixin-0.17.jar'
        library(mixin, 'org.spongepowered.Plugin')
        forge, neo = sources / 'livemod-src', sources / 'livemod-src-neoforge'
        for tree, body in ((forge, 'net.minecraftforge.Game g; org.spongepowered.Plugin p;'),
                           (neo, 'net.neoforged.Carrier c;')):
            (tree / 'live').mkdir(parents=True)
            (tree / 'live/Probe.java').write_text('package live; class Probe { ' + body + ' }')
            (tree / 'data/live').mkdir(parents=True)
            (tree / 'data/live/probe.json').write_text('{}')
            (tree / 'notes.txt').write_text('not packaged')
        (forge / 'META-INF').mkdir()
        (forge / 'META-INF/mods.toml').write_text('modLoader="javafml"')
        (forge / 'forbriclive.mixins.json').write_text('{}')
        (forge / 'assets/live').mkdir(parents=True)
        (forge / 'assets/live/setup_probe.txt').write_text('probe')
        (neo / 'META-INF').mkdir()
        (neo / 'META-INF/neoforge.mods.toml').write_text('modLoader="javafml"')
        # What an earlier prepare left must not survive in either place.
        (stage / 'livemod-src/stale').mkdir(parents=True)

        dev.stage_canaries(stage, [mixin], env['JAVA_HOME'], sources)

        with zipfile.ZipFile(stage / 'forge-runtime/forbriclive.jar') as archive:
            names = set(archive.namelist())
        self.assertTrue({'live/Probe.class', 'META-INF/mods.toml', 'forbriclive.mixins.json', 'data/live/probe.json',
                         'assets/live/setup_probe.txt'} <= names, names)
        self.assertFalse({'live/Probe.java', 'notes.txt'} & names)
        with zipfile.ZipFile(stage / 'neoforge-runtime/forbricneolive.jar') as archive:
            names = set(archive.namelist())
        self.assertTrue({'live/Probe.class', 'META-INF/neoforge.mods.toml', 'data/live/probe.json'} <= names, names)
        self.assertEqual((stage / 'livemod-src/live/Probe.java').read_text(), (forge / 'live/Probe.java').read_text())
        self.assertFalse((stage / 'livemod-src/stale').exists())
        self.assertTrue((stage / 'livemod-src-neoforge/data/live/probe.json').is_file())

    def test_canaries_take_only_mixin_and_asm_from_the_kernel_classpath(self):
        jars = [self.root / name for name in ('sponge-mixin-0.17.3.jar', 'asm-9.10.1.jar', 'asm-tree-9.10.1.jar',
                                              'core-3.8.0.jar')]
        for jar in jars:
            jar.write_bytes(b'')
        classpath = os.pathsep.join([str(self.root / 'classes')] + [str(jar) for jar in jars])
        with patch.object(dev, 'gradle') as gradle:
            chosen = dev.kernel_libraries({'FORBRIC_KERNEL_CLASSPATH': classpath}, self.root, self.root)
        gradle.assert_not_called()
        self.assertEqual(chosen, jars[:3])
        with self.assertRaisesRegex(RuntimeError, 'sponge-mixin'):
            dev.kernel_libraries({'FORBRIC_KERNEL_CLASSPATH': os.pathsep.join(map(str, jars[1:]))}, self.root, self.root)

    def test_windows_retains_argfiles_for_ascii_launches(self):
        path = self.root / 'command.args'
        self.assertEqual(dev.java_command('java', path, ['-cp', 'long;classpath'], windows=True),
                         ['java', '@' + str(path)])
        self.assertEqual(dev.java_command('java', path, ['中文路径'], windows=False),
                         ['java', '@' + str(path)])

    def test_doctor_is_read_only_and_not_ready_is_nonzero(self):
        with patch.object(dev, 'STATE', self.root / 'absent'), patch.dict(os.environ, {}, clear=True), \
                patch.object(dev, 'java_environment', return_value={}):
            self.assertEqual(dev.main(['doctor']), 2)
        self.assertEqual(list(self.root.iterdir()), [])

    def test_invalid_gate_cannot_launch_arbitrary_script(self):
        with patch.object(dev, 'java_environment', return_value={}), patch.object(dev.subprocess, 'run') as run:
            self.assertEqual(dev.main(['gate', '--gate', '../../anything']), 1)
        run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
