/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Lets a Fabric wrap of vanilla's item-damage call run where NeoForge's base makes that call with the entity itself, so
 * fabric-item-api's {@code CustomDamageHandler} is asked on NeoForge's base as on Fabric.
 *
 * <p>Vanilla's {@code ItemStack.hurtAndBreak(int, LivingEntity, EquipmentSlot)} (javap, 26.2) makes one call,
 * {@code hurtAndBreak(int, ServerLevel, ServerPlayer, Consumer)}, with the entity as a server player or null.
 * fabric-item-api-v1's {@code ItemStackMixin.hookDamage} is a {@code @WrapOperation} of exactly that call: it asks the item's
 * {@code CustomDamageHandler} for the amount (and lets it break the item) before the vanilla call. NeoForge 26.2.0.93's
 * {@code hurtAndBreak(int, LivingEntity, EquipmentSlot)} calls {@code hurtAndBreak(int, ServerLevel, LivingEntity, Consumer)}
 * instead, an overload of its own that serves every living entity (it asks NeoForge's {@code Item.damageItem} first); its
 * {@code ServerPlayer} overload now just delegates there. So the wrap attached nowhere ("did not attach … required injection
 * unavailable") and a Fabric item's custom damage handler never ran on any install with fabric-api: a tool such a handler
 * keeps from wearing wore down by vanilla's rule, from a mob or a player alike (measured on the genuine stage, probe
 * {@code damage.mob} / {@code damage.player}: handler calls 0, damage 1).
 *
 * <p>The handler moves (renamed aside, its injector removed, like {@link MixinShearsRelay}'s moved path) behind a new
 * {@code @WrapOperation} of NeoForge's call, which hands it what vanilla's call had: the entity as a server player or null
 * ({@code KernelItemDamage.playerOf}), and an {@code Operation} ({@code KernelItemDamage.relay}) that makes NeoForge's call
 * with the entity when the handler passes that value on. Its sugar parameters ({@code @Local(argsOnly = true)} entity and
 * slot) stay on the new wrap, at the same place, reading the same host method.
 *
 * <p>Only a Fabric mod's handler (NeoForge's mods were written against NeoForge's call; a mixin whose family is not known
 * as Fabric is left alone, as {@link MixinShearsRelay} does), and only this shape: a
 * {@code @WrapOperation} with one {@code INVOKE} point naming vanilla's call (as Mixin reads a target), no ordinal, slice or
 * group, bound by its selectors to {@code hurtAndBreak(int, LivingEntity, EquipmentSlot)} alone, whose handler takes the
 * call's operands and an {@code Operation} first, then only sugar, and returns nothing; and only where that host method
 * makes NeoForge's call exactly once and vanilla's not at all (on vanilla's base nothing changes).
 * {@code -Dforbric.itemDamageHandler=off} leaves the handler as written.
 */
public final class FabricItemDamageMixinAdapter {
	public static final String PROPERTY = "forbric.itemDamageHandler";
	static final String STACK = "net/minecraft/world/item/ItemStack";
	static final String LEVEL = "net/minecraft/server/level/ServerLevel";
	static final String PLAYER = "net/minecraft/server/level/ServerPlayer";
	static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	static final String CONSUMER = "java/util/function/Consumer";
	static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelItemDamage";
	static final String PLAYER_OF = "(Ljava/lang/Object;)Ljava/lang/Object;";
	static final String RELAY = "(L" + "com/llamalad7/mixinextras/injector/wrapoperation/Operation" + ";Ljava/lang/Object;Ljava/lang/Object;)L"
			+ "com/llamalad7/mixinextras/injector/wrapoperation/Operation" + ";";
	static final String HOST = "hurtAndBreak(IL" + LIVING + ";Lnet/minecraft/world/entity/EquipmentSlot;)V";
	static final String VANILLA_CALL = "(IL" + LEVEL + ";L" + PLAYER + ";L" + CONSUMER + ";)V";
	static final String BASE_CALL = "(IL" + LEVEL + ";L" + LIVING + ";L" + CONSUMER + ";)V";
	private static final String WRAP_OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
	private static final String GROUP = "Lorg/spongepowered/asm/mixin/injection/Group;";
	/** The handler's operands and Operation, as vanilla's call has them. */
	private static final Type[] HEAD = { Type.getObjectType(STACK), Type.INT_TYPE, Type.getObjectType(LEVEL), Type.getObjectType(PLAYER),
			Type.getObjectType(CONSUMER), Type.getObjectType(OPERATION) };

	private FabricItemDamageMixinAdapter() { }

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY, "on"));
	}

	/** @return how many handlers now wrap NeoForge's call; {@code targets} must return nodes WITH code */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!enabled() || mixin == null || mixin.methods == null || targets == null) return 0;
		if (MixinStubRebind.ecosystemOf(mixin.name) != Ecosystem.FABRIC || !MixinFit.mixinTargets(mixin).equals(List.of(STACK))) return 0;
		ClassNode stack = targets.apply(STACK);
		MethodNode host = stack == null || stack.methods == null ? null
				: stack.methods.stream().filter(m -> HOST.equals(m.name + m.desc)).findFirst().orElse(null);
		if (host == null || calls(host, VANILLA_CALL) != 0 || calls(host, BASE_CALL) != 1) return 0;
		int moved = 0;
		for (MethodNode handler : new ArrayList<>(mixin.methods)) if (move(mixin, handler, stack, host)) moved++;
		return moved;
	}

	private static boolean move(ClassNode mixin, MethodNode handler, ClassNode stack, MethodNode host) {
		Type[] params = Type.getArgumentTypes(handler.desc);
		if (Type.getReturnType(handler.desc) != Type.VOID_TYPE || params.length < HEAD.length
				|| !Arrays.equals(Arrays.copyOf(params, HEAD.length), HEAD) || grouped(handler)) return false;
		AnnotationNode injector = MixinFit.injectorOf(handler);
		if (injector == null || !WRAP_OPERATION.equals(injector.desc) || MixinFit.value(injector, "slice") != null) return false;
		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.size() != 1) return false;
		AnnotationNode at = points.getFirst();
		if (!"INVOKE".equals(MixinFit.asString(MixinFit.value(at, "value"))) || MixinFit.value(at, "ordinal") != null
				|| MixinFit.value(at, "slice") != null || !MixinCallbackShape.names(at, "L" + STACK + ";hurtAndBreak" + VANILLA_CALL, null)) return false;
		if (MixinTargetSelectors.one(handler, stack) != host) return false;
		for (int i = 0; i < params.length; i++) if (MixinFit.sugar(handler, i) != (i >= HEAD.length)) return false;
		boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		String innerName = MixinHandlerShim.asideName(mixin.name, handler.name, MixinHandlerShim.INNER_SUFFIX);
		for (MethodNode existing : mixin.methods) if (existing.name.equals(innerName)) return false;   // done already

		AnnotationNode moved = new AnnotationNode(injector.desc);
		injector.accept(moved);
		for (AnnotationNode point : MixinFit.atNodes(moved)) MixinPlayerWorldCallbackAdapter.set(point, "target", "L" + STACK + ";hurtAndBreak" + BASE_CALL);
		String outerName = handler.name;
		Type[] outerParams = params.clone();
		outerParams[3] = Type.getObjectType(LIVING);
		MethodNode outer = new MethodNode(Opcodes.ASM9, handler.access & ~Opcodes.ACC_SYNTHETIC, outerName,
				Type.getMethodDescriptor(Type.VOID_TYPE, outerParams), null, null);
		outer.visibleAnnotations = new ArrayList<>(List.of(moved));
		// The sugar stays with the injector: the same parameters, the same host method's arguments.
		outer.visibleParameterAnnotations = handler.visibleParameterAnnotations;
		outer.invisibleParameterAnnotations = handler.invisibleParameterAnnotations;
		outer.visibleAnnotableParameterCount = handler.visibleAnnotableParameterCount;
		outer.invisibleAnnotableParameterCount = handler.invisibleAnnotableParameterCount;
		handler.name = innerName;
		handler.visibleAnnotations = without(handler.visibleAnnotations, injector);
		handler.invisibleAnnotations = without(handler.invisibleAnnotations, injector);
		handler.visibleParameterAnnotations = null;
		handler.invisibleParameterAnnotations = null;
		handler.visibleAnnotableParameterCount = 0;
		handler.invisibleAnnotableParameterCount = 0;

		// outer(stack, amount, level, entity, consumer, operation, sugar…)
		//   → inner(stack, amount, level, playerOf(entity), consumer, relay(operation, entity, playerOf(entity)), sugar…)
		int[] slot = new int[outerParams.length];
		int next = isStatic ? 0 : 1;
		for (int i = 0; i < outerParams.length; i++) { slot[i] = next; next += outerParams[i].getSize(); }
		var code = outer.instructions;
		if (!isStatic) code.add(new VarInsnNode(Opcodes.ALOAD, 0));
		for (int i = 0; i < outerParams.length; i++) {
			if (i == 3) {
				code.add(new VarInsnNode(Opcodes.ALOAD, slot[3]));
				code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "playerOf", PLAYER_OF, false));
				code.add(new TypeInsnNode(Opcodes.CHECKCAST, PLAYER));
			} else if (i == 5) {
				code.add(new VarInsnNode(Opcodes.ALOAD, slot[5]));
				code.add(new VarInsnNode(Opcodes.ALOAD, slot[3]));
				code.add(new VarInsnNode(Opcodes.ALOAD, slot[3]));
				code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "playerOf", PLAYER_OF, false));
				code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "relay", RELAY, false));
			} else {
				code.add(new VarInsnNode(outerParams[i].getOpcode(Opcodes.ILOAD), slot[i]));
			}
		}
		code.add(MixinHandlerShim.callOwn(mixin, isStatic, innerName, handler.desc));
		code.add(new InsnNode(Opcodes.RETURN));
		outer.maxStack = next + 3;
		outer.maxLocals = next;
		mixin.methods.add(outer);
		ForbricLog.info("[Forbric/Mixin] %s: %s now wraps ItemStack.hurtAndBreak(int, ServerLevel, LivingEntity, Consumer), the call "
				+ "the base's hurtAndBreak(int, LivingEntity, EquipmentSlot) makes where vanilla's makes the ServerPlayer one it was "
				+ "written for; it is handed the entity as a server player or null, as vanilla passed it", mixin.name.replace('/', '.'), outerName);
		return true;
	}

	/** Calls in {@code host} of {@code ItemStack.hurtAndBreak} with {@code desc}. */
	static int calls(MethodNode host, String desc) {
		int n = 0;
		for (AbstractInsnNode insn : host.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(STACK) && call.name.equals("hurtAndBreak") && call.desc.equals(desc)) n++;
		}
		return n;
	}

	private static boolean grouped(MethodNode handler) {
		for (List<AnnotationNode> annotations : Arrays.asList(handler.visibleAnnotations, handler.invisibleAnnotations)) {
			if (annotations != null && annotations.stream().anyMatch(a -> GROUP.equals(a.desc))) return true;
		}
		return false;
	}

	private static List<AnnotationNode> without(List<AnnotationNode> annotations, AnnotationNode removed) {
		if (annotations == null) return null;
		List<AnnotationNode> kept = new ArrayList<>(annotations);
		kept.remove(removed);
		return kept;
	}
}
