/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Which instructions take a value off the operand stack — what an analyzer's source frames say only from the producer's
 * side. The copying instructions ({@code DUP*}, {@code SWAP}) and the wide pop count as taking whatever they may reach:
 * their copies no longer name the original producer, so a reader that needs every use of a value must refuse them.
 */
final class OperandStack {
	/** What the copying instructions and the wide pop may reach. */
	static final int COPIES = 4;

	private OperandStack() {
	}

	/** How many stack entries {@code insn} pops (entries, not words: a long or double is one). */
	static int pops(AbstractInsnNode insn) {
		int opcode = insn.getOpcode();
		if (opcode < 0) return 0;
		if (insn instanceof MethodInsnNode call) return Type.getArgumentTypes(call.desc).length + (opcode == Opcodes.INVOKESTATIC ? 0 : 1);
		if (insn instanceof InvokeDynamicInsnNode indy) return Type.getArgumentTypes(indy.desc).length;
		if (insn instanceof FieldInsnNode) return opcode == Opcodes.GETSTATIC ? 0 : opcode == Opcodes.PUTFIELD ? 2 : 1;
		if (insn instanceof MultiANewArrayInsnNode array) return array.dims;
		if (opcode <= Opcodes.ALOAD || opcode == Opcodes.NEW || opcode == Opcodes.GOTO || opcode == Opcodes.JSR || opcode == Opcodes.RET
				|| opcode == Opcodes.RETURN || opcode == Opcodes.IINC) return 0;   // constants, loads, and what touches no operand
		if (opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD) return 2;
		if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) return 1;
		if (opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE) return 3;
		if (opcode == Opcodes.POP) return 1;
		if (opcode >= Opcodes.POP2 && opcode <= Opcodes.SWAP) return COPIES;
		if (opcode >= Opcodes.IADD && opcode <= Opcodes.DREM || opcode >= Opcodes.ISHL && opcode <= Opcodes.LXOR
				|| opcode >= Opcodes.LCMP && opcode <= Opcodes.DCMPG || opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ACMPNE) return 2;
		return 1;   // negations, conversions, one-operand branches, switches, returns, throw, casts, arrays, monitors
	}

	/** Whether {@code opcode} copies or reorders stack entries instead of consuming them. */
	static boolean copies(int opcode) {
		return opcode >= Opcodes.POP2 && opcode <= Opcodes.SWAP;
	}

	/** One use of a value: the instruction that pops it and the entry's depth below the top of its frame (0 = the top). */
	record Use(AbstractInsnNode instruction, int fromTop) {
	}

	/** Every instruction of {@code method} that pops a stack entry {@code producer} pushed, with that entry's depth. */
	static List<Use> uses(MethodNode method, Frame<SourceValue>[] frames, AbstractInsnNode producer) {
		List<Use> out = new ArrayList<>();
		AbstractInsnNode[] code = method.instructions.toArray();
		for (int j = 0; j < code.length; j++) {
			Frame<SourceValue> frame = frames[j];
			if (frame == null) continue;
			int pops = pops(code[j]);
			for (int depth = 0; depth < frame.getStackSize(); depth++) {
				if (!frame.getStack(depth).insns.contains(producer)) continue;
				int fromTop = frame.getStackSize() - 1 - depth;
				if (fromTop < pops) out.add(new Use(code[j], fromTop));
			}
		}
		return out;
	}
}
