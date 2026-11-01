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
 * classes have the shape of carrier-twins.txt's {@code ModelBlockRenderer.shouldRenderFace} row on NeoForge's base (stand-ins,
 * no game code); the guest {@code org.facet} shares no name with any mod.
 *
 * <p>The guest's loader asks vanilla's {@code shouldRenderFace(level, state, direction, neighborPos)} for every face; the guest
 * captures there, at the neighbour read, and shows every top face. NeoForge's base keeps that method but nothing calls it: the
 * draw asks NeoForge's {@code shouldRenderFace(level, pos, state, direction, neighborPos)}, declared first, so the guest's
 * bare name binds an overload whose arguments are not the ones it takes. Moved, the guest runs on NeoForge's overload at the
 * same read, handed vanilla's four arguments out of NeoForge's five (the row's 0,2,3,4), and an unculled draw, which returns
 * before the read, never asks it — every line of the native run. Switched off, it stays on the method nothing calls, never
 * runs, and is reported as never running.
 *
 * <p>No row of NeoForge's table has a call both bodies make once with a local of vanilla's live beyond its arguments (the
 * merged base's MinecraftForge {@code removeBlock} row did), so the capture here holds the arguments alone.
 */
class TwinCapturedLocalsWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/twinlocals");
	private static final String CONFIG = "twinlocals.mixins.json";
	private static final String MOD = "facet";
	private static final String TARGET = "net/minecraft/client/renderer/block/ModelBlockRenderer";
	private static final String NATIVE = "culled: peek stone@p1 UP toward p11, draw UP, peek stone@p1 NORTH toward p12, draw NORTH"
			+ " | unculled: draw UP, draw NORTH";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, off, natively;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = new ArrayList<>(walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList());
		}
		assertEquals(11, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("net/minecraft/client/renderer/block/ModelBlockRenderer.java"));
		nativeSources.add(SOURCES.resolve("native/net/minecraft/client/renderer/block/ModelBlockRenderer.java"));
		Path nativeFixture = WeaveHarness.fixture(work, "native", nativeSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		fixture = WeaveHarness.fixture(work, "twinlocals", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		moved = run(fixture, "moved", Map.of());
		off = run(fixture, "off", Map.of(MixinTwinRebind.PROPERTY, "off"));
		natively = run(nativeFixture, "native", Map.of());
	}

	@Test void theCaptureRunsOnTheCalledOverloadWithTheNativeValues() throws Exception {
		assertEquals(NATIVE, probe(natively), natively.describe());
		assertEquals(NATIVE, probe(moved), moved.describe());
		assertTrue(moved.printed("[Forbric/Mixin] org.facet.mixin.RendererMixin: facet$peek now targets "
				+ "net.minecraft.client.renderer.block.ModelBlockRenderer.shouldRenderFace("
				+ "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
				+ "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;)Z"),
				moved.describe());
		assertTrue(findings(moved).isEmpty(), moved.findings().toString());
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	/** Left on the method nothing calls, the injector binds, never runs, and is said to never run. */
	@Test void switchedOffItNeverRunsAndIsReported() {
		assertEquals("culled: draw NORTH | unculled: draw UP, draw NORTH", probe(off),
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
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:") && f.modId().equals(MOD) && f.id().contains("facet$peek"))
				.toList();
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.CLIENT, "fixture.twinlocals.Probe", "probe", properties);
	}
}
