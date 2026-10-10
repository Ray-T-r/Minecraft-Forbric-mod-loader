/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The extracted-call seam follows the call an {@code @At(INVOKE)} target selects as Mixin matches it, however the target
 * is written: the name, and the owner and descriptor wherever it gives them. The fixture is invented: a lamp whose final
 * {@code Bulb.shine} call the merge moved into a static hook, which makes it first and unconditionally.
 */
class NativeCallbackSeamSpellingTest {
	private static final String LAMP = "brisk/Lamp", BULB = "brisk/Bulb", HOOKS = "brisk/LampHooks", GLOW = "(Lbrisk/Bulb;F)V";
	private static final String SHINE = "L" + BULB + ";shine(F)V";

	@Test
	void theTargetIsReadAsMixinMatchesItWhereverItIsWrittenTheSameCall() {
		for (String target : List.of(SHINE, "brisk.Bulb.shine(F)V", " Lbrisk/Bulb; shine (F)V ", "L" + BULB + ";shine", "shine(F)V", "shine")) {
			NativeCallbackSeam.Plan plan = derive(source(false), current(false), target);
			assertNotNull(plan, target);
			assertEquals(SHINE, plan.member(), target + " names the one call it selects where the callback was written");
			assertEquals(HOOKS, plan.helper());
			assertEquals("glowAll" + GLOW, plan.helperMethod());
			assertArrayEquals(new int[] {0, 1}, plan.hostParameters());
		}
	}

	@Test
	void aTargetSelectingAnotherCallNoCallOrTwoMembersIsNoSeam() {
		for (String target : List.of("Lbrisk/Torch;shine(F)V", "shine(I)V", "brisk.Bulb.dim(F)V"))
			assertNull(derive(source(false), current(false), target), target + " selects no call where the callback was written");
		// A name alone selects the torch's shine too: two members, which one was meant cannot be told.
		assertNull(derive(source(true), current(false), "shine"));
		assertNull(derive(source(true), current(false), "shine(F)V"));
		assertNotNull(derive(source(true), current(false), "Lbrisk/Bulb;shine"), "the owner keeps the bulb's call alone");
		// The merged body still makes a call the owner-less target selects: Mixin injects there, no seam is needed.
		assertNull(derive(source(false), current(true), "shine(F)V"));
		assertNotNull(derive(source(false), current(true), SHINE), "the bulb's call alone is gone from the merged body");
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static NativeCallbackSeam.Plan derive(ClassNode source, ClassNode current, String target) {
		Map<String, ClassNode> classes = Map.of(HOOKS, hooks());
		return NativeCallbackSeam.derive(source, current, NativeCallChanges.method(source, "glow" + GLOW), target, classes::get);
	}

	/**
	 * The lamp the mod was compiled against: {@code bulb.shine(level)} last. With {@code torch}, its body also holds a
	 * torch's shine after the return: the same prefix and final call, and one more call a bare name selects.
	 */
	private static ClassNode source(boolean torch) {
		ClassNode node = node(LAMP);
		MethodNode glow = new MethodNode(Opcodes.ACC_PUBLIC, "glow", GLOW, null, null);
		add(glow, new VarInsnNode(Opcodes.ALOAD, 1), new VarInsnNode(Opcodes.FLOAD, 2),
				new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BULB, "shine", "(F)V", false), new InsnNode(Opcodes.RETURN));
		if (torch) add(glow, new InsnNode(Opcodes.ACONST_NULL), new VarInsnNode(Opcodes.FLOAD, 2),
				new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "brisk/Torch", "shine", "(F)V", false), new InsnNode(Opcodes.RETURN));
		node.methods.add(glow);
		return node;
	}

	/**
	 * The merged lamp: the call went into a static hook. With {@code torch}, its body still holds a torch's shine after the
	 * return — the same prefix and the same final call, and one more call an owner-less target selects.
	 */
	private static ClassNode current(boolean torch) {
		ClassNode node = node(LAMP);
		MethodNode glow = new MethodNode(Opcodes.ACC_PUBLIC, "glow", GLOW, null, null);
		add(glow, new VarInsnNode(Opcodes.ALOAD, 1), new VarInsnNode(Opcodes.FLOAD, 2),
				new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "glowAll", GLOW, false), new InsnNode(Opcodes.RETURN));
		if (torch) add(glow, new InsnNode(Opcodes.ACONST_NULL), new VarInsnNode(Opcodes.FLOAD, 2),
				new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "brisk/Torch", "shine", "(F)V", false), new InsnNode(Opcodes.RETURN));
		node.methods.add(glow);
		return node;
	}

	/** The hook: the bulb shines first and unconditionally, then the hook's own work. */
	private static ClassNode hooks() {
		ClassNode node = node(HOOKS);
		MethodNode glowAll = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "glowAll", GLOW, null, null);
		add(glowAll, new VarInsnNode(Opcodes.ALOAD, 0), new VarInsnNode(Opcodes.FLOAD, 1),
				new MethodInsnNode(Opcodes.INVOKEVIRTUAL, BULB, "shine", "(F)V", false),
				new MethodInsnNode(Opcodes.INVOKESTATIC, HOOKS, "flicker", "()V", false), new InsnNode(Opcodes.RETURN));
		node.methods.add(glowAll);
		return node;
	}

	private static ClassNode node(String name) {
		ClassNode node = new ClassNode();
		node.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
		return node;
	}

	private static void add(MethodNode method, AbstractInsnNode... instructions) {
		for (AbstractInsnNode instruction : instructions) method.instructions.add(instruction);
	}
}
