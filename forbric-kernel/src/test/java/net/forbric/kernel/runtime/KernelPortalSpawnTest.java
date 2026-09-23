/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KernelPortalSpawnTest {
	@TempDir Path temporary;

	@Test void bothHooksPostOnceWithTheLegacyBridgeInstalledBeforeTheCall() throws Exception {
		try (PortalSpawnFixture f = fixture(false)) {
			f.installLegacyBridge(); Object shape = f.shape();
			assertEquals(Optional.of(shape), f.call(Optional.of(shape)));
			assertEquals(List.of("neo", "forge"), f.trace());
			assertEquals(1, f.count("neoCalls")); assertEquals(1, f.count("forgeCalls"));
			assertFalse(f.guarded());
			f.directNeo(Optional.of(shape));
			assertEquals(List.of("neo", "forge", "neo", "forge"), f.trace(), "direct event producers retain the legacy forward");
		}
	}

	@Test void transformedHookReturnContractCarriesReplacementThroughBothCallbacks() throws Exception {
		// These callbacks model mods transforming the hook. Native carrier events have no replacement setter.
		try (PortalSpawnFixture f = fixture(false)) {
			f.installLegacyBridge(); Object original = f.shape(), neo = f.shape(), forge = f.shape();
			f.set("neoResult", (UnaryOperator<Object>) input -> Optional.of(neo));
			AtomicReference<Object> forgeInput = new AtomicReference<>();
			f.set("forgeResult", (UnaryOperator<Object>) input -> { forgeInput.set(input); return Optional.of(forge); });
			Object result = f.call(Optional.of(original));
			assertEquals(Optional.of(neo), forgeInput.get());
			assertEquals(Optional.of(forge), result, "the final shape must be returned to the game's Optional consumer");
			assertEquals(List.of("neo", "forge"), f.trace());
		}
	}

	@Test void eitherRefusalIsFinalAndEmptyInputIsNotRevived() throws Exception {
		try (PortalSpawnFixture f = fixture(false)) {
			f.installLegacyBridge(); Object shape = f.shape();
			f.set("forgeResult", (UnaryOperator<Object>) input -> Optional.of(shape));
			f.set("neoCanceled", true);
			assertEquals(Optional.empty(), f.call(Optional.of(shape)));
			assertEquals(0, f.count("forgeCalls"));
			f.set("neoCanceled", false); f.set("forgeCanceled", true);
			assertEquals(Optional.empty(), f.call(Optional.of(shape)));
			assertEquals(1, f.count("forgeCalls"));
			assertEquals(Optional.empty(), f.call(Optional.empty()));
			assertEquals(2, f.count("neoCalls")); assertEquals(1, f.count("forgeCalls"));
		}
	}

	@Test void nestedWrapperCallsRestoreTheirOuterScopeAndDoNotDuplicateForwards() throws Exception {
		try (PortalSpawnFixture f = fixture(false)) {
			f.installLegacyBridge(); Object outer = f.shape(), inner = f.shape();
			f.set("nested", (Runnable) () -> {
				try {
					assertTrue(f.guarded());
					assertEquals(Optional.of(inner), f.call(Optional.of(inner)));
					assertTrue(f.guarded(), "leaving the inner call must restore the outer guard");
				} catch (Exception e) { throw new AssertionError(e); }
			});
			assertEquals(Optional.of(outer), f.call(Optional.of(outer)));
			assertEquals(List.of("neo", "neo", "forge", "forge"), f.trace());
			assertFalse(f.guarded());
		}
	}

	@Test void anotherProducersEventNestedOnTheSameThreadStillReachesMinecraftForge() throws Exception {
		try (PortalSpawnFixture f = fixture(false)) {
			f.installLegacyBridge(); Object outer = f.shape(), other = f.shape();
			AtomicReference<Object> nestedResult = new AtomicReference<>();
			f.set("nested", (Runnable) () -> {
				try {
					assertTrue(f.guarded(), "the wrapper's own dispatch is in progress on this thread");
					f.set("forgeCanceled", true);
					nestedResult.set(f.directNeo(Optional.of(other)));
					f.set("forgeCanceled", false);
				} catch (Exception failure) { throw new AssertionError(failure); }
			});
			assertEquals(Optional.of(outer), f.call(Optional.of(outer)));
			assertEquals(List.of("neo", "neo", "forge", "forge"), f.trace(),
					"a different producer's portal event is forwarded even while the wrapper dispatches its own");
			assertEquals(2, f.count("forgeCalls"));
			assertEquals(Optional.empty(), nestedResult.get(), "MinecraftForge's veto of the nested portal must carry");
			assertFalse(f.guarded());
		}
	}

	@Test void neoFailureAlwaysClearsScopeAndForgeFailureRetainsTheExistingFallback() throws Exception {
		try (PortalSpawnFixture f = fixture(false)) {
			f.installLegacyBridge(); Object shape = f.shape(); RuntimeException failure = new IllegalStateException("listener");
			f.set("neoFailure", failure);
			assertSame(failure, assertThrows(IllegalStateException.class, () -> f.call(Optional.of(shape))));
			assertFalse(f.guarded()); assertEquals(0, f.count("forgeCalls"));
			f.set("neoFailure", null); f.set("forgeFailure", failure);
			assertEquals(Optional.of(shape), f.call(Optional.of(shape)));
			assertFalse(f.guarded()); assertEquals(1, f.count("forgeCalls"));
			f.set("forgeFailure", null); f.directNeo(Optional.of(shape));
			assertEquals(2, f.count("forgeCalls"), "a failed prior dispatch must not mute later direct events");
		}
	}

	@Test void anotherThreadsDirectEventIsNotSuppressedByTheWrapperScope() throws Exception {
		try (PortalSpawnFixture f = fixture(false)) {
			f.installLegacyBridge(); Object shape = f.shape();
			f.set("nested", (Runnable) () -> CompletableFuture.runAsync(() -> {
				try {
					assertFalse(f.guarded()); f.directNeo(Optional.of(shape));
				} catch (Exception failure) { throw new AssertionError(failure); }
			}).join());
			assertEquals(Optional.of(shape), f.call(Optional.of(shape)));
			assertEquals(2, f.count("neoCalls")); assertEquals(2, f.count("forgeCalls"));
			assertFalse(f.guarded());
		}
	}

	@Test void unmodifiedCarrierHookBytecodeOnlyKeepsOrCancelsTheInputShape() throws Exception {
		try (PortalSpawnFixture f = fixture(true)) {
			f.installLegacyBridge(); Object original = f.shape(), hypothetical = f.shape();
			f.set("forgeResult", (UnaryOperator<Object>) input -> Optional.of(hypothetical));
			assertEquals(Optional.of(original), f.call(Optional.of(original)),
					"real carrier hook bytecode does not consult the synthetic replacement callback");
			assertEquals(List.of("neo", "forge"), f.trace());
			f.set("forgeCanceled", true);
			assertEquals(Optional.empty(), f.call(Optional.of(original)));
			f.set("forgeCanceled", false); f.set("neoCanceled", true);
			assertEquals(Optional.empty(), f.call(Optional.of(original)));
			assertEquals(3, f.count("neoCalls")); assertEquals(2, f.count("forgeCalls"));
		}
	}

	private PortalSpawnFixture fixture(boolean carriers) throws Exception { return new PortalSpawnFixture(temporary, carriers); }
}
