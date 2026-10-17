/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.CarrierDefaultChains;

/**
 * {@code CarrierDefaultChains} through the real Mixin weave, on classes that share no name with any game, loader or mod.
 *
 * <p>{@code org.brook.mill.Wheel}, as the guest's loader has it, makes each vanilla call itself; as merged, the carrier routes
 * each through a method of its own: the grain's {@code milledBy} (an interface default that grinds), and the ear's
 * {@code ripen} (a default that asks the ear's stalk, through a private accessor, whose own default marks the field). The
 * guest {@code io.reed.husk} redirects the grind (with the turn's own arguments captured) and the mark, and modifies the
 * constant a dip adds, which the carrier now pushes in its bucket's default (scaled by a factor of its own). Moved, each runs
 * where it runs natively, once, with every outcome the native one; a carrier mod's grain, stalk or bucket that overrides the
 * carrier's method keeps its override and the guest does not run for it. A sift, whose carrier method reads a member only its package
 * may, is not moved. Switched off, neither guest runs.
 */
class CarrierDefaultChainsWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/carrierchain");
	private static final String CONFIG = "carrierchain.mixins.json";
	private static final String MOD = "husk";
	private static final String TARGET = "org/brook/mill/Wheel";
	/** The new handler asks the kernel's game-side KernelDispatch, which ForbricClassLoader defines from a jar it owns. */
	private static final Path DISPATCH = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelDispatch.java");

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, off, natively;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = new ArrayList<>(walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList());
		}
		sources.add(DISPATCH);
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("org/brook/mill/Wheel.java"));
		nativeSources.add(SOURCES.resolve("native/org/brook/mill/Wheel.java"));
		Path nativeFixture = WeaveHarness.fixture(work, "native", nativeSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "carrierchain", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g")),
				NativeWeaveReferences.classes(nativeFixture));
		moved = run(fixture, "moved", Map.of());
		off = run(fixture, "off", Map.of(CarrierDefaultChains.PROPERTY, "off"));
		natively = run(nativeFixture, "native", Map.of());
	}

	@Test void everyMovedRedirectRunsOnceWhereItRunsNativelyWithTheNativeOutcome() throws Exception {
		Map<String, String> movedSteps = steps(moved), nativeSteps = steps(natively);
		for (String scenario : List.of("turn plain", "harvest plain", "dip plain"))
			assertEquals(nativeSteps.get(scenario), movedSteps.get(scenario), scenario + "\n" + moved.describe() + "\n" + natively.describe());
		assertEquals("husk-grind wheat of wheat, grind wheat fine, got husked ground wheat", movedSteps.get("turn plain"), moved.describe());
		assertEquals("husk-mark barley@3, mark day 4 barley@3", movedSteps.get("harvest plain"), moved.describe());
		assertEquals("husk-dip 0.25, got 1.5", movedSteps.get("dip plain"), "the constant's modifier, handed the constant where the carrier pushes it");
		assertTrue(losses(moved).stream().noneMatch(f -> f.id().contains("husk$grind") || f.id().contains("husk$mark") || f.id().contains("husk$dip")),
				moved.findings().toString());
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	/** A receiver that overrides the carrier's method gets its override, as the game would without the mod. */
	@Test void aCarrierModsOverrideKeepsItsOwnImplementation() {
		assertEquals("custom mill rye, got custom rye", steps(moved).get("turn custom"), moved.describe());
		assertEquals("thorn ripen oat@4", steps(moved).get("harvest thorn"), moved.describe());
		assertEquals("deep dip, got 9.0", steps(moved).get("dip deep"), moved.describe());
		assertEquals("custom mill rye, got custom rye", steps(off).get("turn custom"), off.describe());
	}

	@Test void eachMoveIsLoggedWithTheCarriersCall() {
		assertTrue(moved.printed("[Forbric/Mixin] io.reed.husk.mixin.WheelMixin.husk$grind: org.brook.mill.Stones.grind is no longer called in org.brook.mill.Wheel.turn;"
				+ " the carrier calls org.brook.mill.Grain.milledBy there, whose inherited implementation makes that call"), moved.describe());
		assertTrue(moved.printed("[Forbric/Mixin] io.reed.husk.mixin.WheelMixin.husk$mark: org.brook.mill.Field.mark is no longer called in org.brook.mill.Wheel.harvest;"
				+ " the carrier calls org.brook.mill.Ear.ripen there, whose inherited implementation makes that call (through org.brook.mill.Stalk.ripen)"), moved.describe());
	}

	@Test void aConstantsModifierMovesOntoTheCallWhoseImplementationPushesTheConstant() {
		assertTrue(moved.printed("[Forbric/Mixin] io.reed.husk.mixin.WheelMixin.husk$dip: the constant 0.25 is no longer pushed in org.brook.mill.Wheel.dip;"
				+ " the carrier calls org.brook.mill.Bucket.dipped there, whose inherited implementation pushes it"), moved.describe());
		assertEquals("got 1.25", steps(off).get("dip plain"), off.describe());
	}

	/** A carrier method whose implementation reads a member only its package may is never inlined into the guest's target. */
	@Test void aChainThroughAPackageMemberIsNotMoved() {
		assertFalse(moved.printed("WheelMixin.husk$sift: org.brook.mill.Stones.grind is no longer called"), moved.describe());
		assertEquals("grind husk coarse, got ground husk", steps(moved).get("sift"), moved.describe());
		assertTrue(losses(moved).stream().anyMatch(f -> f.id().contains("husk$sift")), moved.findings().toString());
	}

	@Test void switchedOffNoGuestRunsWhereTheCarrierRoutesTheCall() {
		for (var entry : steps(off).entrySet()) assertFalse(entry.getValue().contains("husk-"), entry.getKey() + " " + off.describe());
		assertEquals(4, losses(off).size(), off.findings().toString());
	}

	@Test void theControlFlipsTheMainAssertion() {
		assertNotEquals(steps(natively).get("turn plain"), steps(off).get("turn plain"), "the native oracle must tell the runs apart");
		assertEquals(steps(natively).get("turn plain"), steps(moved).get("turn plain"));
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:") && f.modId().equals(MOD) && f.required()).toList();
	}

	/** Scenario → its steps. */
	private static Map<String, String> steps(WeaveHarness.Result run) {
		String line = run.output().lines().filter(l -> l.startsWith(WeaveHarnessMain.DONE + " ")).findFirst().orElse("");
		Map<String, String> out = new LinkedHashMap<>();
		for (String entry : line.substring(Math.min(line.length(), WeaveHarnessMain.DONE.length() + 1)).split(" \\| ")) {
			int colon = entry.indexOf(": ");
			if (colon >= 0) out.put(entry.substring(0, colon), entry.substring(colon + 2));
		}
		return out;
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.carrierchain.Probe", "probe", properties);
	}
}
