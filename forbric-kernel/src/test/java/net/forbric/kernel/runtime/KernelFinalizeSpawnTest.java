/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Both families decide, NeoForge first; the mob is finalized once, with what the last family left, or not at all. */
class KernelFinalizeSpawnTest {
	@TempDir Path directory;

	@Test void bothFamiliesThenOneFinalizationWithMinecraftForgesValues() throws Exception {
		try (FinalizeSpawnFixture fixture = new FinalizeSpawnFixture(directory)) {
			Object forgeDifficulty = fixture.newDifficulty(), forgeData = fixture.newData();
			fixture.set("forge", (Consumer<Object>) event -> { set(event, "setDifficulty", forgeDifficulty); set(event, "setSpawnData", forgeData); });
			Object result = fixture.finalizeMobSpawn();
			assertEquals(List.of("neo", "forge", "finalize"), fixture.trace());
			assertEquals(1, fixture.count("finalizes"));
			assertSame(forgeDifficulty, fixture.value("finalDifficulty"), "MinecraftForge's listener chose the difficulty");
			assertSame(forgeData, fixture.value("finalData"), "and the spawn data the one finalization receives");
			assertSame(fixture.value("finalResult"), result, "finalizeSpawn's own answer is returned");
		}
	}

	@Test void minecraftForgeSeesWhatNeoForgesListenersLeft() throws Exception {
		try (FinalizeSpawnFixture fixture = new FinalizeSpawnFixture(directory)) {
			Object neoData = fixture.newData();
			fixture.set("neo", (Consumer<Object>) event -> set(event, "setSpawnData", neoData));
			fixture.finalizeMobSpawn();
			assertSame(neoData, fixture.eventValue(fixture.value("lastForge"), "getSpawnData"));
			assertSame(neoData, fixture.value("finalData"));
		}
	}

	@Test void aNeoForgeCancelSkipsMinecraftForgeAndTheFinalization() throws Exception {
		try (FinalizeSpawnFixture fixture = new FinalizeSpawnFixture(directory)) {
			fixture.set("neo", (Consumer<Object>) event -> set(event, "setCanceled", true));
			assertNull(fixture.finalizeMobSpawn());
			assertEquals(List.of("neo"), fixture.trace());
			assertEquals(0, fixture.count("finalizes"));
		}
	}

	@Test void aMinecraftForgeCancelSkipsTheFinalization() throws Exception {
		try (FinalizeSpawnFixture fixture = new FinalizeSpawnFixture(directory)) {
			fixture.set("forgeCanceled", true);
			assertNull(fixture.finalizeMobSpawn());
			assertEquals(List.of("neo", "forge"), fixture.trace());
			assertEquals(0, fixture.count("finalizes"));
		}
	}

	@Test void aNeoForgeVetoSurvivesAMinecraftForgeListenerClearingIt() throws Exception {
		try (FinalizeSpawnFixture fixture = new FinalizeSpawnFixture(directory)) {
			fixture.set("neo", (Consumer<Object>) event -> set(event, "setSpawnCancelled", true));
			fixture.set("forge", (Consumer<Object>) event -> set(event, "setSpawnCancelled", false));
			fixture.finalizeMobSpawn();
			assertTrue(fixture.vetoed(), "the world insertion stays vetoed");
			assertEquals(1, fixture.count("finalizes"), "a veto is not a cancel: the mob is still finalized");
		}
	}

	@Test void theTrialSpawnerPostsMinecraftForgeOnlyWhereItInitializes() throws Exception {
		try (FinalizeSpawnFixture fixture = new FinalizeSpawnFixture(directory)) {
			Object forgeData = fixture.newData();
			fixture.set("forge", (Consumer<Object>) event -> set(event, "setSpawnData", forgeData));
			Object event = fixture.finalizeTrialSpawner(true);
			assertEquals(List.of("neo", "forge", "finalize"), fixture.trace());
			assertEquals(1, fixture.count("finalizes"), "NeoForge's hook was asked not to initialize; the kernel finalizes once");
			assertSame(forgeData, fixture.value("finalData"));
			assertSame(forgeData, fixture.eventValue(event, "getSpawnData"), "the returned event carries what MinecraftForge left");
			assertNotNull(((Object) fixture.value("lastNeo").getClass().getField("spawner").get(fixture.value("lastNeo"))), "NeoForge's event names its spawner");
		}
		try (FinalizeSpawnFixture fixture = new FinalizeSpawnFixture(directory.resolve("uninitialized"))) {
			Object event = fixture.finalizeTrialSpawner(false);
			assertEquals(List.of("neo"), fixture.trace(), "no MinecraftForge event where vanilla does not initialize");
			assertEquals(0, fixture.count("finalizes"));
			assertFalse(fixture.canceled(event));
		}
	}

	@Test void aMinecraftForgeCancelAtTheTrialSpawnerCancelsNeoForgesEvent() throws Exception {
		try (FinalizeSpawnFixture fixture = new FinalizeSpawnFixture(directory)) {
			fixture.set("forgeCanceled", true);
			Object event = fixture.finalizeTrialSpawner(true);
			assertTrue(fixture.canceled(event));
			assertEquals(0, fixture.count("finalizes"));
		}
		try (FinalizeSpawnFixture fixture = new FinalizeSpawnFixture(directory.resolve("neo-cancel"))) {
			fixture.set("neo", (Consumer<Object>) event -> set(event, "setCanceled", true));
			Object event = fixture.finalizeTrialSpawner(true);
			assertTrue(fixture.canceled(event));
			assertEquals(List.of("neo"), fixture.trace());
			assertEquals(0, fixture.count("finalizes"));
		}
	}

	private static void set(Object event, String setter, Object value) {
		try {
			for (var method : event.getClass().getMethods()) {
				if (method.getName().equals(setter) && method.getParameterCount() == 1) { method.invoke(event, value); return; }
			}
			throw new AssertionError(setter);
		} catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}
}
