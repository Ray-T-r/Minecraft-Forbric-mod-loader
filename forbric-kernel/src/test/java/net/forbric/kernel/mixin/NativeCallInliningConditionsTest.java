package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The branch conditions the inliner compares between the native and the merged method: a condition decides a call only
 * when every way to the call passes it, exception edges included — never a branch a path from the entry goes around.
 */
class NativeCallInliningConditionsTest {
	private static final String OWNER = "org/sample/Owner";

	/** {@code if (a) target();} — the call is made only when {@code a} holds. */
	@Test void aBranchEveryWayPassesDecidesTheCall() {
		MethodNode method = method();
		Label skip = new Label();
		method.visitVarInsn(Opcodes.ILOAD, 1);
		method.visitJumpInsn(Opcodes.IFEQ, skip);
		target(method);
		method.visitLabel(skip);
		method.visitInsn(Opcodes.RETURN);
		assertEquals(1, conditions(method).size(), conditions(method).toString());
	}

	/**
	 * {@code if (a) { if (b) return; } target();} — when {@code a} is false the call is made without {@code b} ever being
	 * asked, so {@code b} decides nothing about it (and neither does {@code a}: both ways out of it reach the call).
	 */
	@Test void aBranchAPathGoesAroundDecidesNothing() {
		MethodNode method = method();
		Label call = new Label();
		method.visitVarInsn(Opcodes.ILOAD, 1);
		method.visitJumpInsn(Opcodes.IFEQ, call);
		method.visitVarInsn(Opcodes.ILOAD, 2);
		method.visitJumpInsn(Opcodes.IFEQ, call);
		method.visitInsn(Opcodes.RETURN);
		method.visitLabel(call);
		target(method);
		method.visitInsn(Opcodes.RETURN);
		assertEquals(Set.of(), conditions(method));
	}

	/**
	 * {@code try { if (a) return; } catch (RuntimeException e) {} target();} — the handler is a way to the call that does
	 * not pass {@code a}.
	 */
	@Test void anExceptionEdgeIsAWayAround() {
		MethodNode method = method();
		Label start = new Label(), end = new Label(), handler = new Label(), call = new Label();
		method.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException");
		method.visitLabel(start);
		method.visitVarInsn(Opcodes.ILOAD, 1);
		method.visitJumpInsn(Opcodes.IFEQ, call);
		method.visitInsn(Opcodes.RETURN);
		method.visitLabel(end);
		method.visitLabel(handler);
		method.visitInsn(Opcodes.POP);
		method.visitLabel(call);
		target(method);
		method.visitInsn(Opcodes.RETURN);
		assertEquals(Set.of(), conditions(method));
	}

	/** {@code for (;;) { if (a) target(); if (b) return; }} — the loop's way back through {@code a} does not make it hold. */
	@Test void aLoopBackThroughTheBranchStillLeavesItDeciding() {
		MethodNode method = method();
		Label top = new Label(), after = new Label();
		method.visitLabel(top);
		method.visitVarInsn(Opcodes.ILOAD, 1);
		method.visitJumpInsn(Opcodes.IFEQ, after);
		target(method);
		method.visitLabel(after);
		method.visitVarInsn(Opcodes.ILOAD, 2);
		method.visitJumpInsn(Opcodes.IFEQ, top);
		method.visitInsn(Opcodes.RETURN);
		Set<String> found = conditions(method);
		assertEquals(1, found.size(), found.toString());
		assertTrue(found.iterator().next().contains("parameter 0"), found.toString());
	}

	private static MethodNode method() {
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "decide", "(ZZ)V", null, null);
		method.visitCode();
		return method;
	}

	private static void target(MethodNode method) {
		method.visitVarInsn(Opcodes.ALOAD, 0);
		method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OWNER, "target", "()V", false);
	}

	private static Set<String> conditions(MethodNode method) {
		method.visitMaxs(4, 3);
		NativeCallInlining.Conditions conditions = NativeCallInlining.Conditions.of(OWNER, method, Integer.MAX_VALUE);
		assertNotNull(conditions);
		for (AbstractInsnNode instruction : method.instructions)
			if (instruction instanceof MethodInsnNode call && call.name.equals("target")) return conditions.of(call);
		throw new AssertionError("no call");
	}
}
