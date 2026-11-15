package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatesAllTest {
	@TempDir Path temporary;

	@Test void listsTheActualGateGlobInNumericalOrder() throws Exception {
		var result = CompatProbeProcess.run(temporary, "bash", "gates-all.sh", "--list");
		assertEquals(0, result.exitCode(), result.output());
		try (var paths = Files.list(Path.of("run"))) {
			Set<String> names = paths.map(p -> p.getFileName().toString())
					.filter(n -> n.startsWith("gate-m") && n.endsWith(".sh")).collect(Collectors.toSet());
			assertEquals(names, result.output().lines().collect(Collectors.toSet()));
		}
		var lines = result.output().lines().toList();
		assertTrue(lines.indexOf("gate-m2b.sh") < lines.indexOf("gate-m10-overlay.sh"));
	}

	@Test void runsEveryGateAndDistinguishesExpectedFailuresFromRegressions() throws Exception {
		Path gates = Files.createDirectory(temporary.resolve("gates"));
		Path output = temporary.resolve("results");
		write(gates, "gate-m1.sh", "[ \"$GATE_PORT\" = 25599 ]\n");
		write(gates, "gate-m2.sh", "# EXPECTED: RED until repair\necho '[kernel] EXPECTED-RED missing Forge registration'\nexit 2\n");
		write(gates, "gate-m3.sh", "# EXPECTED: RED until repair\necho 'Failed to start'\nexit 1\n");
		write(gates, "gate-m4.sh", "exit 0\n");
		write(gates, "gate-m10.sh", "exit 0\n");
		var env = Map.of("FORBRIC_GATE_DIR", gates.toString(), "FORBRIC_GATE_RESULTS", output.toString(), "GATE_PORT", "25599");
		var red = CompatProbeProcess.run(temporary, env, "bash", "gates-all.sh", "--skip", "gate-m4.sh");
		assertEquals(1, red.exitCode(), red.output());
		assertTrue(red.output().contains("gate-m2.sh EXPECTED_RED"));
		assertTrue(red.output().contains("gate-m3.sh RED"));
		assertTrue(red.output().contains("gate-m4.sh SKIP (explicit --skip)"));
		assertTrue(red.output().contains("gate-m10.sh GREEN"), "Failures must not prevent later gates running");
		assertEquals(red.output(), Files.readString(output.resolve("summary.txt")));
		assertFalse(Files.exists(output.resolve("gate-m4.sh.log")));
		var green = CompatProbeProcess.run(temporary, env, "bash", "gates-all.sh", "--skip", "gate-m3.sh");
		assertEquals(0, green.exitCode(), green.output());
		var unknown = CompatProbeProcess.run(temporary, env, "bash", "gates-all.sh", "--skip", "gate-m99.sh");
		assertEquals(2, unknown.exitCode(), unknown.output());
	}

	/**
	 * The lenient sweep above exits 0 with gate-m3 skipped and gate-m2 EXPECTED_RED. A release run is an acceptance:
	 * a gate that was not run, or is still red by declaration, fails it -- and the line says which and why.
	 */
	@Test void aReleaseRunFailsOnASkippedOrExpectedRedGate() throws Exception {
		Path gates = Files.createDirectory(temporary.resolve("release-gates"));
		Path output = temporary.resolve("release-results");
		write(gates, "gate-m1.sh", "exit 0\n");
		write(gates, "gate-m2.sh", "# EXPECTED: RED until repair\necho '[kernel] EXPECTED-RED missing Forge registration'\nexit 2\n");
		write(gates, "gate-m3.sh", "exit 0\n");
		var env = Map.of("FORBRIC_GATE_DIR", gates.toString(), "FORBRIC_GATE_RESULTS", output.toString());
		var lenient = CompatProbeProcess.run(temporary, env, "bash", "gates-all.sh", "--skip", "gate-m3.sh");
		assertEquals(0, lenient.exitCode(), lenient.output());

		var skipped = CompatProbeProcess.run(temporary, env, "bash", "gates-all.sh", "--release", "--skip", "gate-m3.sh");
		assertEquals(1, skipped.exitCode(), skipped.output());
		assertTrue(skipped.output().contains("RESULT gate-m3.sh SKIP (explicit --skip; not run, so the release run fails)"),
				skipped.output());
		assertTrue(skipped.output().contains("RESULT gate-m2.sh EXPECTED_RED (exit=2; still red, so the release run fails)"),
				skipped.output());
		assertEquals(skipped.output(), Files.readString(output.resolve("summary.txt")));

		write(gates, "gate-m2.sh", "exit 0\n");
		var skipOnly = CompatProbeProcess.run(temporary, env, "bash", "gates-all.sh", "--release", "--skip", "gate-m3.sh");
		assertEquals(1, skipOnly.exitCode(), "a skip alone fails a release run: " + skipOnly.output());
		var complete = CompatProbeProcess.run(temporary, env, "bash", "gates-all.sh", "--release");
		assertEquals(0, complete.exitCode(), complete.output());
		assertEquals(List.of("RESULT gate-m1.sh GREEN (exit=0)", "RESULT gate-m2.sh GREEN (exit=0)",
				"RESULT gate-m3.sh GREEN (exit=0)"), complete.output().lines().toList());
	}

	@Test void theDefaultRunExecutesEveryGateWithoutAnySkipArguments() throws Exception {
		Path gates = Files.createDirectory(temporary.resolve("default-gates"));
		write(gates, "gate-m1.sh", "exit 0\n");
		write(gates, "gate-m10.sh", "exit 0\n");
		var result = CompatProbeProcess.run(temporary, Map.of("FORBRIC_GATE_DIR", gates.toString(),
				"FORBRIC_GATE_RESULTS", temporary.resolve("default-results").toString()), "bash", "gates-all.sh");
		assertEquals(0, result.exitCode(), result.output());
		assertEquals(List.of("RESULT gate-m1.sh GREEN (exit=0)", "RESULT gate-m10.sh GREEN (exit=0)"),
				result.output().lines().toList());
	}

	/**
	 * A second working tree links its fixtures to the main checkout's. A gate that asks for its own copy must get a
	 * real directory then, not a second link to the original that it would write its world and mods into.
	 */
	@Test void aGateGetsARealCopyOfAFixtureThatIsASymlink() throws Exception {
		Path fixture = Files.createDirectories(temporary.resolve("main-checkout/client-merged-pack"));
		Files.createDirectories(fixture.resolve("saves/World"));
		Path options = Files.writeString(fixture.resolve("options.txt"), "original\n");
		Path gates = Files.createDirectory(temporary.resolve("linked-gates"));
		Files.createSymbolicLink(gates.resolve("client-merged-pack"), fixture);
		write(gates, "gate-m9.sh", "# GATE-PARALLEL: clone=client-merged-pack:M9_RUNDIR mem=100\n"
				+ "[ -d \"$M9_RUNDIR\" ] && [ ! -L \"$M9_RUNDIR\" ] || exit 1\n"
				+ "echo changed > \"$M9_RUNDIR/options.txt\"\nmkdir \"$M9_RUNDIR/saves/Other\"\n");
		var env = Map.of("FORBRIC_GATE_DIR", gates.toString(),
				"FORBRIC_GATE_RESULTS", temporary.resolve("linked-results").toString());
		var result = CompatProbeProcess.run(temporary, env, "bash", "gates-all.sh");
		assertEquals(0, result.exitCode(), result.output());
		assertEquals("original\n", Files.readString(options));
		assertFalse(Files.exists(fixture.resolve("saves/Other")), "the gate wrote into the main checkout's fixture");
		Path copy = gates.resolve(".gate-clones/gate-m9/client-merged-pack");
		assertFalse(Files.isSymbolicLink(copy));
		assertEquals("changed\n", Files.readString(copy.resolve("options.txt")));
	}

	/**
	 * A gate whose verdict is a timing says {@code alone} on its GATE-PARALLEL line and runs with no other gate.
	 * gate-m12's tick-lateness ceiling went red at -j 4 beside gate-m0's unit suite and four server boots (VERIFY run 1
	 * of C5: 11 of 220 late, ceiling 7) and was green alone: it had counted its neighbours' CPU. Every stub gate here
	 * stamps when it ran; the one declared alone must share no moment with any other, while the others still overlap
	 * each other, so the sweep really did run several wide.
	 */
	@Test void aGateDeclaredAloneRunsWithNoOtherGateBesideIt() throws Exception {
		Path gates = Files.createDirectory(temporary.resolve("alone-gates"));
		Path stamps = Files.createDirectory(temporary.resolve("alone-stamps"));
		String now = "$(python3 -c 'import time; print(time.time())')";
		String body = "n=$(basename \"$0\" .sh)\necho \"" + now + "\" > \"" + stamps + "/$n.start\"\nsleep SECONDS\n"
				+ "echo \"" + now + "\" > \"" + stamps + "/$n.end\"\n";
		String[][] declared = {{"gate-m0.sh", "rundirs=a mem=10", "2"}, {"gate-m1.sh", "rundirs=b mem=10", "2"},
				{"gate-m12.sh", "rundirs=c mem=10 alone", "1"}, {"gate-m13.sh", "rundirs=d mem=10", "2"},
				{"gate-m14.sh", "rundirs=e mem=10", "1"}};
		for (String[] gate : declared) {
			write(gates, gate[0], "# GATE-PARALLEL: " + gate[1] + "\n" + body.replace("SECONDS", gate[2]));
		}
		Path results = temporary.resolve("alone-results");
		var env = Map.of("FORBRIC_GATE_DIR", gates.toString(), "FORBRIC_GATE_RESULTS", results.toString());
		var result = CompatProbeProcess.run(temporary, env, "bash", "gates-all.sh", "-j", "4", "--mem-budget", "1000");
		assertEquals(0, result.exitCode(), result.output());

		Map<String, double[]> ran = new java.util.TreeMap<>();
		for (String[] gate : declared) {
			String name = gate[0].replace(".sh", "");
			ran.put(name, new double[] {Double.parseDouble(Files.readString(stamps.resolve(name + ".start")).trim()),
					Double.parseDouble(Files.readString(stamps.resolve(name + ".end")).trim())});
		}
		double[] alone = ran.get("gate-m12");
		int overlappingPairs = 0;
		for (var a : ran.entrySet()) {
			if (a.getKey().equals("gate-m12")) continue;
			assertFalse(a.getValue()[0] < alone[1] && alone[0] < a.getValue()[1],
					a.getKey() + " ran beside gate-m12, which is declared alone: " + describe(ran));
			for (var b : ran.entrySet()) {
				if (b.getKey().compareTo(a.getKey()) > 0 && !b.getKey().equals("gate-m12")
						&& a.getValue()[0] < b.getValue()[1] && b.getValue()[0] < a.getValue()[1]) overlappingPairs++;
			}
		}
		assertTrue(overlappingPairs >= 3, "the other gates did not overlap, so this proves nothing: " + describe(ran));
		assertTrue(Files.readString(results.resolve("progress.log")).contains("declared 'alone' (a timing verdict), so each"
				+ " of these runs with no other gate: gate-m12.sh"), "the sweep says which gates it ran alone and why");
	}

	/**
	 * Which gates must say {@code alone} is read from what they judge, not kept as a list. Two verdicts are timings that a
	 * neighbouring gate's CPU moves: the server tick sampler (its {@code [Forbric/Tick]} lines, turned up by
	 * {@code -Dforbric.tickSamplerEvery}), whose late-tick count gate-m12 holds to a ceiling, and lib.sh's
	 * {@code check_kept_up}, vanilla's "Can't keep up!" while a player is on (red on gate-m12 beside the unit suite in
	 * Merger wave 1, on the former gate-m15 in two of three side-by-side -j 5 rounds and on gate-m16 beside the full suites, as
	 * introduction.md's gates section measured; HARN review F1 found the rule had missed it). Comment lines are prose,
	 * not a use.
	 */
	@Test void everyGateWhoseVerdictIsATimingIsDeclaredAlone() throws Exception {
		var declaration = java.util.regex.Pattern.compile("(?m)^#[ \\t]*GATE-PARALLEL:[ \\t]*(.*)$");
		var keptUp = java.util.regex.Pattern.compile("(?m)^\\s*check_kept_up\\s");
		List<String> sampler = new java.util.ArrayList<>();
		List<String> overload = new java.util.ArrayList<>();
		List<String> sharing = new java.util.ArrayList<>();
		try (var paths = Files.list(Path.of("run"))) {
			for (Path gate : paths.filter(p -> p.getFileName().toString().matches("gate-m.*\\.sh")).sorted().toList()) {
				String text = Files.readString(gate);
				String code = text.replaceAll("(?m)^\\s*#.*$", "");
				String name = gate.getFileName().toString();
				boolean readsSampler = code.contains("Forbric/Tick") || code.contains("tickSamplerEvery");
				boolean judgesOverload = keptUp.matcher(code).find();
				if (readsSampler) sampler.add(name);
				if (judgesOverload) overload.add(name);
				if (!readsSampler && !judgesOverload) continue;
				var line = declaration.matcher(text);
				// An undeclared gate already runs alone; a declared one has to ask for it.
				// Prose after ` # ` on the line is not a word of the declaration (gates-parallel.py's COMMENT).
				if (line.find() && !List.of(line.group(1).split("(?:^|\\s)#", 2)[0].trim().split("\\s+")).contains("alone"))
					sharing.add(name);
			}
		}
		assertTrue(sampler.contains("gate-m12-multiplayer.sh"), "the rule no longer finds gate-m12's tick check: " + sampler);
		assertTrue(overload.contains("gate-m12-multiplayer.sh"), "the rule no longer finds a check_kept_up call: " + overload);
		assertEquals(List.of(), sharing, "these gates judge a timing but may run beside other gates: add `alone` to their"
				+ " # GATE-PARALLEL: line");
	}

	/**
	 * Every word of a GATE-PARALLEL line is one the scheduler knows, or the sweep stops before any gate runs (C5 wave 2d).
	 * On 421c53cc an unknown word was skipped in silence: a misspelt {@code alon} ran a timing gate beside others, and a
	 * {@code rundir=} (no s) shared a rundir, with nothing said. Prose goes after {@code  # }, which gate-m0 needs for its
	 * note, and a word there is not read: on 421c53cc the {@code alone} in this test's prose made the gate run alone.
	 */
	@Test void aDeclarationWordTheSchedulerDoesNotKnowStopsTheSweep() throws Exception {
		String[][] wrong = {{"rundirs=a mem=10 alon", "unknown word 'alon'"}, {"rundir=a mem=10", "unknown word 'rundir=a'"},
				{"rundirs=a mem=lots", "mem= wants a whole number"}, {"rundirs=a mem=10 alone=yes", "alone takes no value"},
				{"rundirs=a rundirs=b mem=10", "rundirs given twice"}, {"rundirs=a mem=10 (prose without a hash)", "unknown word '(prose'"},
				{"rundirs=a mem=10\n# GATE-PARALLEL: rundirs=b mem=10", "2 '# GATE-PARALLEL:' lines"},
				// HARN2 review A-4: a line naming nothing was a declaration of nothing, run beside any other gate.
				{"", "names nothing"}, {"# only prose here", "names nothing"}};
		for (int i = 0; i < wrong.length; i++) {
			Path gates = Files.createDirectory(temporary.resolve("wrong-gates-" + i));
			Path ran = temporary.resolve("wrong-ran-" + i);
			write(gates, "gate-m1.sh", "touch \"" + ran + "\"\n");
			write(gates, "gate-m2.sh", "# GATE-PARALLEL: " + wrong[i][0] + "\ntouch \"" + ran + "\"\n");
			var env = Map.of("FORBRIC_GATE_DIR", gates.toString(), "FORBRIC_GATE_RESULTS",
					temporary.resolve("wrong-results-" + i).toString());
			var result = CompatProbeProcess.run(temporary, env, "bash", "gates-all.sh", "-j", "2");
			assertNotEquals(0, result.exitCode(), wrong[i][0] + ": " + result.output());
			assertTrue(result.output().contains("gate-m2.sh: ") && result.output().contains(wrong[i][1]),
					wrong[i][0] + ": " + result.output());
			assertFalse(Files.exists(ran), wrong[i][0] + ": a gate ran although the sweep could not read a declaration");
		}

		Path gates = Files.createDirectory(temporary.resolve("prose-gates"));
		write(gates, "gate-m1.sh", "# GATE-PARALLEL: rundirs=a mem=10\nexit 0\n");
		write(gates, "gate-m2.sh", "# GATE-PARALLEL: rundirs=b mem=10  # not alone here, this is prose\nexit 0\n");
		Path results = temporary.resolve("prose-results");
		var env = Map.of("FORBRIC_GATE_DIR", gates.toString(), "FORBRIC_GATE_RESULTS", results.toString());
		var result = CompatProbeProcess.run(temporary, env, "bash", "gates-all.sh", "-j", "2");
		assertEquals(0, result.exitCode(), result.output());
		assertEquals(List.of("RESULT gate-m1.sh GREEN (exit=0)", "RESULT gate-m2.sh GREEN (exit=0)"), result.output().lines().toList());
		assertFalse(Files.readString(results.resolve("progress.log")).contains("runs with no other gate"),
				"a word in the comment was read as `alone`: " + Files.readString(results.resolve("progress.log")));
	}

	/**
	 * A TERM to the sweep reaches the gates it is running, and through lib.sh's EXIT trap what they started (C5 wave 2d).
	 * gates-all.sh execs the scheduler, so heavy.sh's forwarded TERM lands on Python; on 421c53cc it died of it on the spot
	 * and the running gate went on alone, its stand-in JVM still there after the sweep had exited.
	 */
	@Test void aTermToTheSweepStopsItsRunningGatesAndWhatTheyStarted() throws Exception {
		Path gates = Files.createDirectory(temporary.resolve("term-gates"));
		Path pidFile = temporary.resolve("term-jvm.pid");
		Path lib = Path.of("run/lib.sh").toAbsolutePath();
		write(gates, "gate-m1.sh", "# GATE-PARALLEL: rundirs=a mem=10\n. \"" + lib + "\"\n"
				+ "sh -c 'echo $$ > \"$1\"; while :; do sleep 0.1; done' sh \"" + pidFile + "\" &\nwait\n");
		Path results = temporary.resolve("term-results");
		// An earlier sweep's verdict in the same directory: an interrupted sweep must not leave it beside its own
		// progress.log as if it were this one's (HARN2 review B-7; on 63e561cc it stayed).
		Files.createDirectories(results);
		Files.writeString(results.resolve("summary.txt"), "RESULT gate-m1.sh GREEN (exit=0)\n");
		ProcessBuilder builder = new ProcessBuilder(List.of("bash", Path.of("run/compat/gates-all.sh").toAbsolutePath().toString(),
				"-j", "2")).redirectErrorStream(true).redirectOutput(temporary.resolve("term-sweep.log").toFile());
		builder.environment().put("FORBRIC_GATE_DIR", gates.toString());
		builder.environment().put("FORBRIC_GATE_RESULTS", results.toString());
		builder.environment().remove("FORBRIC_CAUGHT_FILE");
		builder.environment().remove("FORBRIC_CAUGHT_GATE");
		Process sweep = builder.start();
		long jvm = -1;
		try {
			for (int i = 0; i < 200 && (!Files.exists(pidFile) || Files.size(pidFile) == 0); i++) Thread.sleep(100);
			jvm = Long.parseLong(Files.readString(pidFile).trim());
			Thread.sleep(300);
			sweep.destroy();   // SIGTERM, as heavy.sh forwards it
			assertTrue(sweep.waitFor(60, TimeUnit.SECONDS), "the sweep did not exit on TERM");
			boolean gone = false;
			for (int i = 0; i < 50 && !(gone = ProcessHandle.of(jvm).map(h -> !h.isAlive()).orElse(true)); i++) Thread.sleep(100);
			String progress = Files.readString(results.resolve("progress.log"));
			assertTrue(gone, "the gate's process outlived the sweep's TERM: " + progress);
			assertEquals(143, sweep.exitValue(), progress);
			assertTrue(progress.contains("SIGTERM: stopping gate-m1.sh"), progress);
			assertFalse(Files.exists(results.resolve("summary.txt")), "an earlier sweep's summary.txt outlived an interrupted one");
			assertTrue(Files.readString(results.resolve("gate-m1.sh.log")).contains(" process(es) running at its exit; stopping them: "),
					Files.readString(results.resolve("gate-m1.sh.log")));
		} finally {
			sweep.descendants().forEach(ProcessHandle::destroyForcibly);
			sweep.destroyForcibly();
			if (jvm > 0) ProcessHandle.of(jvm).ifPresent(ProcessHandle::destroyForcibly);
		}
	}

	private static String describe(Map<String, double[]> ran) {
		double t0 = ran.values().stream().mapToDouble(r -> r[0]).min().orElse(0);
		return ran.entrySet().stream().map(e -> String.format("%s %.2f-%.2f", e.getKey(), e.getValue()[0] - t0,
				e.getValue()[1] - t0)).collect(Collectors.joining(", "));
	}

	private static void write(Path gates, String name, String body) throws Exception {
		Files.writeString(gates.resolve(name), "#!/usr/bin/env bash\n" + body);
	}
}
