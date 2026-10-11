/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * When {@code Mob.getSpawnReason()} may read {@code spawnType} instead: when {@code spawnType} holds every value
 * {@code spawnReason} ever does. A merge that keeps both sides' edits writes MinecraftForge's
 * {@code this.spawnReason = reason} beside NeoForge's {@code this.spawnType = reason} in {@code finalizeSpawn} but nowhere
 * else (the save-data load writes {@code spawnType} alone), so that producer does not make {@code spawnReason} live; one
 * that stores something {@code spawnType} does not receive does, and then the read stays.
 */
class MobSpawnReasonProducerTest {
	private static final String MOB = "net/minecraft/world/entity/Mob";
	private static final String REASON = "Lnet/minecraft/world/entity/EntitySpawnReason;";

	@Test void withNoSpawnReasonProducerTheReadFollowsSpawnType() {
		assertEquals(List.of("spawnType", "spawnType"), reads(repair(mob(Producer.NONE))));
	}

	@Test void aSpawnReasonStoreTwinnedBySpawnTypeDoesNotKeepTheDeadRead() {
		assertEquals(List.of("spawnType", "spawnType"), reads(repair(mob(Producer.TWINNED))));
	}

	@Test void aSpawnReasonStoreOfItsOwnKeepsTheRead() {
		assertEquals(List.of("spawnType", "spawnReason"), reads(repair(mob(Producer.ITS_OWN))));
		assertEquals(List.of("spawnType", "spawnReason"), reads(repair(mob(Producer.REASSIGNED))));
	}

	private enum Producer { NONE, TWINNED, ITS_OWN, REASSIGNED }

	/**
	 * A Mob with both fields, {@code getSpawnType()}/{@code getSpawnReason()} reading one each, a load that writes
	 * {@code spawnType}, and a {@code finalizeSpawn(reason, other)} whose stores depend on {@code producer}.
	 */
	private static byte[] mob(Producer producer) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MOB, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PRIVATE, "spawnType", REASON, null, null).visitEnd();
		cw.visitField(Opcodes.ACC_PRIVATE, "spawnReason", REASON, null, null).visitEnd();
		for (String[] accessor : new String[][] {{"getSpawnType", "spawnType"}, {"getSpawnReason", "spawnReason"}}) {
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, accessor[0], "()" + REASON, null, null);
			mv.visitCode();
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitFieldInsn(Opcodes.GETFIELD, MOB, accessor[1], REASON);
			mv.visitInsn(Opcodes.ARETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		MethodVisitor load = cw.visitMethod(Opcodes.ACC_PUBLIC, "load", "(" + REASON + ")V", null, null);
		load.visitCode();
		load.visitVarInsn(Opcodes.ALOAD, 0);
		load.visitVarInsn(Opcodes.ALOAD, 1);
		load.visitFieldInsn(Opcodes.PUTFIELD, MOB, "spawnType", REASON);
		load.visitInsn(Opcodes.RETURN);
		load.visitMaxs(0, 0);
		load.visitEnd();
		MethodVisitor fin = cw.visitMethod(Opcodes.ACC_PUBLIC, "finalizeSpawn", "(" + REASON + REASON + ")V", null, null);
		fin.visitCode();
		if (producer == Producer.REASSIGNED) {
			fin.visitVarInsn(Opcodes.ALOAD, 2);
			fin.visitVarInsn(Opcodes.ASTORE, 1);
		}
		if (producer != Producer.NONE) {
			fin.visitVarInsn(Opcodes.ALOAD, 0);
			fin.visitVarInsn(Opcodes.ALOAD, producer == Producer.ITS_OWN ? 2 : 1);
			fin.visitFieldInsn(Opcodes.PUTFIELD, MOB, "spawnReason", REASON);
		}
		fin.visitVarInsn(Opcodes.ALOAD, 0);
		fin.visitVarInsn(Opcodes.ALOAD, 1);
		fin.visitFieldInsn(Opcodes.PUTFIELD, MOB, "spawnType", REASON);
		fin.visitInsn(Opcodes.RETURN);
		fin.visitMaxs(0, 0);
		fin.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] repair(byte[] mob) {
		return new ForbricMergedBaseCompatTransformer(name -> null).transform(MOB.replace('/', '.'), mob, null);
	}

	/** What getSpawnType and getSpawnReason read, in that order. */
	private static List<String> reads(byte[] mob) {
		ClassNode node = new ClassNode();
		new ClassReader(mob).accept(node, 0);
		List<String> reads = new ArrayList<>();
		for (String accessor : List.of("getSpawnType", "getSpawnReason")) {
			for (MethodNode method : node.methods) {
				if (!method.name.equals(accessor)) continue;
				for (AbstractInsnNode insn : method.instructions) if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD) reads.add(field.name);
			}
		}
		return reads;
	}
}
