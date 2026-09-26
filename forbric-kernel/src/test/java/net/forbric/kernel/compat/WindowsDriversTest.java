package net.forbric.kernel.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
