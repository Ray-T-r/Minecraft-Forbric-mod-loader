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
import net.forbric.kernel.mixin.CarrierGuardedPoints;

/**
 * {@code CarrierGuardedPoints} through the real Mixin weave, on classes that share no name with any game, loader or mod.
 *
 * <p>{@code org.meadow.kite.Kite}, as merged, puts two native points behind branches of the carrier's own: {@code canFly()}
 * forwards to {@code canFly(true)}, which answers from the carrier's stat on that entry before reading the parts, and
 * {@code flutter} skips the pick when no known part is worn. The guest {@code io.sky.lift} lets a winged kite fly and
 * flutter with cancellable injectors at those points. Moved ahead of the carrier's branches, they run where they run
 * natively and every outcome is the native one. An injector that only watches the pick stays behind the guard, and one
 * behind the carrier's veto of a launch is not moved: the carrier deciding the operation does not happen is not the native
 * point gone around. Switched off, the winged kite cannot fly.
 */
class CarrierGuardedPointsWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/carrierguard");
	private static final String CONFIG = "carrierguard.mixins.json";
	private static final String MOD = "lift";
	private static final String TARGET = "org/meadow/kite/Kite";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, off, natively;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList();
		}
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("org/meadow/kite/Kite.java"));
		nativeSources.add(SOURCES.resolve("native/org/meadow/kite/Kite.java"));
		Path nativeFixture = WeaveHarness.fixture(work, "native", nativeSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "carrierguard", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g")),
				NativeWeaveReferences.classes(nativeFixture));
		moved = run(fixture, "moved", Map.of());
		off = run(fixture, "off", Map.of(CarrierGuardedPoints.PROPERTY, "off"));
		natively = run(nativeFixture, "native", Map.of());
	}

	@Test void theMovedInjectorsRunWhereTheyRunNativelyAndEveryOutcomeIsNative() throws Exception {
		Map<String, String> movedSteps = steps(moved), nativeSteps = steps(natively);
		for (String scenario : List.of("fly wing", "fly tail", "flutter wing", "flutter tail", "launch free"))
			assertEquals(nativeSteps.get(scenario), movedSteps.get(scenario), scenario + "\n" + moved.describe() + "\n" + natively.describe());
		assertEquals("sky-flutter, sky-watch, picked tail", movedSteps.get("flutter tail"), "the watcher stays at the pick, the decider moved ahead");
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	@Test void eachMoveIsLogged() {
		assertTrue(moved.printed("[Forbric/Mixin] io.sky.lift.mixin.KiteMixin.sky$allow: the carrier put"), moved.describe());
		assertTrue(moved.printed("[Forbric/Mixin] io.sky.lift.mixin.KiteMixin.sky$flutter: the carrier put"), moved.describe());
		assertFalse(moved.printed("KiteMixin.sky$watch: the carrier put"), moved.describe());
		assertFalse(moved.printed("KiteMixin.sky$launch: the carrier put"), moved.describe());
	}

	/** Behind the carrier's own veto the guest stays: where the carrier keeps the kite tethered, no launch is asked about. */
	@Test void behindTheCarriersVetoNothingIsMoved() {
		assertEquals("before-launch, launched false", steps(moved).get("launch tethered"), moved.describe());
		assertEquals("before-launch, sky-launch, launch up, launched true", steps(natively).get("launch tethered"), natively.describe());
	}

	@Test void switchedOffTheWingedKiteCannotFly() {
		assertEquals("flies false", steps(off).get("fly wing"), off.describe());
		assertEquals("", steps(off).get("flutter wing"), off.describe());
		assertNotEquals(steps(natively).get("fly wing"), steps(off).get("fly wing"), "the native oracle tells the runs apart");
	}

	/** Scenario → its steps, without the carrier's own lines. */
	private static Map<String, String> steps(WeaveHarness.Result run) {
		String line = run.output().lines().filter(l -> l.startsWith(WeaveHarnessMain.DONE + " ")).findFirst().orElse("");
		Map<String, String> out = new LinkedHashMap<>();
		for (String entry : line.substring(Math.min(line.length(), WeaveHarnessMain.DONE.length() + 1)).split(" \\| ")) {
			int colon = entry.indexOf(":");
			if (colon < 0) continue;
			String rest = entry.substring(colon + 1).strip();
			out.put(entry.substring(0, colon), rest.isEmpty() ? "" : Arrays.stream(rest.split(", ")).filter(step -> !step.startsWith("carrier-"))
					.collect(Collectors.joining(", ")));
		}
		return out;
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.carrierguard.Probe", "probe", properties);
	}
}
