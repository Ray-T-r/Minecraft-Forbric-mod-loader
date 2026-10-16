/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * LocalRanges on hand-built bodies with names of no game: a local whose table entry a splice started after a carrier's
 * reads of it gets its range back from its declaring store; a slot the compiler reused for an independent value, a stretch
 * something jumps into, and a whole range are left alone.
 */
class LocalRangesTest {
	private static final String OWNER = "fixture/ranges/Host";
	private static final String SB = "java/lang/StringBuilder";

	/** {@code StringBuilder made = new StringBuilder(); if (Hooks.cancel(made)) return; [range starts] use(made);} */
	@Test void aRangeSplicedAfterACarriersReadStartsAtItsStoreAgain() {
		LabelNode start = new LabelNode(), end = new LabelNode(), skip = start;
		MethodNode method = method("(Ljava/lang/Object;)V", code -> {
			code.add(new TypeInsnNode(Opcodes.NEW, SB));
			code.add(new InsnNode(Opcodes.DUP));
			code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, SB, "<init>", "()V", false));
			code.add(new VarInsnNode(Opcodes.ASTORE, 1));
			code.add(new VarInsnNode(Opcodes.ALOAD, 1));
			code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "cancel", "(Ljava/lang/Object;)Z", false));
			code.add(new JumpInsnNode(Opcodes.IFEQ, skip));
			code.add(new InsnNode(Opcodes.RETURN));
			code.add(start);
			code.add(new VarInsnNode(Opcodes.ALOAD, 1));
			code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "use", "(Ljava/lang/Object;)V", false));
			code.add(new InsnNode(Opcodes.RETURN));
			code.add(end);
		});
		LocalVariableNode made = local(method, "made", "L" + SB + ";", start, end, 1);
		assertEquals(1, LocalRanges.widen(OWNER, method));
		AbstractInsnNode store = previousReal(made.start);
		assertTrue(store instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ASTORE && variable.var == 1,
				"the range does not start right after the declaring store");
		assertWritable(method);
	}

	/** {@code int level = base * 2; level = hook(level); if (level < 0) return 0; [range starts] return level;} */
	@Test void anIntLocalTheCarrierUpdatesInTheStretchStartsAtItsFirstStore() {
		LabelNode start = new LabelNode(), end = new LabelNode();
		MethodNode method = method("(I)I", code -> {
			code.add(new VarInsnNode(Opcodes.ILOAD, 0));
			code.add(new InsnNode(Opcodes.ICONST_2));
			code.add(new InsnNode(Opcodes.IMUL));
			code.add(new VarInsnNode(Opcodes.ISTORE, 1));
			code.add(new VarInsnNode(Opcodes.ILOAD, 1));
			code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "hook", "(I)I", false));
			code.add(new VarInsnNode(Opcodes.ISTORE, 1));
			code.add(new VarInsnNode(Opcodes.ILOAD, 1));
			code.add(new JumpInsnNode(Opcodes.IFGE, start));
			code.add(new InsnNode(Opcodes.ICONST_0));
			code.add(new InsnNode(Opcodes.IRETURN));
			code.add(start);
			code.add(new VarInsnNode(Opcodes.ILOAD, 1));
			code.add(new InsnNode(Opcodes.IRETURN));
			code.add(end);
		});
		LocalVariableNode level = local(method, "level", "I", start, end, 1);
		assertEquals(1, LocalRanges.widen(OWNER, method));
		AbstractInsnNode store = previousReal(level.start);
		assertTrue(store instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ISTORE
				&& previousReal(store).getOpcode() == Opcodes.IMUL, "the range does not start at the first store");
		assertWritable(method);
	}

	/** {@code Object tmp = owner(); if (tmp instanceof X) ...; Object target = other(); [range starts]} — another variable. */
	@Test void aSlotReusedForAnIndependentValueIsNotTheLocal() {
		LabelNode start = new LabelNode(), end = new LabelNode();
		MethodNode method = method("(Ljava/lang/Object;)V", code -> {
			code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "owner", "()Ljava/lang/Object;", false));
			code.add(new VarInsnNode(Opcodes.ASTORE, 1));
			code.add(new VarInsnNode(Opcodes.ALOAD, 1));
			code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "use", "(Ljava/lang/Object;)V", false));
			code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "other", "()Ljava/lang/Object;", false));
			code.add(new VarInsnNode(Opcodes.ASTORE, 1));
			code.add(start);
			code.add(new VarInsnNode(Opcodes.ALOAD, 1));
			code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "use", "(Ljava/lang/Object;)V", false));
			code.add(new InsnNode(Opcodes.RETURN));
			code.add(end);
		});
		local(method, "target", "Ljava/lang/Object;", start, end, 1);
		assertEquals(0, LocalRanges.widen(OWNER, method));
	}

	/** A label inside the stretch something jumps to: a path may enter it without the store. */
	@Test void aStretchSomethingJumpsIntoIsNotWidened() {
		LabelNode start = new LabelNode(), end = new LabelNode(), inside = new LabelNode();
		MethodNode method = method("(I)I", code -> {
			code.add(new VarInsnNode(Opcodes.ILOAD, 0));
			code.add(new VarInsnNode(Opcodes.ISTORE, 1));
			code.add(inside);
			code.add(new VarInsnNode(Opcodes.ILOAD, 1));
			code.add(new JumpInsnNode(Opcodes.IFLT, inside));
			code.add(start);
			code.add(new VarInsnNode(Opcodes.ILOAD, 1));
			code.add(new InsnNode(Opcodes.IRETURN));
			code.add(end);
		});
		local(method, "value", "I", start, end, 1);
		assertEquals(0, LocalRanges.widen(OWNER, method));
	}

	/** A range that already starts at its store, as a compiler writes it. */
	@Test void aWholeRangeIsLeftAsItIs() {
		LabelNode start = new LabelNode(), end = new LabelNode();
		MethodNode method = method("(I)I", code -> {
			code.add(new VarInsnNode(Opcodes.ILOAD, 0));
			code.add(new VarInsnNode(Opcodes.ISTORE, 1));
			code.add(start);
			code.add(new VarInsnNode(Opcodes.ILOAD, 1));
			code.add(new InsnNode(Opcodes.IRETURN));
			code.add(end);
		});
		local(method, "value", "I", start, end, 1);
		assertEquals(0, LocalRanges.widen(OWNER, method));
	}

	/** Through {@link LocalRanges#transform}: a merged game class is rewritten, a mod's class (attributed) is not read. */
	@Test void onlyMergedGameClassesAreTouched() {
		LabelNode start = new LabelNode(), end = new LabelNode();
		MethodNode method = method("(I)I", code -> {
			code.add(new VarInsnNode(Opcodes.ILOAD, 0));
			code.add(new VarInsnNode(Opcodes.ISTORE, 1));
			code.add(new VarInsnNode(Opcodes.ILOAD, 1));
			code.add(new JumpInsnNode(Opcodes.IFGE, start));
			code.add(new InsnNode(Opcodes.ICONST_0));
			code.add(new InsnNode(Opcodes.IRETURN));
			code.add(start);
			code.add(new VarInsnNode(Opcodes.ILOAD, 1));
			code.add(new InsnNode(Opcodes.IRETURN));
			code.add(end);
		});
		local(method, "value", "I", start, end, 1);
		byte[] bytes = classBytes(method);
		TransformContext game = new TransformContext(net.fabricmc.api.EnvType.SERVER, false, "named");
		byte[] rewritten = new LocalRanges(owner -> owner.equals(OWNER)).transform(OWNER.replace('/', '.'), bytes, game);
		assertNotSame(bytes, rewritten);
		assertSame(bytes, new LocalRanges(owner -> false).transform(OWNER.replace('/', '.'), bytes, game));
		TransformContext mod = new TransformContext(net.fabricmc.api.EnvType.SERVER, false, "named", net.forbric.api.Ecosystem.FABRIC, "somemod");
		assertSame(bytes, new LocalRanges(owner -> true).transform(OWNER.replace('/', '.'), bytes, mod));
		ClassNode read = new ClassNode();
		new ClassReader(rewritten).accept(read, 0);
		LocalVariableNode value = read.methods.getFirst().localVariables.getFirst();
		assertTrue(previousReal(value.start) instanceof VarInsnNode store && store.getOpcode() == Opcodes.ISTORE);
	}

	private static MethodNode method(String desc, Consumer<InsnList> body) {
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "body", desc, null, null);
		LabelNode first = new LabelNode();
		method.instructions.add(first);
		body.accept(method.instructions);
		method.maxStack = 4;
		method.maxLocals = 4;
		method.localVariables = new java.util.ArrayList<>();
		return method;
	}

	private static LocalVariableNode local(MethodNode method, String name, String desc, LabelNode start, LabelNode end, int index) {
		LocalVariableNode local = new LocalVariableNode(name, desc, null, start, end, index);
		method.localVariables.add(local);
		return local;
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode node) {
		AbstractInsnNode previous = node.getPrevious();
		while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
		return previous;
	}

	private static byte[] classBytes(MethodNode method) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V1_6;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = OWNER;
		node.superName = "java/lang/Object";
		node.methods.add(method);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static void assertWritable(MethodNode method) {
		assertDoesNotThrow(() -> classBytes(method));
	}
}
