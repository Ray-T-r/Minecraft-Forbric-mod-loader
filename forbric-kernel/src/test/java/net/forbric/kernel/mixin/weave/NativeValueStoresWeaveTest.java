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
import net.forbric.kernel.mixin.NativeValueStores;

/**
 * {@code NativeValueStores} through the real Mixin and MixinExtras weave, on classes that share no name with any game, loader
 * or mod.
 *
 * <p>Natively {@code org.pottery.kiln.Kiln.fire} stores the baked pot in its one {@code Pot} local and shelves it. As merged, the
 * carrier first keeps a raw copy in a new first {@code Pot} local, then stores what its own firing hook answers for the baked
 * pot in a second one. The guest {@code io.glazer} remembers the raw pot (a {@code @Share}) right before the bake and glazes
 * the pot at its {@code STORE} by {@code ordinal = 0}, reading what it remembered.
 *
 * <p>As compiled, the glaze binds the copy's store — before the bake, before the {@code @Share} is filled — and throws on its
 * {@code null}, as PuzzlesLib's use-item handler did on every meal. Moved, it glazes the baked pot once, after the carrier's
 * hook and after the remembering, and the glazed pot is shelved: every line the native run prints, with the hook's between.
 */
class NativeValueStoresWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/valuestores");
	private static final String CONFIG = "valuestores.mixins.json";
	private static final String MOD = "glazer";
	private static final String TARGET = "org/pottery/kiln/Kiln";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, off, natively;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = new ArrayList<>(walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList());
		}
		assertEquals(6, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("org/pottery/kiln/Kiln.java"));
		nativeSources.add(SOURCES.resolve("native/org/pottery/kiln/Kiln.java"));
		Path nativeFixture = WeaveHarness.fixture(work, "native", nativeSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "valuestores", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)),
				List.of("-g")), NativeWeaveReferences.classes(nativeFixture));
		moved = run(fixture, "moved", Map.of());
		off = run(fixture, "off", Map.of(NativeValueStores.PROPERTY, "off"));
		natively = run(nativeFixture, "native", Map.of());
	}

	@Test void theGlazeRunsOnTheStoreOfTheBakedPotAfterTheRemembering() throws Exception {
		assertEquals("red: fired red into baked-red, glaze baked-red from red, shelf glazed-baked-red | white: fired white into baked-white,"
				+ " glaze baked-white from white, shelf glazed-baked-white", probe(moved), moved.describe());
		assertTrue(moved.printed("[Forbric/Mixin] io.glazer.mixin.KilnMixin.glazer$glaze: the merged org.pottery.kiln.Kiln.fire stores another"
				+ " value first in the local its STORE picks; the native value is stored in local 2"), moved.describe());
		assertTrue(losses(moved).isEmpty(), moved.findings().toString());
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	/** Every line of the native run, in its order, and the carrier's hook between: the guest sees the native value. */
	@Test void theNativeOutcomeWithTheCarriersHookBetween() {
		assertEquals("red: glaze baked-red from red, shelf glazed-baked-red | white: glaze baked-white from white, shelf glazed-baked-white",
				probe(natively), natively.describe());
		assertEquals(probe(natively), probe(moved).replaceAll("fired [a-z]+ into baked-[a-z]+, ", ""));
	}

	@Test void switchedOffTheGlazeBindsTheCopyAndThrows() {
		assertEquals("red: threw NullPointerException: raw pot is null | white: threw NullPointerException: raw pot is null", probe(off),
				off.describe());
	}

	@Test void theControlFlipsTheMainAssertion() {
		assertNotEquals(probe(off), probe(moved));
	}

	private static String probe(WeaveHarness.Result run) {
		String line = run.output().lines().filter(l -> l.startsWith(WeaveHarnessMain.DONE + " ")).findFirst().orElse("");
		return line.substring(Math.min(line.length(), WeaveHarnessMain.DONE.length() + 1));
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:") && f.modId().equals(MOD) && f.required()).toList();
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.valuestores.Probe", "probe", properties);
	}
}
