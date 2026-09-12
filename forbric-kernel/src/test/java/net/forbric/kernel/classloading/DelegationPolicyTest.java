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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

	/**
	 * Every {@code net.forbric.} package this file pins must be a package that EXISTS.
	 *
	 * <p>A pin on a package with no sources is not inert, it is misleading: it reads as a reserved slot, so the
	 * next person looking for where a kernel-owned API goes finds two candidate answers and no way to tell which
	 * one the code means. {@code net.forbric.kernel.api.} was pinned here for exactly that long — named in the
	 * README as the home of a {@code KernelHooks} that was never written, while the real unified API grew in
	 * {@code net.forbric.api.} next to it.
	 *
	 * <p>The scan reads the SOURCE rather than calling {@link DelegationPolicy#alwaysParent} in a loop, because
	 * the defect is a name present in the list that nothing satisfies — and a list that cannot be enumerated
	 * cannot be checked for that. It is the same reason {@code ApiLayeringTest} reads source: the thing being
	 * asserted about is the text, not the behaviour.
	 */
	@Test
	void everyPinnedForbricPackageExists() throws Exception {
		Path root = Path.of(System.getProperty("user.dir"));
		Path source = root.resolve("src/main/java/net/forbric/kernel/classloading/DelegationPolicy.java");
		assumeTrue(Files.isRegularFile(source), "DelegationPolicy source not present");

		Matcher pins = Pattern.compile("\"(net\\.forbric\\.[A-Za-z0-9_.]*)\\.\"").matcher(Files.readString(source));
		List<String> missing = new ArrayList<>();
		int found = 0;
		while (pins.find()) {
			found++;
			String pkg = pins.group(1).replace('.', '/');
			// Two source sets can satisfy a pin: the boot side, and the game side that ALWAYS_GAME reserves.
			if (!Files.isDirectory(root.resolve("src/main/java/" + pkg))
					&& !Files.isDirectory(root.resolve("src/runtime/java/" + pkg))) {
				missing.add(pins.group(1));
			}
		}

		assertTrue(found > 1, "the scan matched nothing, which would make the assertion vacuous");
		assertEquals(List.of(), missing,
				"these packages are pinned but have no sources. Either the package was renamed and the pin was left "
						+ "behind, or the pin is a reservation — and a reservation nothing satisfies belongs in a "
						+ "comment, not in the list the classloader actually consults");
	}
}
