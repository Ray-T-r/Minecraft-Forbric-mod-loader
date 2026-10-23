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
import net.forbric.kernel.mixin.UncalledDecisionEntries;

/**
 * {@code UncalledDecisionEntries} through the real Mixin and MixinExtras weave, on classes that share no name with any game,
 * loader or mod.
 *
 * <p>The stand-in game's {@code Weir}, as the guest's loader has it, decides which side of a neighbour water reaches by asking its
 * static {@code opens(stone, face)} — once directly and twice through {@code opens(stone)}, which forwards {@code (stone, null)}.
 * As merged, the carrier asks each neighbour's own {@code admits(this, face)} at those three places, with its branches laid out
 * its own way, and nothing calls {@code opens}. The guest {@code io.wren.latch} answers at {@code opens}' {@code HEAD} for its own
 * latches (open on every face but north) — the shape of Create's redstone-connection hook on
 * {@code RedStoneWireBlock.shouldConnectTo}.
 *
 * <p>Moved, the hook wraps every {@code admits} call of {@code side}, is handed the stone and the face {@code opens} was handed
 * there (null where the forwarder passed null), and its answer is the place's answer: every case reads and answers as natively.
 * Switched off, the hook binds {@code opens}, never runs, and a latch never opens.
 */
class UncalledDecisionEntriesWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/uncalleddecision");
	private static final String CONFIG = "uncalleddecision.mixins.json";
	private static final String TARGET = "net/minecraft/fixture/weir/Weir";
	private static final String STONE = "Lnet/minecraft/fixture/weir/Stone;", FACE = "Lnet/minecraft/fixture/weir/Face;";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, off, natively;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = new ArrayList<>(walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList());
		}
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("net/minecraft/fixture/weir/Weir.java"));
		nativeSources.add(SOURCES.resolve("native/net/minecraft/fixture/weir/Weir.java"));
		Path nativeFixture = WeaveHarness.fixture(work, "native", nativeSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "uncalleddecision", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)),
				List.of("-g")), NativeWeaveReferences.classes(nativeFixture));
		// The rows this stand-in game's own census would write: nothing calls either opens; natively side calls both, and the
		// forwarder calls the decision.
		String side = TARGET + "#side(" + STONE + STONE + STONE + FACE + ")Ljava/lang/String;";
		Path uncalled = work.resolve("uncalled-extra.txt");
		Files.writeString(uncalled, TARGET + "#opens(" + STONE + ")Z | FABRIC | " + side + "\n"
				+ TARGET + "#opens(" + STONE + FACE + ")Z | FABRIC | " + side + " " + TARGET + "#opens(" + STONE + ")Z | " + TARGET + "#opens(" + STONE + ")Z\n");
		moved = run(fixture, "moved", Map.of(MergedBaseUncalledMethods.EXTRA_PROPERTY, uncalled.toString()));
		off = run(fixture, "off", Map.of(MergedBaseUncalledMethods.EXTRA_PROPERTY, uncalled.toString(), UncalledDecisionEntries.PROPERTY, "off"));
		natively = run(nativeFixture, "native", Map.of());
	}

	@Test void natively() {
		assertEquals("latch/clay/clay/EAST -> SIDE [latch clay/null, latch latch/EAST]"
				+ " | latch/clay/clay/NORTH -> NONE [latch clay/null, latch latch/NORTH, latch clay/null]"
				+ " | clay/latch/clay/EAST -> SIDE [latch latch/null]"
				+ " | rill/clay/latch/WEST -> SIDE [latch clay/null, latch rill/WEST]"
				+ " | latch/clay/latch/NORTH -> SIDE [latch clay/null, latch latch/NORTH, latch latch/null]"
				+ " | gate/clay/clay/NORTH -> SIDE [latch clay/null, latch gate/NORTH]"
				+ " | rill/clay/clay/SOUTH -> SIDE [latch clay/null, latch rill/SOUTH]", cases(natively), natively.describe());
	}

	@Test void theHookWrapsTheCarriersDecisionWhereverTheNativeWeirAskedItsOwn() throws Exception {
		assertEquals(cases(natively), cases(moved), "every place asks the hook as natively, with the native stone and face, and its answer stands — "
				+ moved.describe());
		assertTrue(moved.printed("io.wren.latch.mixin.WeirMixin.latch$opens: net.minecraft.fixture.weir.Weir.opens is no longer called; the carrier decides with "
				+ "net.minecraft.fixture.weir.Stone.admits in side where the native side asked it — the HEAD injector now wraps that call (3 place(s))"), moved.describe());
		assertTrue(moved.findings().stream().noneMatch(f -> f.modId().equals("latch") && f.required()), moved.findings().toString());
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	@Test void switchedOffTheHookNeverRunsAndALatchNeverOpens() throws Exception {
		assertEquals("latch/clay/clay/EAST -> NONE []", cases(off).split(" \\| ")[0], off.describe());
		assertFalse(cases(off).contains("latch "), "the hook is bound to opens, which nothing calls — " + off.describe());
		assertNotEquals(cases(natively), cases(off));
	}

	private static String cases(WeaveHarness.Result run) {
		for (String line : run.output().split("\n")) {
			int at = line.indexOf("[UncalledDecision] ");
			if (at >= 0) return line.substring(at + "[UncalledDecision] ".length()).trim();
		}
		throw new AssertionError("no cases printed — " + run.describe());
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, "latch", Ecosystem.FABRIC, EnvType.CLIENT, "io.wren.latch.Probe", "probe", properties);
	}
}
