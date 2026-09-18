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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The one place {@code fabric:load_conditions} can be judged, and the proof that it really is one place.
 *
 * <p>What makes a single insertion enough is the FUNNEL, not the wrap: {@code ConditionalOps}' other factories
 * delegate into the one that is wrapped. If a future NeoForge stops delegating, the wrap still applies and the
 * other entry points silently stop being judged — so the delegation is asserted, not assumed.
 */
class MergedBaseFabricConditionsTest {
	private static final Path STAGED =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run").normalize();
	private static final Path NEO_CARRIER = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final Path MERGED_BASE = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final String ENTRY = "net/neoforged/neoforge/common/conditions/ConditionalOps.class";
	private static final String BINARY = "net.neoforged.neoforge.common.conditions.ConditionalOps";
	private static final String KERNEL = "net/forbric/kernel/runtime/KernelFabricConditions";
	private static final String FACTORY = "createConditionalCodecWithConditions";
	private static final String FACTORY_DESC =
			"(Lcom/mojang/serialization/Codec;Ljava/lang/String;)Lcom/mojang/serialization/Codec;";

	@Test
	void theFactoryIsWrappedAtItsOnlyExit() throws Exception {
		MethodNode factory = method(repaired(), FACTORY, FACTORY_DESC);
		AbstractInsnNode[] body = factory.instructions.toArray();

		int wrap = -1;
		int exits = 0;
		for (int i = 0; i < body.length; i++) {
			if (body[i] instanceof MethodInsnNode call && KERNEL.equals(call.owner)
					&& "alsoAskFabric".equals(call.name)) {
				assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
				assertEquals("(Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;", call.desc,
						"a Codec in and a Codec out, or the ARETURN after it returns something else");
				wrap = i;
			}
			if (body[i].getOpcode() == Opcodes.ARETURN) exits++;
		}
		assertEquals(1, exits, "the reasoning that one insertion covers the method depends on one exit");
		assertTrue(wrap >= 0, "ConditionalOps' codec factory is not wrapped, so nothing judges fabric conditions");
		assertEquals(Opcodes.ARETURN, body[wrap + 1].getOpcode(),
				"the wrap must be the last thing before the return — anything after it would decode first");
	}

	/** The funnel. Without it, one wrap is one entry point rather than all four. */
	@Test
	void theOtherPublicFactoriesFunnelIntoTheWrappedOne() throws Exception {
		ClassNode node = repaired();
		List<String> funnelled = new ArrayList<>();
		for (MethodNode method : node.methods) {
			if (FACTORY.equals(method.name) && FACTORY_DESC.equals(method.desc)) continue;
			if (!method.name.startsWith("createConditionalCodec") && !method.name.startsWith("decodeList")) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call
						&& "net/neoforged/neoforge/common/conditions/ConditionalOps".equals(call.owner)
						&& (call.name.startsWith("createConditionalCodec") || call.name.startsWith("decodeList"))) {
					funnelled.add(method.name + method.desc);
					break;
				}
			}
		}
		assertTrue(funnelled.size() >= 3,
				"the other public factories must reach the wrapped one; found " + funnelled);
	}

	/**
	 * The census that notices the funnel stopping being one. Recipes are the reason: RecipeManager does not
	 * extend SimpleJsonResourceReloadListener, it calls scanDirectoryWithModifier — so a per-call-site patch
	 * would have judged everything except recipes, and nothing would have said so.
	 */
	@Test
	void everyConsumerInTheMergedBaseGoesThroughConditionalOps() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		Set<String> consumers = new LinkedHashSet<>();
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (!entry.getName().endsWith(".class")) continue;
				byte[] bytes;
				try (InputStream in = zip.getInputStream(entry)) {
					bytes = in.readAllBytes();
				}
				ClassNode node = new ClassNode();
				new ClassReader(bytes).accept(node, org.objectweb.asm.ClassReader.SKIP_DEBUG);
				for (MethodNode method : node.methods) {
					for (AbstractInsnNode insn : method.instructions) {
						if (!(insn instanceof MethodInsnNode call)) continue;
						if (!call.name.startsWith("createConditionalCodec")) continue;
						assertEquals("net/neoforged/neoforge/common/conditions/ConditionalOps", call.owner,
								node.name + " builds a conditional codec somewhere other than ConditionalOps, so "
										+ "the single wrap no longer covers it");
						consumers.add(node.name);
					}
				}
			}
		}
		assertTrue(consumers.size() >= 5,
				"only " + consumers.size() + " consumer(s) of the conditional codec found — the scan did not run, "
						+ "or the merged base stopped using it: " + consumers);
	}

	@Test
	void aSecondPassDoesNotWrapItTwice() throws Exception {
		byte[] once = new ForbricMergedBaseCompatTransformer().transform(BINARY, original(), null);
		byte[] twice = new ForbricMergedBaseCompatTransformer().transform(BINARY, once, null);
		assertSame(once, twice, "wrapping the wrapper would ask fabric-api twice and double every count it prints");
	}

	private static ClassNode repaired() throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(new ForbricMergedBaseCompatTransformer().transform(BINARY, original(), null)).accept(node, 0);
		return node;
	}

	private static byte[] original() throws IOException {
		assumeTrue(Files.isRegularFile(NEO_CARRIER), "staged NeoForge carrier absent");
		try (ZipFile zip = new ZipFile(NEO_CARRIER.toFile())) {
			ZipEntry entry = zip.getEntry(ENTRY);
			assumeTrue(entry != null, "ConditionalOps absent from this carrier");
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) return m;
		}
		throw new AssertionError("missing " + name + desc);
	}
}
