/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.transform.ItemUseOnInjector;
import net.forbric.kernel.util.ForbricLog;

/**
 * Points an injector at the one-call relay the kernel gave a call the surviving carrier moved out of its method.
 *
 * <p>MinecraftForge's {@code ItemStack.useOn} makes its {@code Item.useOn} call in {@code ForgeHooks} (server) and in a
 * lambda (client), not in {@code useOn}; {@link ItemUseOnInjector} routes both through
 * {@code ItemStack.forbric$useOnItem(Item, UseOnContext)}, whose whole body is that call. An injector written for the
 * call inside {@code useOn} — fabric-api's {@code ItemEvents.USE_ON} is one — then selects the relay instead.
 *
 * <p>Only injectors whose handler describes the CALL and nothing about {@code useOn} move, because the relay has the
 * call and nothing else of {@code useOn}: {@code @WrapOperation} taking exactly (receiver, arguments, Operation),
 * {@code @Redirect} taking exactly (receiver, arguments), {@code @ModifyExpressionValue} taking exactly the result,
 * {@code @ModifyArg} with one parameter and {@code @ModifyArgs} with its {@code Args}. No sugar parameter, no slice, no
 * {@code @Group}, and every {@code @At} of the injector must be that call. An {@code @Inject} (it takes {@code useOn}'s
 * own parameters and callback) or a handler that captures {@code useOn}'s locals is left as compiled.
 *
 * <p>The proof is the relay itself: the target's pre-mixin bytes must declare it with exactly
 * {@code aload_1; aload_2; <the call>; areturn}, and {@code useOn} must no longer make the call. Without both — the
 * transformer declined, or a carrier restored the call — nothing moves. {@code -Dforbric.mixinRelocatedCall=off}
 * leaves every selector as compiled.
 */
public final class MixinRelocatedCall {
	public static final String PROPERTY = "forbric.mixinRelocatedCall";

	/** A call the kernel relocated: which method it left, the call, and the relay that now makes it. */
	record Relocation(String owner, String method, String methodDesc, String callOwner, String callName, String callDesc,
			String relay, String relayDesc) {
		String callTarget() {
			return "L" + callOwner + ";" + callName + callDesc;
		}
	}

	static final List<Relocation> RELOCATIONS = List.of(
			new Relocation("net/minecraft/world/item/ItemStack", "useOn",
					"(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;",
					"net/minecraft/world/item/Item", "useOn",
					"(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;",
					ItemUseOnInjector.RELAY, ItemUseOnInjector.RELAY_DESC));

	private static final String WRAP_OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
	private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
	private static final String MODIFY_EXPRESSION_VALUE = "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;";
	private static final String MODIFY_ARG = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;";
	private static final String MODIFY_ARGS = "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;";
	private static final String OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
	private static final String ARGS = "Lorg/spongepowered/asm/mixin/injection/invoke/arg/Args;";
	private static final String GROUP = "Lorg/spongepowered/asm/mixin/injection/Group;";
	private static final Map<String, String> KINDS = Map.of(WRAP_OPERATION, "@WrapOperation", REDIRECT, "@Redirect",
			MODIFY_EXPRESSION_VALUE, "@ModifyExpressionValue", MODIFY_ARG, "@ModifyArg", MODIFY_ARGS, "@ModifyArgs");

	private MixinRelocatedCall() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Moves every eligible injector in {@code mixin}; returns how many. {@code targets} must return nodes WITH code. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!enabled() || mixin == null || mixin.methods == null || targets == null) return 0;
		int moved = 0;
		for (String targetName : MixinOverloadPin.targetsOf(mixin)) {
			for (Relocation relocation : RELOCATIONS) {
				if (!relocation.owner().equals(targetName)) continue;
				ClassNode target = targets.apply(targetName);
				if (target == null || !relocated(target, relocation)) continue;
				for (MethodNode handler : mixin.methods) {
					if (move(handler, relocation)) {
						moved++;
						ForbricLog.info("[Forbric/Mixin] %s: %s now wraps %s.%s in %s — MinecraftForge's %s makes that call "
								+ "outside its own body, and the kernel routes it through that one method",
								mixin.name.replace('/', '.'), handler.name, relocation.callOwner().substring(relocation.callOwner().lastIndexOf('/') + 1),
								relocation.callName(), relocation.relay(), relocation.method());
					}
				}
			}
		}
		return moved;
	}

	/** The relay is there with exactly the one call, and the method it left no longer makes it. */
	static boolean relocated(ClassNode target, Relocation relocation) {
		MethodNode relay = method(target, relocation.relay(), relocation.relayDesc());
		MethodNode left = method(target, relocation.method(), relocation.methodDesc());
		if (relay == null || left == null || (relay.access & Opcodes.ACC_STATIC) != 0) return false;
		List<AbstractInsnNode> real = new ArrayList<>();
		for (AbstractInsnNode insn : relay.instructions) if (insn.getOpcode() >= 0) real.add(insn);
		if (real.size() != 4 || !(real.get(0) instanceof VarInsnNode first && first.var == 1)
				|| !(real.get(1) instanceof VarInsnNode second && second.var == 2)
				|| !(real.get(2) instanceof MethodInsnNode call && calls(call, relocation))
				|| real.get(3).getOpcode() != Opcodes.ARETURN) return false;
		for (AbstractInsnNode insn : left.instructions) {
			if (insn instanceof MethodInsnNode made && calls(made, relocation)) return false;
		}
		return true;
	}

	private static boolean move(MethodNode handler, Relocation relocation) {
		List<AnnotationNode> annotations = new ArrayList<>();
		if (handler.visibleAnnotations != null) annotations.addAll(handler.visibleAnnotations);
		if (handler.invisibleAnnotations != null) annotations.addAll(handler.invisibleAnnotations);
		if (annotations.stream().anyMatch(a -> GROUP.equals(a.desc))) return false;
		AnnotationNode injector = MixinFit.injectorOf(handler);
		if (injector == null || !KINDS.containsKey(injector.desc)) return false;
		List<String> selectors = MixinFit.stringList(MixinFit.value(injector, "method"));
		if (selectors.size() != 1 || !(selectors.getFirst().equals(relocation.method())
				|| selectors.getFirst().equals(relocation.method() + relocation.methodDesc()))) return false;
		if (MixinFit.value(injector, "slice") != null || MixinFit.value(injector, "target") != null) return false;
		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.isEmpty()) return false;
		for (AnnotationNode at : points) {
			if (!"INVOKE".equals(MixinFit.asString(MixinFit.value(at, "value")))
					|| !relocation.callTarget().equals(MixinFit.value(at, "target"))) return false;
		}
		if (hasParameterAnnotations(handler.visibleParameterAnnotations)
				|| hasParameterAnnotations(handler.invisibleParameterAnnotations)) return false;
		if (!callShaped(injector.desc, handler.desc, relocation)) return false;

		for (int i = 0; i + 1 < injector.values.size(); i += 2) {
			if ("method".equals(injector.values.get(i))) {
				injector.values.set(i + 1, new ArrayList<>(List.of(relocation.relay() + relocation.relayDesc())));
			}
		}
		return true;
	}

	/** Whether the handler's parameters are the call's shape for its kind, with nothing of the enclosing method. */
	static boolean callShaped(String kind, String handlerDesc, Relocation relocation) {
		Type[] params = Type.getArgumentTypes(handlerDesc);
		Type returned = Type.getReturnType(handlerDesc);
		Type callReturn = Type.getReturnType(relocation.callDesc());
		List<Type> call = new ArrayList<>();
		call.add(Type.getObjectType(relocation.callOwner()));    // the relocated call is an instance call
		call.addAll(List.of(Type.getArgumentTypes(relocation.callDesc())));
		return switch (kind) {
			case WRAP_OPERATION -> returned.equals(callReturn) && params.length == call.size() + 1
					&& List.of(params).subList(0, call.size()).equals(call) && params[call.size()].getDescriptor().equals(OPERATION);
			case REDIRECT -> returned.equals(callReturn) && List.of(params).equals(call);
			case MODIFY_EXPRESSION_VALUE -> returned.equals(callReturn) && params.length == 1 && params[0].equals(callReturn);
			case MODIFY_ARG -> params.length == 1 && returned.equals(params[0]) && call.subList(1, call.size()).contains(params[0]);
			case MODIFY_ARGS -> params.length == 1 && params[0].getDescriptor().equals(ARGS) && returned.equals(Type.VOID_TYPE);
			default -> false;
		};
	}

	private static boolean hasParameterAnnotations(List<AnnotationNode>[] annotations) {
		if (annotations == null) return false;
		for (List<AnnotationNode> list : annotations) if (list != null && !list.isEmpty()) return true;
		return false;
	}

	private static boolean calls(MethodInsnNode call, Relocation relocation) {
		return call.owner.equals(relocation.callOwner()) && call.name.equals(relocation.callName()) && call.desc.equals(relocation.callDesc());
	}

	private static MethodNode method(ClassNode owner, String name, String desc) {
		if (owner.methods == null) return null;
		for (MethodNode method : owner.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}
}
