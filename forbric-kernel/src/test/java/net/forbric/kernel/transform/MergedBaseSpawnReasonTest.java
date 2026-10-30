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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
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

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * Covers {@code Mob.getSpawnReason()} against the REAL staged base.
 *
 * <p>The merge left {@code Mob} carrying both families' spawn fields under different names. The first merge wrote only
 * one of them ({@code javap}: two {@code putfield spawnType}, zero {@code putfield spawnReason}), so the accessor read a
 * field nothing had ever written and answered null for every mob — which sends every mod that branches on how a mob
 * was spawned down one branch, silently. The edit-splicing merge keeps MinecraftForge's {@code this.spawnReason =
 * reason} in {@code finalizeSpawn}, mirrored by NeoForge's {@code spawnType} store, but the load lambda restores only
 * {@code spawnType}: every mob read back from disk answered null. The read moves in both shapes, and stays where it is
 * when {@code spawnReason} has a producer of its own.
 *
 * <p>Checked against the staged base rather than a fixture, because what can drift is what the OTHER
 * repository's merge emits.
 */
class MergedBaseSpawnReasonTest {
	private static final Path MERGED_BASE = TestFixtures.stagedBase();
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

	/**
	 * Both merged shapes of the staged class: as staged, and with every {@code spawnReason} write taken out (the first
	 * merge's shape). Every read moves to {@code spawnType} either way.
	 */
	@Test
	void theReadMovesWhetherSpawnReasonIsNeverWrittenOrOnlyMirrored() throws Exception {
		for (boolean stripped : new boolean[] { false, true }) {
			ClassNode node = parse(original());
			if (stripped) {
				for (MethodNode method : node.methods) {
					for (AbstractInsnNode insn : method.instructions.toArray()) {
						if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && OWNER.equals(f.owner) && "spawnReason".equals(f.name)) {
							// `aload 0; <value>; putfield` becomes nothing: the value and the receiver are both popped.
							method.instructions.set(insn, new org.objectweb.asm.tree.InsnNode(Opcodes.POP2));
						}
					}
				}
				assertEquals(0, writes(node, "spawnReason"), "premise: the first merge's shape writes no spawnReason");
			}
			ClassNode repaired = parse(new ForbricMergedBaseCompatTransformer().transform("net.minecraft.world.entity.Mob", bytes(node), null));
			assertEquals("spawnType", fieldRead(method(repaired, "getSpawnReason", "()" + SPAWN_REASON)), stripped ? "never written" : "only mirrored");
		}
	}

	/**
	 * A {@code spawnReason} producer without a {@code spawnType} twin makes the field live: here the load lambda,
	 * restoring {@code spawnReason} alone, as MinecraftForge's own does. The read must then stay where it is.
	 */
	@Test
	void aProducerOfItsOwnKeepsTheReadWhereItIs() throws Exception {
		ClassNode node = parse(original());
		int retargeted = 0;
		for (MethodNode method : node.methods) {
			if (!method.name.startsWith("lambda$readAdditionalSaveData$")) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && OWNER.equals(f.owner) && "spawnType".equals(f.name)) {
					f.name = "spawnReason";
					retargeted++;
				}
			}
		}
		assertEquals(1, retargeted, "premise: the load lambda restores the spawn field");
		ClassNode repaired = parse(new ForbricMergedBaseCompatTransformer().transform("net.minecraft.world.entity.Mob", bytes(node), null));
		assertEquals("spawnReason", fieldRead(method(repaired, "getSpawnReason", "()" + SPAWN_REASON)),
				"spawnReason has a producer of its own; rerouting its read would discard it");
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytes(ClassNode node) {
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	@Test
	void anotherClassKeepsItsOwnSpawnFields() throws Exception {
		// The rule is pinned to Mob by name. A subclass cannot read Mob's private field anyway, but a rule that
		// rewrote by field name alone would be a rule waiting to hit an unrelated class.
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] other = readClass("net/minecraft/world/entity/LivingEntity.class");
		assertNotNull(other, "content drift: LivingEntity absent from this base");

		// Byte identity used to be the assertion here, and it stopped being available the moment a LATER repair
		// found something of its own in LivingEntity (the radians-to-degrees constant, in
		// lambda$stopSleeping$0). "No repair touches this class" was never what this test meant; "this repair
		// does not" is, and the claim reporter says exactly that without depending on what else the pass does.
		List<String> hits = new ArrayList<>();
		new ForbricMergedBaseCompatTransformer()
				.transform("net.minecraft.world.entity.LivingEntity", other, null, hits::add);
		assertFalse(hits.contains("forbric-merged-base-compat#readTheSpawnReasonThatIsActuallyWritten"),
				"the rule is pinned to Mob by name; it claimed LivingEntity instead. Claims seen: " + hits);
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
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] in = readClass(ENTRY);
		assertNotNull(in, "content drift: Mob absent from this base");
		ClassNode node = new ClassNode();
		new ClassReader(in).accept(node, 0);
		boolean split = node.fields.stream().anyMatch(f -> "spawnReason".equals(f.name))
				&& node.fields.stream().anyMatch(f -> "spawnType".equals(f.name));
		assertTrue(split, "content drift: this base no longer splits Mob's spawn field — nothing to repair");
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
