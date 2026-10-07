/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** NeoForge joins the key-release and key-press exits; retain the separate callbacks. */
public final class MixinKeyActionAdapter {
	public static final String PROPERTY = "forbric.keyActionCallbacks";
	static final String TARGET = "net/minecraft/client/KeyboardHandler";
	static final String EVENT = "Lnet/minecraft/client/input/KeyEvent;";
	static final String HOST = "keyPress(JI" + EVENT + ")V";
	static final String HANDLER = "(JI" + EVENT + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
	private MixinKeyActionAdapter() { }

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!MixinCallbackShape.targets(mixin, TARGET) || "off".equalsIgnoreCase(System.getProperty(PROPERTY))) return 0;
		ClassNode target = targets.apply(TARGET);
		MethodNode host = target == null ? null : MixinPlayerWorldCallbackAdapter.selector(target, HOST);
		MethodNode released = MixinCallbackShape.unique(mixin, m -> source(m, false));
		MethodNode pressed = MixinCallbackShape.unique(mixin, m -> source(m, true));
		if (host == null || released == null || pressed == null) return 0;
		String live = "Lnet/neoforged/neoforge/client/ClientHooks;onKeyInput(" + EVENT + "I)V";
		if (MixinPlayerWorldCallbackAdapter.count(host, live) != 1) return 0;
		AnnotationNode release = MixinFit.injectorOf(released), press = MixinFit.injectorOf(pressed);
		if (!MixinPlayerWorldCallbackAdapter.selects(release, HOST) || !MixinPlayerWorldCallbackAdapter.selects(press, HOST)) return 0;
		List<AnnotationNode> ra = MixinFit.atNodes(release), pa = MixinFit.atNodes(press);
		if (ra.size() != 1 || pa.size() != 1 || !"RETURN".equals(MixinFit.value(ra.getFirst(), "value"))
				|| !Integer.valueOf(5).equals(MixinFit.value(ra.getFirst(), "ordinal"))
				|| !"TAIL".equals(MixinFit.value(pa.getFirst(), "value"))) return 0;
		if (!originalBody(mixin, released, false) || !originalBody(mixin, pressed, true)) return 0;
        MethodInsnNode releaseCall = java.util.Arrays.stream(released.instructions.toArray()).filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode)i).findFirst().orElseThrow();
        MethodInsnNode pressCall = java.util.Arrays.stream(pressed.instructions.toArray()).filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode)i).findFirst().orElseThrow();
        if (!releaseCall.name.equals(pressCall.name) || releaseCall.getOpcode() != pressCall.getOpcode()) return 0;
		MixinPlayerWorldCallbackAdapter.set(ra.getFirst(), "value", "INVOKE");
		MixinPlayerWorldCallbackAdapter.set(ra.getFirst(), "target", live);
		MixinPlayerWorldCallbackAdapter.set(ra.getFirst(), "ordinal", 0);
		guardAction(released, Opcodes.IFNE); guardAction(pressed, Opcodes.IFEQ);
		return 2;
	}

	private static boolean source(MethodNode method, boolean pressed) {
        if (!HANDLER.equals(method.desc) || !MixinCallbackShape.instance(method) || !MixinCallbackShape.kind(method, "Inject") || !MixinCallbackShape.selects(method, HOST)
                || !MixinCallbackShape.point(method, pressed ? "TAIL" : "RETURN", null)) return false;
        Object ordinal = MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(method)).getFirst(), "ordinal");
        return pressed ? ordinal == null : Integer.valueOf(5).equals(ordinal);
    }

	private static boolean originalBody(ClassNode mixin, MethodNode method, boolean pressed) {
		List<AbstractInsnNode> code = new ArrayList<>();
		for (var instruction : method.instructions) if (instruction.getOpcode() >= 0) code.add(instruction);
		return code.size() == 5 && code.get(0) instanceof VarInsnNode self && self.var == 0 && self.getOpcode() == Opcodes.ALOAD
				&& code.get(1) instanceof VarInsnNode event && event.var == 4 && event.getOpcode() == Opcodes.ALOAD
				&& code.get(2).getOpcode() == (pressed ? Opcodes.ICONST_1 : Opcodes.ICONST_0)
				&& code.get(3) instanceof MethodInsnNode call && call.owner.equals(mixin.name) && mixin.methods.stream().anyMatch(m -> m.name.equals(call.name) && m.desc.equals(call.desc))
				&& call.desc.equals("(" + EVENT + "Z)V") && code.get(4).getOpcode() == Opcodes.RETURN;
	}

	private static void guardAction(MethodNode method, int branch) {
		AbstractInsnNode exit = null;
		for (var instruction : method.instructions) if (instruction.getOpcode() == Opcodes.RETURN) exit = instruction;
		LabelNode done = new LabelNode();
		method.instructions.insertBefore(exit, done);
		method.instructions.insert(done, new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		InsnList guard = new InsnList(); guard.add(new VarInsnNode(Opcodes.ILOAD, 3)); guard.add(new JumpInsnNode(branch, done));
		method.instructions.insert(guard);
	}
}
