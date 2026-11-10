/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.boot.KernelLifecycle;
import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A NeoForge mod's mixin on {@code GameData.postRegisterEvents} runs, through the real weave and the kernel's own
 * registration step ({@code KernelLifecycle.dispatchRegisterEvents}): its HEAD injector once per boot, and
 * supermartijn642corelib's shape — a {@code @ModifyVariable} on the event local and an {@code @Inject} after
 * {@code ModLoader.postEventWrapContainerInModOrder} — once per registry, right after that registry's dispatch.
 *
 * <p>The fixture's {@code GameData} is NeoForge 26.2.0.93's method statement for statement over stand-in types. The
 * kernel used to post RegisterEvent itself and never called the method, so neither handler ran (K3: corelib's
 * {@code @RegistryEntryAcceptor} fields stayed null and packedup's client died at startup). The trace also pins what the
 * kernel's seam keeps: each registry reaches NeoForge's baseline and every mod in ModList order with the receiving
 * container active, a mod whose listener throws loses that registry and nobody else does (NeoForge's own fan-out would
 * stop there and roll every registry back), the Fabric step runs once where the loop ends, and each tail step runs once,
 * in NeoForge's order — one that throws costs only itself.
 */
class RegisterEventsSeamWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/registerevents");
	private static final String CONFIG = "registerevents.mixins.json";
	private static final String MOD = "registerevents";
	private static final String REGISTRY = String.join(",",
			"mixin:head",
			"neoforge:a", "first:a", "thrower:a", "last:a", "mixin:after:a",
			"neoforge:b", "first:b", "last:b", "mixin:after:b",
			"neoforge:c", "first:c", "thrower:c", "last:c", "mixin:after:c",
			"kernel:fabric-mains", "DefaultAttributes:init", "tail:modifyAttributes");
	private static final String FAILED_ON_B = "[Forbric/Lifecycle] a NeoForge mod's RegisterEvent listener failed on b";

	@TempDir static Path work;
	private static WeaveHarness.Result seam;
	private static WeaveHarness.Result spawnThrows;

	@BeforeAll static void weaveAll() throws Exception {
		Path fixture = WeaveHarness.fixture(work, MOD, sources(), Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		seam = run(fixture, "seam", "false");
		spawnThrows = run(fixture, "spawn-throws", "true");
	}

	@Test void theGuestsMixinRunsOnceAtHeadAndOncePerRegistry() {
		String trace = trace(seam);
		assertTrue(trace.startsWith("posted=3 " + REGISTRY + ","), seam.describe());
		assertEquals(1, occurrences(trace, "mixin:head"), "HEAD runs exactly once per boot — " + seam.describe());
		assertEquals(3, occurrences(trace, "mixin:after:"), seam.describe());
		assertEquals(0, occurrences(trace, "native-dispatch"), "the kernel's delivery, not NeoForge's unguarded one");
		assertEquals(0, occurrences(trace, "(not active)") - occurrences(trace, "BlockEntityTypeAddBlocksEvent(not active)"),
				"each RegisterEvent reaches its container with that container active — " + seam.describe());
		assertTrue(seam.printed(FAILED_ON_B), seam.describe());
		assertTrue(!trace.contains("revertToVanilla"), "one mod's failure rolls nothing back — " + seam.describe());
	}

	@Test void theTailRunsOnceInNeoForgesOrder() {
		String trace = trace(seam);
		List<String> tail = Arrays.stream(trace.split(",")).filter(s -> s.startsWith("tail:")).toList();
		assertEquals(List.of("tail:modifyAttributes", "tail:fireSpawnPlacementEvent", "tail:sortTabs",
				"tail:registerModdedCategories"), tail, seam.describe());
		assertEquals(4, occurrences(trace, ":BlockEntityTypeAddBlocksEvent"), "BlockEntityTypeAddBlocksEvent to each of four "
				+ "containers — " + seam.describe());
		assertEquals(1, occurrences(trace, "kernel:fabric-mains"), seam.describe());
		assertTrue(seam.printed("[Forbric/Lifecycle] fired RegisterEvent x3 on 4 bus(es) [NeoForge baseline + 3 mod(s)]"),
				seam.describe());
	}

	@Test void aTailStepThatThrowsCostsOnlyItself() {
		String trace = trace(spawnThrows);
		assertTrue(trace.startsWith("posted=3 " + REGISTRY + ","), spawnThrows.describe());
		assertTrue(spawnThrows.printed("[Forbric/Lifecycle] net.minecraft.world.entity.SpawnPlacements.fireSpawnPlacementEvent() "
				+ "THREW"), spawnThrows.describe());
		List<String> tail = Arrays.stream(trace.split(",")).filter(s -> s.startsWith("tail:")).toList();
		assertEquals(List.of("tail:modifyAttributes", "tail:sortTabs", "tail:registerModdedCategories"), tail,
				spawnThrows.describe());
	}

	private static WeaveHarness.Result run(Path fixture, String label, String spawnThrowsProperty) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.NEOFORGE, EnvType.SERVER,
				"fixture.registerevents.Probe", "run",
				Map.of(KernelLifecycle.NEO_TOOLTIP_APPENDERS, "off", "fixture.registerevents.spawnThrows", spawnThrowsProperty));
	}

	private static String trace(WeaveHarness.Result run) {
		return run.output().lines().filter(l -> l.startsWith(WeaveHarnessMain.DONE)).findFirst()
				.map(l -> l.substring(WeaveHarnessMain.DONE.length() + 1)).orElse("(probe did not return) " + run.describe());
	}

	private static int occurrences(String text, String part) {
		int n = 0;
		for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) n++;
		return n;
	}

	private static List<Path> sources() throws Exception {
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}
}
