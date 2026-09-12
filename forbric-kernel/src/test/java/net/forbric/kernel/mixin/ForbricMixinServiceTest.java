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

package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Pins which registered configs get relaxed. The distinction is invisible until exactly one injector fails to
 * patch, at which point it decides between a soft skip and a fatal {@code MixinApplyError} that aborts the launch.
 */
class ForbricMixinServiceTest {
	private static final List<String> SAMPLE = List.of(
			"forge.mixins.json",
			"neoforge.mixins.json",
			"minecraft.mixins.json",
			"forbric-kernel.mixins.json",
			"bookshelf.common.mixins.json",
			"geckolib.mixins.json");

	@AfterEach
	void reset() {
		System.clearProperty("forbric.relaxGuestMixins");
		System.clearProperty("forbric.relaxMixinOverwrites");
		System.clearProperty("forbric.suppressMixins");
		System.clearProperty("forbric.keepMixins");
		ForbricMixinService.setGuestConfigs(List.of());
	}

	/**
	 * {@code -Dforbric.keepMixins} has to reach the SHIPPED suppression list, not just the adapter's derived one.
	 * It did not, and the failure mode is the expensive kind: re-testing a hand-pinned entry changed nothing while
	 * looking exactly like the mixin having been tried and re-suppressed. Both former pins
	 * ({@code SynchronizeRegistriesTaskMixin}, jade's {@code FogRendererMixin}) were diagnosed only once this
	 * worked.
	 */
	@Test
	void keepMixinsOverridesTheShippedSuppressionList() {
		String config = "fabric-registry-sync-v0.mixins.json";
		assertTrue(ForbricMixinService.suppressedMixinsFor(config).contains("BootstrapMixin"),
				"precondition: this entry ships in MergedBaseMixinCompat.SUPPRESSED_MIXINS");

		System.setProperty("forbric.keepMixins", config + ":BootstrapMixin");
		assertFalse(ForbricMixinService.suppressedMixinsFor(config).contains("BootstrapMixin"),
				"an explicit keepMixins must beat the shipped default");
		assertTrue(ForbricMixinService.suppressedMixinsFor(config).contains("MainMixin"),
				"and must not disturb its siblings — it names one mixin, not the config");
	}

	@Test
	void keepMixinsAlsoOverridesAnExplicitSuppressMixins() {
		String config = "example.mixins.json";
		System.setProperty("forbric.suppressMixins", config + ":SomeMixin");
		assertTrue(ForbricMixinService.suppressedMixinsFor(config).contains("SomeMixin"));

		System.setProperty("forbric.keepMixins", config + ":SomeMixin");
		assertFalse(ForbricMixinService.suppressedMixinsFor(config).contains("SomeMixin"),
				"keepMixins subtracts last, so it wins over suppressMixins too");
	}

	@Test
	void keepMixinsForAnUnrelatedConfigChangesNothing() {
		String config = "fabric-registry-sync-v0.mixins.json";
		System.setProperty("forbric.keepMixins", "other.mixins.json:BootstrapMixin");
		assertTrue(ForbricMixinService.suppressedMixinsFor(config).contains("BootstrapMixin"),
				"the config name is part of the key — a same-named mixin elsewhere must not unpin this one");
	}

	@Test
	void aGuestConfigNamedAfterAnEcosystemIsStillRelaxed() {
		// The exclusion used to be a PREFIX match on forge./neoforge./minecraft., which was harmless only while the
		// registered set was Fabric-only. A guest mod may legitimately name its config forge.mixins.json, and
		// leaving it strict turns one moved anchor into a fatal apply error.
		ForbricMixinService.setGuestConfigs(SAMPLE);

		assertTrue(ForbricMixinService.isRelaxedConfig("forge.mixins.json"));
		assertTrue(ForbricMixinService.isRelaxedConfig("neoforge.mixins.json"));
		assertTrue(ForbricMixinService.isRelaxedConfig("minecraft.mixins.json"));
		assertTrue(ForbricMixinService.isRelaxedConfig("bookshelf.common.mixins.json"));
		assertTrue(ForbricMixinService.isRelaxedConfig("geckolib.mixins.json"));
	}

	@Test
	void theKernelsOwnConfigIsNeverRelaxed() {
		// The kernel authors no mixins today, but a failure in one it did author must crash loudly rather than be
		// silently skipped.
		ForbricMixinService.setGuestConfigs(SAMPLE);

		assertFalse(ForbricMixinService.isRelaxedConfig("forbric-kernel.mixins.json"));
	}

	@Test
	void aConfigThatWasNeverRegisteredIsNotRelaxed() {
		ForbricMixinService.setGuestConfigs(SAMPLE);

		assertFalse(ForbricMixinService.isRelaxedConfig("something-else.mixins.json"));
	}

	@Test
	void relaxGuestMixinsOffRestoresStrictBehaviourForEveryone() {
		System.setProperty("forbric.relaxGuestMixins", "off");
		ForbricMixinService.setGuestConfigs(SAMPLE);

		assertFalse(ForbricMixinService.isRelaxedConfig("bookshelf.common.mixins.json"));
		assertFalse(ForbricMixinService.isRelaxedConfig("geckolib.mixins.json"));
	}

	@Test
	void relaxMixinOverwritesStillWorksAsAnExplicitOverride() {
		ForbricMixinService.setGuestConfigs(List.of());

		System.setProperty("forbric.relaxMixinOverwrites", "explicit.mixins.json,prefixed.*");
		assertTrue(ForbricMixinService.isRelaxedConfig("explicit.mixins.json"));
		assertTrue(ForbricMixinService.isRelaxedConfig("prefixed.anything.json"));
		assertFalse(ForbricMixinService.isRelaxedConfig("unlisted.mixins.json"));
	}
}
