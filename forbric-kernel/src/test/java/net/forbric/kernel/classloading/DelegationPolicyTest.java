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

package net.forbric.kernel.classloading;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DelegationPolicyTest {

	@Test
	void gameAndEcosystemClassesAreDefinedByTheTransformingLoader() {
		assertTrue(DelegationPolicy.alwaysGame("net.minecraft.world.level.Level"));
		assertTrue(DelegationPolicy.alwaysGame("net.minecraftforge.registries.NamespacedWrapper"));
		assertTrue(DelegationPolicy.alwaysGame("net.neoforged.neoforge.resource.ResourcePackLoader"));
		assertFalse(DelegationPolicy.alwaysParent("net.minecraft.world.level.Level"));
	}

	@Test
	void transformMachineryStaysOnTheParent() {
		assertTrue(DelegationPolicy.alwaysParent("org.objectweb.asm.tree.ClassNode"));
		assertTrue(DelegationPolicy.alwaysParent("org.spongepowered.asm.mixin.Mixin"));
		assertTrue(DelegationPolicy.alwaysParent("net.forbric.kernel.boot.KernelLifecycle"));
	}

	@Test
	void mixinGeneratedSyntheticsAreGameSideDespiteLivingUnderAParentPackage() {
		assertTrue(DelegationPolicy.alwaysGame("org.spongepowered.asm.synthetic.args.Args$1"));
		assertFalse(DelegationPolicy.alwaysParent("org.spongepowered.asm.synthetic.args.Args$1"));
	}

	/**
	 * The MinecraftForge runtime carrier bundles NightConfig at the unshaded package name, and its copy is the
	 * 3.7.4 one whose {@code StampedConfig.valueMap()} is a stub that throws. Child-first would hand every
	 * NightConfig class to that copy and shadow the working 3.8.x on the parent classpath — which broke every
	 * config read that descends a dotted path into a nested table. Exactly one NightConfig must exist, and it
	 * must be the one on the parent.
	 */
	@Test
	void nightConfigIsPinnedToTheParentSoACarriersOldCopyCannotWin() {
		assertTrue(DelegationPolicy.alwaysParent("com.electronwill.nightconfig.core.concurrent.StampedConfig"));
		assertTrue(DelegationPolicy.alwaysParent("com.electronwill.nightconfig.core.AbstractConfig"));
		assertTrue(DelegationPolicy.alwaysParent("com.electronwill.nightconfig.toml.TomlParser"));
		assertFalse(DelegationPolicy.alwaysGame("com.electronwill.nightconfig.core.file.CommentedFileConfig"));
	}

	/**
	 * A mod that shades NightConfig under its OWN package must keep its own copy — the pin is on the canonical
	 * coordinates only. lambdynamiclights ships one at {@code dev.lambdaurora.lambdynlights.shadow.nightconfig}.
	 */
	@Test
	void aModsShadedCopyIsNotCaughtByThePin() {
		assertFalse(DelegationPolicy.alwaysParent(
				"dev.lambdaurora.lambdynlights.shadow.nightconfig.core.concurrent.StampedConfig"));
	}

	@Test
	void unlistedLibrariesFallThroughToChildFirst() {
		assertFalse(DelegationPolicy.alwaysParent("com.google.common.collect.ImmutableList"));
		assertFalse(DelegationPolicy.alwaysGame("com.google.common.collect.ImmutableList"));
	}
}
