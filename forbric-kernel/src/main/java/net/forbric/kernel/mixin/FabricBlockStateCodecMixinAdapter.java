/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import net.forbric.kernel.util.ForbricLog;

/**
 * Lets a redirect that replaces the block-state model codecs the carrier builds keep the codec it replaces.
 *
 * <p>fabric-model-loading's {@code BlockStateModelUnbakedMixin} redirects the two {@code Codec.flatComapMap} calls in
 * {@code BlockStateModel$Unbaked.<clinit>} and returns fabric's own codecs instead. On the merged base those calls build
 * NeoForge's codecs, the only readers of a variant's {@code "type"} key, so every NeoForge custom block-state model parsed
 * as an empty plain variant while fabric-api was installed (issue #16: Sophisticated Backpacks' placed backpack drew only
 * its outline). Each such handler now makes the redirected call itself and returns
 * {@code KernelBlockStateModelFormats.either(that, its own)}: the redirect's codec for the variants written in Fabric's
 * block-state model format ({@code "fabric:type"}, the key the public {@code CustomUnbakedBlockStateModel} format is read
 * under), the carrier's for the rest.
 *
 * <p>The handler is recognised by what it does, never by its mixin's, handler's or field owner's names: a static
 * {@code @Redirect} of one of {@code <clinit>}'s {@code flatComapMap} calls, by ordinal, whose every answer is one static
 * {@code Codec} field and which reads none of the call's operands — it discards the call outright. Only against a target
 * whose {@code <clinit>} makes exactly two such calls through NeoForge's {@code BlockStateModelHooks}: the carrier's
 * codecs worth keeping. Anything else in the mixin is left as written. {@code -Dforbric.blockStateModelFormats=off} turns
 * it off.
 */
public final class FabricBlockStateCodecMixinAdapter {
	public static final String PROPERTY = "forbric.blockStateModelFormats";
	static final String TARGET = "net/minecraft/client/renderer/block/dispatch/BlockStateModel$Unbaked";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelBlockStateModelFormats";
	static final String NEOFORGE_HOOKS = "net/neoforged/neoforge/client/model/block/BlockStateModelHooks";
	static final String CODEC = "com/mojang/serialization/Codec";
	static final String FLAT_COMAP = "(Ljava/util/function/Function;Ljava/util/function/Function;)Lcom/mojang/serialization/Codec;";
	static final String HANDLER = "(Lcom/mojang/serialization/Codec;Ljava/util/function/Function;Ljava/util/function/Function;)"
			+ "Lcom/mojang/serialization/Codec;";
	static final String EITHER = "(Lcom/mojang/serialization/Codec;Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;";
	private static final String TARGET_ANCHOR = "L" + CODEC + ";flatComapMap" + FLAT_COMAP;

	private FabricBlockStateCodecMixinAdapter() { }

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** @return the number of handlers adapted: those that discard one of the carrier's two codec calls, or 0 */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!enabled() || !MixinFit.mixinTargets(mixin).equals(List.of(TARGET))) return 0;
		ClassNode target = targets.apply(TARGET);
		if (target == null || !neoForgeBuildsBoth(target)) return 0;
		List<MethodNode> handlers = new ArrayList<>();
		for (int ordinal = 0; ordinal < 2; ordinal++) {
			List<MethodNode> found = handlers(mixin, ordinal);
			if (found.size() > 1) return 0;   // two redirects of one call: Mixin refuses one of them, nothing to decide here
			handlers.addAll(found);
		}
		for (MethodNode handler : handlers) {
			FieldInsnNode replacement = soleReturnedField(mixin.name, handler);
			InsnList call = new InsnList();
			for (int slot = 0; slot < 3; slot++) call.add(new VarInsnNode(Opcodes.ALOAD, slot));
			call.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, CODEC, "flatComapMap", FLAT_COMAP, true));
			handler.instructions.insertBefore(replacement, call);
			handler.instructions.insert(replacement, new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "either", EITHER, false));
			handler.maxStack = Math.max(handler.maxStack, 3);
		}
		if (!handlers.isEmpty()) ForbricLog.info("[Forbric/ModelFormats] %s's block-state codec redirect(s) keep NeoForge's codec for "
				+ "variants without \"fabric:type\" — NeoForge's \"type\" models were parsed as empty plain variants", mixin.name.replace('/', '.'));
		return handlers.size();
	}

	/** Exactly two {@code flatComapMap} calls in {@code <clinit>}, built by NeoForge's hooks — the codecs worth keeping. */
	static boolean neoForgeBuildsBoth(ClassNode target) {
		MethodNode clinit = target.methods.stream().filter(m -> "<clinit>".equals(m.name)).findFirst().orElse(null);
		if (clinit == null) return false;
		int calls = 0;
		boolean neoForge = false;
		for (AbstractInsnNode insn : clinit.instructions) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			if (CODEC.equals(call.owner) && "flatComapMap".equals(call.name) && FLAT_COMAP.equals(call.desc)) calls++;
			if (NEOFORGE_HOOKS.equals(call.owner)) neoForge = true;
		}
		return calls == 2 && neoForge;
	}

	/** The static {@code @Redirect}s of the {@code ordinal}-th {@code flatComapMap} call that discard it (see the class doc). */
	private static List<MethodNode> handlers(ClassNode mixin, int ordinal) {
		List<MethodNode> out = new ArrayList<>();
		for (MethodNode method : mixin.methods) {
			if (!HANDLER.equals(method.desc) || (method.access & Opcodes.ACC_STATIC) == 0) continue;
			AnnotationNode redirect = MixinFit.injectorOf(method);
			if (redirect == null || !"Lorg/spongepowered/asm/mixin/injection/Redirect;".equals(redirect.desc)
					|| MixinFit.value(redirect, "slice") != null
					|| !MixinFit.stringList(MixinFit.value(redirect, "method")).equals(List.of("<clinit>()V"))) continue;
			List<AnnotationNode> at = MixinFit.atNodes(redirect);
			if (at.size() != 1 || !"INVOKE".equals(MixinFit.value(at.getFirst(), "value"))
					|| !MixinFit.sameMember(TARGET_ANCHOR, String.valueOf(MixinFit.value(at.getFirst(), "target")))
					|| !Integer.valueOf(ordinal).equals(MixinFit.value(at.getFirst(), "ordinal"))) continue;
			if (soleReturnedField(mixin.name, method) != null) out.add(method);
		}
		return out;
	}

	/**
	 * The one static {@code Codec} field every answer of the handler is, read by its data flow, when it reads none of its
	 * parameters, calls nothing and writes nothing; else null.
	 */
	static FieldInsnNode soleReturnedField(String owner, MethodNode handler) {
		HandlerFlow flow = HandlerFlow.of(owner, handler);
		if (flow == null || !flow.onlyCalls(call -> false)) return null;
		for (int slot = 0; slot < 3; slot++) if (flow.reads(slot)) return null;
		AbstractInsnNode answer = HandlerFlow.only(flow.returned());
		return answer instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC && ("L" + CODEC + ";").equals(field.desc)
				? field : null;
	}
}
