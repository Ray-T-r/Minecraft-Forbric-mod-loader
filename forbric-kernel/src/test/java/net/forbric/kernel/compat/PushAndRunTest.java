package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PushAndRunTest {
    @TempDir Path temp;

    @Test
    void loadReportsKeepModNamesAndReasonsInBothLanguages() throws Exception {
        var result = python("""
                artifacts=output/'artifacts'; artifacts.mkdir()
                english='Forbric load report\\n  English Example  (english_example)\\n    english.jar\\n    did not finish loading — its constructor threw\\n'
                chinese='Forbric 加载报告\\n  Player Animation Library  (player_animation_library)\\n    animations.jar\\n    没有完成加载 — its @Mod constructor threw\\n'
                for stage,text in [('client',english),('server',chinese)]:
                    directory=artifacts/stage/'.forbric-kernel';directory.mkdir(parents=True)
                    (directory/'load-report.txt').write_text(text,encoding='utf-8')
                assert m.report(args,output,artifacts,1,-1,'fixture-time')==1
                findings=(output/'degraded.txt').read_text()
                assert english in findings and chinese in findings, findings
                assert 'No named degraded/failed mods' not in findings
                assert (artifacts/'server/.forbric-kernel/load-report.txt').read_text()==chinese
                """);
        assertEquals(0, result.exit(), result.output());
    }

    @Test
    void dryRunStagesExactlyFourArtifactsAndKeepsLauncherFiles() throws Exception {
        Path mods = Files.createDirectory(temp.resolve("mods"));
        Files.writeString(mods.resolve("a?b.jar"), "fixture");
        Path profile = profile();
        var result = DriverTools.script("push-and-run.py", Map.of(), "--dry-run", "--label", "baseline",
                "--mc", "D:\\FixtureMC", "--version", "test-profile", "--mods", mods.toString(),
                "--version-json", profile.toString(), "--output", temp.resolve("unused-output").toString());
        assertEquals(0, result.exit(), result.output());
        assertEquals(4, result.output().lines().filter(line -> line.startsWith("PUT_ARTIFACT ")).count());
        String clean = result.output().lines().filter(line -> line.startsWith("CLEAN ")).findFirst().orElseThrow();
        assertTrue(clean.contains("\"config\"") && clean.contains("\"saves\"") && clean.contains("\"mods\""));
        assertTrue(clean.contains("\"replay_recordings\""), clean);
        for (String preserved : new String[] {"options.txt", "natives", ".zip", "PCL"}) assertFalse(clean.contains(preserved));
        assertTrue(result.output().contains(".forbric-sweep.pid"));
        assertTrue(result.output().contains("taskkill /T /F /PID $line"));
        assertTrue(result.output().contains("options.txt.forbric-sweep"), result.output());
        assertTrue(result.output().contains("client-join --lang en_us"), result.output());
        assertFalse(result.output().contains("taskkill /IM"));
        assertTrue(result.output().contains("MOD a?b.jar -> a_b.jar"));
        assertFalse(Files.exists(temp.resolve("unused-output")), "dry run must not create report artifacts");
    }

    /**
     * The stop kills every recorded PID, the client and bisect drivers among them, so their own restore of the
     * player's options.txt never runs, and the stop does it instead. Before its kill it notes whether the record's writer
     * (pid and start time) is alive; after the kill, a writer it saw alive gets its restore done for it, whole, over
     * Minecraft's own rewrite, while a record a reboot left gets only its lang line undone. A record nobody can read
     * must not stop the kill, so the stop names it only afterwards. PowerShell cannot run here, so this pins the parts
     * that carry those promises; win/common.py's note_sweep_writer / restore_after_stop are the executed twin
     * (WindowsDriversTest).
     */
    @Test
    void theStopPutsBackWhatItsOwnKillKeptTheDriverFromRestoring() throws Exception {
        var result = python("""
                import ntpath
                import common
                instance = ntpath.join('D:' + chr(92), "Jerry's MC", 'versions', '26.2-forbric')
                command = m.stop_command(instance)
                path = lambda name: m.ps(ntpath.join(instance, name))
                record, options, temporary = path('options.txt.forbric-sweep'), path('options.txt'), path('options.txt.forbric-tmp')
                assert "Jerry''s MC" in command and "Jerry's" not in command, command
                noted = command.index('elseif (Test-SweepWriter $r) { $sweepWriter = ')
                kill = command.index('taskkill /T /F /PID $line')
                waited = command.index('Wait-Process -Id ([int]$line)')
                named = command.index('if ($sweepUnreadable) { throw ')
                writer = command.index('if ($sweepWriter) { Wait-Process -Id $sweepWriterPid')
                restore = command.index('if (Test-Path -LiteralPath ' + record + ') { $r = Read-SweepRecord; if ($null -eq $r) { throw ')
                assert noted < kill < waited < named < writer < restore, command
                head, tail = command[:kill], command[restore:]
                # Before the kill nothing throws and nothing is written: an unreadable record only sets a flag.
                assert 'throw' not in head and 'WriteAllBytes' not in head and 'Remove-Item' not in head, head
                assert "if ($null -eq $r) { $sweepUnreadable = $true }" in head, head
                # The writer is its pid and its start time, as observe_command identifies a job.
                assert '$p.StartTime.ToUniversalTime().Ticks -eq [long]$r.started' in command, command
                # The killed writer's restore, done whole: its original bytes, or the file it created removed.
                whole = tail.index("if ($sweepWriter -eq ([string]$r.pid + ':' + [string]$r.started)) {")
                assert tail.index('if (-not (Test-SweepWriter $r)) {') < whole, tail
                assert ('[IO.File]::WriteAllBytes(' + temporary + ', [Convert]::FromBase64String($r.original))') in tail[whole:], tail
                assert ('if ($r.absent) { if (Test-Path -LiteralPath ' + options + ') { Remove-Item -LiteralPath ' + options
                        + ' -Force } }') in tail[whole:], tail
                # Otherwise the lang line only, and only while every lang line still names what the sweep wrote.
                only = tail.index('elseif (-not $r.absent -and $null -ne $r.lang -and (Test-Path -LiteralPath ' + options + ')) {')
                assert whole < only, tail
                assert "'(?m)" + common.LANG_LINE.pattern.decode() + "'" in tail[only:], tail
                assert '$_ -cne $r.lang' in tail[only:], tail
                # One-step replace, a plain move only where there is nothing to replace, never Move-Item -Force.
                replace = ('if (Test-Path -LiteralPath ' + options + ') { [IO.File]::Replace(' + temporary + ', ' + options
                           + ', [NullString]::Value) } else { [IO.File]::Move(' + temporary + ', ' + options + ') }')
                assert tail.count(replace) == 2, tail
                assert 'Move-Item' not in command, command
                assert tail.endswith('Remove-Item -LiteralPath ' + record + ' -Force -ErrorAction SilentlyContinue } }'), tail
                # The unreadable-record error names the file to delete, before and after the kill alike.
                message = m.ps(ntpath.join(instance, 'options.txt.forbric-sweep') + ' is not a sweep record')[:-1]
                assert command.count('throw ' + message) == 2 and 'then delete ' + ntpath.join(instance, 'options.txt.forbric-sweep').replace("'", "''") in command, command
                for key in ('lang', 'was', 'absent', 'pid', 'started', 'original'):
                    assert "'" + key + "'" in command, key
                assert command.count('{') == command.count('}') and command.count('(') == command.count(')'), command
                """);
        assertEquals(0, result.exit(), result.output());
    }

    /**
     * The client's language crosses to Windows as an argument: the job starts from the remote shell's environment,
     * not this one. A blank FORBRIC_LANG is the default, `player` passes through, and a bad value stops the run
     * before anything remote is touched.
     */
    @Test
    void theClientLanguageReachesTheWindowsDriverAsAnArgument() throws Exception {
        var result = python("""
                import json, os
                jobs, touched = [], []
                m.remote = lambda command: touched.append(command) or ''
                m.put = lambda *arguments: touched.append(arguments)
                m.stage_tools = lambda args, output: 'D:\\\\tools'
                m.subprocess.run = lambda *arguments, **options: None
                def run_job(args, stage, tools, output, driver, extra=()):
                    jobs.append((driver, list(extra)))
                    return 0
                m.run_job = run_job
                m.finish_run = lambda args, output, remote_tools, server, client, started, errors: 1 if errors else 0
                mods = output / 'mods'; mods.mkdir(); (mods / 'a.jar').write_bytes(b'PK')
                profile = output / 'profile.json'
                profile.write_text(json.dumps(dict(libraries=[dict(name=f'net.forbric:{n}:1', downloads=dict(artifact=dict(path=f'x/{n}.jar')))
                    for n in ('forbric-kernel', 'patched-mc-merged', 'forge-runtime', 'neoforge-runtime')])))
                subset = output / 'subset.txt'; subset.write_text('a.jar\\n')
                def main(label, *arguments, environment={}):
                    os.environ.pop('FORBRIC_LANG', None); os.environ.update(environment)
                    jobs.clear(); touched.clear()
                    sys.argv = ['push-and-run.py', '--label', label, '--mc', 'D:\\\\fixture-mc', '--version', 'fixture',
                                '--no-build', '--output', str(output / label), *arguments]
                    assert m.main() == 0
                    return list(jobs)
                run = ['--mods', str(mods), '--version-json', str(profile)]
                assert main('default', *run) == [('run-server-test.py', []), ('run-client-test.py', ['--lang', 'en_us'])], jobs
                assert main('blank', *run, environment={'FORBRIC_LANG': ''})[-1] == ('run-client-test.py', ['--lang', 'en_us'])
                assert main('chinese', *run, environment={'FORBRIC_LANG': 'zh_cn'})[-1] == ('run-client-test.py', ['--lang', 'zh_cn'])
                assert main('player', *run, '--client-lang', 'player')[-1] == ('run-client-test.py', ['--lang', 'player'])
                bisect = main('bisect', '--bisect', str(subset), '--client-lang', 'zh_cn')
                assert [driver for driver, _ in bisect] == ['bisect.py'] and bisect[0][1][-2:] == ['--lang', 'zh_cn'], bisect
                for arguments, environment in ([('--client-lang', 'zh cn'), {}], [(), {'FORBRIC_LANG': 'zh cn'}]):
                    try:
                        main('rejected', *run, *arguments, environment=environment)
                        raise AssertionError('a bad language was staged')
                    except SystemExit as exit:
                        assert exit.code == 2, exit.code
                    assert touched == [] and jobs == [], (touched, jobs)
                """);
        assertEquals(0, result.exit(), result.output());
    }

    @Test
    void theReportSaysWhichLanguageTheClientPlayed() throws Exception {
        // A language decides which assets every mod loads; two verdicts are only comparable when both name theirs.
        var result = python("""
                import json
                import common
                artifacts = output / 'artifacts'; (artifacts / 'driver').mkdir(parents=True)
                (artifacts / 'files.json').write_text('[]')
                m.check = lambda command, destination: destination.write_text('') or False
                m.report(args, output, artifacts, 0, -1, 'fixture-time')
                assert '- Client language: asked for en_us; no client driver reported one' in (output / 'report.md').read_text()
                (artifacts / 'driver' / 'client.log').write_text(
                    "options.txt: a sweep ended before its own restore; put the player's lang:zh_cn back\\nclient language "
                    + common.describe_language('en_us', 'zh_cn') + '\\nmods=1\\n')
                m.report(args, output, artifacts, 0, 1, 'fixture-time')
                assert "- Client language: en_us; the player's options.txt names zh_cn, restored after the run" in (output / 'report.md').read_text()
                args.bisect = output / 'subset.txt'
                (artifacts / 'driver' / 'bisect.log').write_text('client language ' + common.describe_language('player', 'zh_cn') + '\\n')
                m.report(args, output, artifacts, 'reused world', 1, 'fixture-time')
                assert "- Client language: zh_cn; the player's options.txt, played as it is and restored after the run" in (output / 'report.md').read_text()
                (artifacts / 'driver' / 'bisect.log').write_text('client language ' + common.describe_language('en_us', None, True) + '\\n')
                m.report(args, output, artifacts, 'reused world', 1, 'fixture-time')
                assert ("- Client language: en_us; the player has no options.txt (vanilla plays en_us), and the one the client writes "
                        "is removed after the run") in (output / 'report.md').read_text()
                """);
        assertEquals(0, result.exit(), result.output());
    }

    @Test
    void filenameCollisionsAndTheInstallationRootAreRejectedBeforeCleanup() throws Exception {
        Path mods = Files.createDirectory(temp.resolve("mods"));
        Files.writeString(mods.resolve("a?b.jar"), "one");
        Files.writeString(mods.resolve("a*b.jar"), "two");
        var collision = DriverTools.script("push-and-run.py", Map.of(), "--dry-run", "--label", "baseline",
                "--mc", "D:\\FixtureMC", "--version", "test-profile", "--mods", mods.toString(),
                "--version-json", profile().toString());
        assertEquals(2, collision.exit(), collision.output());
        assertTrue(collision.output().contains("sanitized filename collision"));
        var unsafe = DriverTools.script("push-and-run.py", Map.of(), "--dry-run", "--label", "baseline",
                "--mc", "D:\\FixtureMC", "--instance", "d:\\fixturemc", "--version", "test-profile");
        assertEquals(2, unsafe.exit(), unsafe.output());
        assertTrue(unsafe.output().contains("dedicated instance directory"));
    }

    @Test
    void aStaleRunningFileIsTerminalWhenItsRecordedProcessIsGone() throws Exception {
        var result = python("""
                import json
                answers = iter(['FORBRIC_PID=42\\nFORBRIC_STARTED=123',
                                json.dumps(dict(alive=False, result=dict(state='running', pid=42)))])
                commands = []
                def remote(command):
                    commands.append(command)
                    return next(answers)
                m.remote = remote
                m.time.sleep = lambda _: (_ for _ in ()).throw(AssertionError('dead process must not be polled forever'))
                try:
                    m.run_job(args, 'server', 'D:\\\\fixture-tools', output, 'run-server-test.py')
                    raise AssertionError('stale running file was accepted')
                except RuntimeError as error:
                    assert 'ended before publishing a result' in str(error), str(error)
                assert 'Get-Process -Id 42' in commands[1]
                assert 'StartTime.ToUniversalTime().Ticks -eq 123' in commands[1]
                assert json.loads((output / 'server-handle.json').read_text())['pid'] == 42
                assert json.loads((output / 'server-result.json').read_text())['returncode'] == 2
                """);
        assertEquals(0, result.exit(), result.output());
    }

    @Test
    void observationFailuresRetainAndRepollTheSameLiveJob() throws Exception {
        var result = python("""
                import json
                commands = []
                def remote(command):
                    commands.append(command)
                    if len(commands) == 1: return 'FORBRIC_PID=42\\nFORBRIC_STARTED=123'
                    if len(commands) == 2: raise RuntimeError('temporary transport interruption')
                    if len(commands) == 3: return json.dumps(dict(alive=True, result=dict(state='running', pid=42)))
                    return json.dumps(dict(alive=False, result=dict(state='done', pid=42, returncode=0, started_ns=100)))
                m.remote = remote
                m.time.sleep = lambda _: None
                assert m.run_job(args, 'client', 'D:\\\\fixture-tools', output, 'run-client-test.py') == 0
                assert sum('Start-Process' in command for command in commands) == 1
                assert all('Get-Process -Id 42' in command for command in commands[1:])
                assert json.loads((output / 'client-result.json').read_text())['state'] == 'done'
                """);
        assertEquals(0, result.exit(), result.output());
    }

    @Test
    void theKernelJarIsBuiltBeforeAnythingIsStagedAndAFailedBuildStagesNothing() throws Exception {
        // sweep90-win-r5 reported 4f229131 and ran a build/libs jar from before 8d0fb2ee.
        var result = python("""
                import json, os, stat, sys
                kernel = output / 'kernel'; kernel.mkdir()
                gradlew = kernel / 'gradlew'
                gradlew.write_text('#!/bin/sh\\necho "$@" > "$(dirname "$0")/built"\\necho compile error\\nexit 1\\n')
                gradlew.chmod(gradlew.stat().st_mode | stat.S_IEXEC)
                m.KERNEL = kernel
                calls = []
                m.remote = lambda command: calls.append(command) or ''
                m.put = lambda *a: calls.append(a)
                mods = output / 'mods'; mods.mkdir(); (mods / 'a.jar').write_bytes(b'PK')
                profile = output / 'profile.json'
                profile.write_text(json.dumps(dict(libraries=[dict(name=f'net.forbric:{n}:1', downloads=dict(artifact=dict(path=f'x/{n}.jar')))
                    for n in ('forbric-kernel', 'patched-mc-merged', 'forge-runtime', 'neoforge-runtime')])))
                sys.argv = ['push-and-run.py', '--label', 'built', '--mc', 'D:\\\\fixture-mc', '--version', 'fixture',
                            '--mods', str(mods), '--version-json', str(profile), '--output', str(output / 'run')]
                try:
                    m.main(); raise AssertionError('a failed build was staged')
                except SystemExit as exit:
                    assert exit.code == 2, exit.code
                assert (kernel / 'built').read_text().split() == ['--offline', '-q', 'jar']
                assert calls == [], calls
                """);
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("building the kernel jar failed") && result.output().contains("compile error"),
                result.output());
    }

    @Test
    void theJobInheritsNoStreamOfTheRemoteShellAndWritesItsOwnLogs() throws Exception {
        // With -RedirectStandard* the job held the remote shell's output pipe, and the start command of every
        // client run sat on it until the game exited — past the shell server's 300 s limit.
        var result = python("""
                import json, subprocess
                commands = []
                def remote(command):
                    commands.append(command)
                    if len(commands) == 1: return 'FORBRIC_PID=42\\nFORBRIC_STARTED=123'
                    return json.dumps(dict(alive=False, result=dict(state='done', pid=42, returncode=0)))
                m.remote = remote
                m.time.sleep = lambda _: None
                assert m.run_job(args, 'client', 'D:\\\\fixture-tools', output, 'run-client-test.py') == 0
                assert 'RedirectStandard' not in commands[0], commands[0]
                assert 'client.log' in commands[0] and 'client-stderr.log' in commands[0], commands[0]

                tools = output / 'tools'; tools.mkdir()
                (tools / 'job.py').write_text(m.JOB)
                (tools / 'common.py').write_text((pathlib.Path(sys.argv[1]).parent / 'win' / 'common.py').read_text())
                (tools / 'driver.py').write_text('import sys\\nprint("driver out")\\nprint("driver err", file=sys.stderr)\\n')
                status, out, err = output / 'status.json', output / 'out.log', output / 'err.log'
                ran = subprocess.run([sys.executable, str(tools / 'job.py'), str(status), str(output / 'instance'),
                                      str(out), str(err), str(tools / 'driver.py')], capture_output=True, text=True, timeout=60)
                assert ran.returncode == 0 and ran.stdout == '' and ran.stderr == '', ran
                assert out.read_text() == 'driver out\\n', out.read_text()
                assert err.read_text() == 'driver err\\n', err.read_text()
                assert json.loads(status.read_text())['returncode'] == 0
                """);
        assertEquals(0, result.exit(), result.output());
    }

    @Test
    void aJobPublishedByAProcessWeDidNotStartIsNamedAtOnceAndNeverRetried() throws Exception {
        // A launcher shim re-executes a different interpreter, so the driver publishes ITS pid. Retrying that as
        // if it were transport flakiness reported a server test that had returned 0 as a FAIL, 15 minutes late.
        var result = python("""
                import json
                commands = []
                def remote(command):
                    commands.append(command)
                    if len(commands) == 1: return 'FORBRIC_PID=42\\nFORBRIC_STARTED=123'
                    return json.dumps(dict(alive=False, result=dict(state='done', pid=99, returncode=0)))
                m.remote = remote
                m.time.sleep = lambda _: (_ for _ in ()).throw(AssertionError('a shim is not transient'))
                try:
                    m.run_job(args, 'server', 'D:\\\\fixture-tools', output, 'run-server-test.py')
                    raise AssertionError('a foreign status was accepted')
                except m.NotOurJob as error:
                    message = str(error)
                assert 'started process 42' in message and 'published pid 99' in message, message
                assert 'FORBRIC_PYTHON' in message and '__target__' in message, message
                assert len(commands) == 2, commands
                assert json.loads((output / 'server-result.json').read_text())['state'] == 'unproven'
                """);
        assertEquals(0, result.exit(), result.output());
    }

    @Test
    void aFailedJobStartStillCollectsAndWritesAFailureReport() throws Exception {
        var result = python("""
                import json
                def failed(_): raise RuntimeError('fixture Start-Process failed')
                m.remote = failed
                try:
                    m.run_job(args, 'server', 'D:\\\\fixture-tools', output, 'run-server-test.py')
                    raise AssertionError('failed start was accepted')
                except RuntimeError as error:
                    failure = str(error)
                collected = []
                def collect(*_):
                    collected.append(True)
                    artifacts = output / 'artifacts'
                    artifacts.mkdir()
                    return artifacts
                m.collect = collect
                assert m.finish_run(args, output, 'D:\\\\fixture-run', -1, -1, 'fixture-time', [failure]) == 1
                assert collected == [True]
                report = (output / 'report.md').read_text()
                assert 'fixture Start-Process failed' in report and '**FAIL**' in report
                assert json.loads((output / 'server-result.json').read_text())['state'] == 'unproven'
                assert (output / 'errors.json').is_file()
                assert 'chosen-world' in (output / 'region.txt').read_text()
                """);
        assertEquals(0, result.exit(), result.output());
    }

    @Test
    void collectorActuallyRunsAndKeepsRemoteTimestampsForFrameFreshness() throws Exception {
        var result = python("""
                import json, subprocess
                root = output / 'fixture-instance'; root.mkdir()
                drivers = output / 'fixture-drivers'; drivers.mkdir()
                shots = root / 'screenshots'; shots.mkdir()
                (shots / 'fresh.png').write_bytes(b'fixture')
                regions = root / 'server-gen/chosen-world/dimensions/minecraft/overworld/region'
                regions.mkdir(parents=True)
                (regions / 'r.0.0.mca').write_bytes(b'region-fixture')
                (drivers / 'client-status.json').write_text('{}')
                hidden = root / '.forbric-compat'; hidden.mkdir()
                (hidden / 'old.log').write_text('old run')
                m.remote = lambda _: ''
                m.put = lambda *arguments: None
                def get(source, destination):
                    subprocess.run([sys.executable, str(output / 'collect.py'), str(root), str(drivers), str(destination)], check=True)
                m.get = get
                args.instance = str(root)
                artifacts = m.collect(args, str(drivers), output)
                index = json.loads((artifacts / 'files.json').read_text())
                names = [entry['name'] for entry in index]
                assert 'instance/screenshots/fresh.png' in names
                assert 'instance/server-gen/chosen-world/dimensions/minecraft/overworld/region/r.0.0.mca' in names
                assert 'driver/client-status.json' in names
                assert not any('old.log' in name for name in names)
                assert next(entry for entry in index if entry['name'].endswith('fresh.png'))['mtime_ns'] > 0
                """);
        assertEquals(0, result.exit(), result.output());
    }

    @Test
    void reportUsesTheChosenWorldAndRemoteScreenshotTimes() throws Exception {
        var result = python("""
                import json
                artifacts = output / 'artifacts'; artifacts.mkdir()
                (artifacts / 'files.json').write_text(json.dumps([
                    dict(name='instance/screenshots/z-old.png', mtime_ns=90, size=1),
                    dict(name='instance/screenshots/a-fresh.png', mtime_ns=150, size=1)]))
                (output / 'client-result.json').write_text(json.dumps(dict(started_ns=100)))
                strict_reports(artifacts)
                commands = []
                def check(command, destination):
                    commands.append(command)
                    destination.write_text('unreadable: 0\\nminecraft:coal_ore: 11\\ndungeons: 0\\n')
                    return True
                m.check = check
                assert m.report(args, output, artifacts, 0, 0, 'fixture-time') == 0
                frame = next(command for command in commands if any('frame-verdict.py' in x for x in command))
                region = next(command for command in commands if any('region-probe.py' in x for x in command))
                assert frame[-1].endswith('a-fresh.png'), frame
                assert '/chosen-world/dimensions/minecraft/overworld/region' in region[2].replace('\\\\', '/'), region
                (output / 'client-result.json').write_text(json.dumps(dict(started_ns=1000)))
                assert m.report(args, output, artifacts, 0, 0, 'fixture-time') == 1
                assert 'no-fresh-screenshot' in (output / 'frame.txt').read_text()
                """);
        assertEquals(0, result.exit(), result.output());
    }

    @Test
    void theWorldIsJudgedByOreRatherThanByADungeonItCannotContain() throws Exception {
        // A sweep's server has no player on it, so it generates only the spawn area: 25 chunks of terrain, the
        // same 25 every run because the seed is fixed. Dungeons are about one per 177 chunks (106 across the
        // 18,749 of run/client-merged-pack's real world), so 25 chunks expect 0.14 of one and `dungeons: [1-9]`
        // was red in EVERY run ever kept in build/compat/ — a verdict that says FAIL whatever the client did
        // cannot tell a broken client from a working one. Ore is placed in the same feature stage and is a floor
        // rather than a coincidence.
        var result = python("""
                import json
                artifacts = output / 'artifacts'; artifacts.mkdir()
                (artifacts / 'files.json').write_text(json.dumps([
                    dict(name='instance/screenshots/a.png', mtime_ns=150, size=1)]))
                (output / 'client-result.json').write_text(json.dumps(dict(started_ns=100)))
                strict_reports(artifacts)
                text = {}
                def check(command, destination):
                    destination.write_text(text['region'] if 'region-probe.py' in command[1] else 'verdict=DREW')
                    return True
                m.check = check

                # No dungeon anywhere, ore present: that is what a real sweep writes, and it must PASS.
                text['region'] = 'chunks read: 529\\nminecraft:coal_ore: 11\\ndungeons: 0\\nunreadable: 0 (lz4=0)\\n'
                assert m.report(args, output, artifacts, 0, 0, 'fixture-time') == 0

                # Dungeons present but no ore at all: the world never reached the feature stage, so it FAILS
                # even though the old condition would have passed it.
                text['region'] = 'chunks read: 529\\nminecraft:coal_ore: 0\\ndungeons: 4\\nunreadable: 0 (lz4=0)\\n'
                assert m.report(args, output, artifacts, 0, 0, 'fixture-time') == 1

                # An unreadable chunk still fails, ore or not.
                text['region'] = 'chunks read: 529\\nminecraft:coal_ore: 11\\ndungeons: 0\\nunreadable: 2 (lz4=2)\\n'
                assert m.report(args, output, artifacts, 0, 0, 'fixture-time') == 1
                """);
        assertEquals(0, result.exit(), result.output());
    }

    @Test
    void acceptanceNeedsAFreshStrictReportWithNoRequiredLossOnBothSides() throws Exception {
        // A player's Continue must not turn a confirmed required loss into a passing sweep, and neither may a
        // report left from an earlier run, a missing one, or one decided under another policy.
        var result = python("""
                import json
                artifacts = output / 'artifacts'; artifacts.mkdir()
                (artifacts / 'files.json').write_text(json.dumps([
                    dict(name='instance/screenshots/a.png', mtime_ns=150, size=1)]))
                (output / 'client-result.json').write_text(json.dumps(dict(started_ns=100)))
                def check(command, destination):
                    destination.write_text('unreadable: 0\\nminecraft:coal_ore: 11\\n' if 'region-probe.py' in command[1] else 'verdict=DREW')
                    return True
                m.check = check
                assert m.report(args, output, artifacts, 0, 0, 'fixture-time') == 1, 'no report at all'
                assert 'missing' in (output / 'compatibility.txt').read_text()
                strict_reports(artifacts)
                assert m.report(args, output, artifacts, 0, 0, 'fixture-time') == 0
                assert 'Compatibility report (fresh, STRICT, 0 required losses on both sides): PASS' in (output / 'report.md').read_text()
                assert (output / 'compatibility.txt').read_text().count('STRICT, 0 confirmed required losses') == 2
                lost = dict(id='lost', modId='demo', confidence='CONFIRMED', required=True)
                for bad, why in [(dict(policy='CONTINUE'), 'decided under CONTINUE'),
                                 (dict(policy='ASK'), 'decided under ASK'),
                                 (dict(findings=[lost]), 'demo:lost'),
                                 (dict(failures=[dict(modId='x', status='FAILED')]), 'unclassified FAILED'),
                                 (dict(written=50), 'not written by this run')]:
                    strict_reports(artifacts, **bad)
                    assert m.report(args, output, artifacts, 0, 0, 'fixture-time') == 1, bad
                    assert why in (output / 'compatibility.txt').read_text(), (bad, (output / 'compatibility.txt').read_text())
                    assert 'FAIL, see compatibility.txt' in (output / 'report.md').read_text()
                # Suspicions and optional losses are evidence, not failures.
                strict_reports(artifacts, findings=[dict(id='s', modId='d', confidence='SUSPECTED', required=True),
                                                    dict(id='o', modId='d', confidence='CONFIRMED', required=False)])
                assert m.report(args, output, artifacts, 0, 0, 'fixture-time') == 0
                """);
        assertEquals(0, result.exit(), result.output());
    }

    @Test
    void theCollectorBringsBothCompatibilityReportsBack() throws Exception {
        var result = python("""
                import json, subprocess
                root = output / 'fixture-instance'; root.mkdir()
                drivers = output / 'fixture-drivers'; drivers.mkdir()
                for relative in ('.forbric-kernel', 'server-gen/.forbric-kernel'):
                    (root / relative).mkdir(parents=True)
                    (root / relative / 'compatibility-report.json').write_text('{}')
                m.remote = lambda _: ''
                m.put = lambda *arguments: None
                def get(source, destination):
                    subprocess.run([sys.executable, str(output / 'collect.py'), str(root), str(drivers), str(destination)], check=True)
                m.get = get
                args.instance = str(root)
                artifacts = m.collect(args, str(drivers), output)
                names = [entry['name'] for entry in json.loads((artifacts / 'files.json').read_text())]
                for stage, relative in m.COMPATIBILITY_REPORTS:
                    assert relative in names, (relative, names)
                """);
        assertEquals(0, result.exit(), result.output());
    }

    @Test
    void shellEntryPointForwardsArgumentsToThePythonDriver() throws Exception {
        var result = DriverTools.run(Map.of(), "-c",
                "import subprocess,sys; sys.exit(subprocess.call(['bash',sys.argv[1],'--help']))",
                DriverTools.COMPAT.resolve("push-and-run.sh").toString());
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("--bisect") && result.output().contains("--quarantine"));
    }

    private DriverTools.Result python(String body) throws Exception {
        String prefix = """
                import importlib.util, pathlib, sys
                from types import SimpleNamespace
                spec = importlib.util.spec_from_file_location('push', sys.argv[1])
                m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
                output = pathlib.Path(sys.argv[2])
                args = SimpleNamespace(instance='D:\\\\fixture-instance', mc='D:\\\\fixture-mc', version='fixture',
                    label='fixture', python='python', world='chosen-world', timeout=120, java=None, jvm=[],
                    bisect=None, manifest=None, mods=None, client_lang='en_us')
                def strict_reports(artifacts, started=100, policy='STRICT', findings=(), failures=(), written=200):
                    import json
                    records = json.loads((artifacts / 'files.json').read_text()) if (artifacts / 'files.json').is_file() else []
                    records = [r for r in records if not r['name'].endswith('compatibility-report.json')]
                    required = [f for f in findings if f['confidence'] == 'CONFIRMED' and f['required']]
                    for stage, relative in m.COMPATIBILITY_REPORTS:
                        path = artifacts / relative; path.parent.mkdir(parents=True, exist_ok=True)
                        path.write_text(json.dumps(dict(policy=policy, schemaVersion=1, confirmedRequired=len(required),
                            findings=list(findings), catalogFailures=list(failures), mods=[])))
                        records.append(dict(name=relative, mtime_ns=written, size=1))
                        result = output / (stage + '-result.json')
                        state = json.loads(result.read_text()) if result.is_file() else {}
                        state['started_ns'] = started
                        result.write_text(json.dumps(state))
                    (artifacts / 'files.json').write_text(json.dumps(records))
                """;
        return DriverTools.run(Map.of(), "-c", prefix + body, DriverTools.COMPAT.resolve("push-and-run.py").toString(), temp.toString());
    }

    private Path profile() throws Exception {
        return Files.writeString(temp.resolve("profile.json"), """
                {"libraries":[
                  {"name":"net.forbric:forbric-kernel:1","downloads":{"artifact":{"path":"net/forbric/kernel/1/kernel.jar"}}},
                  {"name":"net.forbric:patched-mc-merged:1","downloads":{"artifact":{"path":"net/forbric/merged/1/merged.jar"}}},
                  {"name":"net.forbric:forge-runtime:1","downloads":{"artifact":{"path":"net/forbric/forge/1/forge.jar"}}},
                  {"name":"net.forbric:neoforge-runtime:1","downloads":{"artifact":{"path":"net/forbric/neo/1/neo.jar"}}},
                  {"name":"fixture:unchanged:1","downloads":{"artifact":{"path":"fixture/unchanged.jar"}}}
                ]}
                """);
    }
}
