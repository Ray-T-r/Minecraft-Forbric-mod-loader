/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.CarrierReturnedCalls;

/**
 * {@code CarrierReturnedCalls} through the real Mixin and MixinExtras weave, on classes that share no name with any game,
 * loader or mod.
 *
 * <p>{@code org.fern.loom.Spindle}, as the guest's loader has it, answers each question with the compass's answer when it has
 * a spot; as merged, the carrier answers its own way there and never asks the compass. The guest {@code com.gale.vane} wraps
 * the compass call and changes its answer. Moved, the wrap runs on the method's answer exactly when it ran natively (with a
 * spot, not without), handed the same values, and every answer is the native one. Not moved: a wrap where the carrier's one
 * return is reached with a spot and without, and one whose handler asks the compass about another spot than it was handed.
 * Switched off, no wrap runs.
 */
class CarrierReturnedCallsWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/carrierreturn");
	private static final String CONFIG = "carrierreturn.mixins.json";
	private static final String MOD = "vane";
	private static final String TARGET = "org/fern/loom/Spindle";
	/** The Operation the moved wrap is handed, answering with the carrier's answer: the kernel's game-side helper. */
	private static final Path CONSTANT = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelWrapOperations.java");

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, off, natively;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = new ArrayList<>(walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList());
		}
		sources.add(CONSTANT);
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("org/fern/loom/Spindle.java"));
		nativeSources.add(SOURCES.resolve("native/org/fern/loom/Spindle.java"));
		Path nativeFixture = WeaveHarness.fixture(work, "native", nativeSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "carrierreturn", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g")),
				NativeWeaveReferences.classes(nativeFixture));
		moved = run(fixture, "moved", Map.of());
		off = run(fixture, "off", Map.of(CarrierReturnedCalls.PROPERTY, "off"));
		natively = run(nativeFixture, "native", Map.of());
	}

	@Test void theMovedWrapRunsWhenItRanNativelyWithTheNativeOutcome() throws Exception {
		Map<String, String> movedSteps = steps(moved), nativeSteps = steps(natively);
		for (String scenario : List.of("facing null", "facing north", "facing west"))
			assertEquals(nativeSteps.get(scenario), movedSteps.get(scenario), scenario + "\n" + moved.describe() + "\n" + natively.describe());
		assertEquals("vane north north, faces NORTH", movedSteps.get("facing north"), moved.describe());
		assertEquals("faces null", movedSteps.get("facing null"), "without a spot the wrap does not run, as natively");
		assertTrue(losses(moved).stream().noneMatch(f -> f.id().contains("vane$facing")), moved.findings().toString());
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	@Test void theMoveIsLogged() {
		assertTrue(moved.printed("[Forbric/Mixin] com.gale.vane.mixin.SpindleMixin.vane$facing: org.fern.loom.Compass.read is no longer called in org.fern.loom.Spindle.facing,"
				+ " whose answer it was; the carrier answers its own way there — the wrap moves to return 1 of the method's answer"), moved.describe());
	}

	@Test void aReturnReachedOnBothWaysOrAHandlerAskingAboutAnotherValueIsNotMoved() {
		assertFalse(moved.printed("SpindleMixin.vane$tilt: org.fern.loom.Compass.read is no longer called"), moved.describe());
		assertFalse(moved.printed("SpindleMixin.vane$lean: org.fern.loom.Compass.read is no longer called"), moved.describe());
		assertEquals("tilts north", steps(moved).get("tilt north"), moved.describe());
		assertTrue(losses(moved).stream().anyMatch(f -> f.id().contains("vane$tilt")) && losses(moved).stream().anyMatch(f -> f.id().contains("vane$lean")),
				moved.findings().toString());
	}

	@Test void switchedOffNoWrapRuns() {
		for (var entry : steps(off).entrySet()) assertFalse(entry.getValue().contains("vane"), entry.getKey() + " " + off.describe());
		assertEquals(3, losses(off).size(), off.findings().toString());
	}

	@Test void theControlFlipsTheMainAssertion() {
		assertNotEquals(steps(natively).get("facing north"), steps(off).get("facing north"), "the native oracle must tell the runs apart");
		assertEquals(steps(natively).get("facing north"), steps(moved).get("facing north"));
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:") && f.modId().equals(MOD) && f.required()).toList();
	}

	/** Scenario → its steps, without vanilla's or the carrier's own lines. */
	private static Map<String, String> steps(WeaveHarness.Result run) {
		String line = run.output().lines().filter(l -> l.startsWith(WeaveHarnessMain.DONE + " ")).findFirst().orElse("");
		Map<String, String> out = new LinkedHashMap<>();
		for (String entry : line.substring(Math.min(line.length(), WeaveHarnessMain.DONE.length() + 1)).split(" \\| ")) {
			int colon = entry.indexOf(": ");
			if (colon < 0) continue;
			out.put(entry.substring(0, colon), Arrays.stream(entry.substring(colon + 2).split(", ")).filter(s -> !s.startsWith("carrier-") && !s.startsWith("read "))
					.collect(Collectors.joining(", ")));
		}
		return out;
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.carrierreturn.Probe", "probe", properties);
	}
}
