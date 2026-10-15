/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.transform.LambdaInvocationThunkInjector;
import net.forbric.kernel.util.ForbricLog;

/**
 * Lets a MixinExtras expression that names a method still find a method reference to it after the kernel routed that
 * reference through a thunk.
 *
 * <p>{@link LambdaInvocationThunkInjector} points each ordinary {@code this::method} reference of a class at a private
 * thunk that makes the one exact call, so that a guest injecting at that call has an instruction to land on. The
 * reference's implementation handle then names the thunk, and an {@code @Expression("this::method")} whose
 * {@code @Definition} names {@code method} matches nothing: Lithium's typed hit predicates on
 * {@code AbstractArrow}, {@code AbstractHurtingProjectile}, {@code FishingHook} and {@code ShulkerBullet} never
 * attached (the three projectiles whose {@code canHitEntity} is inherited, not referenced as their own, did).
 *
 * <p>So a {@code @Definition} that names a method of a class the kernel gave such a thunk names the thunk as well — the
 * same call, by the class's own body ({@link LambdaInvocationThunkInjector#isThunk}), never by name. Its other uses are
 * unchanged: a definition may list several members, a call to the original still matches the original, and nothing
 * calls a thunk directly. {@code -Dforbric.expressionThunks=off} leaves every definition as written.
 */
public final class MixinExpressionThunks {
	public static final String PROPERTY = "forbric.expressionThunks";
	static final String DEFINITION = "Lcom/llamalad7/mixinextras/expression/Definition;";
	static final String DEFINITIONS = "Lcom/llamalad7/mixinextras/expression/Definitions;";

	private MixinExpressionThunks() {
	}

	/** Extends every eligible definition of {@code mixin}; how many members it added. {@code targets} must carry code. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if ("off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY, "on")) || mixin == null
				|| mixin.methods == null || targets == null) return 0;
		int added = 0;
		for (MethodNode handler : mixin.methods) {
			for (AnnotationNode definition : definitions(handler)) {
				if (!(MixinFit.value(definition, "method") instanceof List<?> members)) continue;
				List<String> extended = new ArrayList<>();
				for (Object raw : members) extended.add(String.valueOf(raw));
				int before = extended.size();
				for (Object raw : members) {
					MixinFit.Member member = MixinFit.parseMember(String.valueOf(raw));
					if (member == null || member.owner() == null || member.desc() == null) continue;
					ClassNode owner = targets.apply(member.owner());
					if (owner == null || owner.methods == null) continue;
					for (MethodNode thunk : owner.methods) {
						if (!LambdaInvocationThunkInjector.isThunk(owner.name, thunk)) continue;
						MethodInsnNode call = LambdaInvocationThunkInjector.invocation(thunk);
						if (call == null || !call.owner.equals(member.owner()) || !call.name.equals(member.name()) || !call.desc.equals(member.desc())) continue;
						String spelled = "L" + owner.name + ";" + thunk.name + thunk.desc;
						if (!extended.contains(spelled)) extended.add(spelled);
					}
				}
				if (extended.size() == before) continue;
				MixinPlayerWorldCallbackAdapter.set(definition, "method", extended);
				added += extended.size() - before;
				ForbricLog.info("[Forbric/Mixin] %s: %s's expression definition '%s' also names %s — the kernel's thunk that every "
						+ "method reference to %s in that class now goes through", mixin.name.replace('/', '.'), handler.name,
						MixinFit.value(definition, "id"), extended.subList(before, extended.size()), members);
			}
		}
		return added;
	}

	/** The handler's {@code @Definition}s, alone or in their container, visible or not. */
	static List<AnnotationNode> definitions(MethodNode handler) {
		List<AnnotationNode> out = new ArrayList<>();
		for (List<AnnotationNode> annotations : java.util.Arrays.asList(handler.visibleAnnotations, handler.invisibleAnnotations)) {
			if (annotations == null) continue;
			for (AnnotationNode annotation : annotations) {
				if (DEFINITION.equals(annotation.desc)) out.add(annotation);
				else if (DEFINITIONS.equals(annotation.desc) && MixinFit.value(annotation, "value") instanceof List<?> nested) {
					for (Object inner : nested) if (inner instanceof AnnotationNode one && DEFINITION.equals(one.desc)) out.add(one);
				}
			}
		}
		return out;
	}
}
