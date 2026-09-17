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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Covers {@code Mob.getSpawnReason()} against the REAL staged base.
 *
 * <p>The merge left {@code Mob} carrying both families' spawn fields under different names, and every producer
 * writes only one of them. {@code javap}: two {@code putfield spawnType}, zero {@code putfield spawnReason}. So
 * the accessor read a field nothing had ever written and answered null for every mob — which sends every mod
 * that branches on how a mob was spawned down one branch, silently.
 *
 * <p>Checked against the staged base rather than a fixture, because what can drift is what the OTHER
 * repository's merge emits.
 */
class MergedBaseSpawnReasonTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();
	private static final String ENTRY = "net/minecraft/world/entity/Mob.class";
	private static final String OWNER = "net/minecraft/world/entity/Mob";
	private static final String SPAWN_REASON = "Lnet/minecraft/world/entity/EntitySpawnReason;";

	@Test
	void theFieldTheAccessorReadsIsOneSomethingWrites() throws Exception {
		ClassNode node = repaired();

		int reads = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD
						&& OWNER.equals(f.owner) && "spawnReason".equals(f.name)) {
					reads++;
				}
			}
		}

		assertEquals(0, reads, "nothing may still read spawnReason — it has no producer, so every read is a null");
		assertTrue(writes(node, "spawnType") > 0, "spawnType is the field the game writes; that is the premise");
	}

	@Test
	void getSpawnReasonAndGetSpawnTypeNowAgree() throws Exception {
		ClassNode node = repaired();

		MethodNode reason = method(node, "getSpawnReason", "()" + SPAWN_REASON);
		MethodNode type = method(node, "getSpawnType", "()" + SPAWN_REASON);

		assertEquals(fieldRead(type), fieldRead(reason),
				"both accessors answer the same question and must read the same field");
		assertEquals("spawnType", fieldRead(reason));
	}

	@Test
	void theDeadFieldIsLeftDeclared() throws Exception {
		// Removing it would break an access widener or a mixin that names it, for no gain: an unread field costs
		// one reference per mob.
		assertTrue(repaired().fields.stream()
						.anyMatch(f -> "spawnReason".equals(f.name) && SPAWN_REASON.equals(f.desc)),
				"the declaration stays; only the read moves");
	}

	@Test
	void aSecondPassChangesNothing() throws Exception {
		byte[] once = new ForbricMergedBaseCompatTransformer().transform(
				"net.minecraft.world.entity.Mob", original(), null);
		byte[] twice = new ForbricMergedBaseCompatTransformer().transform(
				"net.minecraft.world.entity.Mob", once, null);

		assertSame(once, twice, "with no reads left there is nothing to rewrite, and the pass must say so");
	}

	@Test
	void anotherClassKeepsItsOwnSpawnFields() throws Exception {
		// The rule is pinned to Mob by name. A subclass cannot read Mob's private field anyway, but a rule that
		// rewrote by field name alone would be a rule waiting to hit an unrelated class.
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] other = readClass("net/minecraft/world/entity/LivingEntity.class");
		assumeTrue(other != null, "LivingEntity absent from this base");

		assertSame(other, new ForbricMergedBaseCompatTransformer()
				.transform("net.minecraft.world.entity.LivingEntity", other, null));
	}

	private static String fieldRead(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD && OWNER.equals(f.owner)) {
				return f.name;
			}
		}
		return null;
	}

	private static int writes(ClassNode node, String field) {
		int n = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
						&& OWNER.equals(f.owner) && field.equals(f.name)) {
					n++;
				}
			}
		}
		return n;
	}

	private static ClassNode repaired() throws Exception {
		byte[] out = new ForbricMergedBaseCompatTransformer().transform(
				"net.minecraft.world.entity.Mob", original(), null);
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		return node;
	}

	private static byte[] original() throws IOException {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] in = readClass(ENTRY);
		assumeTrue(in != null, "Mob absent from this base");
		ClassNode node = new ClassNode();
		new ClassReader(in).accept(node, 0);
		boolean split = node.fields.stream().anyMatch(f -> "spawnReason".equals(f.name))
				&& node.fields.stream().anyMatch(f -> "spawnType".equals(f.name));
		assumeTrue(split, "this base no longer splits Mob's spawn field — nothing to repair");
		return in;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) return m;
		}
		assertNotNull(null, "missing " + name + desc);
		throw new AssertionError();
	}

	private static byte[] readClass(String entry) throws IOException {
		try (ZipFile jar = new ZipFile(MERGED_BASE.toFile())) {
			var e = jar.getEntry(entry);
			if (e == null) return null;
			try (InputStream in = jar.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}
}
