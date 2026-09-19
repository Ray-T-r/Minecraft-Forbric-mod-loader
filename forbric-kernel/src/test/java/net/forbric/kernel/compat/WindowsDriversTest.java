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
                print('client and server arguments PASS')
                """, DriverTools.COMPAT.resolve("win").toString(), temp.toString());
        assertEquals(0, result.exit(), result.output());
    }
}
