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
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.CrossHostPredicateIslandInjector;
import net.forbric.kernel.mixin.MixinCrossHostPredicateIsland;
import net.forbric.kernel.mixin.NativeDecisionIsland;

/**
 * The cross-host predicate island reading a native decision island, through the real weave, with guests that share no name
 * with any mod.
 *
 * <p>Natively the base tick decides the air in water's branch: {@code isEyeInFluid(WATER)}, then — outside a bubble column —
 * {@code canBreatheUnderwater()}, {@code hasWaterBreathing(this)} and a player's invulnerability. The guest {@code org.marsh.reed}
 * makes its own drowning pool count as water at the head check (a value modifier); {@code com.tern.gill} wraps the water
 * breathing check with a tank. As merged, the carrier's breathing hook decides with its own typed questions; for the pool's
 * fluid type (one the kernel owns) it asks whether a body drowns in that type, which never asks whether the body breathes under
 * water — so without the island a body that breathes under water drowns in the pool, where natively it holds its breath.
 *
 * <p>Whichever guest's mixins are read first, and with the pool guest alone, the head check's value modifier runs once per tick,
 * at the native call, and the hook decides the pool on the answer the island got there: every case decides as natively (the
 * consume/hold/refill outcome) and every guest line is the native run's. Switched off, the reader leaves a call another guest's
 * island holds unheard — the body that breathes under water drowns in the pool, as before this change.
 */
@net.forbric.kernel.transform.ExecutesInjector(CrossHostPredicateIslandInjector.class)
class CrossHostIslandReaderWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/crosshostreader");
	private static final String SOAK = "reader-soak.mixins.json", GILL = "reader-gill.mixins.json";
	private static final List<String> CASES = List.of("pool", "pool-breather", "pool-tank", "water", "water-breather", "air");

	@TempDir static Path work;
	private static Path fixture, nativeFixture;
	private static WeaveHarness.Result natively, nativeSoak, soakAlone, gillFirst, soakFirst, readerOff, islandOff;

	@BeforeAll static void weave() throws Exception {
		Path runtime = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelFluidPredicateIslands.java");
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList();
		}
		assertEquals(31, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		List<Path> current = new ArrayList<>(sources);
		current.add(runtime);
		List<Path> natives = new ArrayList<>(current);
		natives.remove(SOURCES.resolve("net/minecraft/world/entity/LivingEntity.java"));
		natives.add(SOURCES.resolve("native/net/minecraft/world/entity/LivingEntity.java"));
		Map<String, Path> configs = Map.of(SOAK, SOURCES.resolve(SOAK), GILL, SOURCES.resolve(GILL));
		nativeFixture = WeaveHarness.fixture(work, "reader-native", natives, configs, List.of("-g"));
		fixture = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "reader", current, configs, List.of("-g")),
				NativeWeaveReferences.classes(nativeFixture));
		natively = run(nativeFixture, "native", List.of(GILL, SOAK), Map.of());
		nativeSoak = run(nativeFixture, "native-soak", List.of(SOAK), Map.of());
		soakAlone = run(fixture, "soak-alone", List.of(SOAK), Map.of());
		gillFirst = run(fixture, "gill-first", List.of(GILL, SOAK), Map.of());
		soakFirst = run(fixture, "soak-first", List.of(SOAK, GILL), Map.of());
		readerOff = run(fixture, "reader-off", List.of(GILL, SOAK), Map.of(MixinCrossHostPredicateIsland.READER_PROPERTY, "off"));
		islandOff = run(fixture, "island-off", List.of(SOAK), Map.of(NativeDecisionIsland.PROPERTY, "off"));
	}

	/** Each case decides the air as natively: consume, hold or refill — with the pool guest alone and with both, in either order. */
	@Test void everyCaseDecidesAsNatively() {
		Map<String, String> expected = outcomes(natively);
		assertEquals(Map.of("pool", "consume", "pool-breather", "hold", "pool-tank", "hold", "water", "consume", "water-breather", "hold", "air", "refill"),
				expected, natively.describe());
		for (WeaveHarness.Result run : List.of(gillFirst, soakFirst)) assertEquals(expected, outcomes(run), trace(run) + "\nnative: " + trace(natively) + "\n" + run.describe());
		assertEquals("consume", outcomes(nativeSoak).get("pool-tank"), nativeSoak.describe());
		assertEquals(outcomes(nativeSoak), outcomes(soakAlone), trace(soakAlone) + "\nnative: " + trace(nativeSoak) + "\n" + soakAlone.describe());
	}

	/** The value modifier runs once per tick, at the native call, and the tank exactly where natively — never in the hook again. */
	@Test void theGuestLinesAreTheNativeOnes() {
		for (WeaveHarness.Result run : List.of(gillFirst, soakFirst))
			for (String test : CASES) assertEquals(guest(natively, test), guest(run, test), test + "\n" + run.describe());
		for (String test : CASES) assertEquals(guest(nativeSoak, test), guest(soakAlone, test), test + "\n" + soakAlone.describe());
		for (WeaveHarness.Result run : List.of(soakAlone, gillFirst, soakFirst))
			for (String test : CASES) assertEquals(1, guest(run, test).stream().filter(l -> l.equals("reed:soak")).count(), test + "\n" + run.describe());
	}

	/** The pool guest alone gets an island for its head check, read by the hook; beside the tank it joins the tank's island. */
	@Test void theHookReadsTheIslandsAnswer() throws Exception {
		assertTrue(soakAlone.printed("[Forbric/Mixin] org.marsh.reed.mixin.SoakMixin.reed$soak: net.minecraft.world.entity.LivingEntity.isEyeInFluid is asked "
				+ "in net.minecraft.world.entity.LivingEntity.baseTick again"), soakAlone.describe());
		for (WeaveHarness.Result run : List.of(soakAlone, gillFirst, soakFirst)) {
			assertTrue(run.printed(WeaveHarnessMain.DONE), run.describe());
			WeaveHarness.assertWovenAndVerified(run, "net/minecraft/world/entity/LivingEntity", fixture);
			WeaveHarness.assertWovenAndVerified(run, "net/neoforged/neoforge/common/CommonHooks", fixture);
		}
	}

	/** Without an island the modifier moves into the hook's predicate copy, as before, and the pool is still decided natively. */
	@Test void withoutAnIslandTheModifierMovesIntoTheCopy() {
		assertEquals(outcomes(nativeSoak), outcomes(islandOff), trace(islandOff) + "\n" + islandOff.describe());
	}

	/** The control: with the reader off, a body that breathes under water drowns in the pool while another guest's island holds the head check. */
	@Test void theControlFlipsTheAssertions() {
		assertEquals("consume", outcomes(readerOff).get("pool-breather"), readerOff.describe());
		assertNotEquals(outcomes(natively), outcomes(readerOff), readerOff.describe());
	}

	private static Map<String, String> outcomes(WeaveHarness.Result run) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String test : CASES) {
			List<String> lines = lines(run, test);
			String outcome = lines.contains("nativeConsume") || lines.stream().anyMatch(l -> l.startsWith("event:false")) ? "consume"
					: lines.contains("nativeHold") || lines.contains("event:true:0") ? "hold"
					: lines.contains("nativeRefill") || lines.stream().anyMatch(l -> l.startsWith("event:true:") && !l.equals("event:true:0")) ? "refill" : "?";
			out.put(test, outcome);
		}
		return out;
	}

	private static String trace(WeaveHarness.Result run) {
		return run.label() + ": " + run.output().lines().filter(l -> l.startsWith("[CrossHostReader] ") || l.contains("was not executed")).toList();
	}

	private static List<String> guest(WeaveHarness.Result run, String test) {
		return lines(run, test).stream().filter(l -> l.startsWith("reed:") || l.startsWith("gill:")).toList();
	}

	private static List<String> lines(WeaveHarness.Result run, String test) {
		String line = run.output().lines().filter(l -> l.startsWith("[CrossHostReader] ")).findFirst().orElse("");
		for (String step : line.substring(Math.min(line.length(), "[CrossHostReader] ".length())).split(" \\| "))
			if (step.startsWith(test + ": ")) return Arrays.asList(step.substring(test.length() + 2).split(", "));
		return List.of();
	}

	private static WeaveHarness.Result run(Path jar, String label, List<String> configs, Map<String, String> properties) throws Exception {
		List<WeaveHarness.Config> list = new ArrayList<>();
		for (String config : configs) list.add(new WeaveHarness.Config(config, config.equals(SOAK) ? "reed" : "gill", Ecosystem.FABRIC));
		return WeaveHarness.run(work, label, jar, list, List.of(CrossHostPredicateIslandInjector.class), EnvType.SERVER,
				"fixture.crosshostreader.Probe", "probe", properties);
	}
}
