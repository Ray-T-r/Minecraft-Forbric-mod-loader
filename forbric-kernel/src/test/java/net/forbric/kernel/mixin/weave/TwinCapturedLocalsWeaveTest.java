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
import net.forbric.kernel.mixin.MixinTwinRebind;

/**
 * MixinTwinRebind on an injector that captures locals the old way, through the real Mixin and MixinExtras weave. The game
 * classes have the shape of carrier-twins.txt's {@code ServerPlayerGameMode.removeBlock} row (stand-ins, no game code); the
 * guest {@code org.rockfall} shares no name with any mod.
 *
 * <p>The guest's loader removes a block in {@code removeBlock(pos, canHarvest)}, which reads the state itself; the guest
 * captures that state and the removal's answer right before {@code Block.destroy}. As merged, that method is kept but nothing
 * calls it: {@code destroyBlock} hands the other carrier's {@code removeBlock(pos, state, canHarvest, tool)} the state on both
 * paths. Moved, the guest runs on that overload, before the destroy, on both paths, handed the position and the harvest flag
 * where the row says and the state and the answer where the overload keeps them — every line of the native run. Switched off,
 * it stays on the method nothing calls, never runs, and is reported as never running.
 */
class TwinCapturedLocalsWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/twinlocals");
	private static final String CONFIG = "twinlocals.mixins.json";
	private static final String MOD = "rockfall";
	private static final String TARGET = "net/minecraft/server/level/ServerPlayerGameMode";
	private static final String NATIVE = "survival: event p1, collapse p1 stone@p1 harvest true removed true, destroy stone@p1, broke true"
			+ " | creative: event p2, collapse p2 stone@p2 harvest false removed true, destroy stone@p2, broke true";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, off, natively;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = new ArrayList<>(walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList());
		}
		assertEquals(10, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("net/minecraft/server/level/ServerPlayerGameMode.java"));
		nativeSources.add(SOURCES.resolve("native/net/minecraft/server/level/ServerPlayerGameMode.java"));
		Path nativeFixture = WeaveHarness.fixture(work, "native", nativeSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		fixture = WeaveHarness.fixture(work, "twinlocals", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		moved = run(fixture, "moved", Map.of());
		off = run(fixture, "off", Map.of(MixinTwinRebind.PROPERTY, "off"));
		natively = run(nativeFixture, "native", Map.of());
	}

	@Test void theCaptureRunsOnTheCalledOverloadWithTheNativeValues() throws Exception {
		assertEquals(NATIVE, probe(natively), natively.describe());
		assertEquals(NATIVE, probe(moved), moved.describe());
		assertTrue(moved.printed("[Forbric/Mixin] org.rockfall.mixin.GameModeMixin: rockfall$collapse now targets "
				+ "net.minecraft.server.level.ServerPlayerGameMode.removeBlock(Lnet/minecraft/core/BlockPos;"
				+ "Lnet/minecraft/world/level/block/state/BlockState;ZLnet/minecraft/world/item/ItemStack;)Z"), moved.describe());
		assertTrue(findings(moved).isEmpty(), moved.findings().toString());
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	/** Left on the method nothing calls, the injector binds, never runs, and is said to never run. */
	@Test void switchedOffItNeverRunsAndIsReported() {
		assertEquals("survival: event p1, destroy stone@p1, broke true | creative: event p2, destroy stone@p2, broke true", probe(off),
				off.describe());
		assertTrue(findings(off).stream().anyMatch(f -> f.toString().toLowerCase().contains("never")), off.findings().toString());
	}

	@Test void theControlFlipsTheMainAssertion() {
		assertNotEquals(probe(off), probe(moved));
	}

	private static String probe(WeaveHarness.Result run) {
		String line = run.output().lines().filter(l -> l.startsWith(WeaveHarnessMain.DONE + " ")).findFirst().orElse("");
		return line.substring(Math.min(line.length(), WeaveHarnessMain.DONE.length() + 1));
	}

	private static List<WeaveHarness.Finding> findings(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:") && f.modId().equals(MOD) && f.id().contains("rockfall$collapse"))
				.toList();
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FORGE, EnvType.SERVER, "fixture.twinlocals.Probe", "probe", properties);
	}
}
