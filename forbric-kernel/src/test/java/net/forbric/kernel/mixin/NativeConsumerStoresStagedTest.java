/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * On the staged merged base, vanilla's {@code BucketItem.playEmptySound} stores its sound once and hands it straight to
 * {@code playSound}; the merged method stores it three times on the carriers' paths. A modifier written for the one native
 * store (as a guest names it: {@code name = "soundEvent"}) moves to the {@code playSound} read — the one read that comes
 * after all three stores. The test names the local as a guest's {@code @ModifyVariable} does; nothing in the proof names a
 * class, a method or a mod.
 */
class NativeConsumerStoresStagedTest {
	private static ClassNode staged(String entry) {
		return staged(TestFixtures.stagedBase(), entry);
	}

	private static ClassNode staged(Path jar, String entry) {
		ClassNode node = new ClassNode();
		new ClassReader(TestFixtures.requireEntry(Fixture.STAGED, jar, entry)).accept(node, 0);
		return node;
	}

	@Test void theSoundModifierMovesToThePlaySoundRead() {
		String owner = "net/minecraft/world/item/BucketItem";
		ClassNode merged = staged(owner + ".class"), vanilla = staged(TestFixtures.mergedBase(), // the reference the merge embeds
				"META-INF/forbric/native-reference/FABRIC/" + owner + ".class.bin");
		String method = "playEmptySound(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;)V";
		MethodNode host = NativeCallInlining.method(merged, method), was = NativeCallInlining.method(vanilla, method);
		AnnotationNode injector = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/ModifyVariable;");
		injector.values = new ArrayList<>(List.of("name", List.of("soundEvent")));
		int nativeSlot = NativeConsumerStores.slot(injector, was), mergedSlot = NativeConsumerStores.slot(injector, host);
		assertTrue(nativeSlot >= 0 && mergedSlot >= 0);
		int ordinal = NativeConsumerStores.consumer(was, nativeSlot, host, mergedSlot, owner, owner);
		assertTrue(ordinal >= 0, "the playSound read is found");
		// The read the ordinal names is the one playSound consumes.
		int reads = 0;
		AbstractInsnNode read = null;
		for (AbstractInsnNode insn : host.instructions)
			if (insn instanceof VarInsnNode variable && variable.var == mergedSlot && variable.getOpcode() == org.objectweb.asm.Opcodes.ALOAD && reads++ == ordinal) read = insn;
		assertNotNull(read);
		AbstractInsnNode next = read.getNext();
		while (next != null && !(next instanceof MethodInsnNode)) next = next.getNext();
		assertTrue(next instanceof MethodInsnNode call && call.name.equals("playSound"), "the moved read feeds playSound");
	}
}
