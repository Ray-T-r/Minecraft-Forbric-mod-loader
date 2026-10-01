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
        # Exercise Java's parser itself, rather than merely comparing an escape implementation to its output.
        source = self.root / 'EchoArgs.java'
        source.write_text('class EchoArgs { public static void main(String[] a) { for (String s:a) '
                          'System.out.println(java.util.Base64.getEncoder().encodeToString('
                          's.getBytes(java.nio.charset.StandardCharsets.UTF_8))); } }')
        arguments = ['C:\\Users\\A B\\.minecraft', '中文路径', 'a"quoted"value', 'literal;classpath', '#leading-comment', '']
        path = self.root / 'command.args'
        dev.write_argument_file(path, [str(source)] + arguments)
        output = subprocess.check_output(dev.java_command(dev.java_bin(), path, [str(source)] + arguments), text=True)
        self.assertEqual([base64.b64decode(line).decode('utf-8') for line in output.splitlines()], arguments)

    def test_windows_uses_argfiles_for_ascii_and_native_arguments_for_unicode(self):
        path = self.root / 'command.args'
        self.assertEqual(dev.java_command('java', path, ['-cp', 'long;classpath'], windows=True),
                         ['java', '@' + str(path)])
        self.assertEqual(dev.java_command('java', path, ['中文路径'], windows=True), ['java', '中文路径'])
        self.assertEqual(dev.java_command('java', self.root / '中文目录' / 'args', ['ascii'], windows=True),
                         ['java', 'ascii'])
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
