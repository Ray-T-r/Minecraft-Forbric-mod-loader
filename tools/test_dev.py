import base64
import argparse
import hashlib
import io
import json
import os
import re
import shutil
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

    def test_a_stage_built_from_other_pins_is_not_ready(self):
        stamp = dev.pins_stamp()
        self.assertRegex(stamp, r'^mc=\S+ neoforge=\S+ nfrt=\S+ result=\S+$')
        # The stamp is the installer's Pins.stamp(), read from its source: the same four pins, in the same order.
        java = re.search(r'static String stamp\(\) \{\s*return (.*?);', dev.PINS_JAVA.read_text(encoding='utf-8'), re.S)
        self.assertEqual(['MINECRAFT', 'NEOFORGE', 'NFRT', 'NFRT_RESULT'], re.findall(r'\b([A-Z_]+)\b', java.group(1)))
        # NeoForge's patched game and the NeoForge carrier carry the stamps, as DevPrepare stages them.
        self.assertEqual(('neoforge-patched/patched-mc-neoforge-26.2.jar.pins', 'neoforge-runtime/neoforge-runtime.jar.pins'),
                         dev.STAMPED_FILES)
        self.assertTrue(set(dev.STAMPED_FILES) <= set(dev.STAGED_FILES))
        stage = self.root / 'run'
        for name in dev.STAMPED_FILES:
            (stage / name).parent.mkdir(parents=True, exist_ok=True)
            (stage / name).write_text(stamp + ' tools=abc\n')
        self.assertEqual([], dev.stale_stamps(stage))
        # A carrier from the previous NeoForge pin: same file name, older stamp. The stage must be rebuilt.
        older = stamp.replace('neoforge=', 'neoforge=0.0.0.0-').split(' nfrt=')[0] + ' nfrt=x result=y'
        (stage / dev.STAMPED_FILES[1]).write_text(older)
        self.assertEqual([dev.STAMPED_FILES[1]], dev.stale_stamps(stage))
        # A base stamped by an installer that still built the merged base: its stamp names MinecraftForge.
        (stage / dev.STAMPED_FILES[0]).write_text(stamp.replace(' neoforge=', ' forge=26.2-65.0.1 neoforge=') + ' tools=abc\n')
        self.assertEqual([dev.STAMPED_FILES[0], dev.STAMPED_FILES[1]], dev.stale_stamps(stage))
        (stage / dev.STAMPED_FILES[0]).unlink()
        self.assertEqual([dev.STAMPED_FILES[0], dev.STAMPED_FILES[1]], dev.stale_stamps(stage))
        self.assertFalse(dev.ready(self.root / 'mc', stage, self.root / 'natives'))

    def test_only_the_checkouts_own_stage_is_owned(self):
        root = self.root.resolve() / 'checkout'
        state = root / 'forbric-kernel' / '.dev'
        (state / 'staged' / 'run').mkdir(parents=True)
        ns = argparse.Namespace(staged=None)
        owned = lambda args=ns, environ={}, worktrees=(): dev.owned_stage(args, environ, root=root, state=state,
                                                                          worktrees=list(worktrees))
        self.assertTrue(owned())
        self.assertFalse(owned(argparse.Namespace(staged='/elsewhere')))
        self.assertFalse(owned(environ={'FORBRIC_OLD': '/shared'}))
        self.assertFalse(owned(argparse.Namespace(), {'FORBRIC_OLD': '/shared'}))
        # The main checkout's stage while a worktree's .dev is a link to it: every worktree reads it.
        worktree = self.root.resolve() / 'worktree'
        (worktree / 'forbric-kernel').mkdir(parents=True)
        (worktree / 'forbric-kernel' / '.dev').symlink_to(state)
        self.assertFalse(owned(worktrees=[worktree]))
        self.assertTrue(owned(worktrees=[self.root.resolve() / 'unrelated']))
        # A tree that is not a git checkout (a source archive) has no other worktrees to share its stage with.
        self.assertTrue(dev.owned_stage(ns, {}, root=root, state=state))
        # And from that worktree: its default stage is reached through the linked .dev, so it is the main checkout's.
        self.assertFalse(dev.owned_stage(ns, {}, root=worktree, state=worktree / 'forbric-kernel' / '.dev', worktrees=[]))
        # A stage whose staged/ is a link elsewhere is not this checkout's either.
        other = self.root.resolve() / 'other'
        (other / 'staged' / 'run').mkdir(parents=True)
        linked = self.root.resolve() / 'linked'
        (linked / 'forbric-kernel' / '.dev').mkdir(parents=True)
        (linked / 'forbric-kernel' / '.dev' / 'staged').symlink_to(other / 'staged')
        self.assertFalse(dev.owned_stage(ns, {}, root=linked, state=linked / 'forbric-kernel' / '.dev', worktrees=[]))

    def test_git_worktrees_are_listed_without_this_checkout(self):
        others = dev.other_worktrees()
        if others is not None:
            self.assertNotIn(dev.ROOT.resolve(), [tree.resolve() for tree in others])
        self.assertEqual([], dev.other_worktrees(self.root))

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

    @staticmethod
    def sidecar(stage, base_sha1):
        """A vanilla-reference sidecar recording base_sha1 as the base it was measured against, as VanillaReference writes."""
        sidecar = stage / dev.VANILLA_REFERENCE
        sidecar.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(sidecar, 'w') as archive:
            archive.writestr('META-INF/forbric/native-reference/FABRIC/index.tsv', '# forbric-native-reference-v1\n')
            archive.writestr(dev.VANILLA_REFERENCE_PROVENANCE, f'format=1\nvanilla-sha1={"0" * 40}\nbase-sha1={base_sha1}\n')
        return sidecar

    def launch_fixture(self, with_runtime=True, root=None):
        root = root or self.root
        mc, stage = root / 'Minecraft With Spaces', root / 'stage/run'
        version = mc / 'versions/26.2/26.2.json'
        version.parent.mkdir(parents=True)
        version.write_text(json.dumps({'assetIndex': {'id': '32'}, 'libraries': []}))
        boot = root / 'kernel.jar'
        with zipfile.ZipFile(boot, 'w') as archive:
            if with_runtime:
                archive.writestr('META-INF/jars/forbric-kernel-runtime.jar', b'runtime')
        base = stage / dev.STAGED_BASE
        base.parent.mkdir(parents=True)
        with zipfile.ZipFile(base, 'w') as archive:
            archive.writestr('version.json', '{"protocol_version":776}')
            archive.writestr('net/minecraft/NotParentLoaded.class', b'bytecode')
        (stage / dev.STAGED_NEO_CARRIER).parent.mkdir(parents=True)
        (stage / dev.STAGED_NEO_CARRIER).write_bytes(b'carrier')
        self.sidecar(stage, dev.digest(base, 'sha1'))
        return {'bootJar': str(boot), 'dependencies': ['dependency.jar']}, mc, stage

    def test_windows_classpaths_and_mac_only_flag(self):
        info, mc, stage = self.launch_fixture()
        with patch.dict(os.environ, {}, clear=True):
            win = dev.launch_arguments('client', info, mc, stage, self.root, self.root, system='windows', pathsep=';')
            mac = dev.launch_arguments('client', info, mc, stage, self.root, self.root, system='osx', pathsep=':')
            linux = dev.launch_arguments('client', info, mc, stage, self.root, self.root, system='linux', pathsep=':')
        self.assertIn(';dependency.jar;', win[win.index('-cp')+1])
        # The vanilla reference travels in its own flag, so a launcher's last-value-wins dedup cannot replace the game.
        for argv in (win, mac, linux):
            self.assertEqual(1, argv.count('--gameJar'))
            self.assertEqual(str(stage / 'vanilla-reference/vanilla-reference-26.2.jar'), argv[argv.index('--nativeReference')+1])
            self.assertLess(argv.index('--nativeReference'), argv.index('--'))
        self.assertNotIn('-XstartOnFirstThread', win)
        self.assertNotIn('-XstartOnFirstThread', linux)
        self.assertIn('-XstartOnFirstThread', mac)
        self.assertIn('Minecraft With Spaces', win[win.index('--assetsDir')+1])
        with zipfile.ZipFile(stage / dev.STAGED_GAME_METADATA) as archive:
            self.assertEqual(archive.namelist(), ['version.json'])

    def test_the_launch_owns_neoforges_patched_game_and_its_one_carrier(self):
        info, mc, stage = self.launch_fixture()
        # What an older prepare left in a shared stage: never launched, whatever is there.
        for retired in ('merged-base/patched-mc-merged-26.2.jar', 'merged-base/forge-runtime-interop.jar',
                        'forge-runtime/forge-runtime.jar'):
            (stage / retired).parent.mkdir(parents=True, exist_ok=True)
            (stage / retired).write_bytes(b'retired')
        with patch.dict(os.environ, {}, clear=True):
            argv = dev.launch_arguments('server', info, mc, stage, self.root, self.root, pathsep=';')
        self.assertEqual(str(stage / 'neoforge-patched/patched-mc-neoforge-26.2.jar'), argv[argv.index('--gameJar')+1])
        self.assertEqual(1, argv.count('--runtimeJar'))
        self.assertEqual(str(stage / 'neoforge-runtime/neoforge-runtime.jar'), argv[argv.index('--runtimeJar')+1])
        self.assertFalse([a for a in argv if any(f'/{d}/' in a for d in ('merged-base', 'forge-runtime', 'forge-patched'))],
                         argv)
        self.assertEqual(str(stage / 'neoforge-patched' / dev.GAME_METADATA_JAR), str(stage / dev.STAGED_GAME_METADATA))
        self.assertFalse(hasattr(dev, 'launch_carriers') or hasattr(dev, 'forge_carrier_expected')
                         or hasattr(dev, 'merge_libraries'))
        with self.assertRaises(SystemExit):
            with patch('sys.stderr', io.StringIO()):
                dev.main(['server', '--no-forge-carrier'])

    @unittest.skipIf(os.name == 'nt' or not shutil.which('bash'), 'runs the POSIX launchers')
    def test_the_shell_launchers_take_the_same_files_and_refuse_the_retired_variables(self):
        stage = self.root / 'stage/run'
        for launcher in ('launch-kernel-server.sh', 'launch-kernel-client.sh'):
            text = (dev.KERNEL / 'run' / launcher).read_text()
            lines = [line for line in text.splitlines() if line.startswith(('BASE=', 'NEO_RT='))]
            self.assertEqual(2, len(lines), launcher + ' no longer picks its base and carrier in the lines this test runs')
            script = '\n'.join(lines + ['printf "%s\\n%s" "$BASE" "$NEO_RT"'])
            picked = subprocess.run(['bash', '-c', script], env={'PATH': os.environ.get('PATH', ''), 'STAGE': str(stage)},
                                    capture_output=True, text=True, check=True).stdout.splitlines()
            self.assertEqual([str(stage / dev.STAGED_BASE), str(stage / dev.STAGED_NEO_CARRIER)], picked, launcher)
            self.assertIn('--gameJar "$BASE" --runtimeJar "$NEO_RT"', text, launcher)
            self.assertNotIn('FORGE_RT:+', text, launcher)
            # MERGED or FORGE_RT set: the launcher stops (exit 2) before it builds or reads anything. With no stage at
            # all it would also exit 2, but naming the missing base -- the control that the guard is what speaks.
            base_env = {'PATH': os.environ.get('PATH', ''), 'HOME': str(self.root), 'FORBRIC_OLD': str(self.root / 'absent')}
            for variable in ('MERGED', 'FORGE_RT'):
                run = subprocess.run(['bash', str(dev.KERNEL / 'run' / launcher)], env=dict(base_env, **{variable: '/x.jar'}),
                                     capture_output=True, text=True, timeout=60)
                self.assertEqual(2, run.returncode, (launcher, variable, run.stdout, run.stderr))
                self.assertIn('MERGED/FORGE_RT are retired', run.stderr, (launcher, variable))
            control = subprocess.run(['bash', str(dev.KERNEL / 'run' / launcher)], env=base_env, capture_output=True,
                                     text=True, timeout=60)
            self.assertEqual(2, control.returncode, control.stderr)
            self.assertNotIn('retired', control.stderr)
            self.assertIn('game base not found', control.stderr)

    def prepared_stage(self):
        """Every input ready() checks for a dedicated server."""
        mc, stage, natives, state = self.root / 'mc', self.root / 'stage/run', self.root / 'natives', self.root / 'state'
        stamp = dev.pins_stamp()
        for name in dev.STAGED_FILES:
            (stage / name).parent.mkdir(parents=True, exist_ok=True)
            (stage / name).write_text(stamp + ' tools=abc\n' if name in dev.STAMPED_FILES else 'staged')
        self.sidecar(stage, dev.digest(stage / dev.STAGED_BASE, 'sha1'))
        (state / 'api').mkdir(parents=True)
        (state / 'api/pinned.jar').write_bytes(b'api')
        version = mc / f'versions/{dev.MC_VERSION}/{dev.MC_VERSION}.json'
        version.parent.mkdir(parents=True)
        version.write_text(json.dumps({'assetIndex': {'id': '32'}, 'libraries': []}))
        for name, _ in dev.CONSOLE_PINS:
            jline = mc / f'libraries/org/jline/{name}/3.25.1/{name}-3.25.1.jar'
            jline.parent.mkdir(parents=True)
            jline.write_bytes(b'jline')
        natives.mkdir()
        (natives / 'liblwjgl.dylib').write_bytes(b'native')
        pins = (('pinned.jar', '', hashlib.sha256(b'api').hexdigest()),)
        return mc, stage, natives, patch.multiple(dev, STATE=state, API_PINS=pins)

    def test_a_prepared_stage_needs_nothing_of_minecraftforge(self):
        mc, stage, natives, pinned = self.prepared_stage()
        with pinned:
            self.assertTrue(dev.ready(mc, stage, natives, assets=False))
            self.assertEqual([], dev.stale_stamps(stage))
            (stage / dev.STAGED_NEO_CARRIER).unlink()
            self.assertFalse(dev.ready(mc, stage, natives, assets=False))
        self.assertFalse([name for name in dev.STAGED_FILES
                          if name.startswith(('merged-base/', 'forge-runtime/', 'forge-patched/'))], dev.STAGED_FILES)
        self.assertEqual((dev.STAGED_BASE, dev.STAGED_BASE + '.pins', dev.STAGED_NEO_CARRIER, dev.STAGED_NEO_CARRIER + '.pins',
                          'neoforge-runtime/forbricneolive.jar', dev.VANILLA_REFERENCE), dev.STAGED_FILES)

    def test_a_vanilla_reference_measured_against_another_base_is_not_handed_out(self):
        mc, stage, natives, pinned = self.prepared_stage()
        with pinned:
            self.assertIsNone(dev.sidecar_problem(stage))
            # Another checkout prepared the shared stage last, or the base was rebuilt: same names, other bytes.
            self.sidecar(stage, '0' * 40)
            problem = dev.sidecar_problem(stage)
            self.assertIn('was measured against another base (base-sha1=' + '0' * 40, problem)
            self.assertFalse(dev.ready(mc, stage, natives, assets=False))
            # Not a sidecar at all (no provenance), and absent.
            (stage / dev.VANILLA_REFERENCE).write_bytes(b'not a jar')
            self.assertIn('is not a vanilla reference', dev.sidecar_problem(stage))
            (stage / dev.VANILLA_REFERENCE).unlink()
            self.assertTrue(dev.sidecar_problem(stage).startswith('missing '))
        info, mc, stage = self.launch_fixture(root=self.root / 'launch')
        self.sidecar(stage, '0' * 40)
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(RuntimeError, 'refusing to launch with this vanilla reference'):
                dev.launch_arguments('server', info, mc, stage, self.root, self.root)

    def test_the_metadata_jar_is_named_once_and_beside_the_base_as_the_shell_writes_it(self):
        self.assertEqual(str(Path(dev.STAGED_BASE).parent / dev.GAME_METADATA_JAR), str(Path(dev.STAGED_GAME_METADATA)))
        script = (dev.KERNEL / 'run/game-metadata-jar.sh').read_text()
        self.assertIn('OUT="${GAME_METADATA_JAR:-$(dirname "$BASE")/' + dev.GAME_METADATA_JAR + '}"', script)

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

    def test_the_canary_compiles_against_the_staged_game_and_packages_its_resources(self):
        env = dev.java_environment(dev.java_bin(), minimum=21)
        bin_dir = Path(env['JAVA_HOME']) / 'bin'
        suffix = '.exe' if os.name == 'nt' else ''
        stage, sources, work = self.root / 'stage', self.root / 'sources', self.root / 'work'

        def library(jar, name):
            # One class per jar, so the compile fails unless its exact jar is on the classpath.
            package, simple = name.rsplit('.', 1)
            java = work / jar.stem / (simple + '.java')
            java.parent.mkdir(parents=True)
            java.write_text(f'package {package}; public class {simple} {{}}')
            subprocess.run([str(bin_dir / ('javac' + suffix)), '-d', str(java.parent / 'classes'), str(java)], check=True)
            jar.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run([str(bin_dir / ('jar' + suffix)), '--create', '--file', str(jar), '-C',
                            str(java.parent / 'classes'), '.'], check=True)

        library(stage / 'neoforge-patched/patched-mc-neoforge-26.2.jar', 'net.minecraft.Game')
        library(stage / 'neoforge-runtime/neoforge-runtime.jar', 'net.neoforged.Carrier')
        mixin = work / 'sponge-mixin-0.17.jar'
        library(mixin, 'org.spongepowered.Plugin')
        neo = sources / 'livemod-src-neoforge'
        (neo / 'live').mkdir(parents=True)
        (neo / 'live/Probe.java').write_text('package live; class Probe { net.neoforged.Carrier c; net.minecraft.Game g; '
                                             'org.spongepowered.Plugin p; }')
        (neo / 'data/live').mkdir(parents=True)
        (neo / 'data/live/probe.json').write_text('{}')
        (neo / 'notes.txt').write_text('not packaged')
        (neo / 'META-INF').mkdir()
        (neo / 'META-INF/neoforge.mods.toml').write_text('modLoader="javafml"')
        (neo / 'forbricneolive.mixins.json').write_text('{}')
        # What an earlier prepare left must not survive.
        (stage / 'livemod-src-neoforge/stale').mkdir(parents=True)

        dev.stage_canaries(stage, [mixin], env['JAVA_HOME'], sources)

        with zipfile.ZipFile(stage / 'neoforge-runtime/forbricneolive.jar') as archive:
            names = set(archive.namelist())
        self.assertTrue({'live/Probe.class', 'META-INF/neoforge.mods.toml', 'forbricneolive.mixins.json',
                         'data/live/probe.json'} <= names, names)
        self.assertFalse({'live/Probe.java', 'notes.txt'} & names)
        self.assertEqual((stage / 'livemod-src-neoforge/live/Probe.java').read_text(), (neo / 'live/Probe.java').read_text())
        self.assertFalse((stage / 'livemod-src-neoforge/stale').exists())
        # No MinecraftForge canary any more: nothing is written where DevPrepare removes the retired directories.
        self.assertEqual([('livemod-src-neoforge', 'neoforge-runtime/forbricneolive.jar')],
                         [(source, output) for source, output, _, _ in dev.CANARIES])
        self.assertFalse((stage / 'forge-runtime').exists() or (stage / 'livemod-src').exists())

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
