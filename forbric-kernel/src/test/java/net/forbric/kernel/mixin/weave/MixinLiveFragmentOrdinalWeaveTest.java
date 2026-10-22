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

/**
 * A numbered {@code @Inject} point in a body nothing calls follows its occurrence into the live verbatim piece that carries
 * it, through the real Mixin weave, on classes that share no name with any game, loader or mod.
 *
 * <p>The stand-in game class {@code Furnace}, as the guest's loader has it, burns by firing — reading the gauge twice — and then
 * leaving ash. As merged, {@code fire} is kept whole and called by nothing: the carrier runs its first half ({@code kindle})
 * and its second ({@code cool}) as stages of its own, with its {@code ash} stage between them. The guest {@code io.wren.soot}
 * hooks the first and the second reading of the gauge in {@code fire} ({@code ordinal = 0}, {@code ordinal = 1}).
 *
 * <p>Moved, each hook runs before its own reading, once per burn: the first in {@code kindle}, the second in {@code cool},
 * where that reading is the piece's first (its point renumbered 1 → 0) — Apoli's power bar at the first
 * {@code getPlayerMode()} of {@code Hud.extractHotbarAndDecorations}, the first reading in NeoForge's hotbar layer, is the
 * case. Switched off, both stay bound in {@code fire} and never run.
 */
class MixinLiveFragmentOrdinalWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/livefragment");
	private static final String CONFIG = "livefragment.mixins.json";
	private static final String TARGET = "net/minecraft/fixture/kiln/Furnace";
	private static final String NATIVE = "[first:oak, a:oak, blow, second:oak, c:oak, first:coal, a:coal, blow, second:coal, c:coal]";
	private static final String UNHEARD = "[a:oak, blow, c:oak, a:coal, blow, c:coal]";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, off, natively;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = new ArrayList<>(walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList());
		}
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("net/minecraft/fixture/kiln/Furnace.java"));
		nativeSources.add(SOURCES.resolve("native/net/minecraft/fixture/kiln/Furnace.java"));
		Path nativeFixture = WeaveHarness.fixture(work, "native", nativeSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "livefragment", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)),
				List.of("-g")), NativeWeaveReferences.classes(nativeFixture));
		// The row this stand-in game's own census would write: nothing calls fire, which the native burn calls.
		Path uncalled = work.resolve("uncalled-extra.txt");
		Files.writeString(uncalled, TARGET + "#fire(Ljava/lang/String;)V | FABRIC | " + TARGET + "#burn(Ljava/lang/String;)V\n");
		moved = run(fixture, "moved", Map.of(MergedBaseUncalledMethods.EXTRA_PROPERTY, uncalled.toString()));
		off = run(fixture, "off", Map.of(MergedBaseUncalledMethods.EXTRA_PROPERTY, uncalled.toString(),
				"forbric.mixinRetarget.executionPaths", "off"));
		natively = run(nativeFixture, "native", Map.of());
	}

	@Test void natively() throws Exception {
		assertEquals(NATIVE, withoutAsh(log(natively)), natively.describe());
	}

	@Test void eachHookFollowsItsOwnReadingIntoTheLivePiece() throws Exception {
		assertEquals(NATIVE, withoutAsh(log(moved)), "each hook before its own reading, once per burn — " + moved.describe());
		assertEquals(2, log(moved).stream().filter(e -> e.startsWith("ash:")).count(), moved.describe());
		assertTrue(moved.printed("fire → kindle(Ljava/lang/String;)V"), moved.describe());
		assertTrue(moved.printed("fire → cool(Ljava/lang/String;)V"), moved.describe());
		assertTrue(moved.printed("Lnet/minecraft/fixture/kiln/Gauge;mode()I#1 → 0"), "the second reading is cool's first — " + moved.describe());
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	@Test void switchedOffBothHooksAreNeverHeard() throws Exception {
		assertEquals(UNHEARD, withoutAsh(log(off)), off.describe());
		WeaveHarness.assertWovenAndVerified(off, TARGET, fixture);
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, "soot", Ecosystem.FABRIC, EnvType.CLIENT, "io.wren.soot.Probe", "probe", properties);
	}

	private static List<String> log(WeaveHarness.Result run) {
		for (String line : run.output().split("\n")) {
			int at = line.indexOf("[LiveFragment] log=[");
			if (at < 0) continue;
			String body = line.substring(at + "[LiveFragment] log=[".length(), line.lastIndexOf(']'));
			return body.isEmpty() ? List.of() : List.of(body.split(", "));
		}
		throw new AssertionError("no log printed — " + run.describe());
	}

	private static String withoutAsh(List<String> log) {
		return log.stream().filter(e -> !e.startsWith("ash:")).toList().toString();
	}
}
