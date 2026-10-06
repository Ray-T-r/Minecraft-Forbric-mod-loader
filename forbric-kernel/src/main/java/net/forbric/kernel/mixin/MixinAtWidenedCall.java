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
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

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
 * <h2>One decision, two readers</h2>
 *
 * <p>{@link MixinFit} judges a mixin at config-read time, before this rewrite runs, and counted an {@code @At(INVOKE)}
 * as resolved whenever a widened call existed — for ANY injector. The rewrite moved only the argument-blind kinds and a
 * fixed-index {@code @ModifyArg}, never a handler in a {@code @Group}. So creativecore's {@code require=1}
 * {@code @Redirect} of {@code RegistryFriendlyByteBuf.decorator(RegistryAccess)}, which NeoForge's configuration
 * listener calls with a {@code ConnectionType} appended, read FIT: no "applies only partially" line, no preflight row,
 * and nothing warned before its miss took the class down. {@link #wouldMove} is the rewrite's own decision, and both
 * the rewrite and the verdict ask it — now including the static-call redirects the next section moves.
 *
 * <h2>A redirect of a widened static call</h2>
 *
 * <p>A {@code @Redirect} mirrors the call and REPLACES it, which is why the rule above leaves it alone: on the merged
 * base the call it would replace is the carrier's extended one, and what the carrier does with its appended arguments
 * is then gone at that call. For an instance call that also skips every override of the carrier's method
 * (fabric-renderer-api's {@code hasMaterialFlag} redirects would have overridden every NeoForge model's context-aware
 * flag), so an instance call never moves. A static call has no override to skip, but its appended arguments can still be
 * the carrier's mechanism — NeoForge's four-argument {@code CustomPacketPayload.codec} is where its payload registry
 * joins the codec, and a Fabric redirect there would take NeoForge's payloads off the wire — so a static call moves only
 * along a reviewed row of {@link #REDIRECTABLE}, each with why replacing the carrier's call is the replacement vanilla's
 * was.
 *
 * <p>The handler is renamed aside and a method of its name and annotation takes the widened call's arguments and hands
 * the original the ones it was written for, in place, followed by whatever of the target's own arguments it captured.
 * creativecore's {@code ServerConfigurationPacketListenerImplMixin} and its client twin redirect
 * {@code RegistryFriendlyByteBuf.decorator(RegistryAccess)} to build their own {@code CreativeByteBuf} for the play
 * phase; NeoForge appends the {@code ConnectionType} it records on the buffer, and with the point unmatched the required
 * redirect stopped a strict launch that native Fabric runs. Only {@code @At(INVOKE)}, only when every widened call in
 * the selected bodies is an {@code invokestatic} of one widened descriptor, and only for a handler that takes the named
 * call's arguments first and returns its type. {@code -Dforbric.mixinAtWidenRedirect=off} leaves these where they are.
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
	private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
	/**
	 * The suffix a redirect handler moves to, under the wrapper that takes the widened call's arguments, before the mixin's
	 * mark ({@link MixinHandlerShim#asideName}).
	 */
	static final String REDIRECT_SUFFIX = "$forbricwidened";

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

	/** {@code -Dforbric.mixinAtWidenRedirect=off} leaves a redirect of a widened static call as compiled. */
	public static final String REDIRECT_PROPERTY = "forbric.mixinAtWidenRedirect";

	/**
	 * A widened static call a {@code @Redirect} of its vanilla form may follow.
	 *
	 * @param member  the widened call, as an {@code @At} target
	 * @param because why the mod's replacement of vanilla's call is a sound replacement of the carrier's
	 */
	public record Redirectable(String member, String because) {
	}

	/** Reviewed: replacing the carrier's call here costs exactly what replacing vanilla's does natively. */
	public static final List<Redirectable> REDIRECTABLE = List.of(
			new Redirectable("Lnet/minecraft/network/RegistryFriendlyByteBuf;decorator(Lnet/minecraft/core/RegistryAccess;"
					+ "Lnet/neoforged/neoforge/network/connection/ConnectionType;)Ljava/util/function/Function;",
					"the appended ConnectionType only becomes the type recorded on each buffer the function builds; the "
							+ "vanilla-form decorator the merged base keeps builds the same buffer typed OTHER, which is "
							+ "what a buffer a mod builds through vanilla's constructor carries, what every vanilla client's "
							+ "connection carries, and what the play buffers creativecore's own PlayerListMixin wrap builds "
							+ "already carry on this base. The cost: NeoForge's connection-aware codecs read that type off "
							+ "the buffer (IngredientCodecs, ByteBufCodecs' HolderSet codec, NeoForgeStreamCodecs"
							+ ".connectionAware), so on these play buffers they write and read vanilla's wire format — a "
							+ "custom ingredient goes as its item list. Two Forbric ends with creativecore agree, both "
							+ "sides typing OTHER as the inbound buffers already did; NetworkRegistry and NetworkFilters "
							+ "read the type off the connection, not the buffer, and are unchanged"));

	/** The reviewed row for a widened call, or null. */
	static Redirectable redirectable(String member) {
		for (Redirectable row : REDIRECTABLE) if (row.member().equals(member)) return row;
		return null;
	}

	static boolean redirectEnabled() {
		return enabled() && !"off".equalsIgnoreCase(System.getProperty(REDIRECT_PROPERTY, "on"));
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
		List<MethodNode> wrappers = new ArrayList<>();
		for (MethodNode method : new ArrayList<>(mixin.methods)) {
			for (AnnotationNode injector : annotationsOf(method)) {
				List<MethodNode> bodies = movable(method, injector, declared);
				if (bodies == null) continue;
				List<String[]> moves = new ArrayList<>();
				widened += widenOne(mixin.name, method, injector, injector, bodies, moves);
				// A redirect's handler mirrors the call, so the call it now names needs a handler of that shape.
				if (REDIRECT.equals(injector.desc) && moves.size() == 1) wrappers.add(redirectWrapper(mixin, method, injector,
						moves.getFirst()[0], moves.getFirst()[1]));
			}
		}
		mixin.methods.addAll(wrappers);
		return widened;
	}

	/**
	 * The handler renamed aside, and in its place a method of its name, access and injector annotation shaped for the
	 * widened static call: it takes the call's arguments, then the target arguments the handler captured, and hands the
	 * original the named call's arguments and those captures.
	 */
	private static MethodNode redirectWrapper(ClassNode mixin, MethodNode handler, AnnotationNode injector, String named,
			String moved) {
		Type[] own = Type.getArgumentTypes(parse(named).descriptor());
		Type[] wide = Type.getArgumentTypes(parse(moved).descriptor());
		Type[] params = Type.getArgumentTypes(handler.desc);
		Type[] outerParams = new Type[wide.length + params.length - own.length];
		System.arraycopy(wide, 0, outerParams, 0, wide.length);
		System.arraycopy(params, own.length, outerParams, wide.length, params.length - own.length);
		Type returned = Type.getReturnType(handler.desc);
		boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		MethodNode outer = new MethodNode(Opcodes.ASM9, handler.access, handler.name,
				Type.getMethodDescriptor(returned, outerParams), null,
				handler.exceptions == null ? null : handler.exceptions.toArray(new String[0]));
		boolean visible = handler.visibleAnnotations != null && handler.visibleAnnotations.remove(injector);
		if (!visible && handler.invisibleAnnotations != null) handler.invisibleAnnotations.remove(injector);
		if (visible) outer.visibleAnnotations = new ArrayList<>(List.of(injector));
		else outer.invisibleAnnotations = new ArrayList<>(List.of(injector));

		int[] slots = new int[outerParams.length + 1];
		int slot = isStatic ? 0 : 1;
		for (int i = 0; i < outerParams.length; i++) {
			slots[i] = slot;
			slot += outerParams[i].getSize();
		}
		int stack = 0;
		if (!isStatic) {
			outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			stack++;
		}
		for (int i = 0; i < outerParams.length; i++) {
			if (i >= own.length && i < wide.length) continue;    // the carrier's appended arguments
			outer.instructions.add(new VarInsnNode(outerParams[i].getOpcode(Opcodes.ILOAD), slots[i]));
			stack += outerParams[i].getSize();
		}
		String aside = MixinHandlerShim.asideName(mixin.name, handler.name, REDIRECT_SUFFIX);
		outer.instructions.add(MixinHandlerShim.callOwn(mixin, isStatic, aside, handler.desc));
		outer.instructions.add(new InsnNode(returned.getOpcode(Opcodes.IRETURN)));
		outer.maxLocals = slot;
		outer.maxStack = Math.max(stack, returned.getSize());
		handler.name = aside;
		return outer;
	}

	/**
	 * Where {@link #widen} will point one {@code @At(atValue, target)} of {@code handler}'s {@code injector}, or null
	 * when it leaves that point as compiled. The rewrite's own decision, for {@link MixinFit}: the verdict and the move
	 * cannot disagree. {@code declared} is the target class's methods WITH instructions.
	 */
	public static String wouldMove(MethodNode handler, AnnotationNode injector, List<MethodNode> declared, String atValue,
			String target) {
		if (!enabled() || handler == null || injector == null || declared == null || atValue == null || target == null) return null;
		List<MethodNode> bodies = movable(handler, injector, declared);
		return bodies == null ? null : decide(handler, injector, bodies, atValue, target);
	}

	/**
	 * The bodies an injector's points may move within, or null when none of its points moves: a handler in a
	 * {@code @Group} (the group is the mod's own statement that some of these points are meant to miss), an injector
	 * whose handler describes the call, or a selector naming nothing.
	 */
	private static List<MethodNode> movable(MethodNode handler, AnnotationNode injector, List<MethodNode> declared) {
		if (!annotationsOf(handler).contains(injector) || inGroup(handler)) return null;
		if (!ARGUMENT_BLIND.contains(injector.desc) && !MODIFY_ARG.equals(injector.desc)
				&& !(REDIRECT.equals(injector.desc) && redirectEnabled())) return null;
		List<MethodNode> bodies = selected(injector, declared);
		return bodies.isEmpty() ? null : bodies;
	}

	/** Whether {@code handler} is one alternative of a callback {@code @Group}, which no point of it moves out of. */
	static boolean inGroup(MethodNode handler) {
		for (AnnotationNode annotation : annotationsOf(handler)) {
			if (GROUP_DESC.equals(annotation.desc)) return true;
		}
		return false;
	}

	/**
	 * Both lists together: {@code @Group} and {@code @Inject} are on the same handler but a compiler may put them in
	 * different retention buckets, and checking one list at a time would miss the group half the time.
	 */
	private static List<AnnotationNode> annotationsOf(MethodNode handler) {
		List<AnnotationNode> annotations = new ArrayList<>();
		if (handler.visibleAnnotations != null) annotations.addAll(handler.visibleAnnotations);
		if (handler.invisibleAnnotations != null) annotations.addAll(handler.invisibleAnnotations);
		return annotations;
	}

	/** The moved target for one point of an injector that {@link #movable} allowed, or null. */
	private static String decide(MethodNode handler, AnnotationNode injector, List<MethodNode> bodies, String atValue,
			String target) {
		boolean blind = ARGUMENT_BLIND.contains(injector.desc);
		if (CALL_SITES.contains(atValue)) {
			String moved = widenedAcross(bodies, target);
			if (moved == null) return null;
			if (REDIRECT.equals(injector.desc)) {
				return "INVOKE".equals(atValue) && staticRedirect(handler, bodies, target, moved) ? moved : null;
			}
			return blind || singleArgumentAtFixedIndex(handler, injector, target, moved, bodies) ? moved : null;
		}
		if ("NEW".equals(atValue) && blind && target.startsWith("(")) return widenedNewAcross(bodies, target);
		return null;
	}

	/**
	 * A redirect of {@code named} the wrapper can serve at {@code moved}: a {@link #REDIRECTABLE} row, every widened call in
	 * the bodies an {@code invokestatic}, and a handler that takes the named call's arguments first and returns its type.
	 */
	private static boolean staticRedirect(MethodNode handler, List<MethodNode> bodies, String named, String moved) {
		Member call = parse(moved), own = parse(named);
		if (call == null || own == null || redirectable(moved) == null) return false;
		int calls = 0;
		for (MethodNode body : bodies) {
			if (body.instructions == null) continue;
			for (AbstractInsnNode insn : body.instructions) {
				if (!(insn instanceof MethodInsnNode site) || !site.owner.equals(call.owner()) || !site.name.equals(call.name())
						|| !site.desc.equals(call.descriptor())) continue;
				if (site.getOpcode() != Opcodes.INVOKESTATIC) return false;
				calls++;
			}
		}
		if (calls == 0 || !Type.getReturnType(handler.desc).equals(Type.getReturnType(own.descriptor()))) return false;
		Type[] wanted = Type.getArgumentTypes(own.descriptor());
		Type[] params = Type.getArgumentTypes(handler.desc);
		if (params.length < wanted.length) return false;
		for (int i = 0; i < wanted.length; i++) if (!params[i].equals(wanted[i])) return false;
		return true;
	}

	/** A fixed prefix argument keeps its index/type when the carrier appends arguments. A full-arguments
	 * handler or inferred index does not have this proof and is left unchanged. */
	private static boolean singleArgumentAtFixedIndex(MethodNode handler, AnnotationNode injector, String target,
			String moved, List<MethodNode> bodies) {
		Object rawIndex = MixinFit.value(injector, "index");
		if (!(rawIndex instanceof Integer index) || index < 0) return false;
		Member member = parse(target);
		if (member == null) return false;
		Type[] parameters = Type.getArgumentTypes(member.descriptor());
		Type[] captured = Type.getArgumentTypes(handler.desc);
		if (index >= parameters.length || captured.length == 0 || !captured[0].equals(parameters[index])
				|| !Type.getReturnType(handler.desc).equals(parameters[index])) return false;
		if (captured.length == 1) return true;
		// Sugar locals belong to the enclosing method, not the widened call's argument list. Keep them only
		// when every additional parameter is an explicitly named/indexed, unique in-scope local at every site.
		Member wide = parse(moved);
		for (int parameter = 1; parameter < captured.length; parameter++) {
			AnnotationNode local = explicitLocal(handler, parameter);
			if (local == null) return false;
			for (MethodNode body : bodies) for (AbstractInsnNode instruction : body.instructions) {
				if (!(instruction instanceof MethodInsnNode call) || !call.owner.equals(wide.owner())
						|| !call.name.equals(wide.name()) || !call.desc.equals(wide.descriptor())) continue;
				if (!uniqueLocal(body, instruction, captured[parameter], local)) return false;
			}
		}
		return true;
	}

	private static AnnotationNode explicitLocal(MethodNode method, int parameter) {
		AnnotationNode found = null;
		for (var annotations : List.of(method.visibleParameterAnnotations == null ? new List<?>[0] : method.visibleParameterAnnotations,
				method.invisibleParameterAnnotations == null ? new List<?>[0] : method.invisibleParameterAnnotations)) {
			if (parameter >= annotations.length || annotations[parameter] == null) continue;
			for (Object value : annotations[parameter]) {
				AnnotationNode annotation = (AnnotationNode) value;
				if (!annotation.desc.equals("Lcom/llamalad7/mixinextras/sugar/Local;") || found != null) return null;
				found = annotation;
			}
		}
		if (found == null || Boolean.TRUE.equals(MixinFit.value(found, "argsOnly"))
				|| MixinFit.value(found, "ordinal") instanceof Number n && n.intValue() >= 0) return null;
		return found;
	}

	private static boolean uniqueLocal(MethodNode body, AbstractInsnNode site, Type type, AnnotationNode annotation) {
		Object names = MixinFit.value(annotation, "name"), slot = MixinFit.value(annotation, "index");
		List<?> selectedNames = names instanceof List<?> values ? values : List.of();
		boolean indexed = slot instanceof Number n && n.intValue() >= 0;
		if (!indexed && selectedNames.isEmpty() || body.localVariables == null) return false;
		int position = body.instructions.indexOf(site), matches = 0;
		for (LocalVariableNode local : body.localVariables) {
			if (!local.desc.equals(type.getDescriptor()) || body.instructions.indexOf(local.start) > position
					|| body.instructions.indexOf(local.end) <= position) continue;
			if (indexed && local.index != ((Number) slot).intValue()) continue;
			if (!selectedNames.isEmpty() && !selectedNames.contains(local.name)) continue;
			matches++;
		}
		return matches == 1;
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
	private static int widenOne(String mixinName, MethodNode handler, AnnotationNode injector, AnnotationNode annotation,
			List<MethodNode> bodies, List<String[]> moves) {
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
				String moved = decide(handler, injector, bodies, atValue, target);
				if (moved != null) {
					annotation.values.set(i + 1, moved);
					widened++;
					moves.add(new String[] {target, moved});
					if (REDIRECT.equals(injector.desc)) {
						ForbricLog.info("[Forbric/Mixin] %s: @Redirect %s names the vanilla signature of a static call, and "
								+ "nothing in the method it selects calls that — pointed at %s, the same call with the "
								+ "parameters the surviving carrier appended; %s takes them and hands its original the "
								+ "arguments it was written for, replacing the call as it replaces vanilla's",
								mixinName.replace('/', '.'), target, moved, handler.name);
					} else {
						ForbricLog.info("[Forbric/Mixin] %s: injection point %s names the vanilla signature, and nothing "
								+ "in the method it selects calls that — pointed at %s, the same call with the "
								+ "parameters the surviving carrier appended", mixinName.replace('/', '.'), target, moved);
					}
				}
			} else if (isAt && "target".equals(name) && value instanceof String target && "NEW".equals(atValue)) {
				String moved = decide(handler, injector, bodies, atValue, target);
				if (moved != null) {
					annotation.values.set(i + 1, moved);
					widened++;
					ForbricLog.info("[Forbric/Mixin] %s: injection point NEW %s names the vanilla constructor, and nothing in "
							+ "the method it selects constructs that — pointed at %s, the same construction with the "
							+ "arguments the surviving carrier appended", mixinName.replace('/', '.'), target, moved);
				}
			} else if (value instanceof AnnotationNode nested) {
				widened += widenOne(mixinName, handler, injector, nested, bodies, moves);
			} else if (value instanceof List<?> list) {
				for (Object item : new ArrayList<>(list)) {
					if (item instanceof AnnotationNode nested) widened += widenOne(mixinName, handler, injector, nested, bodies, moves);
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
