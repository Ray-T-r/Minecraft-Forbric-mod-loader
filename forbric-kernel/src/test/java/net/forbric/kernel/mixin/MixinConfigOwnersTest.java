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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.forbric.api.Ecosystem;

import net.forbric.kernel.mixin.MixinConfigOwners.Owned;

/**
 * Which mod a mixin config belongs to, and — the part that matters — when the honest answer is "not sure".
 *
 * <p>A confidently wrong mod name is worse than no mod name. It sends someone to the wrong jar, and it makes the
 * log look more authoritative than it is. So the ambiguous cases are tested at least as hard as the clear one.
 */
class MixinConfigOwnersTest {

	@AfterEach
	void forgetWhatThisTestPublished() {
		MixinConfigOwners.reset();
	}

	@Test
	void theOwningModIdReachesEveryLogLine() {
		MixinConfigOwners.publish(List.of(new Owned("sodium.mixins.json", "sodium", Ecosystem.FABRIC)));

		assertEquals("sodium", MixinConfigOwners.modIdOf("sodium.mixins.json"));
		assertEquals("sodium (sodium.mixins.json)", MixinConfigOwners.describe("sodium.mixins.json"));
	}

	@Test
	void aConfigTwoModsClaimIsAttributedToNeither() {
		// The central correctness test. A MinecraftForge toml with several [[mods]] sections offers the SAME
		// config list under every id it declares, and two different jars can ship a config with the same name --
		// Mixin de-duplicates those by name, so the loser is invisible. A first-wins rule would name one of them
		// and be quietly wrong.
		MixinConfigOwners.publish(List.of(
				new Owned("collective.mixins.json", "collective", Ecosystem.FORGE),
				new Owned("collective.mixins.json", "collective_extras", Ecosystem.FORGE)));

		assertNull(MixinConfigOwners.modIdOf("collective.mixins.json"));
		assertEquals("collective.mixins.json", MixinConfigOwners.describe("collective.mixins.json"),
				"an ambiguous config is reported by file name, never by a name that might be the wrong one");
	}

	@Test
	void aConfigOneModClaimsTwiceKeepsItsOwner() {
		// Over-caution is still wrong: the same id twice is not two claimants, and refusing to name it would lose
		// attribution for every multi-[[mods]] toml that declares one mod several times.
		MixinConfigOwners.publish(List.of(
				new Owned("balm.mixins.json", "balm", Ecosystem.NEOFORGE),
				new Owned("balm.mixins.json", "balm", Ecosystem.NEOFORGE)));

		assertEquals("balm", MixinConfigOwners.modIdOf("balm.mixins.json"));
	}

	@Test
	void aConfigNobodyPublishedIsReportedByItsOwnName() {
		MixinConfigOwners.publish(List.of(new Owned("known.mixins.json", "known", Ecosystem.FABRIC)));

		assertNull(MixinConfigOwners.modIdOf("stranger.mixins.json"));
		assertEquals("stranger.mixins.json", MixinConfigOwners.describe("stranger.mixins.json"));
	}

	@Test
	void aBundledConfigSaysWhoBroughtIt() {
		// A nested jar's config belongs to the nested mod, which is correct and also names something the player
		// never chose to install. Saying which jar carried it is the difference between a useful line and a
		// confusing one.
		MixinConfigOwners.publish(List.of(
				new Owned("nestlib.mixins.json", "nestlib", Ecosystem.NEOFORGE, "parentmod")));

		assertEquals("nestlib (nestlib.mixins.json, bundled by parentmod)",
				MixinConfigOwners.describe("nestlib.mixins.json"));
	}

	@Test
	void publishingAgainReplacesWhatWasKnownRatherThanAddingToIt() {
		MixinConfigOwners.publish(List.of(new Owned("a.mixins.json", "first", Ecosystem.FABRIC)));
		MixinConfigOwners.publish(List.of(new Owned("b.mixins.json", "second", Ecosystem.FABRIC)));

		assertNull(MixinConfigOwners.modIdOf("a.mixins.json"),
				"a stale owner outliving the boot that published it would attribute failures to a mod that is not "
						+ "even loaded");
		assertEquals("second", MixinConfigOwners.modIdOf("b.mixins.json"));
	}
}
