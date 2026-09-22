/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Native event-hook bytecode is executed, rather than a mock that quietly initializes in the wrong phase. */
class KernelSpawnerFinalizeTest {
	@TempDir Path directory;

	@Test void actualCarrierHooksSeeNonNullEntityInputAndWriteDataBeforeOneFinalization() throws Exception {
		try (SpawnerFinalizeFixture f = fixture("data")) {
			Object neoData = f.data(), forgeData = f.data(), neoDifficulty = f.difficulty(), forgeDifficulty = f.difficulty();
			f.set("neo", (Consumer<Object>) event -> {
				set(event, "setSpawnData", neoData); set(event, "setDifficulty", neoDifficulty);
			});
			f.set("forge", (Consumer<Object>) event -> {
				try {
					assertEquals(0, f.count("finalizes"), "Forge must decide before any native finalization");
					assertSame(f.value("input"), get(event, "getSpawnTag"));
					assertSame(f.value("loadedInput"), get(event, "getSpawnTag"));
					assertNotNull(get(event, "getSpawnTag"));
					assertSame(neoData, get(event, "getSpawnData")); assertSame(neoDifficulty, get(event, "getDifficulty"));
					set(event, "setSpawnData", forgeData); set(event, "setDifficulty", forgeDifficulty);
				} catch (Exception failure) { throw new AssertionError(failure); }
			});
			f.tick();
			assertEquals(List.of("neo", "forge", "finalize"), f.trace());
			assertEquals(1, f.count("finalizes")); assertEquals(1, f.count("inserted"));
			assertSame(forgeData, f.value("finalData")); assertSame(forgeDifficulty, f.value("finalDifficulty"));
			assertSame(forgeData, get(f.value("lastNeo"), "getSpawnData"), "the event keeps the input data; native callers discard finalizeSpawn's result");
			assertNotSame(f.value("finalResult"), get(f.value("lastNeo"), "getSpawnData"));
			assertTrue(f.findings().isEmpty());
		}
	}

	@Test void nativeEventCancellationSkipsInitializationWithoutVetoingWorldInsertion() throws Exception {
		for (String side : List.of("neo", "forge")) {
			try (SpawnerFinalizeFixture f = fixture(side)) {
				if (side.equals("neo")) f.set("neo", (Consumer<Object>) event -> set(event, "setCanceled", true));
				else f.set("forgeCanceled", true);
				f.tick();
				assertEquals(0, f.count("finalizes")); assertEquals(1, f.count("insertAttempts")); assertEquals(1, f.count("inserted"));
				assertEquals(side.equals("neo") ? 0 : 1, f.count("forgePosts"));
				assertEquals(true, get(f.value("lastNeo"), "isCanceled"));
				assertEquals(false, get(f.value("mob"), "isSpawnCancelled"), "do not turn skip-finalize into forbid-spawn");
			}
		}
	}

	@Test void explicitSpawnCancellationFromEitherFamilyPreventsInsertionAndCannotBeRevived() throws Exception {
		for (String side : List.of("neo", "forge")) {
			try (SpawnerFinalizeFixture f = fixture(side)) {
				f.set(side, (Consumer<Object>) event -> set(event, "setSpawnCancelled", true));
				if (side.equals("neo")) f.set("forge", (Consumer<Object>) event -> set(event, "setSpawnCancelled", false));
				f.tick();
				assertEquals(1, f.count("finalizes"), "spawn veto must not suppress the initialization native callers still perform");
				assertEquals(1, f.count("forgePosts")); assertEquals(1, f.count("insertAttempts")); assertEquals(0, f.count("inserted"));
			}
		}
	}

	@Test void neoOnlySpawnVetoMatchesTheRealNativeHookAndDoesNotConsumeTheFinalizerReturn() throws Exception {
		List<Object> nativeOutcome;
		try (SpawnerFinalizeFixture f = fixture("native-veto")) {
			f.set("neo", (Consumer<Object>) event -> set(event, "setSpawnCancelled", true));
			f.nativeTick();
			assertEquals(0, f.count("forgePosts"));
			assertSame(f.value("originalData"), get(f.value("lastNeo"), "getSpawnData"));
			assertNotSame(f.value("finalResult"), get(f.value("lastNeo"), "getSpawnData"));
			nativeOutcome = List.of(f.count("finalizes"), f.count("insertAttempts"), f.count("inserted"),
					get(f.value("mob"), "isSpawnCancelled"), get(f.value("lastNeo"), "isCanceled"));
			assertEquals(List.of(1, 1, 0, true, false), nativeOutcome,
					"the actual Neo hook initializes once despite a separate world-insertion veto");
		}
		try (SpawnerFinalizeFixture f = fixture("bridged-veto")) {
			f.set("neo", (Consumer<Object>) event -> set(event, "setSpawnCancelled", true));
			f.tick();
			assertEquals(nativeOutcome, List.of(f.count("finalizes"), f.count("insertAttempts"), f.count("inserted"),
					get(f.value("mob"), "isSpawnCancelled"), get(f.value("lastNeo"), "isCanceled")));
			assertSame(f.value("originalData"), get(f.value("lastNeo"), "getSpawnData"));
			assertNotSame(f.value("finalResult"), get(f.value("lastNeo"), "getSpawnData"));
		}
	}

	@Test void customNbtFlagStillPostsBothEventsButNeverReinitializesAnAlreadyLoadedMob() throws Exception {
		try (SpawnerFinalizeFixture f = fixture("custom-nbt")) {
			f.set("initialize", false); f.tick();
			assertEquals(List.of("neo", "forge"), f.trace()); assertEquals(0, f.count("finalizes")); assertEquals(1, f.count("inserted"));
		}
	}

	@Test void listenerFailurePropagatesWithoutFinalizingOrInsertingTheMob() throws Exception {
		try (SpawnerFinalizeFixture f = fixture("failure")) {
			IllegalStateException failure = new IllegalStateException("mod callback"); f.set("forgeFailure", failure);
			assertSame(failure, assertThrows(IllegalStateException.class, f::tick));
			assertEquals(0, f.count("finalizes")); assertEquals(0, f.count("insertAttempts"));
		}
	}

	@Test void observedTagReplacementIsRecordedAsNativeBehaviorInsteadOfACompatibilityLoss() throws Exception {
		try (SpawnerFinalizeFixture f = fixture("tag")) {
			Object replacement = f.input(); f.set("forge", (Consumer<Object>) event -> set(event, "setSpawnTag", replacement)); f.tick();
			assertEquals(1, f.count("finalizes")); assertEquals(1, f.findings().size());
			Object finding = f.findings().getFirst();
			assertEquals("spawner-finalize-tag-replacement", get(finding, "id")); assertEquals(false, get(finding, "required"));
			assertEquals("RESOLVED", get(finding, "confidence").toString());
			assertNotSame(replacement, f.value("loadedInput"), "do not invent a second entity-load pass for a native-unused setter");
		}
	}

	@Test void missingValueInputDoesNotFabricateAForgeEventAndReportsTheConfirmedLimitation() throws Exception {
		try (SpawnerFinalizeFixture f = fixture("missing")) {
			f.set("input", null); f.tick();
			assertEquals(0, f.count("forgePosts")); assertEquals(1, f.count("finalizes"), "the supported Neo path remains native");
			assertEquals(1, f.findings().size()); Object finding = f.findings().getFirst();
			assertEquals("spawner-finalize-input", get(finding, "id")); assertEquals(true, get(finding, "required"));
		}
	}

	private SpawnerFinalizeFixture fixture(String name) throws Exception { return new SpawnerFinalizeFixture(directory.resolve(name)); }
	private static Object get(Object value, String method) {
		try { return value.getClass().getMethod(method).invoke(value); }
		catch (Exception failure) { throw new AssertionError(failure); }
	}
	private static void set(Object value, String method, Object argument) {
		try { Arrays.stream(value.getClass().getMethods()).filter(m -> m.getName().equals(method) && m.getParameterCount() == 1).findFirst().orElseThrow().invoke(value, argument); }
		catch (Exception failure) { throw new AssertionError(failure); }
	}
}
