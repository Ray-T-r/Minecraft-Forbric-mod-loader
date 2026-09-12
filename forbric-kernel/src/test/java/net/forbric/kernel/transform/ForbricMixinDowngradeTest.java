/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.spongepowered.asm.mixin.injection.throwables.InjectionError;

/**
 * Pins {@link ForbricMixinDowngrade}'s SCOPE, which is the half that matters.
 *
 * <p>The downgrade exists so a guest Fabric mixin that matches nothing on the Forge-rewritten merged base loads
 * vanilla-shaped with a warning instead of killing the boot. It is a deliberate hole in the "fail loudly" rule,
 * and a hole that leaks is worse than no hole: if it ever swallowed a failure from the forge/neoforge runtime,
 * from {@code forbric*}, or from a wrapped Forge-family mod, a real defect would become an inert class and a
 * warning nobody reads — which is the exact failure shape the rest of this kernel is built to avoid.
 *
 * <p>So most of these tests assert that it says NO.
 */
class ForbricMixinDowngradeTest {
	private static final String PROP = "forbric.downgradeInjectionErrors";

	@AfterEach
	void clearProperty() {
		System.clearProperty(PROP);
	}

	@Test
	void aGuestConfigNamedInTheCsvIsSwallowed() {
		System.setProperty(PROP, "sodium.mixins.json");
		assertTrue(ForbricMixinDowngrade.shouldSkip("net.minecraft.client.Foo", mixinFailure("sodium.mixins.json")));
	}

	/** The safety property: a config the loader did NOT mark non-fatal still crashes. */
	@Test
	void aConfigOutsideTheCsvStillCrashes() {
		System.setProperty(PROP, "sodium.mixins.json");
		assertFalse(ForbricMixinDowngrade.shouldSkip("net.minecraft.client.Foo",
				mixinFailure("forbricruntime.mixins.json")),
				"a runtime/forbric/wrapped config is not a guest config — its failures must stay fatal");
	}

	/** Not Mixin's fault at all: nothing to scope, so it must not be swallowed. */
	@Test
	void aNonMixinFailureIsNeverSwallowed() {
		System.setProperty(PROP, "sodium.mixins.json");
		assertFalse(ForbricMixinDowngrade.shouldSkip("net.minecraft.client.Foo",
				new IllegalStateException("in sodium.mixins.json: something")),
				"the message names a config, but the failure did not come from Mixin — it is not ours to swallow");
	}

	/** A Mixin failure that names no config cannot be scoped, so it stays fatal. */
	@Test
	void aMixinFailureNamingNoConfigIsNeverSwallowed() {
		System.setProperty(PROP, "sodium.mixins.json");
		assertFalse(ForbricMixinDowngrade.shouldSkip("net.minecraft.client.Foo",
				new InjectionError("something went wrong with no config name")));
	}

	@Test
	void withNoPropertySetNothingIsEverSwallowed() {
		assertFalse(ForbricMixinDowngrade.shouldSkip("net.minecraft.client.Foo", mixinFailure("sodium.mixins.json")));
		System.setProperty(PROP, "");
		assertFalse(ForbricMixinDowngrade.shouldSkip("net.minecraft.client.Foo", mixinFailure("sodium.mixins.json")));
	}

	/** Trailing '*' is a prefix glob; anything else is exact. Same contract as substrate patches 0007/0008. */
	@Test
	void theGlobContractIsPrefixOrExact() {
		System.setProperty(PROP, "fabric-*");
		assertTrue(ForbricMixinDowngrade.shouldSkip("X", mixinFailure("fabric-rendering-v1.mixins.json")));
		assertFalse(ForbricMixinDowngrade.shouldSkip("X", mixinFailure("sodium.mixins.json")));

		System.setProperty(PROP, "sodium.mixins.json");
		assertFalse(ForbricMixinDowngrade.shouldSkip("X", mixinFailure("sodium.mixins.json.evil")),
				"an entry without a trailing star is an exact match, not a prefix");
	}

	/** The Mixin type and the config name may sit at different depths of the cause chain. */
	@Test
	void theTypeAndTheConfigAreFoundAtDifferentDepths() {
		System.setProperty(PROP, "sodium.mixins.json");
		Throwable deep = new RuntimeException("outer",
				causedBy(new InjectionError("no name here"), new IllegalStateException("in sodium.mixins.json: gone")));

		assertTrue(ForbricMixinDowngrade.shouldSkip("X", deep));
	}

	/** A cyclic cause chain must not hang the boot — the walk is depth-capped. */
	@Test
	@Timeout(5)
	void aCyclicCauseChainTerminates() {
		System.setProperty(PROP, "sodium.mixins.json");

		SelfCausing loop = new SelfCausing();
		assertFalse(ForbricMixinDowngrade.shouldSkip("X", loop));
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	/** InjectionError has no (message, cause) constructor, so attach the cause the long way. */
	private static Throwable causedBy(Throwable t, Throwable cause) {
		t.initCause(cause);
		return t;
	}

	private static Throwable mixinFailure(String config) {
		return new InjectionError("Critical injection failure in " + config + ":MixinFoo did not match");
	}

	/** getCause() returns itself, so an uncapped walk would spin forever. */
	private static final class SelfCausing extends RuntimeException {
		private static final long serialVersionUID = 1L;

		SelfCausing() {
			super("loops forever");
		}

		@Override
		public synchronized Throwable getCause() {
			return this;
		}
	}
}
