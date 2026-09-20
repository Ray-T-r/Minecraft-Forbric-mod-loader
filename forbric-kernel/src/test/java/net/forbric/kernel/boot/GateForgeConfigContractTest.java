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
	@TempDir Path temporary;

	@Test
	void m28AcceptsExactlyOneLoadAndTheChangedSpecAndFile() throws Exception {
		fixture();
		assertGreen(assertM28());
		Files.writeString(log(), SERVER_LOG + COMMON);
		assertRed(assertM28(), "COMMON Loading fired exactly once");
		Files.writeString(log(), SERVER_LOG.replace(COMMON_SUMMARY, COMMON_SUMMARY.replace("applied 2", "applied 0")));
		assertRed(assertM28(), "Forge COMMON configs were applied");
		Files.writeString(log(), SERVER_LOG.replace("failed 0", "failed 1"));
		assertRed(assertM28(), "no Forge config failed");
		Files.writeString(log(), "");
		assertRed(assertM28(), "no log to read");
	}

	@Test
	void m28RequiresBothCreatedFilesAndRejectsADedicatedClientConfig() throws Exception {
		fixture();
		Files.delete(instance().resolve("config/forge-common.toml"));
		assertRed(assertM28(), "config/forge-common.toml was not created");
		Files.writeString(instance().resolve("config/forge-common.toml"), "# native config\n");
		Files.writeString(config(), "probe = 11\n");
		assertRed(assertM28(), "expected 73");
		Files.writeString(config(), "probe = \"73\"\n");
		assertRed(assertM28(), "expected 73");
		Files.writeString(config(), "probe = 73\n");
		Files.writeString(instance().resolve("config/forbriclive-client.toml"), "probe=17\n");
		assertRed(assertM28(), "dedicated server opened a CLIENT config");
	}

	@Test
	void m28ChangesTheRealDefaultOnceAndRefusesAnAlreadyChangedOrAmbiguousFile() throws Exception {
		fixture();
		Files.writeString(config(), "# keep this comment\nprobe = 11 # watched\nother = 5\n");
		Result edited = run(section(M28, "M28_EDIT"), "assert_eq edited 1 \"$EDITED\"\nexit \"$FAIL\"");
		assertGreen(edited);
		assertEquals("# keep this comment\nprobe = 73 # watched\nother = 5\n", Files.readString(config()));
		assertRed(run(section(M28, "M28_EDIT"), "exit \"$FAIL\""), "expected exactly one default");
		Files.writeString(config(), "probe=11\nprobe=11\n");
		assertRed(run(section(M28, "M28_EDIT"), "exit \"$FAIL\""), "found 2");
		assertEquals("probe=11\nprobe=11\n", Files.readString(config()), "a rejected edit must leave the original intact");
	}

	@Test
	void m28RequiresANewReloadAndBoundsTheObservationAtFortySeconds() throws Exception {
		fixture();
		// The old matching event is in LOG before BEFORE_LINES. It cannot prove that this edit was observed.
		String prefix = "BEFORE_LINES=$(wc -l < \"$LOG\" | tr -d ' ')\nSRVPID=$$\nRELOADED=0\nsleeps=0\n"
				+ "sleep() { sleeps=$((sleeps + 1)); }\n";
		Result stale = run(prefix + section(M28, "M28_WATCH"),
				"assert_eq stale-event-rejected 0 \"$RELOADED\"\nassert_eq bounded-wait 40 \"$sleeps\"\nexit \"$FAIL\"");
		assertGreen(stale);
		assertEquals("", Files.readString(watchLog()));
		String append = "sleep() { sleeps=$((sleeps + 1)); if [ \"$sleeps\" -eq 1 ]; then printf '%s' \"$NEW_EVENT\" >> \"$LOG\"; fi; }\n";
		Result fresh = run(prefix + append + section(M28, "M28_WATCH"),
				"assert_eq fresh-event 1 \"$RELOADED\"\nassert_eq observed-after-edit 1 \"$sleeps\"\nexit \"$FAIL\"");
		assertGreen(fresh);
		assertEquals(RELOAD, Files.readString(watchLog()));
	}

	@Test
	void m28CannotPassWithJustAReloadEventOrJustTheWaitFlag() throws Exception {
		fixture();
		assertRed(run("RELOADED=0\n" + section(M28, "M28_ASSERTIONS"), "exit \"$FAIL\""), "within 40 seconds");
		Files.writeString(watchLog(), RELOAD.replace("probe=73", "probe=11"));
		assertRed(assertM28(), "new native Reloading event");
		Files.writeString(watchLog(), RELOAD.replace("loaded=true", "loaded=false"));
		assertRed(assertM28(), "new native Reloading event");
		Files.delete(watchLog());
		assertRed(assertM28(), "no log to read");
	}

	@Test
	void m16CountsOnlyTheAuthoritativeClientLogAndDetectsMissingOrDuplicateLoading() throws Exception {
		fixture();
		// The launch stdout holds the canary print once plus latest.log's appended copy, which repeats only logged lines.
		Files.writeString(combinedLog(), CLIENT_SUMMARY + CLIENT + CLIENT_SUMMARY);
		assertGreen(assertM16());
		Files.writeString(combinedLog(), CLIENT_SUMMARY + CLIENT + CLIENT_SUMMARY + CLIENT);
		assertRed(assertM16(), "CLIENT Loading fired exactly once");
		Files.writeString(combinedLog(), CLIENT_SUMMARY + CLIENT_SUMMARY);
		assertRed(assertM16(), "CLIENT default was read");
		Files.writeString(combinedLog(), CLIENT_SUMMARY + CLIENT + CLIENT_SUMMARY);
		Files.writeString(clientLog(), CLIENT);
		assertRed(assertM16(), "Forge CLIENT configs were applied");
		Files.delete(combinedLog());
		assertRed(assertM16(), "no log to read");
	}

	@Test
	void m16RequiresAClientFileAndNoDedicatedClientLoad() throws Exception {
		fixture();
		Files.delete(client().resolve("config/forbriclive-client.toml"));
		assertRed(assertM16(), "CLIENT file was not created on the client");
		Files.writeString(client().resolve("config/forbriclive-client.toml"), "probe=17\n");
		Files.writeString(log(), SERVER_LOG + CLIENT);
		assertRed(assertM16(), "no CLIENT Loading on the dedicated server");
		Files.writeString(log(), SERVER_LOG);
		Files.writeString(instance().resolve("config/forbriclive-client.toml"), "probe=17\n");
		assertRed(assertM16(), "dedicated server opened a CLIENT config");
	}

	@Test
	void bothProductionScriptsParseAndKeepTheRealLaunchAndNegativeControl() throws Exception {
		for (Path gate : new Path[] { M16, M28 }) assertGreen(execute(new ProcessBuilder("bash", "-n", gate.toString())));
		String gate = Files.readString(M28);
		assertTrue(gate.contains("FORBRIC_JVM=\"${M28_EXTRA_JVM:-}\" RUNDIR=\"$RUNDIR\""));
		assertTrue(gate.contains("launch-kernel-server.sh\" < \"$FIFO\" > \"$LOG\""));
		assertTrue(gate.contains("-Dforbric.earlyConfigs=off"));
		assertTrue(gate.indexOf("# M28_EDIT_BEGIN") < gate.indexOf("# M28_WATCH_BEGIN"));
	}

	private Path instance() { return temporary.resolve("server with spaces"); }
	private Path client() { return temporary.resolve("client with spaces"); }
	private Path config() { return instance().resolve("config/forbriclive-common.toml"); }
	private Path log() { return temporary.resolve("server.log"); }
	private Path watchLog() { return temporary.resolve("reload.log"); }
	private Path clientLog() { return temporary.resolve("client.log"); }
	private Path combinedLog() { return temporary.resolve("combined-client.log"); }

	private void fixture() throws Exception {
		Files.createDirectories(instance().resolve("config"));
		Files.createDirectories(client().resolve("config"));
		Files.writeString(config(), "probe = 73\n");
		Files.writeString(instance().resolve("config/forge-common.toml"), "# native Forge COMMON\n");
		Files.writeString(client().resolve("config/forbriclive-client.toml"), "probe = 17\n");
		Files.writeString(log(), SERVER_LOG);
		Files.writeString(watchLog(), RELOAD);
		Files.writeString(clientLog(), CLIENT_SUMMARY);
		Files.writeString(combinedLog(), CLIENT_SUMMARY + CLIENT + CLIENT_SUMMARY);
	}

	private Result assertM28() throws Exception { return run(section(M28, "M28_ASSERTIONS"), "exit \"$FAIL\""); }
	private Result assertM16() throws Exception { return run(section(M16, "M16_CLIENT_CONFIG"), "exit \"$FAIL\""); }

	private Result run(String body, String finish) throws Exception {
		ProcessBuilder builder = new ProcessBuilder("bash", "-c", ". run/lib.sh\n" + body + "\n" + finish);
		builder.environment().putAll(Map.ofEntries(Map.entry("RUNDIR", instance().toString()), Map.entry("CONFIG", config().toString()),
				Map.entry("LOG", log().toString()), Map.entry("WATCH_LOG", watchLog().toString()), Map.entry("READY", "1"),
				Map.entry("EDITED", "1"), Map.entry("RELOADED", "1"), Map.entry("CLI", client().toString()),
				Map.entry("SRV", instance().toString()), Map.entry("SLOG", log().toString()), Map.entry("CGAME", clientLog().toString()),
				Map.entry("CLOG", combinedLog().toString()), Map.entry("NEW_EVENT", RELOAD)));
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
