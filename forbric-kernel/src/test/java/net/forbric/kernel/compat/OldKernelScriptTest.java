package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@code run/old-kernel.sh}'s {@code archived_kernel}, the build of the tag the world-upgrade and build-swap gates
 * write their old worlds with, against a fake archived tree whose {@code gradlew} records how it was called.
 *
 * <p>Why: the first version marked a built tree with an empty {@code .built} file and trusted it. On 2026-11-08 the Gradle
 * cache was wiped; the marker still said "built", the script skipped the build, and the old launcher failed its own
 * {@code gradlew --offline jar} on the next boot, far from the cause. A built tree now counts as built only while its jar
 * and every entry of the boot classpath recorded in {@code .built} exist; a rebuild is offline unless
 * {@code FORBRIC_OLD_KERNEL_ONLINE=1}, and an offline rebuild that misses a dependency stops with a SKIP-FATAL naming that
 * knob.
 */
class OldKernelScriptTest {
	private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
	private static final Path LIB = Path.of("run/lib.sh");
	private static final Path SCRIPT = Path.of("run/old-kernel.sh");

	@TempDir Path temporary;
	private Path root;
	private Path calls;
	private Path dependency;

	@BeforeEach void fakeArchivedTree() throws Exception {
		root = temporary.resolve("cache").resolve(COMMIT);
		Path tree = root.resolve("forbric-kernel");
		Files.createDirectories(tree.resolve(".dev/api"));
		Files.writeString(root.resolve(".archived"), "");
		calls = temporary.resolve("calls.log");
		dependency = Files.writeString(temporary.resolve("dep.jar"), "jar");
		// The fake build: records its arguments, writes the kernel jar, prints the boot classpath on stdout (as the real
		// `gradlew -q jar printBootClasspath` does). FAKE_OFFLINE_MISS makes it fail the way an offline resolve does.
		Path gradlew = tree.resolve("gradlew");
		Files.writeString(gradlew, String.join("\n",
				"#!/usr/bin/env bash",
				"echo \"$*\" >> \"" + calls + "\"",
				"if [ -n \"${FAKE_OFFLINE_MISS:-}\" ] && [ \"$1\" = --offline ]; then",
				"  echo '      > No cached version of org.joml:joml:1.10.8 available for offline mode.' >&2; exit 1",
				"fi",
				"mkdir -p \"$(dirname \"$0\")/build/libs\" && echo jar > \"$(dirname \"$0\")/build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar\"",
				"echo '" + tree.resolve("build/classes/java/main") + ":" + dependency + "'",
				""), StandardCharsets.UTF_8);
		assertTrue(gradlew.toFile().setExecutable(true));
		Files.createDirectories(tree.resolve("build/classes/java/main"));
	}

	@Test void aMarkerNamingAVanishedDependencyIsRebuilt() throws Exception {
		build();
		Files.deleteIfExists(calls);
		Files.writeString(root.resolve(".built"), temporary.resolve("wiped-gradle-cache/joml-1.10.8.jar") + ":" + dependency + "\n");
		Result r = run("");
		assertEquals(0, r.exit(), r.out());
		assertTrue(r.out().contains("which is gone; rebuilding"), r.out());
		assertEquals(List.of("--offline --no-daemon -q -p " + tree() + " jar printBootClasspath"), calls());
		assertTrue(Files.readString(root.resolve(".built")).contains(dependency.toString()));
		assertTrue(r.out().strip().endsWith(tree().toString()), "prints the tree last: " + r.out());
	}

	@Test void theEmptyMarkerOfTheFirstVersionIsRebuilt() throws Exception {
		build();
		Files.deleteIfExists(calls);
		Files.writeString(root.resolve(".built"), "");
		assertEquals(0, run("").exit());
		assertEquals(1, calls().size(), "rebuilt once");
	}

	@Test void aTreeWhoseClasspathStillExistsIsNotRebuilt() throws Exception {
		build();
		Files.deleteIfExists(calls);
		Result r = run("");
		assertEquals(0, r.exit(), r.out());
		assertFalse(Files.exists(calls), "no build for a tree whose recorded classpath is all there");
	}

	@Test void anOfflineMissStopsAndNamesTheOnlineKnob() throws Exception {
		Result r = run("FAKE_OFFLINE_MISS=1");
		assertEquals(3, r.exit(), r.out());
		assertTrue(r.out().contains("[kernel] SKIP-FATAL:") && r.out().contains("FORBRIC_OLD_KERNEL_ONLINE=1"), r.out());
		assertFalse(Files.exists(root.resolve(".built")), "a failed build leaves no marker");
		Result online = run("FAKE_OFFLINE_MISS=1 FORBRIC_OLD_KERNEL_ONLINE=1");
		assertEquals(0, online.exit(), online.out());
		assertEquals("--no-daemon -q -p " + tree() + " jar printBootClasspath", calls().get(calls().size() - 1),
				"online: the same build without --offline");
	}

	private void build() throws Exception {
		Result r = run("");
		assertEquals(0, r.exit(), r.out());
		assertTrue(Files.isRegularFile(root.resolve(".built")));
	}

	private Path tree() {
		return root.resolve("forbric-kernel");
	}

	private List<String> calls() throws Exception {
		return Files.exists(calls) ? Files.readAllLines(calls) : List.of();
	}

	private Result run(String environment) throws Exception {
		Path old = Files.createDirectories(temporary.resolve("old-stage"));
		String script = "export FORBRIC_OLD=\"" + old + "\" FORBRIC_OLD_KERNEL_CACHE=\"" + temporary.resolve("cache") + "\"\n"
				+ ". \"" + LIB.toAbsolutePath() + "\"\n"
				+ ". \"" + SCRIPT.toAbsolutePath() + "\"\n"
				+ (environment.isEmpty() ? "" : "export " + environment + "\n")
				+ "archived_kernel " + COMMIT + "\n";
		Path runner = temporary.resolve("runner.sh");
		Files.writeString(runner, script, StandardCharsets.UTF_8);
		ProcessBuilder pb = new ProcessBuilder(List.of("bash", runner.toString()));
		pb.directory(Path.of("").toAbsolutePath().toFile());
		pb.environment().remove("FORBRIC_OLD_KERNEL_ONLINE");
		Path output = temporary.resolve("runner.out");
		pb.redirectErrorStream(true);
		pb.redirectOutput(output.toFile());
		Process p = pb.start();
		if (!p.waitFor(60, TimeUnit.SECONDS)) {
			p.descendants().forEach(ProcessHandle::destroyForcibly);
			p.destroyForcibly();
			throw new IllegalStateException("old-kernel.sh did not finish: " + Files.readString(output, StandardCharsets.UTF_8));
		}
		return new Result(p.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
	}

	private record Result(int exit, String out) {
	}
}
