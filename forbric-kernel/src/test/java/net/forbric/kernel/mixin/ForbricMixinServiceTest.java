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
		ForbricMixinService.setGuestConfigs(List.of());
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
