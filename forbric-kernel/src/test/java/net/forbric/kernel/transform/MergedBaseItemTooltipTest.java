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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Covers the tooltip repair, whose whole symptom was a load-report row.
 *
 * <p>The merged {@code ItemStack.getTooltipLines} carries exactly ONE event call and it is MinecraftForge's
 * {@code ForgeEventFactory.onItemTooltip}. NeoForge's {@code ItemTooltipEvent} is never constructed, so every
 * NeoForge mod that adds a line to an item's tooltip adds it to nothing — Architectury and RarityCore both do,
 * and neither produced any other visible sign.
 *
 * <p>Asserted against the REAL merged base rather than a fixture, because the thing being claimed is a fact about
 * that jar: that there is one Forge call there and no NeoForge one. A fixture would let the repair keep passing
 * after the merge changed underneath it.
 */
class MergedBaseItemTooltipTest {
	private static final Path MERGED_BASE =
			Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();
	private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
	private static final String BRIDGE = "net/forbric/kernel/runtime/KernelItemTooltips";
	private static final String FORGE_FACTORY = "net/minecraftforge/event/ForgeEventFactory";

	@Test
	void theMergedBaseOnlyEverAsksMinecraftForge() throws Exception {
		byte[] bytes = readMergedBase();
		assumeTrue(bytes != null, "merged base not staged");

		MethodNode tooltip = tooltipLines(parse(bytes));
		assertNotNull(tooltip, "the merged ItemStack must still have getTooltipLines");
		assertEquals(1, calls(tooltip, FORGE_FACTORY, "onItemTooltip"),
				"MinecraftForge's is the one call the merge kept");
		assertEquals(0, calls(tooltip, "net/neoforged/neoforge/common/NeoForge", "post"),
				"and NeoForge's event is constructed nowhere — which is the defect");
	}

	@Test
	void theRepairPostsNeoForgesEventWithTheRealContextAndDisplay() throws Exception {
		byte[] bytes = readMergedBase();
		assumeTrue(bytes != null, "merged base not staged");

		MethodNode tooltip = tooltipLines(parse(transform(bytes)));
		assertNotNull(tooltip);
		MethodInsnNode posted = null;
		for (AbstractInsnNode insn : tooltip.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call && BRIDGE.equals(call.owner)) posted = call;
		}
		assertNotNull(posted, "the repair must add the NeoForge post");
		assertEquals("postNeoForge", posted.name);

		// Six ALOADs, and the last two are the context (parameter 1) and the display local — not placeholders.
		List<VarInsnNode> loads = new ArrayList<>();
		for (AbstractInsnNode cursor = posted.getPrevious();
				cursor != null && cursor.getOpcode() == Opcodes.ALOAD; cursor = cursor.getPrevious()) {
			loads.add(0, (VarInsnNode) cursor);
		}
		assertEquals(6, loads.size(), "stack, player, list, flag, context, display");
		assertEquals(0, loads.get(0).var, "the stack is this");
		assertEquals(1, loads.get(4).var, "the Item.TooltipContext is the method's own first parameter");
		assertTrue(loads.get(5).var > 3, "the TooltipDisplay is a local the method computed, not a parameter");
	}

	/** Twice would post the event twice, and every NeoForge mod's tooltip line would appear twice. */
	@Test
	void aSecondPassLeavesTheRepairedClassAlone() throws Exception {
		byte[] bytes = readMergedBase();
		assumeTrue(bytes != null, "merged base not staged");

		byte[] once = transform(bytes);
		assertSame(once, transform(once), "a body that already posts must not be rewritten");
	}

	private static MethodNode tooltipLines(ClassNode node) {
		for (MethodNode method : node.methods) {
			if ("getTooltipLines".equals(method.name)) return method;
		}
		return null;
	}

	private static int calls(MethodNode method, String owner, String name) {
		int found = 0;
		for (AbstractInsnNode insn : method.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)) found++;
		}
		return found;
	}

	private static byte[] transform(byte[] bytes) {
		return new ForbricMergedBaseCompatTransformer().transform(ITEM_STACK.replace('/', '.'), bytes, null);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] readMergedBase() throws Exception {
		if (!Files.isRegularFile(MERGED_BASE)) return null;
		try (ZipFile jar = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry entry = jar.getEntry(ITEM_STACK + ".class");
			if (entry == null) return null;
			try (InputStream in = jar.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
