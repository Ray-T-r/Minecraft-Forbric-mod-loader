/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Where MixinCaptureLanding puts its NOP: after the call a moved capture names, counted as the adapter counted it, and
 * only where the next node is a pseudo-instruction. The weave itself, both of Mixin's local walks on it, is
 * GuiItemCaptureMixinAdapterWeaveTest.
 */
class MixinCaptureLandingTest {
	private static final String OWNER = "fixture/Target";
	private static final String SUBMIT = "Lfixture/State;submit()V";
	private static final String ADD = "Ljava/util/List;add(Ljava/lang/Object;)Z";

	@AfterEach void forget() {
		MixinCaptureLanding.clear();
		System.clearProperty(MixinCaptureLanding.PROPERTY);
	}

	@Test void aCallThatFallsIntoALabelGetsOneNopRightAfterIt() {
		ClassNode target = target(method("item", submit(), join()));
		MixinCaptureLanding.after(OWNER, target.methods.get(0), SUBMIT, -1);

		assertEquals(1, MixinCaptureLanding.land(target));
		assertEquals(List.of("ALOAD", "SUBMIT", "NOP", "LABEL", "LINE", "RETURN"), shape(target.methods.get(0)));
		// Woven again from the same node (or Mixin handed the class twice): the call already has its real instruction.
		assertEquals(0, MixinCaptureLanding.land(target));
	}

	@Test void aCallFollowedByARealInstructionAlreadyHasOne() {
		InsnList body = new InsnList();
		body.add(new VarInsnNode(Opcodes.ALOAD, 1));
		body.add(new VarInsnNode(Opcodes.ALOAD, 2));
		body.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true));
		body.add(new InsnNode(Opcodes.POP));
		ClassNode target = target(method("submit", body, join()));
		MixinCaptureLanding.after(OWNER, target.methods.get(0), ADD, -1);

		assertEquals(0, MixinCaptureLanding.land(target));
	}

	@Test void theOrdinalPicksTheCallAsTheAdapterCountedIt() {
		InsnList twice = submit();
		twice.add(new LabelNode());
		twice.add(submit());
		ClassNode target = target(method("item", twice, join()));
		MixinCaptureLanding.after(OWNER, target.methods.get(0), SUBMIT, 1);

		assertEquals(1, MixinCaptureLanding.land(target));
		assertEquals(List.of("ALOAD", "SUBMIT", "LABEL", "ALOAD", "SUBMIT", "NOP", "LABEL", "LINE", "RETURN"),
				shape(target.methods.get(0)));
	}

	@Test void onlyTheMethodAndClassTheCaptureWasMovedIn() {
		ClassNode target = target(method("item", submit(), join()), method("other", submit(), join()));
		MixinCaptureLanding.after(OWNER, target.methods.get(0), SUBMIT, -1);
		ClassNode elsewhere = target(method("item", submit(), join()));
		elsewhere.name = "fixture/Elsewhere";

		assertEquals(1, MixinCaptureLanding.land(target));
		assertEquals(List.of("ALOAD", "SUBMIT", "LABEL", "LINE", "RETURN"), shape(target.methods.get(1)));
		assertEquals(0, MixinCaptureLanding.land(elsewhere));
	}

	@Test void switchedOffTheTargetIsLeftAsItWas() {
		ClassNode target = target(method("item", submit(), join()));
		MixinCaptureLanding.after(OWNER, target.methods.get(0), SUBMIT, -1);
		System.setProperty(MixinCaptureLanding.PROPERTY, "off");

		assertEquals(0, MixinCaptureLanding.land(target));
		assertEquals(List.of("ALOAD", "SUBMIT", "LABEL", "LINE", "RETURN"), shape(target.methods.get(0)));
	}

	private static InsnList submit() {
		InsnList call = new InsnList();
		call.add(new VarInsnNode(Opcodes.ALOAD, 1));
		call.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "fixture/State", "submit", "()V", false));
		return call;
	}

	/** The join the tail sits on: its label and line number, then the return. */
	private static InsnList join() {
		InsnList join = new InsnList();
		LabelNode label = new LabelNode();
		join.add(label);
		join.add(new LineNumberNode(9, label));
		join.add(new InsnNode(Opcodes.RETURN));
		return join;
	}

	private static MethodNode method(String name, InsnList body, InsnList tail) {
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, "(Lfixture/State;)V", null, null);
		method.instructions.add(body);
		method.instructions.add(tail);
		return method;
	}

	private static ClassNode target(MethodNode... methods) {
		ClassNode target = new ClassNode();
		target.name = OWNER;
		target.methods = new ArrayList<>(List.of(methods));
		return target;
	}

	private static List<String> shape(MethodNode method) {
		List<String> shape = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			shape.add(switch (insn) {
				case LabelNode label -> "LABEL";
				case LineNumberNode line -> "LINE";
				case MethodInsnNode call -> call.name.toUpperCase();
				case VarInsnNode load -> "ALOAD";
				default -> insn.getOpcode() == Opcodes.NOP ? "NOP" : insn.getOpcode() == Opcodes.RETURN ? "RETURN" : "?" + insn.getOpcode();
			});
		}
		return shape;
	}
}
