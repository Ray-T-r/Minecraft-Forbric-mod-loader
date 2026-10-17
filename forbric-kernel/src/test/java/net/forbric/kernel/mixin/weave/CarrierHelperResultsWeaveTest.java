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
import net.forbric.kernel.mixin.CarrierHelperResults;

/**
 * {@code CarrierHelperResults} through the real Mixin and MixinExtras weave, on classes that share no name with any game,
 * loader or mod.
 *
 * <p>{@code org.quarry.stone.Mason}, as the guest's loader has it, shatters the slab itself in {@code chip}, once, when the yard
 * cleared the spot; as merged, a private helper {@code crumble} clears and shatters, called on two exclusive paths (careful and
 * not), and answers whether it cleared — so whether it shattered. The guest {@code io.lark.chisel} injects at the shatter and
 * reads the relic and the shaped slab by {@code @Local} name. Moved, it runs once per shatter, on both paths, never when the
 * yard refused, with the very relic and slab the native method hands it, and its callback carries the method's name. Its order
 * against the shatter is the merged method's: it runs after the helper returned, so after the shatter it ran before natively.
 *
 * <p>{@code split}'s helper {@code shiver} answers true whether or not it shattered anything: no proof, no move, and the guest's
 * injector there is a reported loss. Switched off, neither moves.
 */
class CarrierHelperResultsWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/helperresult");
	private static final String CONFIG = "helperresult.mixins.json";
	private static final String MOD = "chisel";
	private static final String TARGET = "org/quarry/stone/Mason";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, off, natively;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = new ArrayList<>(walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList());
		}
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("org/quarry/stone/Mason.java"));
		nativeSources.add(SOURCES.resolve("native/org/quarry/stone/Mason.java"));
		Path nativeFixture = WeaveHarness.fixture(work, "native", nativeSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "helperresult", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g")),
				NativeWeaveReferences.classes(nativeFixture));
		moved = run(fixture, "moved", Map.of());
		off = run(fixture, "off", Map.of(CarrierHelperResults.PROPERTY, "off"));
		natively = run(nativeFixture, "native", Map.of());
	}

	@Test void theInjectorRunsOncePerShatterOnBothPathsWithTheNativeLocals() throws Exception {
		Map<String, String> steps = steps(moved);
		assertEquals("shatter shaped-slab1 at p1, chisel p1 relic1 shaped-slab1 chip, chipped true", steps.get("chip plain"), moved.describe());
		assertEquals("shatter shaped-slab2 at p2, chisel p2 relic2 shaped-slab2 chip, chipped true", steps.get("chip careful"), moved.describe());
		assertEquals("chipped false", steps.get("chip stuck"), "the yard refused: no shatter, and no injector");
		assertTrue(moved.printed("[Forbric/Mixin] io.lark.chisel.mixin.MasonMixin.chisel$chip: org.quarry.stone.Slab.shatter is no longer called in org.quarry.stone.Mason.chip;"
				+ " the carrier makes it in org.quarry.stone.Mason.crumble, which answers true exactly when it made it — the injector runs on that answer, at its 2 call(s),"
				+ " with 2 local(s) read where the merged method keeps them"), moved.describe());
		assertTrue(losses(moved).stream().noneMatch(f -> f.id().contains("chisel$chip")), moved.findings().toString());
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	/** The same injector runs as natively: the same lines, each once, with the same values; only its place against the shatter differs. */
	@Test void theNativeOutcomeUpToTheMergedMethodsOrder() {
		for (String scenario : List.of("chip plain", "chip careful", "chip stuck")) {
			List<String> got = new ArrayList<>(List.of(steps(moved).get(scenario).split(", "))), want = new ArrayList<>(List.of(steps(natively).get(scenario).split(", ")));
			got.sort(null);
			want.sort(null);
			assertEquals(want, got, scenario + "\n" + moved.describe() + "\n" + natively.describe());
		}
		assertEquals("chisel p1 relic1 shaped-slab1 chip, shatter shaped-slab1 at p1, chipped true", steps(natively).get("chip plain"), natively.describe());
	}

	/** A helper that answers true whether or not it made the call proves nothing: the injector stays, and binds nowhere. */
	@Test void aHelperWhoseAnswerDoesNotSayWhetherItCalledIsNotFollowed() {
		assertEquals("shatter slab4 at p4", steps(moved).get("split"), moved.describe());
		assertFalse(moved.printed("MasonMixin.chisel$split: org.quarry.stone.Slab.shatter is no longer called"), moved.describe());
		assertTrue(losses(moved).stream().anyMatch(f -> f.id().contains("chisel$split")), moved.findings().toString());
		assertEquals("chisel-split p4 slab4, shatter slab4 at p4", steps(natively).get("split"), natively.describe());
	}

	@Test void switchedOffNeitherMoves() {
		for (var entry : steps(off).entrySet()) assertFalse(entry.getValue().contains("chisel"), entry.getKey() + " " + off.describe());
		assertEquals(2, losses(off).size(), off.findings().toString());
	}

	@Test void theControlFlipsTheMainAssertion() {
		assertNotEquals(steps(off).get("chip plain"), steps(moved).get("chip plain"), "the switched-off run must tell the runs apart");
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
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.helperresult.Probe", "probe", properties);
	}
}
