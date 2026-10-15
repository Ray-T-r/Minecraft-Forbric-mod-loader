/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * A call whose operand is a value two branches produce (a local assigned on either side of an if). After the weave
 * NeoForge's {@code ClientHooks.getFogColor} call in {@code FogRenderer.computeFogColor} takes the fog colour that way,
 * stored aside and loaded back for MixinExtras' Operation, and the definition-time proof that the call still runs
 * straight through a transported callback's Operation found no such call at all: the operand had no expression, so
 * the site was dropped, and PuzzlesLib's FOG_COLOR callback was declined on every frame. With opaque joins the operand is
 * the token of its producers, equal to itself in that method and nothing else.
 */
class NativeCallChangesJoinTest {
	/** {@code static void m(int x) { int j; if (x > 0) j = x; else j = -x; T.take(j, j, x); }} */
	private static ClassNode owner() {
		MethodNode m = new MethodNode(Opcodes.ACC_STATIC, "m", "(I)V", null, null);
		LabelNode negative = new LabelNode(), joined = new LabelNode();
		InsnList c = m.instructions;
		c.add(new VarInsnNode(Opcodes.ILOAD, 0));
		c.add(new JumpInsnNode(Opcodes.IFLE, negative));
		c.add(new VarInsnNode(Opcodes.ILOAD, 0));
		c.add(new VarInsnNode(Opcodes.ISTORE, 1));
		c.add(new JumpInsnNode(Opcodes.GOTO, joined));
		c.add(negative);
		c.add(new VarInsnNode(Opcodes.ILOAD, 0));
		c.add(new InsnNode(Opcodes.INEG));
		c.add(new VarInsnNode(Opcodes.ISTORE, 1));
		c.add(joined);
		c.add(new VarInsnNode(Opcodes.ILOAD, 1));
		c.add(new VarInsnNode(Opcodes.ILOAD, 1));
		c.add(new VarInsnNode(Opcodes.ILOAD, 0));
		c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "t/T", "take", "(III)V", false));
		c.add(new InsnNode(Opcodes.RETURN));
		m.maxLocals = 2;
		m.maxStack = 3;
		ClassNode owner = new ClassNode();
		owner.name = "t/T";
		owner.superName = "java/lang/Object";
		owner.methods.add(m);
		return owner;
	}

	@Test void aJoinedOperandIsATokenOnlyWhereAsked() {
		ClassNode owner = owner();
		MethodNode m = owner.methods.getFirst();
		assertTrue(NativeCallChanges.sites(owner, m).isEmpty(), "without joins the call has no site: the operand has no expression");
		List<NativeCallChanges.Site> sites = NativeCallChanges.sites(owner, m, true);
		assertEquals(1, sites.size());
		List<NativeCallChanges.Expr> operands = sites.getFirst().operands();
		assertEquals("join", operands.get(0).kind());
		assertEquals(operands.get(0), operands.get(1), "the same producers are the same value");
		assertEquals(new NativeCallChanges.Expr("parameter", "0"), operands.get(2), "everything else reads as before");
	}
}
