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
import net.forbric.kernel.mixin.CarrierMethodReferences;

/**
 * {@code CarrierMethodReferences} through the real Mixin weave, on classes that share no name with any game, loader or mod.
 *
 * <p>{@code org.meadow.sieve.Sieve}, as the guest's loader has it, filters seeds with a lambda that asks the seed's trait
 * whether it fits the pouch, or takes anything into an empty pouch ({@code fits(pouch) || bare}); as merged, it filters with a
 * method reference to the carrier's pouch question, whose default answers true for an empty pouch first and otherwise asks the
 * pouch's kind. The guest {@code io.wren.sift} redirects the trait question inside the native lambda. Moved, the method
 * reference is pointed at a method of the sieve that takes the lambda's own parameters, the redirect lands on its call, and the
 * native lambda runs there with the guest's handler: every seed asked once, the empty pouch's disjunct kept, the native
 * outcome. Switched off, the guest is a reported loss and the carrier's kind decides.
 */
class CarrierMethodReferencesWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/methodreference");
	private static final String CONFIG = "methodreference.mixins.json";
	private static final String MOD = "sift";
	private static final String TARGET = "org/meadow/sieve/Sieve";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, off, natively;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = new ArrayList<>(walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList());
		}
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("org/meadow/sieve/Sieve.java"));
		nativeSources.add(SOURCES.resolve("native/org/meadow/sieve/Sieve.java"));
		Path nativeFixture = WeaveHarness.fixture(work, "native", nativeSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "methodreference", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g")),
				NativeWeaveReferences.classes(nativeFixture));
		moved = run(fixture, "moved", Map.of());
		off = run(fixture, "off", Map.of(CarrierMethodReferences.PROPERTY, "off"));
		natively = run(nativeFixture, "native", Map.of());
	}

	@Test void theNativeLambdaRunsWithTheGuestsHandlerWhereTheCarrierAsksItsMethodReference() throws Exception {
		Map<String, String> steps = steps(moved);
		assertEquals("sift wheat, sift oat, sift wild rye, kept [wheat, wild rye]", steps.get("pick full"), moved.describe());
		assertEquals("sift wheat bare, sift oat bare, sift wild rye bare, kept [wheat, oat, wild rye]", steps.get("pick empty"),
				"the folded disjunct: an empty pouch takes every seed, each still asked once");
		for (String scenario : List.of("pick full", "pick empty"))
			assertEquals(steps(natively).get(scenario), steps.get(scenario), scenario + "\n" + moved.describe() + "\n" + natively.describe());
		assertTrue(moved.printed("[Forbric/Mixin] io.wren.sift.mixin.SieveMixin.sift$fits: org.meadow.sieve.Trait.fits is no longer called in org.meadow.sieve.Sieve.lambda$pick$0,"
				+ " a lambda the carrier replaced with a method reference to org.meadow.sieve.PouchExtension.accepts, whose inherited implementation folds in the lambda's captured boolean"), moved.describe());
		assertTrue(losses(moved).isEmpty(), moved.findings().toString());
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	/** Switched off, the guest's only injector binds nowhere: the mixin is left out and reported, and the carrier's kind decides. */
	@Test void switchedOffTheGuestIsALossAndTheCarrierDecides() {
		assertEquals("kept [wheat, oat]", steps(off).get("pick full"), off.describe());
		assertEquals(1, off.findings().stream().filter(f -> f.modId().equals(MOD) && f.required()).count(), off.findings().toString());
	}

	@Test void theControlFlipsTheMainAssertion() {
		assertNotEquals(steps(off).get("pick full"), steps(moved).get("pick full"));
		assertEquals(steps(natively).get("pick full"), steps(moved).get("pick full"));
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
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.methodreference.Probe", "probe", properties);
	}
}
