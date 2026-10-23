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
import net.forbric.kernel.mixin.UncalledValueRelay;

/**
 * {@code UncalledValueRelay} through the real Mixin and MixinExtras weave, on a stand-in game that shares no name with any game,
 * loader or mod.
 *
 * <p>The guest's own game cracks a {@code Kiln} by asking {@code vent(level, pos, tool, range)}: it draws the range, refines
 * the draw ({@code Lore.refine}: a silk tool keeps nothing) and puffs what is left — the shape of vanilla's
 * {@code tryDropExperience} under {@code DropExperienceBlock.spawnAfterBreak}. As merged, the hearth draws the kiln's yield and
 * refines it itself, a carrier listener sees the value, and the hearth puffs it; nothing calls {@code vent} — the shape of
 * NeoForge's {@code BlockDropsEvent} and {@code handleBlockDrops}. The guest {@code io.tern.tally} replaces the refined value at
 * its store (ten times it) and cancels {@code vent} at its start while the hearth is hushed, with a handler handed every
 * argument, the range included. A {@code Brazier} puffs a fixed amount and never asks {@code vent}; an {@code Oven}'s crack
 * only adds to its parent's; a {@code Cinder} asks {@code vent} with a range it makes on the spot.
 *
 * <p>Relayed, every break reads as natively: the hooks run where {@code vent} would have run, in the native order, handed the
 * native range (the kiln's field, or the cinder's {@code Range.of(2, 4)}), and on a brazier not at all. Switched off they never
 * run. A hook of a mod whose own game never calls {@code vent} either (NeoForge's, here) keeps the class as Mixin wove it.
 */
class UncalledValueRelayWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/valuerelay");
	private static final String TALLY = "tally.mixins.json", SEAR = "sear.mixins.json";
	private static final String TARGET = "net/minecraft/fixture/kiln/Kiln";
	private static final String K = "Lnet/minecraft/fixture/kiln/";
	private static final String VENT = TARGET + "#vent(" + K + "Hearth;" + K + "Spot;" + K + "Tool;" + K + "Range;)V";
	private static final String CRACK = "(" + K + "Hearth;" + K + "Spot;" + K + "Tool;)V";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result relayed, off, declined, natively;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = new ArrayList<>(walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList());
		}
		List<Path> nativeSources = new ArrayList<>(sources);
		for (String replaced : List.of("Kiln", "Hearth", "Brazier", "Cinder")) {
			nativeSources.remove(SOURCES.resolve("net/minecraft/fixture/kiln/" + replaced + ".java"));
			nativeSources.add(SOURCES.resolve("native/net/minecraft/fixture/kiln/" + replaced + ".java"));
		}
		Map<String, Path> configs = Map.of(TALLY, SOURCES.resolve(TALLY), SEAR, SOURCES.resolve(SEAR));
		Path nativeFixture = WeaveHarness.fixture(work, "native", nativeSources, configs, List.of("-g"));
		fixture = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "valuerelay", sources, configs, List.of("-g")),
				NativeWeaveReferences.classes(nativeFixture));
		// The rows this stand-in game's census would write (nothing calls vent; natively the kiln's and the cinder's crack do),
		// and the contract tying vent to the hearth: the range's draw, the refining, the puff, and the range itself unread.
		Path uncalled = work.resolve("uncalled-extra.txt"), contract = work.resolve("relay-extra.txt");
		Files.writeString(uncalled, VENT + " | FABRIC | " + TARGET + "#crack" + CRACK + " net/minecraft/fixture/kiln/Cinder#crack" + CRACK + "\n");
		Files.writeString(contract, VENT + " | net/minecraft/fixture/kiln/Range.roll(" + K + "Dice;)I | net/minecraft/fixture/kiln/Lore.refine(" + K
				+ "Hearth;" + K + "Tool;I)I | " + TARGET + ".puff(" + K + "Hearth;" + K + "Spot;I)V | 3\n");
		Map<String, String> census = Map.of(MergedBaseUncalledMethods.EXTRA_PROPERTY, uncalled.toString(), UncalledValueRelay.EXTRA_PROPERTY,
				contract.toString());
		relayed = run(fixture, "relayed", List.of(tally()), census);
		Map<String, String> switchedOff = new java.util.HashMap<>(census);
		switchedOff.put(UncalledValueRelay.PROPERTY, "off");
		off = run(fixture, "off", List.of(tally()), switchedOff);
		declined = run(fixture, "declined", List.of(tally(), new WeaveHarness.Config(SEAR, "sear", Ecosystem.NEOFORGE)), census);
		natively = run(nativeFixture, "native", List.of(tally()), Map.of());
	}

	@Test void natively() {
		String cases = cases(natively);
		assertTrue(cases.startsWith("Kiln/pick/loud [hush Range[3..5], tally 4 pick, Kiln puff 40] | Kiln/silk/loud [hush Range[3..5], tally 0 silk]"
				+ " | Brazier/pick/loud [Brazier puff 7] | Brazier/silk/loud []"
				+ " | Oven/pick/loud [hush Range[3..5], tally 4 pick, Oven puff 40] | Oven/silk/loud [hush Range[3..5], tally 0 silk]"
				+ " | Cinder/pick/loud [hush Range[2..4], tally 3 pick, Cinder puff 30]"), natively.describe());
		assertTrue(cases.contains("Kiln/pick/hushed [hush Range[3..5]]"), natively.describe());
	}

	@Test void theHooksRunOnTheCarriersValueWhereTheNativeGameAskedVent() throws Exception {
		assertEquals(cases(natively), cases(relayed), "every break, as natively — " + relayed.describe());
		assertTrue(relayed.printed("Kiln.vent never runs in the merged game; the 2 hook(s) woven into it"), relayed.describe());
		assertTrue(relayed.findings().stream().noneMatch(f -> f.modId().equals("tally") && f.required()), relayed.findings().toString());
		WeaveHarness.assertWovenAndVerified(relayed, TARGET, fixture);
	}

	@Test void switchedOffTheHooksNeverRun() {
		assertFalse(cases(off).contains("tally "), off.describe());
		assertFalse(cases(off).contains("hush "), off.describe());
		assertTrue(cases(off).startsWith("Kiln/pick/loud [Kiln puff 4]"), off.describe());
		assertNotEquals(cases(natively), cases(off));
	}

	@Test void aHookOfAnEcosystemWhoseGameNeverCallsTheMethodKeepsItAsWoven() {
		assertTrue(declined.printed("are not relayed to where the carrier computes its value — a hook of io.tern.sear.mixin.KilnSearMixin (NEOFORGE) is woven"),
				declined.describe());
		assertEquals(cases(off), cases(declined), declined.describe());
	}

	private static WeaveHarness.Config tally() {
		return new WeaveHarness.Config(TALLY, "tally", Ecosystem.FABRIC);
	}

	private static String cases(WeaveHarness.Result run) {
		for (String line : run.output().split("\n")) {
			int at = line.indexOf("[ValueRelay] ");
			if (at >= 0) return line.substring(at + "[ValueRelay] ".length()).trim();
		}
		throw new AssertionError("no cases printed — " + run.describe());
	}

	private static WeaveHarness.Result run(Path fixture, String label, List<WeaveHarness.Config> configs, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, configs, List.of(), EnvType.CLIENT, "io.tern.tally.Probe", "probe", properties);
	}
}
