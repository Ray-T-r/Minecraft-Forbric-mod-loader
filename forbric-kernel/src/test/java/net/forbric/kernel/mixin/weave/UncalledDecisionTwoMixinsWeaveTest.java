/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MergedBaseUncalledMethods;
import net.forbric.kernel.mixin.MixinHandlerShim;

/**
 * Two mixins on one uncalled decision whose HEAD handlers share a name, through the real Mixin and MixinExtras weave.
 *
 * <p>The stand-in game is {@link UncalledDecisionEntriesWeaveTest}'s {@code Weir}: natively {@code side} asks the static
 * {@code opens}; merged, the carrier asks {@code Stone.admits} and nothing calls {@code opens}. Here a second mixin of the same
 * guest ({@code SluiceMixin}, not cancellable, only logging) hooks {@code opens} with a handler of the very name the first
 * ({@code WeirMixin}, cancellable) uses. Both are moved onto the carrier's decision, each keeping its body as a plain method:
 * named only {@code latch$opens$forbricOriginal}, Mixin merged one of the two bodies ("Method overwrite conflict … Skipping
 * method"), the second wrapper ran the first mixin's body with a callback that cannot be cancelled, and {@code side} threw
 * {@code CancellationException}. Each body now carries its mixin's mark ({@link MixinHandlerShim#markAsides}).
 *
 * <p>Not native yet, and pinned here so a fix shows: the two moved hooks are two MixinExtras wraps of one call, and the wrap
 * applied last is the outer one, so the second mixin's hook runs before the first's — natively the first's runs first — and it
 * still runs when the first cancels (natively a cancel at {@code HEAD} skips every later hook). With one cancellable hook and
 * one that only observes, every place answers as natively; two cancelling hooks on one moved decision would trade who wins.
 */
class UncalledDecisionTwoMixinsWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/uncalleddup");
	private static final String CONFIG = "uncalleddup.mixins.json", BLOCKED = "uncalleddup-blocked.mixins.json";
	private static final String TARGET = "net/minecraft/fixture/weir/Weir";
	private static final String STONE = "Lnet/minecraft/fixture/weir/Stone;", FACE = "Lnet/minecraft/fixture/weir/Face;";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, unmarked, blocked, natively;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = new ArrayList<>(walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList());
		}
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("net/minecraft/fixture/weir/Weir.java"));
		nativeSources.add(SOURCES.resolve("native/net/minecraft/fixture/weir/Weir.java"));
		Map<String, Path> configs = Map.of(CONFIG, SOURCES.resolve(CONFIG), BLOCKED, SOURCES.resolve(BLOCKED));
		Path nativeFixture = WeaveHarness.fixture(work, "native", nativeSources, configs, List.of("-g"));
		fixture = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "uncalleddup", sources, configs, List.of("-g")),
				NativeWeaveReferences.classes(nativeFixture));
		String side = TARGET + "#side(" + STONE + STONE + STONE + FACE + ")Ljava/lang/String;";
		Path uncalled = work.resolve("uncalled-extra.txt");
		Files.writeString(uncalled, TARGET + "#opens(" + STONE + ")Z | FABRIC | " + side + "\n"
				+ TARGET + "#opens(" + STONE + FACE + ")Z | FABRIC | " + side + " " + TARGET + "#opens(" + STONE + ")Z | " + TARGET + "#opens(" + STONE + ")Z\n");
		moved = run(fixture, "moved", Map.of(MergedBaseUncalledMethods.EXTRA_PROPERTY, uncalled.toString()));
		unmarked = run(fixture, "unmarked", Map.of(MergedBaseUncalledMethods.EXTRA_PROPERTY, uncalled.toString(), MixinHandlerShim.MARK_PROPERTY, "off"));
		blocked = WeaveHarness.run(work, "blocked", fixture, BLOCKED, "latch", Ecosystem.FABRIC, EnvType.CLIENT, "io.wren.latch.Probe", "probe",
				Map.of(MergedBaseUncalledMethods.EXTRA_PROPERTY, uncalled.toString()));
		natively = run(nativeFixture, "native", Map.of());
	}

	@Test void eachMixinKeepsItsOwnBodyAndEveryPlaceAnswersAsNatively() throws Exception {
		assertTrue(cases(natively).contains("latchB "), "natively both mixins' handlers run — " + natively.describe());
		assertFalse(moved.output().contains("Method overwrite conflict"), moved.describe());
		assertFalse(moved.output().contains("CancellationException"), moved.describe());
		for (String mixin : List.of("WeirMixin", "SluiceMixin")) {
			assertTrue(moved.printed("io.wren.latch.mixin." + mixin + ".latch$opens: net.minecraft.fixture.weir.Weir.opens is no longer called"), moved.describe());
		}
		String[] nativeCases = cases(natively).split(" \\| "), movedCases = cases(moved).split(" \\| ");
		assertEquals(nativeCases.length, movedCases.length, moved.describe());
		for (int i = 0; i < nativeCases.length; i++) {
			assertEquals(answer(nativeCases[i]), answer(movedCases[i]), "the place answers as natively — " + movedCases[i]);
			assertEquals(hooks(nativeCases[i], "latch "), hooks(movedCases[i], "latch "), "the first mixin's hook runs exactly where it runs natively — " + movedCases[i]);
			assertTrue(hooks(movedCases[i], "latchB ").containsAll(hooks(nativeCases[i], "latchB ")), "the second mixin's hook runs wherever it runs natively — "
					+ movedCases[i]);
		}
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	/** What is not native yet (see the class comment): the order of the two moved hooks, and the second's run after the first cancels. */
	@Test void theTwoMovedHooksRunInTheReverseOfTheNativeOrder() {
		assertTrue(cases(natively).startsWith("latch/clay/clay/EAST -> SIDE [latch clay/null, latchB clay/null, latch latch/EAST]"), natively.describe());
		assertTrue(cases(moved).startsWith("latch/clay/clay/EAST -> SIDE [latchB clay/null, latch clay/null, latchB latch/EAST, latch latch/EAST]"), moved.describe());
	}

	@Test void unmarkedTheTwoBodiesCollideAndTheGameMethodThrows() {
		assertTrue(unmarked.output().contains("Method overwrite conflict for latch$opens$forbricOriginal"), unmarked.describe());
		assertTrue(unmarked.output().contains("CancellationException") || !cases(unmarked).equals(cases(natively)), unmarked.describe());
	}

	/** A move the plan proved necessary and the rule could not make is said in a WARN, not dropped silently. */
	@Test void aMoveThatCannotBeMadeIsSaid() {
		assertTrue(blocked.output().contains("WARN") && blocked.printed("io.wren.latch.mixin.GateMixin.latch$gate: net.minecraft.fixture.weir.Weir.opens never "
				+ "runs for this mod's ecosystem, and its HEAD injector was to wrap the carrier's decision in side"), blocked.describe());
		assertTrue(blocked.printed("but the move could not be made — the mixin already declares latch$gate$forbricentry"), blocked.describe());
		assertTrue(blocked.printed("io.wren.latch.mixin.WeirMixin.latch$opens: net.minecraft.fixture.weir.Weir.opens is no longer called"), blocked.describe());
		assertFalse(cases(blocked).contains("latchG "), "the blocked hook stays where nothing runs it — " + blocked.describe());
	}

	private static String answer(String place) {
		return place.substring(0, place.indexOf(" ["));
	}

	private static List<String> hooks(String place, String prefix) {
		List<String> out = new ArrayList<>();
		for (String entry : place.substring(place.indexOf('[') + 1, place.lastIndexOf(']')).split(", ")) if (entry.startsWith(prefix)) out.add(entry);
		return out;
	}

	private static String cases(WeaveHarness.Result run) {
		for (String line : run.output().split("\n")) {
			int at = line.indexOf("[UncalledDecision] ");
			if (at >= 0) return line.substring(at + "[UncalledDecision] ".length()).trim();
		}
		return "<no cases printed>";
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, "latch", Ecosystem.FABRIC, EnvType.CLIENT, "io.wren.latch.Probe", "probe", properties);
	}
}
