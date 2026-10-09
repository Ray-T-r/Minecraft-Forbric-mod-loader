/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.util.ForbricLog;

/** Run a closed fluid interaction predicate at the live carrier reaction sites. */
public final class MixinFluidInteractionAdapter {
	public static final String PROPERTY = "forbric.fluidInteractionCallbacks";
	static final String TARGET = "net/minecraft/world/level/block/LiquidBlock";
	private MixinFluidInteractionAdapter() { }

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!MixinCallbackShape.targets(mixin, TARGET) || "off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY))) return 0;
		ClassNode target = targets.apply(TARGET);
		MethodNode original = MixinCallbackShape.unique(mixin, m -> MixinCallbackShape.shape(m, MixinFluidReactionAdapter.HANDLER) && MixinCallbackShape.instance(m) && MixinCallbackShape.kind(m, "Inject")
                && MixinCallbackShape.binds(m, target, MixinFluidReactionAdapter.SPREAD)
                && MixinCallbackShape.plainPoint(m, "HEAD", null));
		if (original == null || target == null || (original.access & Opcodes.ACC_STATIC) != 0
				|| !MixinFluidReactionAdapter.HANDLER.equals(original.desc)) return 0;
		AnnotationNode injection = MixinFit.injectorOf(original);
		List<AnnotationNode> points = injection == null ? List.of() : MixinFit.atNodes(injection);
		if (!MixinCallbackShape.binds(original, target, MixinFluidReactionAdapter.SPREAD) || points.size() != 1
				|| !"HEAD".equals(MixinFit.value(points.getFirst(), "value"))
				|| !Boolean.TRUE.equals(MixinFit.value(injection, "cancellable"))) return 0;
		// Only a closed static predicate followed by setReturnValue(false) is transported; its owner and method name are immaterial.
		List<MethodInsnNode> bodyCalls = new ArrayList<>();
		for (var instruction : original.instructions) if (instruction instanceof MethodInsnNode call) bodyCalls.add(call);
		if (bodyCalls.size() != 3 || bodyCalls.get(0).getOpcode() != Opcodes.INVOKESTATIC || !bodyCalls.get(0).desc.equals(MixinFluidReactionAdapter.INTERACT)
				|| !bodyCalls.get(1).owner.equals("java/lang/Boolean") || !bodyCalls.get(1).name.equals("valueOf")
				|| !bodyCalls.get(2).owner.equals(MixinFluidReactionAdapter.CALLBACK) || !bodyCalls.get(2).name.equals("setReturnValue")) return 0;
        if (!closedPredicate(original)) return 0;
		List<MethodNode> hosts = new ArrayList<>(); List<MethodInsnNode> calls = new ArrayList<>();
		for (String name : List.of("onPlace", "neighborChanged")) {
			MethodNode host = MixinPlayerWorldCallbackAdapter.named(target, name);
			if (host == null) return 0;
			List<MethodInsnNode> nativeCalls = new ArrayList<>();
			for (var instruction : host.instructions) if (instruction instanceof MethodInsnNode call
					&& call.getOpcode() == Opcodes.INVOKESTATIC && MixinFluidReactionAdapter.REGISTRIES.contains(call.owner)
					&& call.name.equals("canInteract") && call.desc.equals(MixinFluidReactionAdapter.INTERACT)) nativeCalls.add(call);
			if (nativeCalls.size() != 1) return 0;
			hosts.add(host); calls.add(nativeCalls.getFirst());
		}
		FabricFluidFlowMixinAdapter.retainOriginal(original, injection);
		for (int i = 0; i < hosts.size(); i++) mixin.methods.add(FabricFluidFlowMixinAdapter.wrapper(mixin, original, hosts.get(i), calls.get(i)));
		ForbricLog.info("[Forbric/Mixin] original fluid interaction callback now guards both live carrier reaction sites");
		return 2;
	}
    private static boolean closedPredicate(MethodNode method) {
        List<AbstractInsnNode> code = java.util.Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
        if (code.size() != 9 || !method.tryCatchBlocks.isEmpty()) return false;
        return load(code.get(0), 1) && load(code.get(1), 2) && code.get(2) instanceof MethodInsnNode
                && code.get(3) instanceof JumpInsnNode branch && branch.getOpcode() == Opcodes.IFEQ
                && MixinPlayerWorldCallbackAdapter.next(branch.label) == code.get(8) && load(code.get(4), 4)
                && code.get(5).getOpcode() == Opcodes.ICONST_0 && code.get(6) instanceof MethodInsnNode boxing
                && boxing.desc.equals("(Z)Ljava/lang/Boolean;") && code.get(7) instanceof MethodInsnNode cancel
                && cancel.desc.equals("(Ljava/lang/Object;)V") && code.get(8).getOpcode() == Opcodes.RETURN;
    }
    private static boolean load(AbstractInsnNode instruction, int slot) {
        return instruction instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ALOAD && variable.var == slot;
    }
}
