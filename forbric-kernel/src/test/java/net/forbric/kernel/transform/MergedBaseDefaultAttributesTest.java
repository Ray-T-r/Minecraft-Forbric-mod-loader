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

/**
 * {@code DefaultAttributes} is the single consumer both ecosystems patch, and the merge kept one of them.
 *
 * <p>The assertion that matters is that BOTH of its call sites move: {@code getSupplier} is what an entity needs
 * to exist at all, and {@code hasSupplier} is what decides whether the game even asks. Redirecting one and not
 * the other would give a MinecraftForge mod's entity attributes that the game never looks for.
 */
class MergedBaseDefaultAttributesTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();
	private static final String ENTRY = "net/minecraft/world/entity/ai/attributes/DefaultAttributes.class";
	private static final String BINARY = "net.minecraft.world.entity.ai.attributes.DefaultAttributes";
	private static final String KERNEL = "net/forbric/kernel/runtime/KernelForgeAttributes";

	@Test
	void bothLookupsGoThroughTheKernelsCompositionOfTheTwoMaps() throws Exception {
		ClassNode node = repaired();
		List<String> redirected = new ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				assertTrue(!("net/neoforged/neoforge/common/CommonHooks".equals(call.owner)
								&& "getAttributesView".equals(call.name)),
						"DefaultAttributes." + method.name + " still reads only NeoForge's attribute map — a "
								+ "traditional MinecraftForge mod's entities then have no attributes and are refused");
				if (KERNEL.equals(call.owner) && "attributesView".equals(call.name)) {
					assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
					assertEquals("()Ljava/util/Map;", call.desc,
							"same descriptor as the call it replaced, or the stack moves");
					redirected.add(method.name);
				}
			}
		}
		assertTrue(redirected.contains("getSupplier"),
				"getSupplier must consult both maps — it is what gives the entity its attributes: " + redirected);
		assertTrue(redirected.contains("hasSupplier"),
				"hasSupplier must consult both maps too — it is what decides whether the game asks at all: "
						+ redirected);
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		byte[] once = new ForbricMergedBaseCompatTransformer().transform(BINARY, original(), null);
		byte[] twice = new ForbricMergedBaseCompatTransformer().transform(BINARY, once, null);
		assertSame(once, twice, "no CommonHooks call is left, so the second pass must find nothing");
	}

	private static ClassNode repaired() throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(new ForbricMergedBaseCompatTransformer().transform(BINARY, original(), null)).accept(node, 0);
		return node;
	}

	private static byte[] original() throws IOException {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry entry = zip.getEntry(ENTRY);
			assumeTrue(entry != null, "DefaultAttributes absent from this base");
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
