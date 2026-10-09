/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * The pass-through proof on handler bodies no compiler in the other tests writes: a primitive operand boxed on its way
 * into the array, Kotlin's parameter null check on the Operation, an array duplicated on the stack instead of kept in a
 * local — proved; an array that escapes to a field, an array of the wrong length, an original called on another
 * Operation, an operand stored after the call — not proved.
 */
class MixinOperationPassThroughTest {
	private static final String OP = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";

	/** {@code (I, Operation)I}, instance: slot 1 the int, slot 2 the Operation. */
	private static MethodNode handler(Consumer<InsnList> body) {
		MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, "handler", "(IL" + OP + ";)I", null, null);
		body.accept(method.instructions);
		method.maxStack = 8;
		method.maxLocals = 5;
		return method;
	}

	private static void call(InsnList c) {
		c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, OP, "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true));
		c.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
		c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
		c.add(new InsnNode(Opcodes.IRETURN));
	}

	private static void boxedArray(InsnList c, int length) {
		c.add(new InsnNode(Opcodes.ICONST_0 + length));
		c.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
		c.add(new InsnNode(Opcodes.DUP));
		c.add(new InsnNode(Opcodes.ICONST_0));
		c.add(new VarInsnNode(Opcodes.ILOAD, 1));
		c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
		c.add(new InsnNode(Opcodes.AASTORE));
	}

	private static boolean proves(MethodNode handler) {
		return MixinOperationPassThrough.proves("example/Owner", handler, 1, 1, Set.of(0));
	}

	@Test void aBoxedPrimitiveAndKotlinsNullCheckArePassThrough() {
		assertTrue(proves(handler(c -> { c.add(new VarInsnNode(Opcodes.ALOAD, 2)); boxedArray(c, 1); call(c); })));
		assertTrue(proves(handler(c -> {
			c.add(new VarInsnNode(Opcodes.ALOAD, 2));
			c.add(new LdcInsnNode("original"));
			c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "kotlin/jvm/internal/Intrinsics", "checkNotNullParameter", "(Ljava/lang/Object;Ljava/lang/String;)V", false));
			c.add(new VarInsnNode(Opcodes.ALOAD, 2)); boxedArray(c, 1); call(c);
		})));
		// No original call at all passes nothing on.
		assertTrue(proves(handler(c -> { c.add(new InsnNode(Opcodes.ICONST_0)); c.add(new InsnNode(Opcodes.IRETURN)); })));
	}

	@Test void anArrayThatEscapesOrHasTheWrongLengthIsNotProved() {
		assertFalse(proves(handler(c -> {
			c.add(new VarInsnNode(Opcodes.ALOAD, 2)); boxedArray(c, 1);
			c.add(new InsnNode(Opcodes.DUP));
			c.add(new FieldInsnNode(Opcodes.PUTSTATIC, "example/Owner", "kept", "[Ljava/lang/Object;"));
			call(c);
		})), "another holder of the array can change it");
		assertFalse(proves(handler(c -> { c.add(new VarInsnNode(Opcodes.ALOAD, 2)); boxedArray(c, 2); call(c); })), "two operands for a one-operand call");
	}

	@Test void anOriginalOfAnotherOperationOrAnOperandStoredLateIsNotProved() {
		assertFalse(proves(handler(c -> {
			c.add(new FieldInsnNode(Opcodes.GETSTATIC, "example/Owner", "other", "L" + OP + ";"));
			boxedArray(c, 1); call(c);
		})), "the call is made on an Operation that is not the handler's");
		assertFalse(proves(handler(c -> {
			c.add(new VarInsnNode(Opcodes.ALOAD, 2));
			c.add(new InsnNode(Opcodes.ICONST_1));
			c.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
			c.add(new VarInsnNode(Opcodes.ASTORE, 3));
			c.add(new VarInsnNode(Opcodes.ALOAD, 3));
			c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, OP, "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true));
			c.add(new InsnNode(Opcodes.POP));
			c.add(new VarInsnNode(Opcodes.ALOAD, 3));
			c.add(new InsnNode(Opcodes.ICONST_0));
			c.add(new VarInsnNode(Opcodes.ILOAD, 1));
			c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
			c.add(new InsnNode(Opcodes.AASTORE));
			c.add(new InsnNode(Opcodes.ICONST_0));
			c.add(new InsnNode(Opcodes.IRETURN));
		})), "the operand reaches the array after the call has run");
		assertFalse(proves(handler(c -> {
			c.add(new VarInsnNode(Opcodes.ALOAD, 2));
			c.add(new InsnNode(Opcodes.ICONST_1));
			c.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
			c.add(new InsnNode(Opcodes.DUP));
			c.add(new InsnNode(Opcodes.ICONST_0));
			c.add(new IntInsnNode(Opcodes.BIPUSH, 7));
			c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
			c.add(new InsnNode(Opcodes.AASTORE));
			call(c);
		})), "a constant, not the operand the handler was handed");
	}
}
