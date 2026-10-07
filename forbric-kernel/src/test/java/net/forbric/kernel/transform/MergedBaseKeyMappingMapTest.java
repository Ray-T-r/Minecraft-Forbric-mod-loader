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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * {@code KeyMapping} on the real staged base gets vanilla's {@code MAP:Ljava/util/Map;} back, as
 * {@code KernelKeyMappingMap}'s view of {@code ALL}: LiquidBounce reads it on every key press in a screen.
 */
class MergedBaseKeyMappingMapTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final String KEY_MAPPING = "net/minecraft/client/KeyMapping";

	@Test
	void vanillasMapIsBackAndPublicSoAModReadingItLinks() throws Exception {
		ClassNode node = repaired(original());
		FieldNode map = node.fields.stream().filter(f -> f.name.equals("MAP") && f.desc.equals("Ljava/util/Map;")).findFirst()
				.orElse(null);
		assertNotNull(map, "KeyMapping must declare vanilla's MAP:Ljava/util/Map; again");
		assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, map.access,
				"public, because the access wideners that open vanilla's private field have already run");
		assertTrue(node.fields.stream().anyMatch(f -> f.name.equals("MAP")
				&& f.desc.equals("Lnet/neoforged/neoforge/client/settings/KeyMappingLookup;")), "NeoForge's own MAP stays");
	}

	@Test
	void itIsAViewOfAllAssignedRightAfterAll() throws Exception {
		MethodNode clinit = repaired(original()).methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElseThrow();
		AbstractInsnNode[] body = clinit.instructions.toArray();
		int all = -1, view = -1, put = -1;
		for (int i = 0; i < body.length; i++) {
			if (body[i] instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC && f.name.equals("ALL") && all < 0) all = i;
			if (body[i] instanceof MethodInsnNode m && m.owner.equals("net/forbric/kernel/runtime/KernelKeyMappingMap")
					&& m.name.equals("vanillaView")) view = i;
			if (body[i] instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC && f.name.equals("MAP")
					&& f.desc.equals("Ljava/util/Map;")) put = i;
		}
		assertTrue(all >= 0 && view > all && put > view, "ALL, then the view over it, then MAP — before any mapping exists");
		assertTrue(body[view - 1] instanceof FieldInsnNode src && src.getOpcode() == Opcodes.GETSTATIC && src.name.equals("ALL"),
				"the view is built over the live ALL, not a fresh map");
		int nextPut = -1;
		for (int i = all + 1; i < body.length; i++) if (body[i] instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC) { nextPut = i; break; }
		assertEquals(put, nextPut, "nothing else is assigned in between");
	}

	@Test
	void aSecondPassAndAnotherClassAreLeftAlone() throws Exception {
		byte[] once = new ForbricMergedBaseCompatTransformer().transform("net.minecraft.client.KeyMapping", original(), null);
		byte[] twice = new ForbricMergedBaseCompatTransformer().transform("net.minecraft.client.KeyMapping", once, null);
		assertSame(once, twice, "the repair stands down once vanilla's MAP exists");
	}

	private static ClassNode repaired(byte[] original) {
		byte[] out = new ForbricMergedBaseCompatTransformer().transform("net.minecraft.client.KeyMapping", original, null);
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		return node;
	}

	private static byte[] original() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		try (ZipFile jar = new ZipFile(MERGED_BASE.toFile())) {
			byte[] in = jar.getInputStream(jar.getEntry(KEY_MAPPING + ".class")).readAllBytes();
			ClassNode node = new ClassNode();
			new ClassReader(in).accept(node, 0);
			assertTrue(node.fields.stream().noneMatch(f -> f.name.equals("MAP") && f.desc.equals("Ljava/util/Map;")),
					"content drift: this base keeps vanilla's MAP — nothing to repair");
			return in;
		}
	}
}
