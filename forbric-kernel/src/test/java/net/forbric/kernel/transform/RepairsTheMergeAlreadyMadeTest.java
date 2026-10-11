/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Two compat repairs on bases whose merge already does part of their work, the shapes the edit-spliced merge emits.
 * Invented classes under the game's names, so each shape and its near misses are exact.
 *
 * <p>{@code Mob}: the merge keeps both families' spawn fields, and a base may write both from one statement each
 * ({@code this.spawnReason = reason; this.spawnType = reason;}) while the save is read and written through
 * {@code spawnType} alone. That mirrored write adds nothing {@code spawnType} lacks, so the read still moves; a write
 * {@code spawnType} does not repeat (another value, or control that can arrive between the two) keeps the field live.
 *
 * <p>{@code ModelManager}: a base whose {@code reload} already starts with MinecraftForge's
 * {@code GeometryLoaderManager.init()} needs no kernel call, and the repair's claim is met without a change.
 */
class RepairsTheMergeAlreadyMadeTest implements Opcodes {
	private static final String MOB = "net/minecraft/world/entity/Mob";
	private static final String REASON = "Lnet/minecraft/world/entity/EntitySpawnReason;";
	private static final String SPAWN_CLAIM = "forbric-merged-base-compat#readTheSpawnReasonThatIsActuallyWritten";
	private static final String MODELS = "net/minecraft/client/resources/model/ModelManager";
	private static final String RELOAD = "(Lnet/minecraft/server/packs/resources/PreparableReloadListener$SharedState;Ljava/util/concurrent/Executor;"
			+ "Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Ljava/util/concurrent/Executor;)"
			+ "Ljava/util/concurrent/CompletableFuture;";
	private static final String GEOMETRY_CLAIM = "forbric-merged-base-compat#restoreForgeGeometryReload";

	private enum Write { NONE, MIRRORED_AFTER, MIRRORED_BEFORE, OTHER_VALUE, ALONE, ENTRY_BETWEEN }

	@Test void aSpawnReasonWriteSpawnTypeRepeatsLeavesTheFieldDeadAndTheReadMoves() {
		for (Write write : List.of(Write.NONE, Write.MIRRORED_AFTER, Write.MIRRORED_BEFORE)) {
			List<String> hits = new ArrayList<>();
			ClassNode after = parse(new ForbricMergedBaseCompatTransformer().transform(MOB.replace('/', '.'), mob(write), null, hits::add));
			assertTrue(hits.contains(SPAWN_CLAIM), write + ": " + hits);
			assertEquals("spawnType", read(method(after, "getSpawnReason")), write + ": the accessor reads the field the save restores");
			assertEquals(write == Write.NONE ? 0 : 1, writes(after, "spawnReason"), write + ": only the read moves");
		}
	}

	@Test void aSpawnReasonWriteSpawnTypeDoesNotRepeatKeepsTheFieldLive() {
		for (Write write : List.of(Write.OTHER_VALUE, Write.ALONE, Write.ENTRY_BETWEEN)) {
			byte[] in = mob(write);
			List<String> hits = new ArrayList<>();
			byte[] out = new ForbricMergedBaseCompatTransformer().transform(MOB.replace('/', '.'), in, null, hits::add);
			assertFalse(hits.contains(SPAWN_CLAIM), write + ": " + hits);
			assertEquals("spawnReason", read(method(parse(out), "getSpawnReason")), write + ": a live field keeps its read");
		}
	}

	@Test void aReloadThatAlreadyInitialisesForgeGeometryMeetsTheClaimUnchanged() {
		for (String owner : List.of("net/minecraftforge/client/model/geometry/GeometryLoaderManager", "net/forbric/kernel/runtime/KernelForgeClientInit")) {
			byte[] in = models(owner, owner.endsWith("GeometryLoaderManager") ? "init" : "initGeometryLoaders");
			List<String> hits = new ArrayList<>();
			assertSame(in, new ForbricMergedBaseCompatTransformer().transform(MODELS.replace('/', '.'), in, null, hits::add), owner);
			assertTrue(hits.contains(GEOMETRY_CLAIM), owner + ": " + hits);
		}
		// Elsewhere than first, or another static call first: not where MinecraftForge initialises, so not met.
		ClassNode notFirst = parse(models("net/minecraftforge/client/model/geometry/GeometryLoaderManager", "init"));
		MethodNode reload = notFirst.methods.stream().filter(m -> m.name.equals("reload")).findFirst().orElseThrow();
		AbstractInsnNode call = reload.instructions.getFirst();
		while (call.getOpcode() < 0) call = call.getNext();
		reload.instructions.remove(call);
		reload.instructions.insertBefore(reload.instructions.getLast(), call);
		assertFalse(ForbricMergedBaseCompatTransformer.forgeGeometryReloadAlreadyInPlace(notFirst));
		assertFalse(ForbricMergedBaseCompatTransformer.forgeGeometryReloadAlreadyInPlace(parse(models("java/lang/Thread", "dumpStack"))));
	}

	/** A Mob with both spawn fields, the save path through spawnType, and {@code finalizeSpawn} writing as {@code write} says. */
	private static byte[] mob(Write write) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(V1_6, ACC_PUBLIC, MOB, null, "java/lang/Object", null);
		cw.visitField(ACC_PRIVATE, "spawnReason", REASON, null, null).visitEnd();
		cw.visitField(ACC_PRIVATE, "spawnType", REASON, null, null).visitEnd();
		for (String[] getter : new String[][] {{"getSpawnReason", "spawnReason"}, {"getSpawnType", "spawnType"}}) {
			MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, getter[0], "()" + REASON, null, null);
			mv.visitCode();
			mv.visitVarInsn(ALOAD, 0);
			mv.visitFieldInsn(GETFIELD, MOB, getter[1], REASON);
			mv.visitInsn(ARETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		// The save restores spawnType only.
		MethodVisitor load = cw.visitMethod(ACC_PRIVATE, "restore", "(" + REASON + ")V", null, null);
		load.visitCode();
		put(load, "spawnType", 1);
		load.visitInsn(RETURN);
		load.visitMaxs(0, 0);
		load.visitEnd();
		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "finalizeSpawn", "(" + REASON + REASON + "Z)V", null, null);
		mv.visitCode();
		switch (write) {
			case NONE -> put(mv, "spawnType", 1);
			case MIRRORED_AFTER -> { put(mv, "spawnReason", 1); put(mv, "spawnType", 1); }
			case MIRRORED_BEFORE -> { put(mv, "spawnType", 1); put(mv, "spawnReason", 1); }
			case OTHER_VALUE -> { put(mv, "spawnReason", 2); put(mv, "spawnType", 1); }
			case ALONE -> put(mv, "spawnReason", 1);
			case ENTRY_BETWEEN -> {
				Label between = new Label();
				mv.visitVarInsn(ILOAD, 3);
				mv.visitJumpInsn(IFNE, between);
				put(mv, "spawnReason", 1);
				mv.visitLabel(between);
				put(mv, "spawnType", 1);
			}
		}
		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void put(MethodVisitor mv, String field, int local) {
		mv.visitVarInsn(ALOAD, 0);
		mv.visitVarInsn(ALOAD, local);
		mv.visitFieldInsn(PUTFIELD, MOB, field, REASON);
	}

	/** A ModelManager whose {@code reload} starts with {@code owner.name()V}, then MinecraftForge's own next step. */
	private static byte[] models(String owner, String name) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(V1_6, ACC_PUBLIC, MODELS, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "reload", RELOAD, null, null);
		mv.visitCode();
		mv.visitMethodInsn(INVOKESTATIC, owner, name, "()V", false);
		mv.visitVarInsn(ALOAD, 1);
		mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/server/packs/resources/PreparableReloadListener$SharedState", "resourceManager",
				"()Lnet/minecraft/server/packs/resources/ResourceManager;", false);
		mv.visitInsn(POP);
		mv.visitInsn(ACONST_NULL);
		mv.visitInsn(ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name) {
		return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
	}

	private static String read(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == GETFIELD) return field.name;
		}
		return null;
	}

	private static int writes(ClassNode node, String field) {
		int n = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == PUTFIELD && f.name.equals(field)) n++;
			}
		}
		return n;
	}
}
