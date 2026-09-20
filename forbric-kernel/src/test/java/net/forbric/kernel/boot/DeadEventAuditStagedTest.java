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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The premise of the NeoForge ItemTooltipEvent row, pinned on the staged merged base: getTooltipLines posts
 * MinecraftForge's event and none of NeoForge's. If a merge or a repair ever brings NeoForge's back, this goes
 * red in the over-report-safe direction and the row is deleted.
 */
class DeadEventAuditStagedTest {
	@Test
	void theMergedItemStackPostsOnlyMinecraftForgesTooltipEvent() throws Exception {
		Path merged = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
				"patched-mc-merged-26.2.jar").normalize();
		assumeTrue(Files.isRegularFile(merged), "staged merged base absent");
		ClassNode node = new ClassNode();
		try (ZipFile zip = new ZipFile(merged.toFile())) {
			ZipEntry entry = zip.getEntry("net/minecraft/world/item/ItemStack.class");
			assumeTrue(entry != null);
			try (InputStream in = zip.getInputStream(entry)) {
				new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			}
		}
		boolean forge = false, neo = false, found = false;
		for (MethodNode m : node.methods) {
			if (!m.name.equals("getTooltipLines")) continue;
			found = true;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				if ("net/minecraftforge/event/ForgeEventFactory".equals(call.owner) && "onItemTooltip".equals(call.name)) forge = true;
				if (call.owner.startsWith("net/neoforged/")) neo = true;
			}
		}
		assertTrue(found, "ItemStack.getTooltipLines is gone");
		assertTrue(forge, "MinecraftForge's onItemTooltip is the one the merge kept");
		assertFalse(neo, "NeoForge's ItemTooltipEvent is posted after all — delete the DeadEventAudit row");
	}
}
