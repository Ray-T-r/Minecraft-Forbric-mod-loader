/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import net.forbric.api.Ecosystem;

/**
 * Keeps a mod's breathing callbacks running where NeoForge now computes the air supply.
 *
 * <p>Vanilla's {@code LivingEntity.baseTick} asks {@code isEyeInFluid(FluidTags.WATER)} and
 * {@code MobEffectUtil.hasWaterBreathing(this)} itself. The merged {@code baseTick} hands the whole calculation to
 * NeoForge's {@code CommonHooks.onLivingBreathe}, so a {@code @WrapOperation} of either call in {@code baseTick} has
 * nothing to wrap. Each such handler is kept on its own: one wrap around NeoForge's call opens
 * {@code BreathingCallbackScope} with the handlers as callbacks, and {@code BreathingCallbackInjector} consults the scope
 * where NeoForge's calculation takes the same two decisions — at its start, where vanilla asked whether the eye is in
 * water, and at its own {@code hasWaterBreathing}.
 *
 * <p>The water-breathing callback's answer replaces NeoForge's, as the wrap's replaced vanilla's. The eye-in-water
 * callback runs for its effects only: NeoForge decides "in a drowning fluid" itself and has no place for another answer.
 * So that handler is kept only when its answer cannot differ from the call it wraps
 * ({@link MixinCallbackProofs#returnsOriginal}); one whose answer would change whether the entity drowns is left as
 * compiled, never silently ignored. Every kept handler hands its own receiver and arguments to {@code original.call}
 * ({@link MixinCallbackProofs#forwardsOperands}), since the callback's {@code Operation} answers for the call NeoForge
 * made; does not read its receiver, since the scope serves whichever entity is breathing; and may ask for the tick's
 * {@code ServerLevel} as a {@code @Local}, which is what NeoForge's call is handed (proved by its producer when the native
 * {@code baseTick} is at hand). Two handlers wrapping the same call are left alone: the scope carries one callback per
 * call.
 */
public final class MixinBreathingCallbackAdapter {
	public static final String PROPERTY = "forbric.breathingCallbacks";
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity", LEVEL = "net/minecraft/server/level/ServerLevel",
			HOOKS = "net/neoforged/neoforge/common/CommonHooks", OP = MixinWrapOperationShim.OPERATION,
			SCOPE = "net/forbric/kernel/interop/BreathingCallbackScope";
	private static final String NATIVE_DESC = "(L" + LIVING + ";L" + LEVEL + ";II)V";
	private static final String EYE = "L" + LIVING + ";isEyeInFluid(Lnet/minecraft/tags/TagKey;)Z",
			WATER = "Lnet/minecraft/world/effect/MobEffectUtil;hasWaterBreathing(L" + LIVING + ";)Z";
	private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
			"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);

	private MixinBreathingCallbackAdapter() { }

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		return adapt(mixin, targets, NativeGameReferences::reference);
	}

	/** {@code references} gives the class the mod was compiled against, whose {@code baseTick} proves a {@code @Local}'s producer. */
	static int adapt(ClassNode mixin, Function<String, ClassNode> targets, BiFunction<Ecosystem, String, ClassNode> references) {
		if (!MixinCallbackShape.targets(mixin, LIVING) || "off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY))) return 0;
		ClassNode living = targets.apply(LIVING), hooks = targets.apply(HOOKS);
		if (living == null || hooks == null) return 0;
		String nativeCall = "L" + HOOKS + ";onLivingBreathe" + NATIVE_DESC;
		MethodNode host = MixinPlayerWorldCallbackAdapter.selector(living, "baseTick()V");
		if (host == null || MixinPlayerWorldCallbackAdapter.count(host, nativeCall) != 1) return 0;
		MethodNode nativeHook = MixinPlayerWorldCallbackAdapter.selector(hooks, "onLivingBreathe" + NATIVE_DESC);
		if (nativeHook == null || MixinPlayerWorldCallbackAdapter.count(nativeHook, WATER) != 1) return 0;
		ClassNode source = references == null ? null : references.apply(MixinStubRebind.ecosystemOf(mixin.name), LIVING);
		MethodNode reference = source == null ? null : MixinPlayerWorldCallbackAdapter.selector(source, "baseTick()V");
		MethodInsnNode hookCall = null;
		for (AbstractInsnNode instruction : host.instructions)
			if (instruction instanceof MethodInsnNode call && MixinPlayerWorldCallbackAdapter.member(call).equals(nativeCall)) hookCall = call;

		MethodNode lava = kept(mixin, living, host, hookCall, source, reference, EYE, "(L" + LIVING + ";Lnet/minecraft/tags/TagKey;L" + OP + ";)Z", true);
		MethodNode water = kept(mixin, living, host, hookCall, source, reference, WATER, "(L" + LIVING + ";L" + OP + ";)Z", false);
		if (lava == null && water == null) return 0;

		MethodNode lavaFn = lava == null ? null : callback(mixin, lava, true), waterFn = water == null ? null : callback(mixin, water, false);
		MethodNode wrapper = new MethodNode(Opcodes.ACC_PRIVATE, "forbric$breathingCallback", "(L" + LIVING + ";L" + LEVEL + ";IIL" + OP + ";)V", null, null);
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", nativeCall));
		AnnotationNode wrap = new AnnotationNode(MixinWrapOperationShim.WRAP_OPERATION);
		wrap.values = new ArrayList<>(List.of("method", List.of("baseTick()V"), "at", at, "require", 1));
		wrapper.visibleAnnotations = new ArrayList<>(List.of(wrap));
		InsnList c = wrapper.instructions;
		function(c, mixin, lavaFn);
		function(c, mixin, waterFn);
		c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SCOPE, "enter", "(Ljava/util/function/Function;Ljava/util/function/Function;)Ljava/lang/Object;", false));
		c.add(new VarInsnNode(Opcodes.ASTORE, 6));
		LabelNode start = new LabelNode(), end = new LabelNode(), fail = new LabelNode();
		c.add(start);
		c.add(new VarInsnNode(Opcodes.ALOAD, 5));
		c.add(new InsnNode(Opcodes.ICONST_4));
		c.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
		for (int i = 0; i < 4; i++) {
			c.add(new InsnNode(Opcodes.DUP));
			c.add(new IntInsnNode(Opcodes.BIPUSH, i));
			c.add(new VarInsnNode(i < 2 ? Opcodes.ALOAD : Opcodes.ILOAD, i + 1));
			if (i >= 2) c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
			c.add(new InsnNode(Opcodes.AASTORE));
		}
		c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, OP, "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true));
		c.add(new InsnNode(Opcodes.POP));
		c.add(end);
		leave(c);
		c.add(new InsnNode(Opcodes.RETURN));
		c.add(fail);
		c.add(new FrameNode(Opcodes.F_FULL, 7, new Object[]{mixin.name, LIVING, LEVEL, Opcodes.INTEGER, Opcodes.INTEGER, OP, "java/lang/Object"}, 1, new Object[]{"java/lang/Throwable"}));
		c.add(new VarInsnNode(Opcodes.ASTORE, 7));
		leave(c);
		c.add(new VarInsnNode(Opcodes.ALOAD, 7));
		c.add(new InsnNode(Opcodes.ATHROW));
		wrapper.tryCatchBlocks.add(new TryCatchBlockNode(start, end, fail, null));
		wrapper.maxStack = 7;
		wrapper.maxLocals = 8;
		for (MethodNode original : new MethodNode[]{lava, water}) {
			if (original == null) continue;
			MixinCarrierCallbackAdapters.removeInjector(original, MixinFit.injectorOf(original));
			original.name += "$forbricOriginal";
			original.invisibleParameterAnnotations = null;
			original.visibleParameterAnnotations = null;
		}
		if (lavaFn != null) mixin.methods.add(lavaFn);
		if (waterFn != null) mixin.methods.add(waterFn);
		mixin.methods.add(wrapper);
		return (lava == null ? 0 : 1) + (water == null ? 0 : 1);
	}

	/**
	 * The one handler that wraps {@code call} in vanilla's {@code baseTick} with {@code operands}, if it can be kept;
	 * null when no handler wraps it, two do, or the one that does fails a condition on the class.
	 */
	private static MethodNode kept(ClassNode mixin, ClassNode living, MethodNode host, MethodInsnNode hookCall, ClassNode source,
			MethodNode reference, String call, String operands, boolean effectsOnly) {
		MethodNode handler = MixinCallbackShape.unique(mixin, m -> MixinCallbackShape.kind(m, "WrapOperation")
				&& MixinCallbackShape.binds(m, living, "baseTick()V") && MixinCallbackShape.plainPoint(m, "INVOKE", call)
				&& MixinHandlerShape.of(m).operands(operands));
		if (handler == null || !MixinCallbackShape.instance(handler) || !MixinCallbackShape.noReceiver(handler)) return null;
		MixinHandlerShape shape = MixinHandlerShape.of(handler);
		if (shape.within(List.of(MixinHandlerShape.Want.local("L" + LEVEL + ";"))) == null) return null;
		int operandCount = Type.getArgumentTypes(operands).length - 1;
		if (!MixinCallbackProofs.forwardsOperands(mixin.name, handler, operandCount)) return null;
		if (effectsOnly && !MixinCallbackProofs.returnsOriginal(mixin.name, handler)) return null;
		if (reference != null && !shape.locals().isEmpty()) {
			// The @Local is the level vanilla's baseTick held at the call; NeoForge's call must be handed that same level.
			List<AbstractInsnNode> points = MixinCallbackProofs.points(reference, MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst());
			if (points == null || points.size() != 1 || hookCall == null) return null;
			for (MixinHandlerShape.Extra local : shape.locals())
				if (!MixinCallbackProofs.localIsOperand(handler, local, source.name, reference, points.getFirst(), living.name, host, hookCall, 1)) return null;
		}
		return handler;
	}

	private static void leave(InsnList c) {
		c.add(new VarInsnNode(Opcodes.ALOAD, 6));
		c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SCOPE, "leave", "(Ljava/lang/Object;)V", false));
	}

	/** Pushes {@code args -> this.method(args)} as a {@code Function}, or null for a callback the mixin does not keep. */
	private static void function(InsnList c, ClassNode mixin, MethodNode method) {
		if (method == null) { c.add(new InsnNode(Opcodes.ACONST_NULL)); return; }
		c.add(new VarInsnNode(Opcodes.ALOAD, 0));
		c.add(new InvokeDynamicInsnNode("apply", "(L" + mixin.name + ";)Ljava/util/function/Function;", METAFACTORY,
				Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"), new Handle(Opcodes.H_INVOKEVIRTUAL, mixin.name, method.name, method.desc, false),
				Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;")));
	}

	/**
	 * The callback the scope calls with {@code {entity, level}} (eye in water) or {@code {entity, nativeAnswer, level}}
	 * (water breathing): the original handler, handed the entity, vanilla's {@code FluidTags.WATER} for the eye check, an
	 * {@code Operation} answering what the wrapped call answers there, and the level for a {@code @Local ServerLevel}.
	 */
	private static MethodNode callback(ClassNode mixin, MethodNode original, boolean eye) {
		MethodNode fn = new MethodNode(Opcodes.ACC_PRIVATE, "forbric$" + (eye ? "lava" : "water") + "Callback", "([Ljava/lang/Object;)Ljava/lang/Object;", null, null);
		InsnList c = fn.instructions;
		c.add(new VarInsnNode(Opcodes.ALOAD, 0));
		element(c, 0, LIVING);
		if (eye) {
			c.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraft/tags/FluidTags", "WATER", "Lnet/minecraft/tags/TagKey;"));
			element(c, 0, LIVING);
			c.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraft/tags/FluidTags", "WATER", "Lnet/minecraft/tags/TagKey;"));
			c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LIVING, "isEyeInFluid", "(Lnet/minecraft/tags/TagKey;)Z", false));
			c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
		} else {
			c.add(new VarInsnNode(Opcodes.ALOAD, 1));
			c.add(new InsnNode(Opcodes.ICONST_1));
			c.add(new InsnNode(Opcodes.AALOAD));
		}
		c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MixinWrapOperationShim.RUNTIME, "constant", "(Ljava/lang/Object;)L" + OP + ";", false));
		MixinHandlerShape shape = MixinHandlerShape.of(original);
		Map<Integer, Integer> served = shape.within(List.of(MixinHandlerShape.Want.local("L" + LEVEL + ";")));
		for (int i = 0; i < shape.extras().size(); i++) if (served.get(i) == 0) element(c, eye ? 1 : 2, LEVEL);
		c.add(MixinHandlerShim.callOwn(mixin, false, original.name + "$forbricOriginal", original.desc));
		c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
		c.add(new InsnNode(Opcodes.ARETURN));
		fn.maxStack = 7;
		fn.maxLocals = 2;
		MixinCallbackShape.uniqueMember(fn);
		return fn;
	}

	private static void element(InsnList c, int index, String type) {
		c.add(new VarInsnNode(Opcodes.ALOAD, 1));
		c.add(new IntInsnNode(Opcodes.BIPUSH, index));
		c.add(new InsnNode(Opcodes.AALOAD));
		c.add(new TypeInsnNode(Opcodes.CHECKCAST, type));
	}
}
