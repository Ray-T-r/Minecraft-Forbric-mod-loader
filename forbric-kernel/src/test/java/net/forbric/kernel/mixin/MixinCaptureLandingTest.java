/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.FabricUtil;

/**
 * Where MixinCaptureLanding puts its NOP: in a method a mixin below 0.10.0 is being applied to, after the call its moved
 * capture's point binds, counted as Mixin counts it, only where the next node is a pseudo-instruction, and not where an
 * applied mixin shifts a point BY in that method. The weave itself, both of Mixin's local walks on it, is
 * GuiItemCaptureMixinAdapterWeaveTest and TwinCaptureLandingWeaveTest.
 */
class MixinCaptureLandingTest {
	private static final String OWNER = "fixture/Target";
	private static final String SUBMIT = "Lfixture/State;submit()V";
	private static final String ADD = "Ljava/util/List;add(Ljava/lang/Object;)Z";
	private static final int LEGACY = FabricUtil.COMPATIBILITY_0_9_2;

	@AfterEach void forget() {
		MixinCaptureLanding.clear();
		System.clearProperty(MixinCaptureLanding.PROPERTY);
	}

	@Test void aCallThatFallsIntoALabelGetsOneNopRightAfterIt() {
		ClassNode target = target(method("item", submit(), join()));
		ClassNode mixin = moved("fixture/mod/Capture", "item", SUBMIT, -1);

		assertEquals(1, MixinCaptureLanding.land(target, List.of(applied(mixin, LEGACY))));
		assertEquals(List.of("ALOAD", "SUBMIT", "NOP", "LABEL", "LINE", "RETURN"), shape(target.methods.get(0)));
		// Woven again from the same node: the call already has its real instruction.
		assertEquals(0, MixinCaptureLanding.land(target, List.of(applied(mixin, LEGACY))));
	}

	/** From 0.10.0 on, Mixin's local walk stops before the join's frame: the capture needs nothing, and the body is kept. */
	@Test void fromZeroTenTheTargetIsLeftAsItIs() {
		ClassNode target = target(method("item", submit(), join()));
		ClassNode mixin = moved("fixture/mod/Capture", "item", SUBMIT, -1);

		assertEquals(0, MixinCaptureLanding.land(target, List.of(applied(mixin, FabricUtil.COMPATIBILITY_0_10_0))));
		assertEquals(List.of("ALOAD", "SUBMIT", "LABEL", "LINE", "RETURN"), shape(target.methods.get(0)));
	}

	@Test void aCallFollowedByARealInstructionAlreadyHasOne() {
		InsnList body = new InsnList();
		body.add(new VarInsnNode(Opcodes.ALOAD, 1));
		body.add(new VarInsnNode(Opcodes.ALOAD, 2));
		body.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true));
		body.add(new InsnNode(Opcodes.POP));
		ClassNode target = target(method("submit", body, join()));

		assertEquals(0, MixinCaptureLanding.land(target, List.of(applied(moved("fixture/mod/Capture", "submit", ADD, -1), LEGACY))));
	}

	@Test void theOrdinalPicksTheCallAsMixinCountsIt() {
		InsnList twice = submit();
		twice.add(new LabelNode());
		twice.add(submit());
		ClassNode target = target(method("item", twice, join()));

		assertEquals(1, MixinCaptureLanding.land(target, List.of(applied(moved("fixture/mod/Capture", "item", SUBMIT, 1), LEGACY))));
		assertEquals(List.of("ALOAD", "SUBMIT", "LABEL", "ALOAD", "SUBMIT", "NOP", "LABEL", "LINE", "RETURN"),
				shape(target.methods.get(0)));
	}

	/** A moved capture whose mixin is not being applied — another class's, or one its config plugin refused — lands nowhere. */
	@Test void onlyWhileTheMovedCapturesMixinIsApplied() {
		moved("fixture/mod/Capture", "item", SUBMIT, -1);
		ClassNode target = target(method("item", submit(), join()));
		ClassNode other = mixin("fixture/mod/Other", handler("other$tail", "item", at("TAIL", null, "NONE", -1)));

		assertEquals(0, MixinCaptureLanding.land(target, List.of(applied(other, LEGACY))));
		assertEquals(List.of("ALOAD", "SUBMIT", "LABEL", "LINE", "RETURN"), shape(target.methods.get(0)));
	}

	/** The landing follows every class the mixin is applied to, a merged twin the adapter never read included. */
	@Test void anyClassTheMixinIsAppliedToGetsItsLanding() {
		ClassNode mixin = moved("fixture/mod/Capture", "item", SUBMIT, -1);
		ClassNode twin = target(method("item", submit(), join()), method("other", submit(), join()));
		twin.name = OWNER + MixinMergedTwin.NEO_SUFFIX;

		assertEquals(1, MixinCaptureLanding.land(twin, List.of(applied(mixin, LEGACY))));
		assertEquals(List.of("ALOAD", "SUBMIT", "LABEL", "LINE", "RETURN"), shape(twin.methods.get(1)),
				"only the method the handler binds");
	}

	/** Shift.BY counts every node: a NOP would move another point across the call, so that method keeps its body. */
	@Test void aPointShiftedByInTheMethodKeepsTheBodyAsItIs() {
		ClassNode mixin = moved("fixture/mod/Capture", "item", SUBMIT, -1);
		ClassNode shifting = mixin("fixture/mod/Shifting", handler("shifting$by", "item", at("INVOKE", SUBMIT, "BY", -1)));
		ClassNode elsewhere = mixin("fixture/mod/Elsewhere", handler("elsewhere$by", "other", at("INVOKE", SUBMIT, "BY", -1)));
		ClassNode target = target(method("item", submit(), join()), method("other", submit(), join()));

		assertEquals(0, MixinCaptureLanding.land(target, List.of(applied(mixin, LEGACY), applied(shifting, 17001))));
		assertEquals(List.of("ALOAD", "SUBMIT", "LABEL", "LINE", "RETURN"), shape(target.methods.get(0)));
		assertEquals(1, MixinCaptureLanding.land(target, List.of(applied(mixin, LEGACY), applied(elsewhere, 17001))),
				"a BY in another method moves nothing here");
	}

	/** A slice bound shifted BY counts as well: it is an @At inside the injector. */
	@Test void aSliceBoundShiftedByCountsToo() {
		ClassNode mixin = moved("fixture/mod/Capture", "item", SUBMIT, -1);
		MethodNode sliced = handler("sliced$head", "item", at("HEAD", null, "NONE", -1));
		AnnotationNode slice = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Slice;");
		slice.values = new ArrayList<>(List.of("to", at("INVOKE", SUBMIT, "BY", -1)));
		MixinFit.injectorOf(sliced).values.addAll(List.of("slice", List.of(slice)));
		ClassNode target = target(method("item", submit(), join()));

		assertEquals(0, MixinCaptureLanding.land(target, List.of(applied(mixin, LEGACY), applied(mixin("fixture/mod/Sliced", sliced), 17001))));
	}

	@Test void switchedOffTheTargetIsLeftAsItWas() {
		ClassNode target = target(method("item", submit(), join()));
		ClassNode mixin = moved("fixture/mod/Capture", "item", SUBMIT, -1);
		System.setProperty(MixinCaptureLanding.PROPERTY, "off");

		assertEquals(0, MixinCaptureLanding.land(target, List.of(applied(mixin, LEGACY))));
		assertEquals(List.of("ALOAD", "SUBMIT", "LABEL", "LINE", "RETURN"), shape(target.methods.get(0)));
	}

	/** A mixin whose one handler is a capture the adapter moved to {@code INVOKE member, AFTER} in {@code method}. */
	private static ClassNode moved(String name, String method, String member, int ordinal) {
		MethodNode handler = handler("capture$tail", method, at("INVOKE", member, "AFTER", ordinal));
		ClassNode mixin = mixin(name, handler);
		MixinCaptureLanding.moved(mixin, handler);
		return mixin;
	}

	private static AnnotationNode at(String value, String target, String shift, int ordinal) {
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", value));
		if (target != null) at.values.addAll(List.of("target", target));
		if (!"NONE".equals(shift)) {
			at.values.addAll(List.of("shift", new String[] {"Lorg/spongepowered/asm/mixin/injection/At$Shift;", shift}));
		}
		if ("BY".equals(shift)) at.values.addAll(List.of("by", 2));
		if (ordinal >= 0) at.values.addAll(List.of("ordinal", ordinal));
		return at;
	}

	private static MethodNode handler(String name, String method, AnnotationNode at) {
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, name,
				"(Lfixture/State;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", List.of(method), "at", List.of(at)));
		handler.invisibleAnnotations = new ArrayList<>(List.of(inject));
		return handler;
	}

	private static ClassNode mixin(String name, MethodNode... handlers) {
		ClassNode mixin = new ClassNode();
		mixin.name = name;
		mixin.methods = new ArrayList<>(List.of(handlers));
		return mixin;
	}

	private static MixinCaptureLanding.Applied applied(ClassNode mixin, int level) {
		return new MixinCaptureLanding.Applied(mixin.name, level, () -> mixin);
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
