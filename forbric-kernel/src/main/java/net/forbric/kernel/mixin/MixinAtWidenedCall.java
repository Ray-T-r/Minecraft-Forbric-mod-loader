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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Re-points an {@code @At} whose call the surviving carrier gave extra parameters.
 *
 * <p>A carrier routinely extends a vanilla method rather than replacing it, and the merge keeps whichever half
 * won. {@code CustomPacketPayload.codec} is vanilla's {@code (FallbackProvider, List)}; NeoForge's takes
 * {@code (FallbackProvider, List, ConnectionProtocol, PacketFlow)}, and that is the one every call site in this
 * base uses — vanilla's is still DECLARED, so nothing looks wrong, and nothing calls it. A mixin compiled against
 * vanilla names the short descriptor, its injection point matches nothing, and Mixin reports only that an anchor
 * did not resolve.
 *
 * <p>What that costs is not a missing feature. Polymer patches the payload codec exactly there — it is how every
 * Polymer payload becomes encodable — and with the point unmatched its {@code polymer:hello} went out with
 * vanilla's unknown-id fallback codec. The client was disconnected at world join on
 * {@code HelloS2CPayload cannot be cast to DiscardedPayload}: a Netty stack naming the mod and the game, and
 * nothing about a method signature that grew two parameters.
 *
 * <h2>Which handler contracts survive appended arguments</h2>
 *
 * <p>Moving a point is only safe when the handler's signature does not describe the CALL. {@code @Inject} takes
 * the enclosing method's parameters and {@code @ModifyExpressionValue} takes the value the call returned, so
 * neither cares how many arguments the call has. {@code @WrapOperation}, {@code @Redirect} and the
 * {@code @ModifyArg} family mirror the call's own arguments, and pointing one of those at a longer call makes
 * Mixin reject the handler outright — which is exactly what happened to fabric-networking's own
 * {@code @WrapOperation} on this same method the first time this rule was written without the restriction.
 *
 * <p>A single-argument {@code @ModifyArg} is also safe when it declares an explicit original argument index
 * and both its parameter and return type equal that argument. Appending parameters cannot change that index.
 * A handler receiving the entire argument list or relying on an inferred index remains untouched.
 *
 * <p>The move itself is a prefix, and only a prefix: same owner, same name, same return type, and the parameters
 * the mixin named must be the FIRST ones of the call it lands on. It fires only when the named call is nowhere in
 * the bodies the injector selects and exactly ONE widened call is; two and it declines, because picking between
 * overloads is how an injection lands silently in the wrong place.
 *
 * <p>The same holds for a construction. NeoForge builds {@code BlockParticleOption} with the block position appended
 * in {@code Entity.spawnSprintParticle} and {@code LivingEntity.checkFallDamage}; fabric-particles'
 * {@code @ModifyExpressionValue} names vanilla's {@code NEW (ParticleType, BlockState)} there, so it attached nowhere
 * and a mob's landing dust and sprint dust never learned the ground block. An {@code @At(NEW)} whose target is a
 * constructor descriptor moves the same way — only for the argument-blind injectors, pairing each {@code NEW} with
 * its own {@code <init>} ({@code -Dforbric.mixinAtWidenNew=off} for this part alone).
 *
 * <p>{@code -Dforbric.mixinAtWiden=off} leaves every injection point as compiled.
 */
public final class MixinAtWidenedCall {
	public static final String PROPERTY = "forbric.mixinAtWiden";
	private static final String AT_DESC = "Lorg/spongepowered/asm/mixin/injection/At;";
	private static final Set<String> CALL_SITES = Set.of("INVOKE", "INVOKE_ASSIGN");

	/**
	 * The injectors whose handler signature is independent of the call's arguments.
	 *
	 * <p>A deliberate allow-list rather than a deny-list: a new MixinExtras injector that mirrors the call would
	 * otherwise be moved the day it appears, and the failure mode is a mixin that stops applying at all.
	 */
	private static final Set<String> ARGUMENT_BLIND = Set.of(
			"Lorg/spongepowered/asm/mixin/injection/Inject;",
			"Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;");

	/**
	 * A handler in a callback group is left alone, whatever its injector.
	 *
	 * <p>{@code @Group(min=1, max=1)} is how a mod writes "exactly one of these alternatives should match here" —
	 * the alternatives are the shapes different game versions have, and the ones that do not match are SUPPOSED
	 * not to match. Widening one of them makes two match and the group's own check fails the whole mixin class.
	 * Iris is the case that paid for it: its {@code MixinLevelRenderer} has a {@code max=1} group on
	 * {@code addMainPass}, and moving one member's point took shaders down with an
	 * {@code InvalidInjectionException} that named the group and not the move.
	 */
	private static final String GROUP_DESC = "Lorg/spongepowered/asm/mixin/injection/Group;";
	private static final String MODIFY_ARG = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;";

	private MixinAtWidenedCall() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** {@code -Dforbric.mixinAtWidenNew=off} leaves {@code @At(NEW)} points as compiled; INVOKE widening goes on. */
	public static final String NEW_PROPERTY = "forbric.mixinAtWidenNew";

	static boolean newEnabled() {
		return enabled() && !"off".equalsIgnoreCase(System.getProperty(NEW_PROPERTY, "on"));
	}

	/** Whether an injector of this kind ignores the arguments of the call or construction it anchors on. */
	static boolean argumentBlind(String injectorDesc) {
		return ARGUMENT_BLIND.contains(injectorDesc);
	}

	/**
	 * The one construction inside {@code body} that is the {@code @At(NEW)} target {@code (args)Ltype;} with the
	 * carrier's extra constructor parameters, as a NEW target; {@code null} when the named constructor is built there,
	 * nothing widened is, or more than one widened form is. Each {@code NEW} is paired with its own {@code <init>}
	 * by nesting depth (as Mixin's BeforeNew does), so {@code new Outer(new T(a, b, c))} is judged by T's.
	 */
	public static String widenedNewIn(MethodNode body, String target) {
		if (!newEnabled() || body == null || body.instructions == null || target == null || !target.startsWith("(")) return null;
		Type method;
		try {
			method = Type.getMethodType(target);
		} catch (RuntimeException malformed) {
			return null;
		}
		if (method.getReturnType().getSort() != Type.OBJECT) return null;
		String type = method.getReturnType().getInternalName();
		String named = Type.getMethodDescriptor(Type.VOID_TYPE, method.getArgumentTypes());
		Set<String> widened = new LinkedHashSet<>();
		for (AbstractInsnNode insn : body.instructions) {
			if (!(insn instanceof TypeInsnNode created) || created.getOpcode() != Opcodes.NEW || !created.desc.equals(type)) continue;
			MethodInsnNode init = null;
			int depth = 0;
			for (AbstractInsnNode next = insn.getNext(); next != null; next = next.getNext()) {
				if (next.getOpcode() == Opcodes.NEW) depth++;
				if (next instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("<init>")) {
					if (depth == 0) { init = call; break; }
					depth--;
				}
			}
			if (init == null || !init.owner.equals(type)) return null;
			if (init.desc.equals(named)) return null;
			if (widens(named, init.desc)) widened.add(init.desc);
		}
		if (widened.size() != 1) return null;
		String desc = widened.iterator().next();
		return Type.getMethodDescriptor(Type.getObjectType(type), Type.getArgumentTypes(desc));
	}

	/** One {@code @At} member target, split into the parts this rule reasons about. */
	record Member(String owner, String name, String descriptor) {
		String render() {
			return "L" + owner + ";" + name + descriptor;
		}
	}

	/**
	 * Parses the {@code Lowner;name(args)Ret} form Mixin compiles an {@code @At} target into.
	 *
	 * @return the parts, or {@code null} when this is not a method member (a field target has no {@code (})
	 */
	static Member parse(String target) {
		if (target == null) return null;
		int parenthesis = target.indexOf('(');
		if (parenthesis < 0) return null;

		String head = target.substring(0, parenthesis);
		String descriptor = target.substring(parenthesis);
		int separator = head.indexOf(';');
		String owner;
		String name;
		if (head.startsWith("L") && separator > 0) {
			owner = head.substring(1, separator);
			name = head.substring(separator + 1);
		} else {
			separator = head.lastIndexOf('.');
			if (separator < 0) return null;
			owner = head.substring(0, separator).replace('.', '/');
			name = head.substring(separator + 1);
		}
		return owner.isEmpty() || name.isEmpty() ? null : new Member(owner, name, descriptor);
	}

	/** Whether {@code call} is {@code named} with parameters appended. */
	static boolean widens(String named, String call) {
		if (named.equals(call)) return false;
		if (!Type.getReturnType(named).equals(Type.getReturnType(call))) return false;

		Type[] wanted = Type.getArgumentTypes(named);
		Type[] actual = Type.getArgumentTypes(call);
		if (actual.length <= wanted.length) return false;
		for (int i = 0; i < wanted.length; i++) {
			if (!wanted[i].equals(actual[i])) return false;
		}
		return true;
	}

	/**
	 * The one call inside {@code body} that is {@code target} with the carrier's extra parameters, or {@code null}.
	 *
	 * <p>Judged on the CALL SITES, not on what the owner declares, and that distinction is the rule. The merged
	 * base still declares vanilla's two-argument {@code CustomPacketPayload.codec} beside NeoForge's four-argument
	 * one, and nothing calls the short one — a declaration-based check would answer "it is right there" about a
	 * method no instruction in this base reaches.
	 */
	public static String widenedIn(MethodNode body, String target) {
		if (!enabled() || body == null || body.instructions == null) return null;
		Member member = parse(target);
		if (member == null) return null;

		Set<String> candidates = new LinkedHashSet<>();
		for (AbstractInsnNode insn : body.instructions) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			if (!call.owner.equals(member.owner()) || !call.name.equals(member.name())) continue;
			// The named call is really here: the point resolves on its own and must not be moved.
			if (call.desc.equals(member.descriptor())) return null;
			if (widens(member.descriptor(), call.desc)) candidates.add(call.desc);
		}
		if (candidates.size() != 1) return null;
		return new Member(member.owner(), member.name(), candidates.iterator().next()).render();
	}

	/**
	 * Rewrites every eligible {@code @At(INVOKE…)} in {@code mixin} whose named call the carrier widened.
	 *
	 * @param targets resolves an internal class name to its merged-base node WITH instructions
	 * @return how many injection points were moved
	 */
	public static int widen(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!enabled() || mixin == null || mixin.methods == null || targets == null) return 0;

		List<MethodNode> declared = new ArrayList<>();
		for (String targetName : MixinOverloadPin.targetsOf(mixin)) {
			ClassNode target = targets.apply(targetName);
			if (target != null && target.methods != null) declared.addAll(target.methods);
		}
		if (declared.isEmpty()) return 0;

		int widened = 0;
		for (MethodNode method : mixin.methods) {
			// Both lists together: @Group and @Inject are on the same handler but a compiler may put them in
			// different retention buckets, and checking one list at a time would miss the group half the time.
			List<AnnotationNode> annotations = new ArrayList<>();
			if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
			if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
			widened += widenAll(mixin.name, method, annotations, declared);
		}
		return widened;
	}

	private static int widenAll(String mixinName, MethodNode handler, List<AnnotationNode> annotations, List<MethodNode> declared) {
		if (annotations == null) return 0;
		// One @Group anywhere on this handler and nothing on it moves: the group is the mod's own statement that
		// some of these points are meant to miss.
		for (AnnotationNode annotation : annotations) {
			if (GROUP_DESC.equals(annotation.desc)) return 0;
		}
		int widened = 0;
		for (AnnotationNode injector : annotations) {
			if (!ARGUMENT_BLIND.contains(injector.desc) && !MODIFY_ARG.equals(injector.desc)) continue;
			List<MethodNode> bodies = selected(injector, declared);
			if (bodies.isEmpty()) continue;
			widened += widenOne(mixinName, injector, bodies, member -> ARGUMENT_BLIND.contains(injector.desc)
					|| singleArgumentAtFixedIndex(handler, injector, member), ARGUMENT_BLIND.contains(injector.desc));
		}
		return widened;
	}

	/** A fixed prefix argument keeps its index/type when the carrier appends arguments. A full-arguments
	 * handler or inferred index does not have this proof and is left unchanged. */
	private static boolean singleArgumentAtFixedIndex(MethodNode handler, AnnotationNode injector, String target) {
		Object rawIndex = MixinFit.value(injector, "index");
		if (!(rawIndex instanceof Integer index) || index < 0) return false;
		Member member = parse(target);
		if (member == null) return false;
		Type[] parameters = Type.getArgumentTypes(member.descriptor());
		Type[] captured = Type.getArgumentTypes(handler.desc);
		return index < parameters.length && captured.length == 1 && captured[0].equals(parameters[index])
				&& Type.getReturnType(handler.desc).equals(parameters[index]);
	}

	/** The target methods this injector's {@code method} selectors name, matched exactly as written. */
	private static List<MethodNode> selected(AnnotationNode injector, List<MethodNode> declared) {
		Set<String> selectors = new LinkedHashSet<>();
		collectSelectors(injector, selectors);
		if (selectors.isEmpty()) return List.of();

		List<MethodNode> bodies = new ArrayList<>();
		for (MethodNode body : declared) {
			for (String selector : selectors) {
				if (selector.equals(body.name) || selector.equals(body.name + body.desc)) {
					bodies.add(body);
					break;
				}
			}
		}
		return bodies;
	}

	private static void collectSelectors(AnnotationNode annotation, Set<String> out) {
		if (annotation == null || annotation.values == null) return;
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			if ("method".equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof List<?> entries) {
				for (Object entry : entries) {
					if (entry instanceof String selector) out.add(selector);
				}
			}
		}
	}

	/** Walks the injector's values — {@code @At} sits nested inside it, sometimes in a list. */
	private static int widenOne(String mixinName, AnnotationNode annotation, List<MethodNode> bodies,
			java.util.function.Predicate<String> safe, boolean blind) {
		if (annotation == null || annotation.values == null) return 0;

		int widened = 0;
		boolean isAt = AT_DESC.equals(annotation.desc);
		String atValue = null;
		if (isAt) {
			for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
				if ("value".equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof String v) {
					atValue = v;
				}
			}
		}
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			Object name = annotation.values.get(i);
			Object value = annotation.values.get(i + 1);
			if (isAt && "target".equals(name) && value instanceof String target && CALL_SITES.contains(atValue)) {
				String moved = widenedAcross(bodies, target);
				if (moved != null && safe.test(target)) {
					annotation.values.set(i + 1, moved);
					widened++;
					ForbricLog.info("[Forbric/Mixin] %s: injection point %s names the vanilla signature, and nothing "
							+ "in the method it selects calls that — pointed at %s, the same call with the "
							+ "parameters the surviving carrier appended", mixinName.replace('/', '.'), target, moved);
				}
			} else if (isAt && "target".equals(name) && value instanceof String target && "NEW".equals(atValue) && blind
					&& target.startsWith("(")) {
				String moved = widenedNewAcross(bodies, target);
				if (moved != null) {
					annotation.values.set(i + 1, moved);
					widened++;
					ForbricLog.info("[Forbric/Mixin] %s: injection point NEW %s names the vanilla constructor, and nothing in "
							+ "the method it selects constructs that — pointed at %s, the same construction with the "
							+ "arguments the surviving carrier appended", mixinName.replace('/', '.'), target, moved);
				}
			} else if (value instanceof AnnotationNode nested) {
				widened += widenOne(mixinName, nested, bodies, safe, blind);
			} else if (value instanceof List<?> list) {
				for (Object item : new ArrayList<>(list)) {
					if (item instanceof AnnotationNode nested) widened += widenOne(mixinName, nested, bodies, safe, blind);
				}
			}
		}
		return widened;
	}

	/** The one widened form across every selected body, or {@code null} if any body calls the named one as written. */
	private static String widenedAcross(List<MethodNode> bodies, String target) {
		Set<String> moved = new LinkedHashSet<>();
		for (MethodNode body : bodies) {
			String one = widenedIn(body, target);
			if (one == null && callsExactly(body, target)) return null;
			if (one != null) moved.add(one);
		}
		return moved.size() == 1 ? moved.iterator().next() : null;
	}

	/** The one widened construction across every selected body, or {@code null} if any body builds the named one. */
	private static String widenedNewAcross(List<MethodNode> bodies, String target) {
		Set<String> moved = new LinkedHashSet<>();
		Type method = Type.getMethodType(target);
		String named = Type.getMethodDescriptor(Type.VOID_TYPE, method.getArgumentTypes());
		for (MethodNode body : bodies) {
			if (body.instructions != null) for (AbstractInsnNode insn : body.instructions) {
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("<init>")
						&& call.owner.equals(method.getReturnType().getInternalName()) && call.desc.equals(named)) return null;
			}
			String one = widenedNewIn(body, target);
			if (one != null) moved.add(one);
		}
		return moved.size() == 1 ? moved.iterator().next() : null;
	}

	private static boolean callsExactly(MethodNode body, String target) {
		Member member = parse(target);
		if (member == null || body.instructions == null) return false;
		for (AbstractInsnNode insn : body.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(member.owner())
					&& call.name.equals(member.name()) && call.desc.equals(member.descriptor())) {
				return true;
			}
		}
		return false;
	}
}
