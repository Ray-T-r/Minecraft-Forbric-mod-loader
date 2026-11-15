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
 * container active, the Fabric step runs once where the loop ends, and each tail step runs once, in NeoForge's order — one
 * that throws costs only itself.
 *
 * <p>And what it no longer keeps (owner ruling D82, "stop like native"): a mod whose RegisterEvent listener throws, or a
 * guest handler that throws inside NeoForge's per-registry try, stops the boot as native NeoForge 26.2.0.93 does — the
 * registry's dispatch ends at that mod, NeoForge rolls every registry back to vanilla and throws, its {@code ModLoader}
 * turns that into the mod-loading error the boot ends on, and neither the Fabric step nor the tail runs. The kernel used to
 * contain the listener (that mod lost the registry, everyone else got it) and to boot on after a roll-back.
 */
class RegisterEventsSeamWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/registerevents");
	private static final String CONFIG = "registerevents.mixins.json";
	private static final String MOD = "registerevents";
	private static final String REGISTRY = String.join(",",
			"mixin:head",
			"neoforge:a", "first:a", "thrower:a", "last:a", "mixin:after:a",
			"neoforge:b", "first:b", "thrower:b", "last:b", "mixin:after:b",
			"neoforge:c", "first:c", "thrower:c", "last:c", "mixin:after:c",
			"kernel:fabric-mains", "DefaultAttributes:init", "tail:modifyAttributes");
	private static final String FAILED_ON_B = "[Forbric/Lifecycle] a NeoForge mod's RegisterEvent listener failed on b";
	/** What the kernel says when NeoForge's registration failed and the boot stops (RegistrationStop). */
	private static final String STOPS = "the boot stops here, as native NeoForge stops it";

	@TempDir static Path work;
	private static WeaveHarness.Result seam;
	private static WeaveHarness.Result spawnThrows;
	private static WeaveHarness.Result guestThrows;
	private static WeaveHarness.Result listenerThrows;

	@BeforeAll static void weaveAll() throws Exception {
		Path fixture = WeaveHarness.fixture(work, MOD, sources(), Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		seam = run(fixture, "seam", "false", "false", "false");
		spawnThrows = run(fixture, "spawn-throws", "true", "false", "false");
		guestThrows = run(fixture, "guest-throws", "false", "true", "false");
		listenerThrows = run(fixture, "listener-throws", "false", "false", "true");
	}

	@Test void theGuestsMixinRunsOnceAtHeadAndOncePerRegistry() {
		String trace = trace(seam);
		assertTrue(trace.startsWith("posted=3 " + REGISTRY + ","), seam.describe());
		assertEquals(1, occurrences(trace, "mixin:head"), "HEAD runs exactly once per boot — " + seam.describe());
		assertEquals(3, occurrences(trace, "mixin:after:"), seam.describe());
		assertEquals(0, occurrences(trace, "native-dispatch"), "the kernel's delivery, not NeoForge's unguarded one");
		assertEquals(0, occurrences(trace, "(not active)") - occurrences(trace, "BlockEntityTypeAddBlocksEvent(not active)"),
				"each RegisterEvent reaches its container with that container active — " + seam.describe());
		assertTrue(!trace.contains("revertToVanilla"), seam.describe());
		assertTrue(!seam.printed(STOPS), seam.describe());
	}

	/**
	 * A mod whose RegisterEvent listener throws ends that registry's dispatch, as NeoForge's own fan-out ends it ("last" never
	 * gets b, nor does the guest's after-handler, which sits inside the same try), every later registry is still dispatched,
	 * then NeoForge rolls every registry back to vanilla and throws, and the kernel stops the boot with the error NeoForge's
	 * ModLoader builds for it ("Registry initialization", the init task CommonModLoader runs the method as). No Fabric step,
	 * no tail. Red on the containing kernel (2c231c9c): there "last" got b, the dispatch returned and the Fabric step ran.
	 */
	@Test void aThrowingRegisterEventListenerStopsTheBootAsNeoForgeDoes() {
		String trace = trace(listenerThrows);
		assertTrue(trace.startsWith("stopped=ModLoadingException: "), listenerThrows.describe());
		assertTrue(trace.contains("thrower encountered an error while dispatching net.neoforged.neoforge.registries.RegisterEvent"),
				"the error names the mod — " + listenerThrows.describe());
		assertEquals(List.of("mixin:head", "neoforge:a", "first:a", "thrower:a", "last:a", "mixin:after:a", "neoforge:b", "first:b",
				"neoforge:c", "first:c", "thrower:c", "last:c", "mixin:after:c", "revertToVanilla",
				"waitForTask:Registry initialization"), steps(trace), listenerThrows.describe());
		assertTrue(listenerThrows.printed(FAILED_ON_B), listenerThrows.describe());
		assertTrue(listenerThrows.printed("[Forbric/Lifecycle] NeoForge's GameData.postRegisterEvents failed for 1 registr(ies) "
				+ "and rolled every registry back to vanilla"), listenerThrows.describe());
		assertTrue(listenerThrows.printed(STOPS), listenerThrows.describe());
		assertTrue(!listenerThrows.printed("fired RegisterEvent x"), listenerThrows.describe());
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
		// ModLoader.postEvent walks ModList.sortedContainers phase by phase, none of them active: the tail's mod-bus event
		// goes the same way, not in the hash order of the kernel's published-mod map.
		assertEquals(List.of("neoforge:BlockEntityTypeAddBlocksEvent(not active)", "first:BlockEntityTypeAddBlocksEvent(not active)",
				"thrower:BlockEntityTypeAddBlocksEvent(not active)", "last:BlockEntityTypeAddBlocksEvent(not active)"),
				Arrays.stream(trace.split(",")).filter(s -> s.contains(":BlockEntityTypeAddBlocksEvent")).toList(),
				seam.describe());
	}

	/**
	 * A throw inside NeoForge's per-registry try but outside any mod's listener — here a guest handler woven after the
	 * dispatch call — is NeoForge's own: every registry still gets its event, then NeoForge rolls every registry back to
	 * vanilla and throws before its tail, and the boot stops as natively, the error naming the cause. No Fabric step, no
	 * tail. Red on 2c231c9c, which carried on after the roll-back and ran the Fabric step.
	 */
	@Test void aGuestThrowInsideNeoForgesTryStopsTheBootAsNeoForgeDoes() {
		String trace = trace(guestThrows);
		assertTrue(trace.startsWith("stopped=ModLoadingException: "), guestThrows.describe());
		assertTrue(trace.contains("the guest's handler failed on b"), "the error names the cause — " + guestThrows.describe());
		assertEquals(1, occurrences(trace, "revertToVanilla"), guestThrows.describe());
		assertEquals(List.of("mixin:after:a", "mixin:after:c"),
				Arrays.stream(trace.split(",")).filter(s -> s.startsWith("mixin:after:")).toList(), guestThrows.describe());
		assertEquals(3, occurrences(trace, "last:"), "every mod still gets every registry — " + guestThrows.describe());
		assertTrue(trace.endsWith(",revertToVanilla,waitForTask:Registry initialization"), guestThrows.describe());
		assertEquals(0, occurrences(trace, "kernel:fabric-mains"), "nothing of a mod runs after the roll-back — "
				+ guestThrows.describe());
		assertEquals(0, occurrences(trace, "tail:"), "NeoForge throws before its tail — " + guestThrows.describe());
		assertTrue(guestThrows.printed("[Forbric/Lifecycle] NeoForge's GameData.postRegisterEvents failed for 1 registr(ies) "
				+ "and rolled every registry back to vanilla"), guestThrows.describe());
		assertTrue(guestThrows.printed(STOPS), guestThrows.describe());
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

	private static WeaveHarness.Result run(Path fixture, String label, String spawnThrowsProperty, String guestThrowsProperty,
			String listenerThrowsProperty) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.NEOFORGE, EnvType.SERVER,
				"fixture.registerevents.Probe", "run",
				Map.of(KernelLifecycle.NEO_TOOLTIP_APPENDERS, "off", "fixture.registerevents.spawnThrows", spawnThrowsProperty,
						"fixture.registerevents.guestThrows", guestThrowsProperty,
						"fixture.registerevents.listenerThrows", listenerThrowsProperty));
	}

	/** The trace's steps, after the outcome (the {@code posted=}/{@code stopped=} part, which may contain commas). */
	private static List<String> steps(String trace) {
		int start = trace.indexOf("mixin:head");
		return start < 0 ? List.of() : Arrays.asList(trace.substring(start).split(","));
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
