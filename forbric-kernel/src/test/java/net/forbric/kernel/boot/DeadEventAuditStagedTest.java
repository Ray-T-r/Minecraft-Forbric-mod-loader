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

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.api.GameEventBridge;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * The NeoForge ItemTooltipEvent, pinned on the staged merged base by what a player sees: each family's tooltip event is
 * posted exactly once per tooltip, and the audit's table agrees with the bytecode about whether NeoForge's is posted.
 *
 * <p>This used to pin a premise instead — "getTooltipLines posts MinecraftForge's event and none of NeoForge's" — the
 * reason for a dead-table row and for the kernel's tooltip repair. A merge that keeps both families' edits in a method
 * both patched gives getTooltipLines NeoForge's call back, so the premise stopped being true and both had to follow:
 * the row went, and the repair stands down where the call is native. What must hold on either base is the outcome:
 * one MinecraftForge event and one NeoForge event per tooltip, never none and never two.
 */
class DeadEventAuditStagedTest {
	private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
	private static final String NEO_TOOLTIP = "net/neoforged/neoforge/event/entity/player/ItemTooltipEvent";
	private static final String FORGE_TOOLTIP = "net/minecraftforge/event/entity/player/ItemTooltipEvent";
	private static final String REPAIR = "net/forbric/kernel/runtime/KernelItemTooltips";

	private static ClassNode itemStack(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return node;
	}

	private static byte[] stagedItemStack() throws Exception {
		Path merged = TestFixtures.stagedBase().normalize();
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(merged), "staged merged base absent");
		try (ZipFile zip = new ZipFile(merged.toFile())) {
			ZipEntry entry = zip.getEntry(ITEM_STACK + ".class");
			assertNotNull(entry, "ItemStack is missing from the staged merged base " + merged);
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}

	/** {MinecraftForge deliveries, NeoForge deliveries} of the tooltip event in getTooltipLines. */
	private static int[] deliveries(ClassNode node) {
		int forge = 0, neo = 0;
		boolean found = false;
		for (MethodNode m : node.methods) {
			if (!m.name.equals("getTooltipLines")) continue;
			found = true;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof TypeInsnNode type && type.desc.equals(NEO_TOOLTIP)) neo++;
				if (insn instanceof TypeInsnNode type && type.desc.equals(FORGE_TOOLTIP)) forge++;
				if (!(insn instanceof MethodInsnNode call)) continue;
				String returned = Type.getReturnType(call.desc).getInternalName();
				if (call.owner.startsWith("net/minecraftforge/") && returned.equals(FORGE_TOOLTIP)) forge++;
				if (call.owner.startsWith("net/neoforged/") && returned.equals(NEO_TOOLTIP)) neo++;
				if (call.owner.equals(REPAIR) && call.name.equals("postNeoForge")) neo++;
			}
		}
		assertTrue(found, "ItemStack.getTooltipLines is gone");
		return new int[] {forge, neo};
	}

	@Test
	void eachFamilysTooltipEventIsPostedOncePerTooltip() throws Exception {
		byte[] staged = stagedItemStack();
		byte[] repaired = new ForbricMergedBaseCompatTransformer().transform(ITEM_STACK.replace('/', '.'), staged, null);
		int[] after = deliveries(itemStack(repaired));
		assertEquals(1, after[0], "MinecraftForge's ItemTooltipEvent, once");
		assertEquals(1, after[1], "NeoForge's ItemTooltipEvent, once — natively, or by the repair where the merge kept "
				+ "only MinecraftForge's call; both would draw every NeoForge mod's tooltip line twice");
	}

	@Test
	void theAuditTablesAgreeWithTheBaseAboutNeoForgesTooltipEvent() throws Exception {
		boolean posted = deliveries(itemStack(stagedItemStack()))[1] > 0;
		if (posted) {
			assertFalse(DeadEventAudit.DEAD.containsKey(NEO_TOOLTIP), "the merged base posts NeoForge's ItemTooltipEvent, "
					+ "so a row calling it never posted would accuse every NeoForge tooltip mod falsely");
		} else {
			assertEquals(GameEventBridge.ITEM_TOOLTIP, DeadEventAudit.BRIDGED.get(NEO_TOOLTIP),
					"the merged base never posts NeoForge's ItemTooltipEvent, so the repair delivers it and the audit must know");
		}
	}
}
