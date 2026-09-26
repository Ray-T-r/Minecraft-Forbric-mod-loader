package net.forbric.kernel.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WindowsDriversTest {
    @TempDir Path temp;

    @Test void launcherPrintsParameterizedConfiguration() throws Exception { checkConfig("forbric-launch.py"); }
    @Test void serverLauncherPrintsParameterizedConfiguration() throws Exception { checkConfig("forbric-server.py"); }
    @Test void serverDriverPrintsParameterizedConfiguration() throws Exception { checkConfig("run-server-test.py"); }
    @Test void clientDriverPrintsParameterizedConfiguration() throws Exception { checkConfig("run-client-test.py"); }
    @Test void bisectPrintsParameterizedConfiguration() throws Exception { checkConfig("bisect.py"); }
    @Test void sharedHelpersPrintParameterizedConfiguration() throws Exception { checkConfig("common.py"); }

    private void checkConfig(String script) throws Exception {
        Map<String, String> environment = Map.of("FORBRIC_MC", temp.resolve("installation").toString(),
                "FORBRIC_VERSION", "test-version", "FORBRIC_INSTANCE", temp.resolve("instance").toString(),
                "FORBRIC_WORLD", "test-world");
        var result = DriverTools.script("win/" + script, environment, "--print-config");
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("\"version\": \"test-version\""), result.output());
        assertTrue(result.output().contains("\"world\": \"test-world\""), result.output());
        assertTrue(result.output().contains(".forbric-sweep.pid"), result.output());
        assertTrue(result.output().contains("clientSmokeScreenshots=100"), result.output());
        assertTrue(result.output().contains(temp.resolve("instance").toString()), result.output());
        assertFalse(Files.exists(temp.resolve("instance")), "--print-config must not mutate the installation");
        result = DriverTools.script("win/" + script, environment, "--print-config", "--version", "override-version", "--world", "override-world");
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("\"version\": \"override-version\""), result.output());
        assertTrue(result.output().contains("\"world\": \"override-world\""), result.output());
    }

    @Test void compilesEveryWindowsDriverWithoutImportingWindowsApis() throws Exception {
        for (String filename : List.of("common.py", "forbric-launch.py", "forbric-server.py", "run-server-test.py", "run-client-test.py", "bisect.py")) {
            var result = DriverTools.run(Map.of("PYTHONPYCACHEPREFIX", temp.resolve("pycache").toString()),
                    "-m", "py_compile", DriverTools.COMPAT.resolve("win").resolve(filename).toString());
            assertEquals(0, result.exit(), result.output());
        }
    }

    /**
     * A boot that has stopped talking must be told apart from one that is merely slow.
     *
     * Before await_outcome existed there was only --boot-timeout, and a wedged server held it for the whole
     * 900 seconds: two runs in build/compat/ cost 820s and 1615s to report a failure their console logs had
     * already settled inside the first 20 seconds. The numbers below are the ones that evidence supports —
     * across sixteen recorded sweeps a boot that reached Done never went quiet for more than 8 seconds, and
     * every boot that did not went silent 13-17 seconds in and stayed that way.
     *
     * The negative control is the half that matters. A stall detector that fires on a slow machine does not
     * save fifteen minutes, it invents a red sweep, so this asserts that output arriving steadily keeps the
     * wait alive well past the stall window.
     */
    @Test void aBootThatStopsTalkingIsCutShortAndOneThatKeepsTalkingIsNot() throws Exception {
        var result = DriverTools.run(Map.of(), "-c", """
                import sys, types
                sys.path.insert(0, sys.argv[1]); import common

                class Event:
                    def __init__(self): self.value = False
                    def is_set(self): return self.value
                class Process:
                    def __init__(self, exit_at=None): self.exit_at = exit_at
                    def poll(self): return 0 if self.exit_at is not None and clock[0] >= self.exit_at else None

                clock = [0.0]
                def now(): return clock[0]
                def tick(seconds): clock[0] += seconds

                # 1. silent from the start: stalls at the threshold, NOT at the ceiling.
                last = [0.0]
                verdict = common.await_outcome(ready=Event(), failed=Event(), process=Process(),
                                               timeout=900, stall=120, last_output=last, now=now, sleep=tick)
                assert verdict == 'stalled', verdict
                assert 120 <= clock[0] <= 122, clock[0]

                # 2. NEGATIVE CONTROL: still printing, just slowly. Must never stall, however long it takes.
                clock[0] = 0.0; last = [0.0]; ready = Event()
                def talk(seconds):
                    tick(seconds)
                    last[0] = clock[0]          # a line arrived on every poll
                    if clock[0] >= 600: ready.value = True
                verdict = common.await_outcome(ready=ready, failed=Event(), process=Process(),
                                               timeout=900, stall=120, last_output=last, now=now, sleep=talk)
                assert verdict == 'ready', verdict
                assert clock[0] >= 600, clock[0]

                # 3. a boot quiet for 119s and then noisy again is not a stall either.
                clock[0] = 0.0; last = [0.0]; ready = Event()
                def late(seconds):
                    tick(seconds)
                    if clock[0] >= 119: last[0] = clock[0]
                    if clock[0] >= 200: ready.value = True
                assert common.await_outcome(ready=ready, failed=Event(), process=Process(), timeout=900,
                                            stall=120, last_output=last, now=now, sleep=late) == 'ready'

                # 4. the other verdicts keep the precedence the drivers' own conditions had.
                clock[0] = 0.0; last = [0.0]
                both = Event(); both.value = True; failed = Event(); failed.value = True
                assert common.await_outcome(ready=both, failed=failed, process=Process(), timeout=900,
                                            stall=120, last_output=last, now=now, sleep=tick) == 'failed'
                clock[0] = 0.0; last = [0.0]; ready = Event(); ready.value = True
                assert common.await_outcome(ready=ready, failed=Event(), process=Process(exit_at=0), timeout=900,
                                            stall=120, last_output=last, now=now, sleep=tick) == 'exited'
                clock[0] = 0.0; last = [0.0]
                def quiet_but_fed(seconds):
                    tick(seconds); last[0] = clock[0]
                assert common.await_outcome(ready=Event(), failed=Event(), process=Process(), timeout=300,
                                            stall=120, last_output=last, now=now, sleep=quiet_but_fed) == 'timeout'
                print('stall detection PASS')
                """, DriverTools.COMPAT.resolve("win").toString());
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("stall detection PASS"), result.output());
    }

    /**
     * The soak has to be able to tick for the whole of --tick-seconds, which means the empty-server pause has
     * to be off.
     *
     * Vanilla defaults pause-when-empty-seconds to 60 and a sweep's server never has a player on it, so
     * MinecraftServer.tickServer stops ticking at Done+60 and returns before tickCount++ and before
     * fireServerTickPre. The sweeps in build/compat/ ran a 90-second soak against that: `Server empty for 60
     * seconds, pausing` lands at Done+60 and nothing follows it until the stop at Done+90. A third of every
     * soak proved nothing, and nobody could see it, because the symptom is silence.
     *
     * So this pins the property rather than the duration. --tick-seconds is a knob someone may reasonably
     * raise; if this line ever goes missing again, every second above sixty is dead and the run still says
     * PASS. Zero disables the pause — it does not mean pause immediately, which is the reading that would
     * gut the soak entirely.
     */
    @Test void theSweepServerNeverPausesItselfForBeingEmpty() throws Exception {
        var result = DriverTools.run(Map.of(), "-c", """
                import sys
                sys.path.insert(0, sys.argv[1]); import common
                written = common.server_properties('compat-world', '20260919', 25599)
                settings = dict(line.split('=', 1) for line in written.splitlines() if line)
                assert settings['pause-when-empty-seconds'] == '0', written
                assert settings['level-name'] == 'compat-world', written
                assert settings['level-seed'] == '20260919', written
                assert settings['server-port'] == '25599', written
                assert settings['online-mode'] == 'false', written
                assert settings['simulation-distance'] == '10', written
                print('server properties PASS')
                """, DriverTools.COMPAT.resolve("win").toString());
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("server properties PASS"), result.output());
    }

    @Test void aFirstRunScreenIsMarkedSeenWithoutTouchingTheModsOtherSettings() throws Exception {
        // sweep90-win-r6 sat on wover-ui's BetterX welcome for five minutes: vanilla runs quick-play only after it.
        var result = DriverTools.run(Map.of(), "-c", """
                import json, pathlib, sys
                sys.path.insert(0, sys.argv[1]); import common
                instance = pathlib.Path(sys.argv[2])
                common.acknowledge_first_run(instance)
                fresh = json.loads((instance / 'config/wover/client.json').read_text())
                assert fresh == {'internal': {'did_present_welcome_screen': True}}, fresh
                (instance / 'config/wover/client.json').write_text(json.dumps({'create_version': '26.201.2',
                    'internal': {'did_present_welcome_screen': False}, 'general': {'check_for_new_versions': True}}))
                common.acknowledge_first_run(instance)
                merged = json.loads((instance / 'config/wover/client.json').read_text())
                assert merged == {'create_version': '26.201.2', 'internal': {'did_present_welcome_screen': True},
                                  'general': {'check_for_new_versions': True}}, merged
                (instance / 'config/wover/client.json').write_text('not json')
                common.acknowledge_first_run(instance)
                assert json.loads((instance / 'config/wover/client.json').read_text())['internal']['did_present_welcome_screen']
                for driver in ('run-client-test.py', 'bisect.py'):
                    source = (pathlib.Path(sys.argv[1]) / driver).read_text()
                    assert source.index('acknowledge_first_run(instance)') < source.index("driver_command(configuration, 'forbric-launch.py')"), driver
                print('first run PASS')
                """, DriverTools.COMPAT.resolve("win").toString(), temp.toString());
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("first run PASS"), result.output());
    }

    /**
     * A sweep's driver as far as options.txt goes, for the tests below that kill one: it pins, its client rewrites the
     * file while it loads (startedCleanly:false, and a key of a mod the pack does not have drops out), and it waits.
     * Closing its stdin ends the block normally; kill() is taskkill /F, and the block's finally never runs.
     */
    private static final String SWEEP_DRIVER = """
            LOADING = '\\n'.join([
                'import pathlib, sys',
                'sys.path.insert(0, sys.argv[1]); import common',
                'options = pathlib.Path(sys.argv[2]) / "options.txt"',
                'with common.sweep_language(sys.argv[2], sys.argv[3]):',
                '    text = options.read_bytes() if options.exists() else b"version:4671\\\\nlang:en_us\\\\n"',
                '    options.write_bytes(text.replace(b"startedCleanly:true", b"startedCleanly:false").replace(b"key_key.xaero_minimap:key.keyboard.y\\\\r\\\\n", b"")',
                '                        + (b"" if b"startedCleanly" in text else b"startedCleanly:false\\\\n"))',
                '    print("loading", flush=True)',
                '    sys.stdin.read()',
            ])
            def loading(code='en_us'):
                child = subprocess.Popen([sys.executable, '-c', LOADING, sys.argv[1], str(instance), code],
                                         stdin=subprocess.PIPE, stdout=subprocess.PIPE)
                assert child.stdout.readline() == b'loading\\n'
                return child
            """;

    /**
     * A sweep plays en_us whoever runs it, and the player's options.txt comes back byte for byte — over the client's
     * own rewrite of it too, in every --lang, and without a file when the player had none.
     *
     * sweep90-win-r7c inherited its player's zh_cn and died at world join: Axiom 6.1.3's bundled Dear ImGui 1.92.7
     * keeps a pointer into the font byte[]s past the JNI call that pinned them, and the two CJK fonts it loads for
     * zh (19 MB) are humongous reads that start the GC which moves or frees them before the atlas builds —
     * imstb_truetype.h:1590, then System.exit(1). Native Fabric with only Axiom and fabric-api asserts the same way
     * once a GC lands between the add and the build (forced, or under -XX:+UseSerialGC -Xmn16m; under default G1 that
     * minimal pack did not crash in the runs recorded), so the kernel stays as it is; what was wrong is a verdict that
     * depended on who ran the sweep. The file below is Windows-shaped (CRLF, the player's other settings around the
     * line) because that is the file a sweep edits.
     */
    @Test void theClientPlaysOneLanguageAndThePlayersOptionsComeBackByteForByte() throws Exception {
        var result = DriverTools.run(Map.of(), "-c", """
                import base64, json, os, pathlib, sys
                sys.path.insert(0, sys.argv[1]); import common
                instance = pathlib.Path(sys.argv[2]); instance.mkdir()
                options, record = instance / 'options.txt', instance / 'options.txt.forbric-sweep'
                player = b'version:4671\\r\\nlang:zh_cn\\r\\nonboardAccessibility:false\\r\\nstartedCleanly:true\\r\\nguiScale:2\\r\\n'
                def rewrite():  # what the client saves while it loads: Minecraft.<init> sets startedCleanly false and saves
                    options.write_bytes(options.read_bytes().replace(b'startedCleanly:true', b'startedCleanly:false') + b'tutorialStep:none\\r\\n')
                options.write_bytes(player)
                with common.sweep_language(instance) as played:
                    assert played == "en_us; the player's options.txt names zh_cn, restored after the run", played
                    assert options.read_bytes() == player.replace(b'lang:zh_cn', b'lang:en_us'), options.read_bytes()
                    kept = json.loads(record.read_text())
                    assert (kept['lang'], kept['was'], kept['absent'], kept['pid']) == ('en_us', 'zh_cn', False, os.getpid()), kept
                    assert base64.b64decode(kept['original']) == player and kept['started'] == common.process_started(os.getpid()), kept
                    rewrite()
                assert options.read_bytes() == player and not record.exists(), options.read_bytes()
                try:
                    with common.sweep_language(instance):
                        rewrite()
                        raise RuntimeError('client died')
                except RuntimeError:
                    pass
                assert options.read_bytes() == player and not record.exists()
                # `player` pins nothing, and the client's rewrite still goes.
                with common.sweep_language(instance, 'player') as played:
                    assert played == "zh_cn; the player's options.txt, played as it is and restored after the run", played
                    assert options.read_bytes() == player and json.loads(record.read_text())['lang'] is None
                    rewrite()
                assert options.read_bytes() == player and not record.exists()
                # No lang line: one is appended for the run and the file comes back without it.
                bare = b'version:4671\\nguiScale:2'
                options.write_bytes(bare)
                with common.sweep_language(instance) as played:
                    assert 'names no language (vanilla plays en_us)' in played and options.read_bytes() == bare + b'\\nlang:en_us\\n'
                    assert json.loads(record.read_text())['was'] is None
                assert options.read_bytes() == bare
                # No options.txt: none is written for the run, and the one the client writes goes afterwards.
                options.unlink()
                for code in ('en_us', 'player'):
                    with common.sweep_language(instance, code) as played:
                        assert played.startswith('en_us; the player has no options.txt (vanilla plays en_us)'), played
                        kept = json.loads(record.read_text())
                        assert not options.exists() and kept['absent'] is True and kept['lang'] is None and kept['original'] is None, kept
                        options.write_bytes(b'version:4671\\nlang:en_us\\nstartedCleanly:false\\n')
                    assert not options.exists() and not record.exists(), code
                for bad in ('zh_cn\\nguiScale:4', ''):
                    try:
                        with common.sweep_language(instance, bad):
                            raise AssertionError('entered with ' + repr(bad))
                    except ValueError:
                        pass
                try:
                    with common.sweep_language(instance, 'zh_cn'):
                        raise AssertionError('no options.txt, yet zh_cn was promised')
                except ValueError:
                    pass
                assert not options.exists() and not record.exists()
                for driver in ('run-client-test.py', 'bisect.py'):
                    text = (pathlib.Path(sys.argv[1]) / driver).read_text()
                    assert 'language_argument(argument_parser)' in text, driver
                    lines = text.splitlines()
                    pin = next(i for i, text in enumerate(lines) if 'with sweep_language(instance, args.lang) as played' in text)
                    said = next(i for i, text in enumerate(lines) if "print('client language ' + played, flush=True)" in text)
                    run = next(i for i, text in enumerate(lines) if 'spawn(configuration, command' in text)
                    stop = next(i for i, text in enumerate(lines) if 'finish(configuration, process)' in text)
                    depth = len(lines[pin]) - len(lines[pin].lstrip())
                    assert pin < said < run < stop, driver
                    assert all(len(text) - len(text.lstrip()) > depth for text in lines[pin + 2:stop + 1] if text.strip()), driver
                print('language PASS')
                """, DriverTools.COMPAT.resolve("win").toString(), temp.resolve("instance").toString());
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("language PASS"), result.output());
    }

    /**
     * A sweep whose writer died on its own — a reboot; os._exit skips the finally as taskkill /F does — gives the
     * player back only the lang line it changed, never the rest of a newer file.
     *
     * Whoever finds that record later, the stop or the next sweep, cannot know what the player did in between.
     * Putting the saved copy of the whole file back would turn a guiScale the player raised to 3 back into 2, which is
     * the first case below.
     */
    @Test void aSweepThatDiedOnItsOwnGivesBackOnlyTheLanguageItChanged() throws Exception {
        var result = DriverTools.run(Map.of(), "-c", """
                import json, os, pathlib, subprocess, sys
                sys.path.insert(0, sys.argv[1]); import common
                instance = pathlib.Path(sys.argv[2]); instance.mkdir()
                options, record = instance / 'options.txt', instance / 'options.txt.forbric-sweep'
                def killed_inside(code='en_us'):
                    script = 'import os,sys\\nsys.path.insert(0,sys.argv[1]);import common\\nwith common.sweep_language(sys.argv[2],sys.argv[3]):\\n    os._exit(9)\\n'
                    assert subprocess.call([sys.executable, '-c', script, sys.argv[1], str(instance), code]) == 9
                player = b'version:4671\\r\\nlang:zh_cn\\r\\nguiScale:2\\r\\n'
                mine = b'version:4671\\r\\nlang:zh_cn\\r\\nguiScale:3\\r\\n'

                # Killed, then the player switches back to Chinese and raises guiScale; the next sweep
                # finds the record and must leave that file exactly as the player made it.
                options.write_bytes(player); killed_inside()
                assert options.read_bytes() == player.replace(b'zh_cn', b'en_us') and record.is_file()
                options.write_bytes(mine)
                with common.sweep_language(instance) as played:
                    assert 'names zh_cn' in played, played
                assert options.read_bytes() == mine and not record.exists(), options.read_bytes()

                # Killed, then the player plays on in the language the sweep left and raises guiScale: lang comes back,
                # guiScale stays.
                options.write_bytes(player); killed_inside()
                options.write_bytes(options.read_bytes().replace(b'guiScale:2', b'guiScale:3'))
                assert "lang:zh_cn back" in common.restore_player_language(instance)
                assert options.read_bytes() == mine and not record.exists(), options.read_bytes()

                # Killed, then the player picks a third language: theirs stands.
                options.write_bytes(player); killed_inside()
                options.write_bytes(b'version:4671\\r\\nlang:ja_jp\\r\\nguiScale:3\\r\\n')
                assert 'kept lang:ja_jp' in common.restore_player_language(instance)
                assert options.read_bytes() == b'version:4671\\r\\nlang:ja_jp\\r\\nguiScale:3\\r\\n' and not record.exists()

                # A file without a lang line got one appended; the undo takes exactly that line away again.
                options.write_bytes(b'version:4671\\nguiScale:2\\n'); killed_inside()
                options.write_bytes(options.read_bytes().replace(b'guiScale:2', b'guiScale:3'))
                assert 'removed the lang:en_us line' in common.restore_player_language(instance)
                assert options.read_bytes() == b'version:4671\\nguiScale:3\\n', options.read_bytes()

                # `player` changed no line of its own, so a record it left undoes nothing.
                options.write_bytes(player); killed_inside('player')
                assert record.is_file() and json.loads(record.read_text())['lang'] is None
                options.write_bytes(mine)
                assert 'nothing to undo' in common.restore_player_language(instance)
                assert options.read_bytes() == mine and not record.exists()
                assert common.restore_player_language(instance) is None

                # No options.txt: the sweep writes none and pins no line, so a file there later, the killed client's or one
                # the player made since, is not the sweep's to take away.
                options.unlink(); killed_inside()
                assert not options.exists() and json.loads(record.read_text())['lang'] is None
                options.write_bytes(b'version:4671\\nlang:en_us\\nstartedCleanly:false\\n')
                assert 'stays' in common.restore_player_language(instance)
                assert options.read_bytes() == b'version:4671\\nlang:en_us\\nstartedCleanly:false\\n' and not record.exists()

                # After a reboot the recorded pid may be some other process's: a pid alone is not the writer, its start
                # time has to match too, so this is a leftover like any other and no reason to refuse the run.
                options.write_bytes(player); killed_inside()
                kept = json.loads(record.read_text()); kept.update(pid=os.getpid(), started=kept['started'] + 1)
                record.write_text(json.dumps(kept))
                assert common.note_sweep_writer(instance) is None
                assert "lang:zh_cn back" in common.restore_player_language(instance) and options.read_bytes() == player

                # A record whose options.txt has gone creates nothing; an unreadable one names the file to delete and acts on nothing.
                options.write_bytes(player); killed_inside(); options.unlink()
                assert 'gone' in common.restore_player_language(instance) and not options.exists() and not record.exists()
                options.write_bytes(player); record.write_text('{"lang": 3}')
                try:
                    common.restore_player_language(instance); raise AssertionError('a malformed record was acted on')
                except ValueError as error:
                    assert 'not a sweep record' in str(error) and 'then delete ' + str(record) in str(error), error
                assert options.read_bytes() == player and record.exists()
                print('killed sweep PASS')
                """, DriverTools.COMPAT.resolve("win").toString(), temp.resolve("instance").toString());
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("killed sweep PASS"), result.output());
    }

    /**
     * push-and-run's stop kills a sweep while its client loads — the driver along with it, so the driver's own restore
     * never runs — and the player gets their options.txt back byte for byte, not only its language: Minecraft.<init>
     * saves startedCleanly:false at once, and left behind it makes the player's next start reset its fullscreen mode.
     * This runs the stop's Python twin (note_sweep_writer before the kill, restore_after_stop after it) against a real
     * process killed inside the block; PushAndRunTest pins the PowerShell that mirrors it.
     */
    @Test void aStopThatKillsALoadingClientPutsThePlayersBytesBackWhole() throws Exception {
        var result = DriverTools.run(Map.of(), "-c", """
                import json, pathlib, subprocess, sys
                sys.path.insert(0, sys.argv[1]); import common
                instance = pathlib.Path(sys.argv[2]); instance.mkdir()
                options, record = instance / 'options.txt', instance / 'options.txt.forbric-sweep'
                """ + SWEEP_DRIVER + """
                player = ('version:4671\\r\\nfullscreen:true\\r\\nlang:zh_cn\\r\\nlastServer:中文服\\r\\n'
                          'key_key.xaero_minimap:key.keyboard.y\\r\\nstartedCleanly:true\\r\\n').encode('utf-8')
                def stop(child):
                    # push-and-run's stop: note the record's writer, kill (taskkill /F: no finally), wait, then restore.
                    writer = common.note_sweep_writer(instance)
                    child.kill(); child.wait()
                    return writer, common.restore_after_stop(instance, writer)

                # Killed while it loads: the player's bytes come back whole, over the client's startedCleanly:false and the key line
                # it dropped, not only the language.
                options.write_bytes(player)
                child = loading()
                assert b'lang:en_us' in options.read_bytes() and b'startedCleanly:false' in options.read_bytes()
                writer, done = stop(child)
                assert writer and writer.startswith(f'{child.pid}:') and 'byte for byte' in done, (writer, done)
                assert options.read_bytes() == player and not record.exists(), options.read_bytes()
                # The same under `player`, which pins nothing but lets the client rewrite the file all the same.
                child = loading('player')
                assert b'startedCleanly:false' in options.read_bytes()
                stop(child)
                assert options.read_bytes() == player and not record.exists(), options.read_bytes()
                # The player had no options.txt: the one the killed client wrote goes.
                options.unlink()
                child = loading()
                assert options.is_file()
                stop(child)
                assert not options.exists() and not record.exists()
                # The writer was gone before the stop came (a reboot): the player may have played since, so only the lang line goes
                # back and their own change stays — and so does the client's rewrite, which is what the reboot costs.
                options.write_bytes(player)
                child = loading(); child.kill(); child.wait()
                options.write_bytes(options.read_bytes().replace(b'fullscreen:true', b'fullscreen:false'))
                assert common.note_sweep_writer(instance) is None
                assert "lang:zh_cn back" in common.restore_after_stop(instance, None)
                assert options.read_bytes() == (player.replace(b'fullscreen:true', b'fullscreen:false')
                                                .replace(b'key_key.xaero_minimap:key.keyboard.y\\r\\n', b'')
                                                .replace(b'startedCleanly:true', b'startedCleanly:false')), options.read_bytes()
                assert not record.exists()
                # A writer the stop saw but did not kill — not in its pid files — is left to its own finally.
                options.write_bytes(player)
                child = loading()
                assert common.restore_after_stop(instance, common.note_sweep_writer(instance)) is None and record.exists()
                child.stdin.close()
                assert child.wait() == 0 and options.read_bytes() == player and not record.exists()
                # A record nobody can read: the stop still kills (the twin notes no writer), then fails naming the file to delete.
                record.write_text('{"lang": "en_us"')
                assert common.note_sweep_writer(instance) is None
                try:
                    common.restore_after_stop(instance, None)
                    raise AssertionError('an unreadable record was acted on')
                except ValueError as error:
                    assert 'then delete ' + str(record) in str(error), error
                assert options.read_bytes() == player and record.exists()
                print('stop PASS')
                """, DriverTools.COMPAT.resolve("win").toString(), temp.resolve("instance").toString());
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("stop PASS"), result.output());
    }

    /**
     * Two drivers on one instance: the second refuses to start while the first one's record names a live writer, and
     * finding no record and writing one happen under one lock, so two starting together cannot both pin.
     */
    @Test void aSecondDriverWaitsItsTurnForThePlayersOptions() throws Exception {
        var result = DriverTools.run(Map.of(), "-c", """
                import json, pathlib, select, subprocess, sys
                sys.path.insert(0, sys.argv[1]); import common
                instance = pathlib.Path(sys.argv[2]); instance.mkdir()
                options, record = instance / 'options.txt', instance / 'options.txt.forbric-sweep'
                """ + SWEEP_DRIVER + """
                player = b'version:4671\\r\\nlang:zh_cn\\r\\nstartedCleanly:true\\r\\n'
                options.write_bytes(player)
                first = loading()
                pinned = options.read_bytes()
                try:
                    with common.sweep_language(instance):
                        raise AssertionError('two sweeps pinned one options.txt')
                except RuntimeError as error:
                    assert f'pid {first.pid}' in str(error) and str(record) in str(error), error
                assert options.read_bytes() == pinned and json.loads(record.read_text())['pid'] == first.pid
                first.stdin.close()
                assert first.wait() == 0 and options.read_bytes() == player and not record.exists()
                # Finding no record and writing one is a single step under the pid file's lock: a second driver waits for it.
                with common.pid_lock(dict(pid_file=str(instance / '.forbric-sweep.pid'))):
                    second = subprocess.Popen([sys.executable, '-c', LOADING, sys.argv[1], str(instance), 'en_us'],
                                              stdin=subprocess.PIPE, stdout=subprocess.PIPE)
                    assert select.select([second.stdout], [], [], 2)[0] == [] and not record.exists() and options.read_bytes() == player
                assert second.stdout.readline() == b'loading\\n' and record.exists()
                second.stdin.close()
                assert second.wait() == 0 and options.read_bytes() == player and not record.exists()
                # A record already gone when the block ends does not fail a run whose restore succeeded.
                with common.sweep_language(instance):
                    record.unlink()
                assert options.read_bytes() == player
                print('twice PASS')
                """, DriverTools.COMPAT.resolve("win").toString(), temp.resolve("instance").toString());
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("twice PASS"), result.output());
    }

    /** A blank FORBRIC_LANG is the default rather than a client driver that stops on it; `player` passes through. */
    @Test void anEmptyLanguageIsTheDefaultAndPlayerIsAChoice() throws Exception {
        var result = DriverTools.run(Map.of(), "-c", """
                import argparse, os, sys
                sys.path.insert(0, sys.argv[1]); import common
                assert common.client_language('') == common.client_language(None) == common.client_language('  ') == 'en_us'
                assert common.client_language('player') == 'player' and common.client_language('zh_cn') == 'zh_cn'
                for bad in ('zh cn', 'ZH_CN', 'zh_cn\\nguiScale:4'):
                    try:
                        common.client_language(bad); raise AssertionError('accepted ' + repr(bad))
                    except argparse.ArgumentTypeError:
                        pass
                def parsed(environment, *arguments):
                    os.environ.pop('FORBRIC_LANG', None); os.environ.update(environment)
                    parser = argparse.ArgumentParser(); common.language_argument(parser)
                    return parser.parse_args(list(arguments)).lang
                assert parsed({}) == 'en_us'
                assert parsed({'FORBRIC_LANG': ''}) == 'en_us'
                assert parsed({'FORBRIC_LANG': 'zh_cn'}) == 'zh_cn'
                assert parsed({'FORBRIC_LANG': 'zh_cn'}, '--lang', 'player') == 'player'
                print('choices PASS')
                """, DriverTools.COMPAT.resolve("win").toString());
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("choices PASS"), result.output());
        // Through the drivers themselves: argparse converts the default too, so a bad value stops before any file.
        for (String driver : List.of("run-client-test.py", "bisect.py")) {
            Map<String, String> environment = new HashMap<>(Map.of("FORBRIC_MC", temp.resolve("installation").toString(),
                    "FORBRIC_VERSION", "test-version", "FORBRIC_INSTANCE", temp.resolve("instance").toString()));
            environment.put("FORBRIC_LANG", "");
            var blank = DriverTools.script("win/" + driver, environment, "--print-config");
            assertEquals(0, blank.exit(), driver + ": " + blank.output());
            environment.put("FORBRIC_LANG", "zh cn");
            var wrong = DriverTools.script("win/" + driver, environment, "--print-config");
            assertEquals(2, wrong.exit(), driver + ": " + wrong.output());
            assertTrue(wrong.output().contains("not a Minecraft language code"), wrong.output());
        }
    }

    @Test void pidBookkeepingPreservesOtherProcessesAndSanitizesNames() throws Exception {
        var result = DriverTools.run(Map.of(), "-c", """
                import pathlib,sys,subprocess
                sys.path.insert(0,sys.argv[1]); import common
                config={'pid_file':str(pathlib.Path(sys.argv[2])/'.forbric-sweep.pid')}
                common.record_pid(config, 101)
                common.record_pid(config, 202)
                common.record_pid(config, 202)
                assert pathlib.Path(config['pid_file']).read_text().splitlines()==['101','202']
                code='import sys;sys.path.insert(0,sys.argv[1]);import common;common.record_pid({"pid_file":sys.argv[2]},int(sys.argv[3]))'
                children=[subprocess.Popen([sys.executable,'-c',code,sys.argv[1],config['pid_file'],str(i)]) for i in range(300,310)]
                assert all(child.wait()==0 for child in children)
                assert set(pathlib.Path(config['pid_file']).read_text().splitlines())=={'101','202',*(str(i) for i in range(300,310))}
                common.record_pid(config,101,remove=True)
                assert '101' not in pathlib.Path(config['pid_file']).read_text().splitlines()
                assert '202' in pathlib.Path(config['pid_file']).read_text().splitlines()
                assert common.safe_filename('a?b.jar')=='a_b.jar'
                assert common.safe_filename('CON.jar')=='_CON.jar'
                print('pid and filenames PASS')
                """, DriverTools.COMPAT.resolve("win").toString(), temp.toString());
        assertEquals(0, result.exit(), result.output());
    }

    @Test void screenshotSelectionWaitsForPngCompletionAndRejectsOldFrames() throws Exception {
        var result = DriverTools.run(Map.of(), "-c", """
                import os,pathlib,sys
                sys.path.insert(0,sys.argv[1]); import common
                root=pathlib.Path(sys.argv[2]); c={'screenshots':str(root)}
                head=bytes.fromhex('89504e470d0a1a0a'); tail=bytes.fromhex('0000000049454e44ae426082')
                partial=root/'partial.png'; partial.write_bytes(head+b'writing PNG data')
                os.utime(partial,(101,101))
                old=root/'old.png'; old.write_bytes(head+tail); os.utime(old,(99,99))
                equal=root/'equal.png'; equal.write_bytes(head+tail); os.utime(equal,(100,100))
                assert common.fresh_shots(c,100)==[]
                partial.write_bytes(head+b'finished'+tail); os.utime(partial,(101,101))
                assert common.fresh_shots(c,100)==[partial]
                print('fresh complete PNG selector PASS')
                """, DriverTools.COMPAT.resolve("win").toString(), temp.toString());
        assertEquals(0, result.exit(), result.output());
    }

    @Test void launcherBuildsClientAndServerArgumentsFromInstalledProfile() throws Exception {
        var result = DriverTools.run(Map.of(), "-c", """
                import json,pathlib,sys,zipfile
                sys.path.insert(0,sys.argv[1]); import common
                root=pathlib.Path(sys.argv[2]); mc=root/'mc'; instance=root/'instance'
                (instance/'child-natives').mkdir(parents=True)
                (mc/'libraries').mkdir(parents=True)
                (mc/'libraries'/'a.jar').write_bytes(b'jar')
                (mc/'libraries'/'b.jar').write_bytes(b'jar')
                parent={'id':'base','assetIndex':{'id':'assets'},'libraries':[{'name':'g:a:1','downloads':{'artifact':{'path':'a.jar'}}}],
                        'arguments':{'jvm':['-cp','${classpath}'],'game':['--gameDir','${game_directory}','--assetsDir','${assets_root}']}}
                child={'id':'child','inheritsFrom':'base','mainClass':'net.forbric.kernel.boot.KernelClientLaunch',
                       'libraries':[{'name':'g:b:1','downloads':{'artifact':{'path':'b.jar'}}}],
                       'arguments':{'game':['--gameJar','${library_directory}/merged.jar','--runtimeJar','${library_directory}/runtime.jar',
                                            '--libraryPath','${library_directory}/a.jar']}}
                for profile in (parent,child):
                    directory=mc/'versions'/profile['id']; directory.mkdir(parents=True)
                    (directory/(profile['id']+'.json')).write_text(json.dumps(profile))
                with zipfile.ZipFile(mc/'versions'/'base'/'base.jar','w') as jar:
                    jar.writestr('version.json','{}'); jar.writestr('never/OnParent.class',b'bad')
                c={'mc':str(mc),'instance':str(instance),'version':'child','java':'fixture-java','world':'fixture-world',
                   'server_dir':str(instance/'server-gen'),'jvm':['-Dforbric.clientSmokeScreenshots=100']}
                args=common.launch_command(c)
                assert args[0]=='fixture-java'
                assert args[args.index('--quickPlaySingleplayer')+1]=='fixture-world'
                assert args[args.index('--gameDir')+1]==str(instance)
                assert '-Djava.library.path='+str(instance/'child-natives') in args
                assert '-Dforbric.clientSmokeScreenshots=100' in args
                assert args.index('--gameJar') < args.index('--') < args.index('--gameDir')
                assert str(mc/'versions'/'base'/'base.jar') not in args[args.index('-cp')+1]
                with zipfile.ZipFile(instance/'game-metadata.jar') as jar: assert jar.namelist()==['version.json']
                server=common.launch_command(c,server=True)
                assert 'net.forbric.kernel.boot.KernelServerLaunch' in server
                assert '--quickPlaySingleplayer' not in server
                assert server[server.index('--gameDir')+1]==str(instance/'server-gen')
                assert common.rules_allow({'rules':[{'action':'allow','os':{'name':'windows'}}]})
                assert not common.rules_allow({'rules':[{'action':'allow','os':{'name':'linux'}}]})
                assert not common.rules_allow({'rules':[{'action':'allow','features':{'is_demo_user':True}}]})
                # Acceptance runs strict, whatever the installed profile says; only the operator's own --jvm may
                # ask for another policy, and the last -D is the one the JVM keeps.
                policy=lambda argv:[a for a in argv if a.startswith('-Dforbric.compatibilityPolicy=')][-1]
                parent['arguments']['jvm'].append('-Dforbric.compatibilityPolicy=ask')
                (mc/'versions'/'base'/'base.json').write_text(json.dumps(parent))
                assert policy(common.launch_command(c))=='-Dforbric.compatibilityPolicy=strict'
                assert policy(common.launch_command(c,server=True))=='-Dforbric.compatibilityPolicy=strict'
                c['jvm']=['-Dforbric.compatibilityPolicy=continue']
                assert policy(common.launch_command(c))=='-Dforbric.compatibilityPolicy=continue'
                print('client and server arguments PASS')
                """, DriverTools.COMPAT.resolve("win").toString(), temp.toString());
        assertEquals(0, result.exit(), result.output());
    }
}
