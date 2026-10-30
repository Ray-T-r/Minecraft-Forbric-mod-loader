package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the production gate's edit, observation and assertions without launching a game or replacing Forge's watcher. */
class GateForgeConfigContractTest {
	private static final Path M28 = Path.of("run/gate-m28-forgeconfig.sh");
	private static final Path M16 = Path.of("run/gate-m16-forge-handshake.sh");
	private static final String COMMON = "[ForbricLive/CFG] LOADING forbriclive-common.toml: probe=11 loaded=true path=/config/forbriclive-common.toml\n";
	private static final String RELOAD = "[ForbricLive/CFG] RELOADING forbriclive-common.toml: probe=73 loaded=true path=/config/forbriclive-common.toml\n";
	private static final String CLIENT = "[ForbricLive/CFG] LOADING forbriclive-client.toml: probe=17 loaded=true path=/config/forbriclive-client.toml\n";
	private static final String COMMON_SUMMARY = "[Forbric/Lifecycle] loaded MinecraftForge configs (COMMON): applied 2, already loaded 0, failed 0 from /config\n";
	private static final String CLIENT_SUMMARY = "[Forbric/Lifecycle] loaded MinecraftForge configs (CLIENT): applied 2, already loaded 0, failed 0 from /config\n";
	private static final String SERVER_LOG = COMMON_SUMMARY + COMMON + RELOAD + "All dimensions are saved\n";
	private static final String NEO_COMMON = "[ForbricNeoLive/CFG] LOADING forbricneolive-common.toml: probe=11 loaded=true path=/config/forbricneolive-common.toml\n";
	private static final String NEO_RELOAD = "[ForbricNeoLive/CFG] RELOADING forbricneolive-common.toml: probe=73 loaded=true path=/config/forbricneolive-common.toml\n";
	private static final String NEO_SUMMARY = "[Forbric/Lifecycle] loaded NeoForge configs (COMMON) from /config — opened 2 of 2 config(s), 0 already loaded\n";
	private static final String NEO_SERVER_LOG = SERVER_LOG + NEO_SUMMARY + NEO_COMMON + NEO_RELOAD;
	private static final String NEO_CLIENT = "[ForbricNeoLive/CFG] LOADING forbricneolive-client.toml: probe=17 loaded=true path=/config/forbricneolive-client.toml\n";
	private static final String NEO_SERVER_CONFIG = "[ForbricNeoLive/CFG] LOADING forbricneolive-server.toml: greeting=from-the-gate loaded=true path=/srv/FhsWorld/serverconfig/forbricneolive-server.toml\n";
	private static final String NEO_SYNCED = "[ForbricNeoLive/CFG] RELOADING forbricneolive-server.toml: greeting=from-the-gate loaded=true path=null\n"
			+ "[ForbricNeoLive/CFG] greeting seen on the client in a world: from-the-gate\n";
	private static final String NEO_PLUGIN_SERVER = "[ForbricNeoLive/PLUGIN] prepareConfigs saw dist=DEDICATED_SERVER\n";
	private static final String NEO_PLUGIN_CLIENT = "[ForbricNeoLive/PLUGIN] prepareConfigs saw dist=CLIENT\n";
	@TempDir Path temporary;

	// Stage 3 of dropping MinecraftForge took the MinecraftForge canary's half out of gate-m28 (its M28_EDIT, M28_WATCH
	// and M28_ASSERTIONS blocks) and gate-m16 (M16_CLIENT_CONFIG): Forbric refuses that canary now, and a dedicated server
	// stops on the refusal. The seven tests that ran those blocks went with them; the NeoForge twins below were written in
	// Stage 2.5 to outlive them, and the three generic m28 checks (Done, a clean stop, no watcher failure) moved into the
	// NeoForge block, where m28NeoAcceptsExactlyOneLoadAndTheChangedSpecAndFile runs them.

	/** gate-m28's contract on the NeoForge canary: one load, the changed spec and file, the generic server checks. */
	@Test
	void m28NeoAcceptsExactlyOneLoadAndTheChangedSpecAndFile() throws Exception {
		neoFixture();
		assertGreen(assertM28Neo());
		Files.writeString(log(), NEO_SERVER_LOG + NEO_COMMON);
		assertRed(assertM28Neo(), "NeoForge COMMON Loading fired exactly once");
		Files.writeString(log(), NEO_SERVER_LOG.replace(NEO_SUMMARY, ""));
		assertRed(assertM28Neo(), "the kernel opened the NeoForge COMMON configs");
		Files.writeString(log(), NEO_SERVER_LOG.replace(NEO_COMMON, NEO_COMMON.replace("loaded=true", "loaded=false")));
		assertRed(assertM28Neo(), "the NeoForge COMMON default was read from the loaded spec");
		Files.writeString(log(), NEO_SERVER_LOG + "Opening a config that was already loaded\n");
		assertRed(assertM28Neo(), "no NeoForge config opened twice");
		Files.writeString(log(), NEO_SERVER_LOG);
		Files.writeString(neoWatchLog(), NEO_RELOAD.replace("probe=73", "probe=11"));
		assertRed(assertM28Neo(), "a new native Reloading event read the changed NeoForge value");
		Files.writeString(neoWatchLog(), NEO_RELOAD);
		Files.writeString(neoConfig(), "probe = 11\n");
		assertRed(assertM28Neo(), "expected 73");
		Files.writeString(neoConfig(), "probe = 73\n");
		Files.writeString(instance().resolve("config/forbricneolive-client.toml"), "probe=17\n");
		assertRed(assertM28Neo(), "dedicated server opened a NeoForge CLIENT config");
		Files.delete(instance().resolve("config/forbricneolive-client.toml"));
		assertRed(run("NEO_RELOADED=0\n" + section(M28, "M28_NEO_ASSERTIONS"), "exit \"$FAIL\""), "within 40 seconds");
	}

	/** The edit and the watch on the NeoForge file are the MinecraftForge ones, on their own file and their own log. */
	@Test
	void m28NeoEditsItsOwnFileAndOnlyANewReloadCounts() throws Exception {
		neoFixture();
		Files.writeString(neoConfig(), "# keep this comment\nprobe = 11 # watched\n");
		Files.writeString(config(), "probe = 11\n");
		assertGreen(run(section(M28, "M28_NEO_EDIT"), "assert_eq edited 1 \"$NEO_EDITED\"\nexit \"$FAIL\""));
		assertEquals("# keep this comment\nprobe = 73 # watched\n", Files.readString(neoConfig()));
		assertEquals("probe = 11\n", Files.readString(config()), "the NeoForge edit must not touch the MinecraftForge file");
		assertRed(run(section(M28, "M28_NEO_EDIT"), "exit \"$FAIL\""), "expected exactly one default");
		String prefix = "NEO_BEFORE_LINES=$(wc -l < \"$LOG\" | tr -d ' ')\nSRVPID=$$\nNEO_RELOADED=0\nsleeps=0\n"
				+ "sleep() { sleeps=$((sleeps + 1)); }\n";
		Files.writeString(log(), NEO_SERVER_LOG);
		assertGreen(run(prefix + section(M28, "M28_NEO_WATCH"),
				"assert_eq stale-event-rejected 0 \"$NEO_RELOADED\"\nassert_eq bounded-wait 40 \"$sleeps\"\nexit \"$FAIL\""));
		String append = "sleep() { sleeps=$((sleeps + 1)); if [ \"$sleeps\" -eq 1 ]; then printf '%s' \"$NEO_NEW_EVENT\" >> \"$LOG\"; fi; }\n";
		assertGreen(run(prefix + append + section(M28, "M28_NEO_WATCH"),
				"assert_eq fresh-event 1 \"$NEO_RELOADED\"\nexit \"$FAIL\""));
		assertEquals(NEO_RELOAD, Files.readString(neoWatchLog()));
	}

	/** gate-m16's NeoForge half: the synced value, the synced SHAPE, one CLIENT load, and the plugin's answer on each end. */
	@Test
	void m16NeoRequiresTheSyncedValueOneClientLoadAndThePluginOnBothEnds() throws Exception {
		neoFixture();
		assertGreen(assertM16Neo());
		Files.writeString(combinedLog(), NEO_CLIENT + NEO_SYNCED.replace("path=null", "path=/cli/config/forbricneolive-server.toml") + NEO_PLUGIN_CLIENT);
		assertRed(assertM16Neo(), "NeoForge: the client was handed the server's config");
		Files.writeString(combinedLog(), NEO_CLIENT + NEO_SYNCED + NEO_PLUGIN_CLIENT
				+ "[ForbricNeoLive/CFG] greeting seen on the client in a world: default\n");
		assertRed(assertM16Neo(), "NeoForge: the client is not on its own default");
		Files.writeString(combinedLog(), NEO_CLIENT + NEO_SYNCED + NEO_PLUGIN_CLIENT);
		Files.writeString(clientLog(), NEO_CLIENT + NEO_CLIENT);
		assertRed(assertM16Neo(), "NeoForge: CLIENT Loading fired exactly once");
		Files.writeString(clientLog(), NEO_CLIENT);
		Files.writeString(log(), NEO_SERVER_CONFIG + NEO_SERVER_CONFIG + NEO_PLUGIN_SERVER);
		assertRed(assertM16Neo(), "NeoForge: the per-world SERVER config loads once on the server");
		Files.writeString(log(), NEO_SERVER_CONFIG.replace("from-the-gate", "default") + NEO_PLUGIN_SERVER);
		assertRed(assertM16Neo(), "NeoForge: and it holds the pre-written value");
		Files.writeString(log(), NEO_SERVER_CONFIG + NEO_PLUGIN_SERVER.replace("DEDICATED_SERVER", "FAILED java.lang.IllegalStateException"));
		assertRed(assertM16Neo(), "NeoForge: the plugin saw the dedicated server");
		Files.writeString(log(), NEO_SERVER_CONFIG + NEO_PLUGIN_SERVER);
		Files.delete(client().resolve("config/forbricneolive-client.toml"));
		assertRed(assertM16Neo(), "NeoForge CLIENT file was not created on the client");
	}

	@Test
	void bothProductionScriptsParseAndKeepTheRealLaunchAndNegativeControl() throws Exception {
		for (Path gate : new Path[] { M16, M28 }) assertGreen(execute(new ProcessBuilder("bash", "-n", gate.toString())));
		String gate = Files.readString(M28);
		assertTrue(gate.contains("FORBRIC_JVM=\"${M28_EXTRA_JVM:-}\" RUNDIR=\"$RUNDIR\""));
		assertTrue(gate.contains("launch-kernel-server.sh\" < \"$FIFO\" > \"$LOG\""));
		assertTrue(gate.contains("-Dforbric.earlyConfigs=off"));
		assertTrue(gate.indexOf("# M28_NEO_EDIT_BEGIN") < gate.indexOf("# M28_NEO_WATCH_BEGIN"));
		assertTrue(!gate.contains("forbriclive.jar"), "Stage 3: gate-m28 no longer stages the MinecraftForge canary");
	}

	private Path instance() { return temporary.resolve("server with spaces"); }
	private Path client() { return temporary.resolve("client with spaces"); }
	private Path config() { return instance().resolve("config/forbriclive-common.toml"); }
	private Path log() { return temporary.resolve("server.log"); }
	private Path watchLog() { return temporary.resolve("reload.log"); }
	private Path clientLog() { return temporary.resolve("client.log"); }
	private Path combinedLog() { return temporary.resolve("combined-client.log"); }
	private Path neoConfig() { return instance().resolve("config/forbricneolive-common.toml"); }
	private Path neoWatchLog() { return temporary.resolve("neo-reload.log"); }

	private void neoFixture() throws Exception {
		fixture();
		Files.writeString(neoConfig(), "probe = 73\n");
		Files.writeString(client().resolve("config/forbricneolive-client.toml"), "probe = 17\n");
		Files.writeString(log(), NEO_SERVER_LOG + NEO_SERVER_CONFIG + NEO_PLUGIN_SERVER);
		Files.writeString(neoWatchLog(), NEO_RELOAD);
		Files.writeString(clientLog(), NEO_CLIENT);
		Files.writeString(combinedLog(), NEO_CLIENT + NEO_SYNCED + NEO_PLUGIN_CLIENT + NEO_CLIENT);
	}

	private Result assertM28Neo() throws Exception { return run(section(M28, "M28_NEO_ASSERTIONS"), "exit \"$FAIL\""); }
	private Result assertM16Neo() throws Exception { return run(section(M16, "M16_NEO_CONFIG"), "exit \"$FAIL\""); }

	private void fixture() throws Exception {
		Files.createDirectories(instance().resolve("config"));
		Files.createDirectories(client().resolve("config"));
		Files.writeString(config(), "probe = 73\n");
		Files.writeString(instance().resolve("config/forge-common.toml"), "# native Forge COMMON\n");
		Files.writeString(client().resolve("config/forbriclive-client.toml"), "probe = 17\n");
		Files.writeString(log(), SERVER_LOG);
		Files.writeString(watchLog(), RELOAD);
		Files.writeString(clientLog(), CLIENT_SUMMARY + CLIENT);
		Files.writeString(combinedLog(), CLIENT_SUMMARY + CLIENT + CLIENT_SUMMARY);
	}


	private Result run(String body, String finish) throws Exception {
		ProcessBuilder builder = new ProcessBuilder("bash", "-c", ". run/lib.sh\n" + body + "\n" + finish);
		builder.environment().putAll(Map.ofEntries(Map.entry("RUNDIR", instance().toString()), Map.entry("CONFIG", config().toString()),
				Map.entry("LOG", log().toString()), Map.entry("WATCH_LOG", watchLog().toString()), Map.entry("READY", "1"),
				Map.entry("EDITED", "1"), Map.entry("RELOADED", "1"), Map.entry("CLI", client().toString()),
				Map.entry("SRV", instance().toString()), Map.entry("SLOG", log().toString()), Map.entry("CGAME", clientLog().toString()),
				Map.entry("CLOG", combinedLog().toString()), Map.entry("NEW_EVENT", RELOAD),
				Map.entry("NEO_CONFIG", neoConfig().toString()), Map.entry("NEO_WATCH_LOG", neoWatchLog().toString()),
				Map.entry("NEO_EDITED", "1"), Map.entry("NEO_RELOADED", "1"), Map.entry("NEO_NEW_EVENT", NEO_RELOAD)));
		return execute(builder);
	}

	private static String section(Path file, String name) throws Exception {
		String script = Files.readString(file);
		int begin = script.indexOf("# " + name + "_BEGIN");
		int end = script.indexOf("# " + name + "_END", begin);
		assertTrue(begin >= 0 && end > begin, "missing executable gate contract " + name);
		return script.substring(begin, end);
	}

	private static Result execute(ProcessBuilder builder) throws Exception {
		Process process = builder.redirectErrorStream(true).start();
		assertTrue(process.waitFor(15, TimeUnit.SECONDS), "offline config gate contract timed out");
		return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
	}

	private static void assertGreen(Result result) { assertEquals(0, result.exit(), result.output()); }
	private static void assertRed(Result result, String diagnostic) {
		assertEquals(1, result.exit(), result.output());
		assertTrue(result.output().contains(diagnostic), result.output());
	}
	private record Result(int exit, String output) {}
}
