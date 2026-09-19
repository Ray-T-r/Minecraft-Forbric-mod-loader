package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Execute the gate's actual frame selector: existing screenshots must never make a broken run green. */
class GateFrameContractTest {
	private static final Path GATE = Path.of("run/gate-m27-frame.sh");
	private static final long START = 1_600_000_000_000_000_000L;
	@TempDir Path temporary;

	@Test
	void oldEqualTimestampAndEmptyScreenshotsCannotSatisfyThisLaunch() throws Exception {
		image("old.png", START - 1_000_000_000L, "old");
		image("at-launch.png", START, "equal");
		image("empty.png", START + 1_000_000_000L, "");
		Result result = select();
		assertEquals(1, result.exit(), result.output());
		assertTrue(result.output().contains("No PNG newer than this launch"), result.output());
	}

	@Test
	void choosesTheNewestNonemptyPngCreatedAfterLaunch() throws Exception {
		image("old.png", START - 1_000_000_000L, "old");
		image("first.png", START + 1_000_000_000L, "first");
		Path latest = image("latest.png", START + 2_000_000_000L, "latest");
		image("empty.png", START + 3_000_000_000L, "");
		image("not-an-image.txt", START + 4_000_000_000L, "text");
		Result result = select();
		assertEquals(0, result.exit(), result.output());
		assertEquals(latest.toString(), result.output().strip());
	}

	@Test
	void anExplicitEmptyTickSettingSurvivesTheShellDefault() throws Exception {
		String script = Files.readString(GATE);
		String setting = script.lines().filter(line -> line.startsWith("SHOT_TICKS=")).findFirst().orElseThrow();
		ProcessBuilder disabled = new ProcessBuilder("bash", "-c", setting + "\nprintf '%s' \"$SHOT_TICKS\"");
		disabled.environment().put("M27_SHOT_TICKS", "");
		assertEquals("", run(disabled).output());
		ProcessBuilder defaults = new ProcessBuilder("bash", "-c", setting + "\nprintf '%s' \"$SHOT_TICKS\"");
		defaults.environment().remove("M27_SHOT_TICKS");
		assertEquals("100", run(defaults).output());
		assertTrue(script.contains("assert_eq \"the full 97-jar pack is staged\" 97"));
		assertTrue(script.contains("clientSmokeScreenshots=$SHOT_TICKS"));
		assertTrue(script.contains("verdict=DREW"));
		assertTrue(script.contains("M27_FRAME"));
		assertEquals(0, run(new ProcessBuilder("bash", "-n", GATE.toString())).exit());
	}

	private Path image(String name, long modified, String contents) throws Exception {
		Path screenshots = temporary.resolve("screenshots");
		Files.createDirectories(screenshots);
		Path file = Files.writeString(screenshots.resolve(name), contents);
		Files.setLastModifiedTime(file, FileTime.from(modified, TimeUnit.NANOSECONDS));
		return file;
	}

	private Result select() throws Exception {
		Path marker = Files.writeString(temporary.resolve("started.ns"), Long.toString(START));
		String script = Files.readString(GATE);
		int begin = script.indexOf("# FRAME_SELECTION_BEGIN");
		int body = script.indexOf("<<'PY'\n", begin) + "<<'PY'\n".length();
		int end = script.indexOf("\nPY\n", body);
		assertTrue(begin >= 0 && body > begin && end > body, "missing executable frame-selection contract");
		return run(new ProcessBuilder("python3", "-c", script.substring(body, end),
				temporary.resolve("screenshots").toString(), marker.toString()));
	}

	private static Result run(ProcessBuilder builder) throws Exception {
		Process process = builder.redirectErrorStream(true).start();
		assertTrue(process.waitFor(15, TimeUnit.SECONDS), "frame selector timed out");
		return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
	}

	private record Result(int exit, String output) {}
}
