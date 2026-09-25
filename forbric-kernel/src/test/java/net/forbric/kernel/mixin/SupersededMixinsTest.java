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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Every entry in {@link SupersededMixins} claims a named kernel repair does a guest mixin's whole job.
 *
 * <p>The claim is what makes the entry safe, and it is the thing that rots: a repair gets renamed, or narrowed,
 * or deleted, and the entry keeps quietly suppressing a real failure report. So the kernel class each sentence
 * names has to exist — a sentence naming a class that is gone is a claim nobody is keeping.
 */
class SupersededMixinsTest {
	/** The kernel classes an entry's sentence may name, e.g. {@code (KernelFabricConditions)}. */
	private static final Pattern KERNEL_CLASS = Pattern.compile("\\b(Kernel[A-Za-z0-9]+)\\b");

	@Test
	void everyEntryNamesAKernelRepairThatStillExists() throws Exception {
		Path main = Path.of(System.getProperty("user.dir"), "src").normalize();
		List<String> broken = new ArrayList<>();

		for (Map.Entry<String, String> entry : SupersededMixins.all().entrySet()) {
			List<String> named = new ArrayList<>();
			Matcher matcher = KERNEL_CLASS.matcher(entry.getValue());
			while (matcher.find()) named.add(matcher.group(1));

			if (named.isEmpty()) {
				broken.add(entry.getKey() + ": its sentence names no kernel class, so nothing can be checked "
						+ "against it");
				continue;
			}
			for (String kernelClass : named) {
				if (!exists(main, kernelClass)) broken.add(entry.getKey() + " -> " + kernelClass + " is gone");
			}
		}

		assertTrue(broken.isEmpty(), "these entries suppress a mixin failure report on the strength of a repair "
				+ "that is no longer there: " + broken);
	}

	/** An entry must name a mixin class, not a config or a partial name — the handler matches on it exactly. */
	@Test
	void everyKeyIsAFullyQualifiedMixinClassName() {
		List<String> wrong = new ArrayList<>();
		for (String key : SupersededMixins.all().keySet()) {
			if (!key.contains(".") || key.endsWith(".json") || key.contains("/")) wrong.add(key);
		}
		assertTrue(wrong.isEmpty(), "the handler compares these to IMixinInfo.getClassName(), which is a binary "
				+ "class name: " + wrong);
	}

	@Test
	void theSwitchIsOnByDefaultAndOffAnswersNothingAtAll() {
		String previous = System.getProperty(SupersededMixins.PROPERTY);
		try {
			System.clearProperty(SupersededMixins.PROPERTY);
			String any = "net.fabricmc.fabric.mixin.resource.conditions.SimpleJsonResourceReloadListenerMixin";
			assertTrue(SupersededMixins.enabled());
			assertFalse(SupersededMixins.replacementFor(any) == null);

			System.setProperty(SupersededMixins.PROPERTY, "off");
			assertNull(SupersededMixins.replacementFor(any),
					"off must answer null rather than change the wording, so the entry becomes an ordinary "
							+ "marked failure and the claim can be checked against the running game");
		} finally {
			if (previous == null) System.clearProperty(SupersededMixins.PROPERTY);
			else System.setProperty(SupersededMixins.PROPERTY, previous);
		}
	}

	@Test
	void aMixinWithNoEntryIsNotSuperseded() {
		assertNull(SupersededMixins.replacementFor("a.b.SomeOtherMixin"));
	}

	/** Turning the repair itself off turns the entry off too: a switched-off repair replaces nothing. */
	@Test
	void theRepairsOwnSwitchAnswersNothingAtAll() {
		String previous = System.getProperty("forbric.fabricConditions");
		try {
			String any = "net.fabricmc.fabric.mixin.resource.conditions.SimpleJsonResourceReloadListenerMixin";
			System.setProperty("forbric.fabricConditions", "off");
			assertNull(SupersededMixins.replacementFor(any));
			System.setProperty("forbric.fabricConditions", "on");
			assertFalse(SupersededMixins.replacementFor(any) == null);
		} finally {
			if (previous == null) System.clearProperty("forbric.fabricConditions");
			else System.setProperty("forbric.fabricConditions", previous);
		}
	}

	/**
	 * The proof reads what the real repair writes: NeoForge's own ConditionalOps is not proof, and the same class
	 * after ForbricMergedBaseCompatTransformer is. A renamed or reshaped repair fails here rather than quietly
	 * leaving every fabric-conditions failure reported forever -- or resolving it on a class that lacks it.
	 */
	@Test
	void theProofMatchesWhatTheRealRepairWritesIntoTheRealClass() throws Exception {
		Path carrier = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"),
				"run", "neoforge-runtime", "neoforge-runtime.jar").normalize();
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(carrier), "staged NeoForge carrier absent");
		byte[] original;
		try (var zip = new java.util.zip.ZipFile(carrier.toFile())) {
			var entry = zip.getEntry("net/neoforged/neoforge/common/conditions/ConditionalOps.class");
			assertTrue(entry != null, "ConditionalOps absent from " + carrier);
			try (var in = zip.getInputStream(entry)) { original = in.readAllBytes(); }
		}
		byte[] repaired = new net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer()
				.transform("net.neoforged.neoforge.common.conditions.ConditionalOps", original, null);
		assertFalse(SupersededMixins.conditionalOpsAsksFabric(node(original)), "NeoForge's own class is not the repair");
		assertTrue(SupersededMixins.conditionalOpsAsksFabric(node(repaired)), "the repaired class must prove itself");
	}

	private static org.objectweb.asm.tree.ClassNode node(byte[] bytes) {
		org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static boolean exists(Path sourceRoot, String simpleName) throws Exception {
		try (var files = Files.walk(sourceRoot)) {
			return files.anyMatch(f -> f.getFileName().toString().equals(simpleName + ".java"));
		}
	}

	/** The hopper entry's proof matches what HopperFabricStorageInjector writes into the real merged hopper. */
	@Test
	void theHopperProofMatchesWhatTheRealRepairWritesIntoTheRealClass() throws Exception {
		java.nio.file.Path merged = java.nio.file.Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"),
				"merged-base/patched-mc-merged-26.2.jar");
		org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(merged), "actual game required");
		byte[] original;
		try (var zip = new java.util.zip.ZipFile(merged.toFile())) {
			original = zip.getInputStream(zip.getEntry("net/minecraft/world/level/block/entity/HopperBlockEntity.class")).readAllBytes();
		}
		assertFalse(SupersededMixins.hopperAsksFabric(node(original)));
		byte[] repaired = new net.forbric.kernel.transform.HopperFabricStorageInjector()
				.transform("net.minecraft.world.level.block.entity.HopperBlockEntity", original, null);
		assertTrue(SupersededMixins.hopperAsksFabric(node(repaired)));
	}

	@Test
	void theHopperRepairsOwnSwitchAnswersNothing() {
		String mixin = "net.fabricmc.fabric.mixin.transfer.HopperBlockEntityMixin";
		try {
			System.setProperty("forbric.hopperFabricStorage", "off");
			assertNull(SupersededMixins.replacementFor(mixin));
			System.clearProperty("forbric.hopperFabricStorage");
			assertFalse(SupersededMixins.replacementFor(mixin) == null);
		} finally {
			System.clearProperty("forbric.hopperFabricStorage");
		}
	}
}
