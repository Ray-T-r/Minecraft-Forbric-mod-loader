package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@code run/lib.sh}'s caught-failure file through a gate started by a gate.
 *
 * <p>Six gates start their servers by running themselves again under evidence.py, and the inner run is a gate-* script
 * that sources lib.sh too. It used to take a file of its own, print its CAUGHT line into the server log, and leave the
 * outer gate reading an empty file: gate-m39's server hit an NPE on every run while the gate printed "CAUGHT none".
 * What has to hold: the inner run writes into the outer gate's file and prints nothing, the outer gate reports what the
 * inner run's JVMs caught, and a variable left over from somewhere else is not mistaken for an outer gate.
 */
class LibCaughtContractTest {
	private static final Path LIB = Path.of("run/lib.sh").toAbsolutePath();
	private static final Path CAUGHT = Path.of("build/caught").toAbsolutePath();
	/** One failure line as Caught.java appends it. */
	private static final String FAILURE = "printf 'FAILURE\\tWARN\\tnet.forbric.Probe.site:7\\t[Forbric/Probe] inner JVM failed\\tjava.lang.NullPointerException\\n'"
			+ " >> \"$FORBRIC_CAUGHT_FILE\"";

	@TempDir Path temporary;
	private final String tag = "gate-caughtcontract" + UUID.randomUUID().toString().substring(0, 8);

	@AfterEach void removeTheFilesTheseGatesOwned() throws IOException {
		if (!Files.isDirectory(CAUGHT)) return;
		try (Stream<Path> files = Files.list(CAUGHT)) {
			for (Path file : files.filter(f -> f.getFileName().toString().startsWith(tag)).toList()) Files.delete(file);
		}
	}

	@Test void aGateStartedByAGateReportsThroughTheOuterGate() throws Exception {
		Path inner = gate("inner", "echo \"inner file: $FORBRIC_CAUGHT_FILE\"\n" + FAILURE + "\n");
		Path outer = gate("outer", "echo \"outer file: $FORBRIC_CAUGHT_FILE\"\nbash \"" + inner + "\"\n");
		Result r = run(outer, Map.of());
		assertEquals(0, r.exit(), r.out());
		assertEquals(group(r.out(), "outer file: (\\S+)"), group(r.out(), "inner file: (\\S+)"), "the inner run writes the outer gate's file: " + r.out());
		assertEquals(1, count(r.out(), "[kernel] CAUGHT"), "only the gate that owns the file reports it: " + r.out());
		assertTrue(r.out().contains("[kernel] CAUGHT 1 failure site(s)"), r.out());
		assertTrue(r.out().contains("net.forbric.Probe.site:7: [Forbric/Probe] inner JVM failed"), r.out());
		assertFalse(r.out().contains("CAUGHT none"), r.out());
		assertEquals(1, owned(), "one file for the whole run");
	}

	@Test void anInnerRunThatCaughtNothingLeavesTheOuterReportAtNone() throws Exception {
		Path inner = gate("inner", "true\n");
		Path outer = gate("outer", "bash \"" + inner + "\"\n");
		Result r = run(outer, Map.of());
		assertEquals(0, r.exit(), r.out());
		assertEquals(1, count(r.out(), "[kernel] CAUGHT none"), r.out());
	}

	@Test void aLeftoverVariableFromADeadGateIsNotAnOuterGate() throws Exception {
		// An owner pid that is not alive (and a file that is somebody else's) is not inherited ownership: the gate takes
		// its own file, writes nothing into the stale one, and reports its own failures.
		Path stale = temporary.resolve("stale.tsv");
		Files.writeString(stale, "", StandardCharsets.UTF_8);
		Path lone = gate("lone", "echo \"lone file: $FORBRIC_CAUGHT_FILE\"\n" + FAILURE + "\n");
		Result r = run(lone, Map.of("FORBRIC_CAUGHT_FILE", stale.toString(), "FORBRIC_CAUGHT_GATE", "gate-gone:" + deadPid()));
		assertEquals(0, r.exit(), r.out());
		assertFalse(group(r.out(), "lone file: (\\S+)").equals(stale.toString()), r.out());
		assertEquals("", Files.readString(stale, StandardCharsets.UTF_8));
		assertTrue(r.out().contains("[kernel] CAUGHT 1 failure site(s)"), r.out());
	}

	@Test void anExportedFileWithoutAnOwningGateIsNotAnOuterGateEither() throws Exception {
		Path stray = temporary.resolve("stray.tsv");
		Files.writeString(stray, "", StandardCharsets.UTF_8);
		Path lone = gate("lone", FAILURE + "\n");
		Result r = run(lone, Map.of("FORBRIC_CAUGHT_FILE", stray.toString()));
		assertEquals(0, r.exit(), r.out());
		assertEquals("", Files.readString(stray, StandardCharsets.UTF_8));
		assertTrue(r.out().contains("[kernel] CAUGHT 1 failure site(s)"), r.out());
	}

	@Test void strictStillTurnsTheOuterGateRedForAnInnerRunsFailure() throws Exception {
		Path inner = gate("inner", FAILURE + "\n");
		Path outer = gate("outer", "bash \"" + inner + "\"\n");
		Result r = run(outer, Map.of("FORBRIC_CAUGHT_STRICT", "1"));
		assertEquals(1, r.exit(), r.out());
		assertTrue(r.out().contains("FAIL the kernel carried on from failures, and FORBRIC_CAUGHT_STRICT=1"), r.out());
		assertEquals(1, count(r.out(), "FORBRIC_CAUGHT_STRICT=1"), "the inner run does not judge a file it does not own: " + r.out());
	}

	/** A gate-* script (lib.sh owns a caught file only for those) that sources the real lib.sh, then runs {@code body}. */
	private Path gate(String role, String body) throws IOException {
		Path script = temporary.resolve(tag + "-" + role + ".sh");
		Files.writeString(script, ". \"" + LIB + "\"\n" + body, StandardCharsets.UTF_8);
		return script;
	}

	private long owned() throws IOException {
		try (Stream<Path> files = Files.list(CAUGHT)) {
			return files.filter(f -> f.getFileName().toString().startsWith(tag)).count();
		}
	}

	private static long deadPid() throws Exception {
		Process gone = new ProcessBuilder("true").start();
		gone.waitFor();
		return gone.pid();
	}

	private static String group(String text, String regex) {
		Matcher m = Pattern.compile(regex).matcher(text);
		assertTrue(m.find(), "no match for " + regex + " in: " + text);
		return m.group(1);
	}

	private static int count(String text, String needle) {
		int n = 0;
		for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) n++;
		return n;
	}

	private Result run(Path script, Map<String, String> extra) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(List.of("bash", script.toString()));
		pb.directory(Path.of("").toAbsolutePath().toFile());
		// Whatever this JVM inherited (a gate running the suite exports both) must not decide who owns what here.
		pb.environment().remove("FORBRIC_CAUGHT_FILE");
		pb.environment().remove("FORBRIC_CAUGHT_GATE");
		pb.environment().remove("FORBRIC_CAUGHT_STRICT");
		pb.environment().putAll(extra);
		Path output = temporary.resolve(script.getFileName() + ".out");
		pb.redirectErrorStream(true);
		pb.redirectOutput(output.toFile());
		Process p = pb.start();
		if (!p.waitFor(60, TimeUnit.SECONDS)) {
			p.descendants().forEach(ProcessHandle::destroyForcibly);
			p.destroyForcibly();
			throw new IllegalStateException("lib.sh did not finish: " + Files.readString(output, StandardCharsets.UTF_8));
		}
		return new Result(p.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
	}

	private record Result(int exit, String out) {
	}
}
