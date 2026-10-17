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
import net.forbric.kernel.mixin.CarrierDecisions;

/**
 * {@code CarrierDecisions} through the real Mixin and MixinExtras weave, on classes that share no name with any game, loader
 * or mod.
 *
 * <p>{@code org.quarry.pit.Drill}, as the guest's loader has it, decides everything itself; as merged, a carrier decides in
 * its place: the bit's own {@code dulls} (the opposite question, with a tolerance of its own) where the drill asked
 * {@code Bit.sameEdge}, the stack's {@code accepts(holder)} where it asked {@code recipe.fits(stack)} (the recipe read off the
 * holder there, or kept in a local), the carrier's hook where it asked {@code canFeed}, a map of its own where it copied one,
 * the loaded map itself where it froze a copy. The guest, {@code net.lark.bore}, wrote a redirect, an injector or a value
 * redirect for each. Moved, every guest runs where the carrier decides and every outcome is the native one; where the
 * carrier's own answer differs from vanilla's (a bit one step worn), the guest's fallback is the carrier's answer. Where
 * the carrier skips another block ({@code shine}), nothing is moved. Switched off, no guest runs anywhere.
 */
class CarrierDecisionsWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/carrierdecision");
	private static final String CONFIG = "carrierdecision.mixins.json";
	private static final String MOD = "bore";
	private static final String TARGET = "org/quarry/pit/Drill";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, off, natively;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList();
		}
		List<Path> nativeSources = new ArrayList<>(sources);
		for (String replaced : List.of("Drill", "Bit", "Stack")) {
			nativeSources.remove(SOURCES.resolve("org/quarry/pit/" + replaced + ".java"));
			nativeSources.add(SOURCES.resolve("native/org/quarry/pit/" + replaced + ".java"));
		}
		Path nativeFixture = WeaveHarness.fixture(work, "native", nativeSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "carrierdecision", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)),
				List.of("-g")), NativeWeaveReferences.classes(nativeFixture));
		moved = run(fixture, "moved", Map.of());
		off = run(fixture, "off", Map.of(CarrierDecisions.PROPERTY, "off"));
		natively = run(nativeFixture, "native", Map.of());
	}

	@Test void everyMovedGuestRunsAndEveryOutcomeIsTheNativeOne() throws Exception {
		Map<String, String> movedSteps = steps(moved, false), nativeSteps = steps(natively, false);
		for (String scenario : List.of("keep hard0", "keep soft0", "keep soft3", "takes ore", "takes wild", "takes dirt", "price ore", "price wild",
				"price dirt", "feed ore", "feed stone", "empty a", "empty locked", "freeze"))
			assertEquals(nativeSteps.get(scenario), movedSteps.get(scenario), scenario + "\n" + moved.describe() + "\n" + natively.describe());
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	/** The guest's own call of the question it redirects is the carrier's question there, its answer turned back. */
	@Test void theGuestsFallbackIsTheCarriersOppositeQuestion() {
		assertEquals("carrier-dulls, bore-keep true, keep", steps(moved, true).get("keep hard1"), moved.describe());
		assertEquals("bore-keep false, reset", steps(natively, true).get("keep hard1"), natively.describe());
	}

	@Test void eachMoveIsLoggedWithWhatTheCarrierDecides() {
		for (String handler : List.of("bore$keep", "bore$takes", "bore$price", "bore$feed", "bore$empty"))
			assertTrue(moved.printed("[Forbric/Mixin] net.lark.bore.mixin.DrillMixin." + handler + ": "), handler + "\n" + moved.describe());
		assertTrue(moved.printed("net.lark.bore.mixin.DrillMixin.bore$keep: org.quarry.pit.Bit.sameEdge is no longer called in org.quarry.pit.Drill.keepsBoring;"
				+ " the carrier decides in its place with org.quarry.pit.Bit.dulls, answering the opposite question"), moved.describe());
		assertTrue(moved.printed("net.lark.bore.mixin.DrillMixin.bore$freeze: java.util.Map.copyOf's copy is no longer made in org.quarry.pit.Drill.freeze"),
				moved.describe());
	}

	@Test void whereTheCarrierSkipsAnotherBlockNothingIsMoved() {
		assertFalse(moved.printed("DrillMixin.bore$shine: org.quarry.pit.Bit.sameEdge is no longer called"), moved.describe());
		assertEquals("carrier-dulls, glint, shone", steps(moved, true).get("shine"), moved.describe());
		assertEquals("bore-shine, shine, shone", steps(natively, true).get("shine"), natively.describe());
	}

	@Test void switchedOffNoGuestRunsWhereTheCarrierDecides() {
		Map<String, String> offSteps = steps(off, true);
		for (var entry : offSteps.entrySet())
			if (!entry.getKey().equals("shine")) assertFalse(entry.getValue().contains("bore-"), entry.getKey() + " " + off.describe());
	}

	@Test void theControlFlipsTheMainAssertion() {
		assertNotEquals(steps(natively, false).get("takes wild"), steps(off, false).get("takes wild"), "the native oracle must tell the runs apart");
		assertEquals(steps(natively, false).get("takes wild"), steps(moved, false).get("takes wild"));
	}

	/** Scenario → its steps; without the carrier's own lines unless {@code all}. */
	private static Map<String, String> steps(WeaveHarness.Result run, boolean all) {
		String line = run.output().lines().filter(l -> l.startsWith(WeaveHarnessMain.DONE + " ")).findFirst().orElse("");
		Map<String, String> out = new LinkedHashMap<>();
		for (String entry : line.substring(Math.min(line.length(), WeaveHarnessMain.DONE.length() + 1)).split(" \\| ")) {
			int colon = entry.indexOf(": ");
			if (colon < 0) continue;
			out.put(entry.substring(0, colon), Arrays.stream(entry.substring(colon + 2).split(", ")).filter(step -> all || !step.startsWith("carrier-"))
					.collect(Collectors.joining(", ")));
		}
		return out;
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.carrierdecision.Probe", "probe", properties);
	}
}
