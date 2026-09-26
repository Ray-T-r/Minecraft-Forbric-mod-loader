/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Moves a Fabric mod's injector off a merge-added delegating stub onto the method that carries the body.
 *
 * <p>Mixin binds a selector without a descriptor to the FIRST declared method of that name (the selector's default
 * quantifier is one match; {@code TargetSelectors} stops there), and one with a descriptor to exactly that method. A
 * carrier that widened a vanilla method usually kept vanilla's signature in place as a stub —
 * {@code Player.getDestroySpeed(BlockState)} is {@code return getDestroySpeed(state, null)} on the merged base — and
 * put the body in the new overload after it. A Fabric mod was compiled against vanilla, where that one method IS the
 * body, so its injector lands on the stub: an anchor inside the body is simply missing, and a {@code HEAD} or
 * {@code RETURN} injection runs only when something calls the stub. Nothing on the merged base calls
 * {@code getDestroySpeed(BlockState)}: architectury's and Collective's break-speed events never fired.
 *
 * <p>The injector's selector moves to the delegate. A handler that captures the target's arguments is wrapped: the
 * outer takes the delegate's parameters and hands the original the stub's, read off the stub's own delegation call —
 * each stub parameter must reach the call unchanged, or nothing moves. Kept exactly:
 * <ul>
 *   <li>only along a row of {@code carrier-stubs.txt}: the stub's signature is vanilla's and the overload is the
 *       carrier's. Vanilla keeps stub-and-overload pairs of its own ({@code Minecraft.disconnect(Screen, boolean)}
 *       forwarding to the three-argument one) and a mod that chose the short one there meant it;</li>
 *   <li>only mixins from Fabric mods — a NeoForge or MinecraftForge mod was compiled against the stub-first shape and
 *       gets what it would get natively;</li>
 *   <li>only a PURE stub: loads, constants, static fields, zero-argument static factories, non-capturing lambdas and
 *       method references (a constant, like a static field — NeoForge's {@code Language.loadFromJson(InputStream,
 *       BiConsumer)} passes a no-op component consumer) and argument construction, then one call to a same-name
 *       overload of the same class and static-ness, returning its result unchanged; the overload must have a body
 *       (an interface default forwarding to an abstract overload is no stub);</li>
 *   <li>every {@code INVOKE}/{@code FIELD}/{@code NEW} anchor absent from the stub and present in the delegate
 *       ({@code HEAD}, {@code RETURN} and {@code TAIL} are equivalent on both: the stub returns what the delegate
 *       returns);</li>
 *   <li>{@code @Inject} capturing nothing or exactly the stub's arguments, no locals capture; a MixinExtras
 *       {@code @Local} only by a name the delegate's local variable table has in that type; no {@code @Share}, no
 *       {@code @Group};</li>
 *   <li>the {@code @At}-driven kinds only when every parameter past the injector's own contract — the value it
 *       modifies, or the receiver and arguments of the call it replaces or wraps — is a capture of the stub's LEADING
 *       arguments that the stub passes to the delegate at the same positions. Mixin lets any of these kinds take a
 *       prefix of the target's arguments after its own, and the rule used to read every one of them as part of the
 *       call: torrential's {@code @ModifyReturnValue} on {@code FuelValues.vanillaBurnTimes(Provider, FeatureFlagSet,
 *       int)} captures all three, the stub feeds the first two into a {@code Builder}, and moving it to
 *       {@code (Builder, int)} made MixinExtras reject the handler and the whole required mixin with it. It now
 *       stays on the stub, where it binds; puzzleslib's {@code getDestroySpeed(float, BlockState)} still moves,
 *       because the stub passes its {@code BlockState} straight through as the delegate's first argument. When the
 *       contract's size cannot be told, nothing moves;</li>
 *   <li>a {@code @ModifyVariable} only by one {@code name} (never {@code ordinal}/{@code index}, which count locals by
 *       type across a body the carrier widened), at {@code LOAD}/{@code STORE} with no slice, when the stub has no
 *       local of that name and the delegate's table has it in one slot of the handler's type, accessed while live more
 *       times than the {@code @At}'s ordinal. torrential's Conduit Power mining bonus names {@code speed} in
 *       {@code Player.getDestroySpeed}; the merged stub has no {@code speed}, the body is the overload the game calls,
 *       and the bonus silently never applied. {@code -Dforbric.mixinStubRebind.modifyVariable=off} leaves these where
 *       they are.</li>
 * </ul>
 * {@code -Dforbric.mixinStubRebind=off} leaves every selector as compiled.
 */
public final class MixinStubRebind {
	public static final String PROPERTY = "forbric.mixinStubRebind";
	/** {@code -Dforbric.mixinStubRebind.modifyVariable=off}: no {@code @ModifyVariable} moves; everything else still does. */
	public static final String MODIFY_VARIABLE_PROPERTY = "forbric.mixinStubRebind.modifyVariable";
	/**
	 * {@code -Dforbric.mixinStubRebind.captures=off}: the {@code @At}-driven kinds move (here and in MixinRetarget's R1)
	 * as they did before trailing captures were told apart — an A/B switch; with it torrential's fuel hook fails again.
	 */
	public static final String CAPTURES_PROPERTY = "forbric.mixinStubRebind.captures";

	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String CALLBACK_INFO = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	private static final String CALLBACK_INFO_RETURNABLE = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
	private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
	private static final String GROUP = "Lorg/spongepowered/asm/mixin/injection/Group;";
	private static final String MODIFY_VARIABLE = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";
	private static final String MODIFY_ARG = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;";
	private static final String MODIFY_ARGS = "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;";
	private static final String WRAP_OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
	private static final String OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
	/** Kinds whose own contract is ONE value: the one they modify, or {@code @ModifyArgs}' {@code Args}. */
	private static final Set<String> ONE_VALUE = Set.of(
			"Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
			"Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
			"Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
			MODIFY_VARIABLE,
			MODIFY_ARGS);
	/** Kinds whose own contract is the receiver and arguments of the access they replace or guard. */
	private static final Set<String> CALL_SHAPED = Set.of(
			"Lorg/spongepowered/asm/mixin/injection/Redirect;",
			"Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
			"Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
			WRAP_OPERATION);
	private static final Set<String> LOCAL_POINTS = Set.of("LOAD", "STORE");
	private static final Set<String> CALL_POINTS = Set.of("INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING", "FIELD", "NEW");
	private static final Set<String> EDGE_POINTS = Set.of("HEAD", "RETURN", "TAIL");

	/** The shipped table of carrier-added stubs; CarrierStubCensusTest pins it to the staged artifacts. */
	static final String TABLE = "/net/forbric/kernel/mixin/carrier-stubs.txt";
	private static volatile Set<String> carrierStubs;

	/** Mixin class (internal name) → the ecosystem of the mod whose config declares it; filled as configs are read. */
	private static final Map<String, Ecosystem> ECOSYSTEMS = new ConcurrentHashMap<>();

	private MixinStubRebind() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	static boolean modifyVariableEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(MODIFY_VARIABLE_PROPERTY, "on"));
	}

	static boolean capturesGuarded() {
		return !"off".equalsIgnoreCase(System.getProperty(CAPTURES_PROPERTY, "on"));
	}

	/** Records which family's mod declared {@code mixinInternalName}; null when the config's owner is ambiguous. */
	public static void noteEcosystem(String mixinInternalName, Ecosystem ecosystem) {
		if (mixinInternalName != null && ecosystem != null) ECOSYSTEMS.put(mixinInternalName, ecosystem);
	}

	/** Which family's mod declared {@code mixinInternalName}; null when not recorded or the config's owner is ambiguous. */
	static Ecosystem ecosystemOf(String mixinInternalName) {
		return mixinInternalName == null ? null : ECOSYSTEMS.get(mixinInternalName);
	}

	/** Test seam. */
	static void forget() {
		ECOSYSTEMS.clear();
	}

	/** Moves every eligible injector of a Fabric mod's {@code mixin}; returns how many. {@code targets} must return nodes WITH code. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!enabled() || mixin == null || mixin.methods == null || targets == null) return 0;
		if (ECOSYSTEMS.get(mixin.name) != Ecosystem.FABRIC) return 0;
		List<String> targetNames = MixinOverloadPin.targetsOf(mixin);
		if (targetNames.size() != 1) return 0;   // one target: a selector means one method
		ClassNode target = targets.apply(targetNames.getFirst());
		if (target == null || target.methods == null) return 0;
		int moved = 0;
		for (MethodNode handler : new ArrayList<>(mixin.methods)) {
			if (handler.name.endsWith(MixinHandlerShim.INNER_SUFFIX)) continue;
			MethodNode outer = move(mixin, handler, target);
			if (outer == null) continue;
			if (outer != handler) mixin.methods.add(outer);
			moved++;
		}
		return moved;
	}

	/**
	 * The body a Fabric mod's injector bound to a carrier stub will move to, or null when it will not move: every check
	 * {@link #adapt} makes, and nothing changed. MixinFit asks this so its verdict and the rebind cannot disagree.
	 */
	public static MethodNode destination(String mixinInternalName, MethodNode handler, ClassNode target) {
		if (!enabled() || handler == null || target == null || target.methods == null) return null;
		if (ECOSYSTEMS.get(mixinInternalName) != Ecosystem.FABRIC) return null;
		Plan plan = plan(handler, target);
		return plan == null ? null : plan.delegation().delegate();
	}

	/** Whether {@code method} of {@code target} heads a row of carrier-stubs.txt — cheap, for callers deciding whether to look closer. */
	public static boolean isCarrierStub(ClassNode target, MethodNode method) {
		if (target == null || method == null) return false;
		String head = target.name + "#" + method.name + method.desc + " -> ";
		for (String row : carrierStubs()) if (row.startsWith(head)) return true;
		return false;
	}

	/** What one move needs: the injector, the stub it is bound to, where that forwards, and whether it captures the stub's arguments. */
	private record Plan(AnnotationNode injector, MethodNode stub, Delegation delegation, boolean captures) {
	}

	/** The handler to carry the injector after the move (the same one, or a new outer), or null when nothing moves. */
	private static MethodNode move(ClassNode mixin, MethodNode handler, ClassNode target) {
		Plan plan = plan(handler, target);
		if (plan == null) return null;
		AnnotationNode injector = plan.injector();
		MethodNode stub = plan.stub();
		Delegation delegation = plan.delegation();
		MethodNode delegate = delegation.delegate();
		Type[] stubParams = Type.getArgumentTypes(stub.desc);
		boolean captures = plan.captures();
		String selector = delegate.name + delegate.desc;
		MethodNode carrier = handler;
		if (captures) {
			carrier = shim(mixin, handler, injector, delegate, delegation.positions(), stubParams.length);
		}
		for (int i = 0; i + 1 < injector.values.size(); i += 2) {
			if ("method".equals(injector.values.get(i))) injector.values.set(i + 1, new ArrayList<>(List.of(selector)));
		}
		ForbricLog.info("[Forbric/Mixin] %s: %s now targets %s.%s%s — Mixin bound its selector to the merge-added stub %s, "
				+ "which only forwards to it%s", mixin.name.replace('/', '.'), handler.name, target.name.replace('/', '.'),
				delegate.name, delegate.desc, stub.desc, captures ? "; the handler still receives the stub's arguments" : "");
		return carrier;
	}

	private static Plan plan(MethodNode handler, ClassNode target) {
		List<AnnotationNode> annotations = new ArrayList<>();
		if (handler.visibleAnnotations != null) annotations.addAll(handler.visibleAnnotations);
		if (handler.invisibleAnnotations != null) annotations.addAll(handler.invisibleAnnotations);
		if (annotations.stream().anyMatch(a -> GROUP.equals(a.desc))) return null;
		AnnotationNode injector = MixinFit.injectorOf(handler);
		if (injector == null) return null;
		boolean inject = INJECT.equals(injector.desc);
		boolean variable = MODIFY_VARIABLE.equals(injector.desc);
		if (variable && !modifyVariableEnabled()) return null;
		if (!inject && !variable && !MixinRetarget.AT_DRIVEN.contains(injector.desc)) return null;
		if (MixinFit.value(injector, "locals") != null || MixinFit.value(injector, "slice") != null) return null;
		List<String> selectors = MixinFit.stringList(MixinFit.value(injector, "method"));
		if (selectors.size() != 1) return null;
		MethodNode stub = bound(target, selectors.getFirst());
		if (stub == null) return null;
		Delegation delegation = delegation(target, stub);
		if (delegation == null) return null;
		MethodNode delegate = delegation.delegate();
		// Only where the carrier added the overload: when vanilla has both, a Fabric mod that chose the short one meant it.
		if (!carrierStubs().contains(target.name + "#" + stub.name + stub.desc + " -> " + delegate.desc)) return null;

		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.isEmpty()) return null;
		if (variable) {
			if (!namedLocalMoved(injector, handler, stub, delegate)) return null;
		} else {
			for (AnnotationNode at : points) {
				String value = MixinFit.asString(MixinFit.value(at, "value"));
				if (EDGE_POINTS.contains(value)) continue;
				String member = MixinFit.asString(MixinFit.value(at, "target"));
				if (!CALL_POINTS.contains(value) || member == null) return null;
				if (MixinFit.containsMember(stub, member) || !MixinFit.containsMember(delegate, member)) return null;
			}
		}

		Type[] params = Type.getArgumentTypes(handler.desc);
		Type[] stubParams = Type.getArgumentTypes(stub.desc);
		// Where the handler's call-shaped part ends and its trailing (sugar) parameters begin.
		int plain;
		if (inject) {
			int callback = -1;
			for (int i = 0; i < params.length; i++) {
				String d = params[i].getDescriptor();
				if (CALLBACK_INFO.equals(d) || CALLBACK_INFO_RETURNABLE.equals(d)) { callback = i; break; }
			}
			if (callback != 0 && callback != stubParams.length) return null;
			if (callback == stubParams.length && !Arrays.equals(Arrays.copyOf(params, callback), stubParams)) return null;
			plain = callback + 1;
		} else {
			plain = params.length;
			for (int i = 0; i < params.length; i++) if (annotated(handler, i)) { plain = i; break; }
			// Past the injector's own contract, un-annotated parameters are captures of the target's arguments: they
			// must still be the delegate's, in the same places, carrying what the stub was handed.
			int own = capturesGuarded() ? intrinsicArity(injector, params, plain, delegate) : plain;
			if (own < 0 || own > plain || !capturesSurvive(injector, params, own, plain, stub, delegation)) return null;
		}
		for (int i = 0; i < plain; i++) if (annotated(handler, i)) return null;
		for (int i = plain; i < params.length; i++) {
			AnnotationNode local = local(handler, i);
			if (local == null) return null;   // a trailing capture of the stub's arguments, or @Share
			List<String> names = MixinFit.stringList(MixinFit.value(local, "name"));
			if (names.size() != 1 || MixinFit.value(local, "argsOnly") != null || !hasLocal(delegate, names.getFirst(), params[i])) return null;
		}

		boolean captures = inject && plain - 1 == stubParams.length && stubParams.length > 0;
		if (captures) {
			for (int i = 0; i < stubParams.length; i++) if (delegation.positions()[i] < 0) return null;
		}
		return new Plan(injector, stub, delegation, captures);
	}

	/** The outer handler for an {@code @Inject} that captured the stub's arguments: delegate parameters in, the original called. */
	private static MethodNode shim(ClassNode mixin, MethodNode handler, AnnotationNode injector, MethodNode delegate, int[] positions, int stubCount) {
		boolean handlerStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		Type[] params = Type.getArgumentTypes(handler.desc);
		Type[] delegateParams = Type.getArgumentTypes(delegate.desc);
		List<Type> outerParams = new ArrayList<>(Arrays.asList(delegateParams));
		outerParams.addAll(Arrays.asList(params).subList(stubCount, params.length));   // callback and sugar
		MethodNode outer = new MethodNode(Opcodes.ASM9, handler.access, handler.name,
				Type.getMethodDescriptor(Type.getReturnType(handler.desc), outerParams.toArray(Type[]::new)), null, null);
		outer.visibleAnnotations = handler.visibleAnnotations == null ? null : new ArrayList<>(handler.visibleAnnotations);
		outer.invisibleAnnotations = handler.invisibleAnnotations == null ? null : new ArrayList<>(handler.invisibleAnnotations);
		int shift = delegateParams.length - stubCount;
		outer.visibleParameterAnnotations = shifted(handler.visibleParameterAnnotations, stubCount, shift, outerParams.size());
		outer.invisibleParameterAnnotations = shifted(handler.invisibleParameterAnnotations, stubCount, shift, outerParams.size());
		int[] slots = new int[outerParams.size()];
		int slot = handlerStatic ? 0 : 1;
		for (int i = 0; i < outerParams.size(); i++) { slots[i] = slot; slot += outerParams.get(i).getSize(); }
		if (!handlerStatic) outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		for (int i = 0; i < stubCount; i++) {
			outer.instructions.add(new VarInsnNode(outerParams.get(positions[i]).getOpcode(Opcodes.ILOAD), slots[positions[i]]));
		}
		for (int i = delegateParams.length; i < outerParams.size(); i++) {
			outer.instructions.add(new VarInsnNode(outerParams.get(i).getOpcode(Opcodes.ILOAD), slots[i]));
		}
		outer.instructions.add(MixinHandlerShim.callOwn(mixin, handlerStatic, handler.name + MixinHandlerShim.INNER_SUFFIX,
				handler.desc));
		outer.instructions.add(new InsnNode(Type.getReturnType(handler.desc).getOpcode(Opcodes.IRETURN)));
		outer.maxLocals = slot;
		outer.maxStack = slot + 2;
		handler.name = handler.name + MixinHandlerShim.INNER_SUFFIX;
		handler.visibleAnnotations = without(handler.visibleAnnotations, injector.desc);
		handler.invisibleAnnotations = without(handler.invisibleAnnotations, injector.desc);
		handler.visibleParameterAnnotations = null;
		handler.invisibleParameterAnnotations = null;
		return outer;
	}

	/**
	 * How many of an {@code @At}-driven handler's leading parameters its injector's own contract fills, before any
	 * capture of the target method's arguments; -1 when that cannot be told. One for the value kinds; for
	 * {@code @ModifyArg} one, or all of the call's arguments (this Mixin lets it capture nothing); for the call-shaped
	 * kinds the receiver (when the access has one) and arguments of the access the {@code @At} names in {@code body},
	 * plus the {@code Operation} of a {@code @WrapOperation}. {@code plain} is where the handler's annotated (sugar)
	 * parameters begin.
	 */
	static int intrinsicArity(AnnotationNode injector, Type[] params, int plain, MethodNode body) {
		if (injector == null) return -1;
		if (ONE_VALUE.contains(injector.desc)) return plain >= 1 ? 1 : -1;
		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.size() != 1) return -1;
		AnnotationNode at = points.getFirst();
		String value = MixinFit.asString(MixinFit.value(at, "value"));
		String target = MixinFit.asString(MixinFit.value(at, "target"));
		if (MODIFY_ARG.equals(injector.desc)) {
			if (plain == 1) return 1;
			MixinFit.Member member = target == null ? null : MixinFit.parseMember(target);
			if (member == null || member.desc() == null || !member.desc().startsWith("(")) return -1;
			Type[] call = Type.getArgumentTypes(member.desc());
			return call.length == plain && Arrays.equals(call, Arrays.copyOf(params, plain)) ? plain : -1;
		}
		if (!CALL_SHAPED.contains(injector.desc) || target == null || value == null) return -1;
		int own = accessShape(value, target, at, body);
		if (own < 0) return -1;
		if (WRAP_OPERATION.equals(injector.desc)) {
			if (own >= plain || !OPERATION.equals(params[own].getDescriptor())) return -1;
			own++;
		}
		return own;
	}

	/** Receiver plus arguments of the access {@code target} names in {@code body}; -1 when absent or not one shape. */
	private static int accessShape(String value, String target, AnnotationNode at, MethodNode body) {
		if (body == null || body.instructions == null) return -1;
		if ("NEW".equals(value)) {
			if (!target.startsWith("(")) return -1;   // a class-name NEW: which constructor is not written down
			try {
				return Type.getArgumentTypes(target).length;
			} catch (RuntimeException malformed) {
				return -1;
			}
		}
		MixinFit.Member member = MixinFit.parseMember(target);
		if (member == null) return -1;
		boolean field = "FIELD".equals(value);
		if (field && MixinFit.value(at, "args") != null) return -1;   // array element access: another handler shape
		if (!field && !CALL_POINTS.contains(value)) return -1;
		int shape = -1;
		for (AbstractInsnNode insn : body.instructions) {
			int one;
			if (!field && insn instanceof MethodInsnNode call) {
				if (member.desc() == null || !call.name.equals(member.name()) || !call.desc.equals(member.desc())
						|| member.owner() != null && !call.owner.equals(member.owner())) continue;
				one = (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1) + Type.getArgumentTypes(call.desc).length;
			} else if (field && insn instanceof FieldInsnNode access) {
				if (!access.name.equals(member.name()) || member.desc() != null && !access.desc.equals(member.desc())
						|| member.owner() != null && !access.owner.equals(member.owner())) continue;
				one = switch (access.getOpcode()) {
					case Opcodes.GETSTATIC -> 0;
					case Opcodes.GETFIELD, Opcodes.PUTSTATIC -> 1;
					default -> 2;   // PUTFIELD: the receiver and the value
				};
			} else {
				continue;
			}
			if (shape >= 0 && shape != one) return -1;   // a read and a write of one field: no single handler shape
			shape = one;
		}
		return shape;
	}

	/**
	 * Whether the handler's parameters {@code own..plain} — captures of the target's leading arguments — bind to the
	 * same values on the delegate: each is the stub's argument at that position, the stub passes it through as the
	 * delegate's argument at the SAME position, and the delegate declares the same type there. {@code @ModifyArgs}
	 * takes all of the target's arguments or none, and the move changes how many there are.
	 */
	static boolean capturesSurvive(AnnotationNode injector, Type[] params, int own, int plain, MethodNode stub, Delegation delegation) {
		int captured = plain - own;
		if (captured == 0) return true;
		if (captured < 0 || MODIFY_ARGS.equals(injector.desc) || delegation == null) return false;
		Type[] stubParams = Type.getArgumentTypes(stub.desc);
		Type[] delegateParams = Type.getArgumentTypes(delegation.delegate().desc);
		if (captured > stubParams.length || captured > delegateParams.length) return false;
		for (int i = 0; i < captured; i++) {
			if (!params[own + i].equals(stubParams[i]) || delegation.positions()[i] != i || !delegateParams[i].equals(stubParams[i])) return false;
		}
		return true;
	}

	/**
	 * Whether a {@code @ModifyVariable}'s one named local is in the delegate and not the stub, provably, and reached by
	 * its {@code @At}: one name and no {@code ordinal}/{@code index}/{@code argsOnly} discriminator; the stub declares
	 * no local of that name; every entry of that name in the delegate's table is one slot of the handler's type; and
	 * the delegate loads or stores that slot, while the name is live, more times than the {@code @At}'s ordinal.
	 */
	private static boolean namedLocalMoved(AnnotationNode injector, MethodNode handler, MethodNode stub, MethodNode delegate) {
		List<String> names = MixinFit.stringList(MixinFit.value(injector, "name"));
		if (names.size() != 1 || MixinFit.value(injector, "index") != null || MixinFit.value(injector, "ordinal") != null
				|| Boolean.TRUE.equals(MixinFit.value(injector, "argsOnly"))) return false;
		Type[] params = Type.getArgumentTypes(handler.desc);
		if (params.length == 0 || !params[0].equals(Type.getReturnType(handler.desc))) return false;
		String name = names.getFirst();
		if (stub.localVariables != null && stub.localVariables.stream().anyMatch(local -> local.name.equals(name))) return false;
		if (delegate.localVariables == null) return false;
		List<LocalVariableNode> entries = delegate.localVariables.stream().filter(local -> local.name.equals(name)).toList();
		if (entries.isEmpty()) return false;
		int slot = entries.getFirst().index;
		for (LocalVariableNode entry : entries) if (entry.index != slot || !entry.desc.equals(params[0].getDescriptor())) return false;

		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.size() != 1) return false;
		AnnotationNode at = points.getFirst();
		String value = MixinFit.asString(MixinFit.value(at, "value"));
		if (!LOCAL_POINTS.contains(value) || MixinFit.value(at, "slice") != null || MixinFit.value(at, "shift") != null
				|| MixinFit.value(at, "target") != null) return false;
		Object ordinal = MixinFit.value(at, "ordinal");
		if (ordinal != null && !(ordinal instanceof Integer)) return false;
		boolean store = "STORE".equals(value);
		int opcode = params[0].getOpcode(store ? Opcodes.ISTORE : Opcodes.ILOAD);
		int accesses = 0;
		for (AbstractInsnNode insn : delegate.instructions) {
			if (!(insn instanceof VarInsnNode access) || access.getOpcode() != opcode || access.var != slot) continue;
			// A store starts the variable: the name is live from the next instruction, not at the store itself.
			AbstractInsnNode live = insn;
			if (store) {
				live = insn.getNext();
				while (live != null && live.getOpcode() < 0) live = live.getNext();
			}
			if (live == null) continue;
			int where = delegate.instructions.indexOf(live);
			for (LocalVariableNode entry : entries) {
				if (delegate.instructions.indexOf(entry.start) <= where && where < delegate.instructions.indexOf(entry.end)) {
					accesses++;
					break;
				}
			}
		}
		int wanted = ordinal instanceof Integer n ? n : -1;
		return wanted < 0 ? accesses > 0 : accesses > wanted;
	}

	static Set<String> carrierStubs() {
		Set<String> rows = carrierStubs;
		if (rows != null) return rows;
		Set<String> loaded = new java.util.HashSet<>();
		try (java.io.InputStream in = MixinStubRebind.class.getResourceAsStream(TABLE)) {
			if (in != null) {
				for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
					if (!line.isBlank() && !line.startsWith("#")) loaded.add(line.trim());
				}
			}
		} catch (java.io.IOException unreadable) {
			ForbricLog.warn("[Forbric/Mixin] could not read %s; no injector moves off a stub", TABLE);
		}
		carrierStubs = Set.copyOf(loaded);
		return carrierStubs;
	}

	/** The target method Mixin binds {@code selector} to: the first declared of that name, or the one with that descriptor. */
	static MethodNode bound(ClassNode target, String selector) {
		String s = selector.trim();
		if (s.indexOf('*') >= 0 || s.startsWith("/") || s.indexOf(' ') >= 0 || s.indexOf('=') >= 0) return null;
		int semi = s.indexOf(';');
		if (s.startsWith("L") && semi > 0) s = s.substring(semi + 1);
		int paren = s.indexOf('(');
		String name = paren < 0 ? s : s.substring(0, paren), desc = paren < 0 ? null : s.substring(paren);
		for (MethodNode m : target.methods) if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return m;
		return null;
	}

	/** A stub's delegate, and for each stub parameter the delegate position it reaches unchanged (-1: not directly). */
	record Delegation(MethodNode delegate, int[] positions) {
	}

	/**
	 * The one same-name overload {@code stub} forwards to, with the argument mapping, when {@code stub} is nothing else:
	 * loads, constants, static fields, zero-argument static factories and argument construction feeding one call,
	 * whose result is returned unchanged. Null otherwise.
	 */
	static Delegation delegation(ClassNode owner, MethodNode stub) {
		if (stub.instructions == null || stub.instructions.size() == 0) return null;
		if ((stub.access & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) != 0) return null;   // a compiler's bridge, not a carrier's stub
		boolean isStatic = (stub.access & Opcodes.ACC_STATIC) != 0;
		Type[] stubParams = Type.getArgumentTypes(stub.desc);
		int[] paramBySlot = new int[256];
		Arrays.fill(paramBySlot, -1);
		int slot = isStatic ? 0 : 1;
		for (int i = 0; i < stubParams.length; i++) { if (slot < 256) paramBySlot[slot] = i; slot += stubParams[i].getSize(); }
		List<Integer> stack = new ArrayList<>();   // each entry: the stub parameter it is, -1 synthesized, -2 this
		MethodInsnNode call = null;
		MethodNode found = null;
		int[] mapping = null;
		AbstractInsnNode after = null;
		for (AbstractInsnNode insn = stub.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			int op = insn.getOpcode();
			if (op < 0) continue;
			if (call != null) { after = insn; break; }
			if (insn instanceof VarInsnNode load && op >= Opcodes.ILOAD && op <= Opcodes.ALOAD) {
				stack.add(load.var < 256 ? paramBySlot[load.var] : -1);
				if (!isStatic && load.var == 0) stack.set(stack.size() - 1, -2);
			} else if (insn instanceof LdcInsnNode || (op >= Opcodes.ACONST_NULL && op <= Opcodes.DCONST_1) || op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) {
				stack.add(-1);
			} else if (insn instanceof FieldInsnNode get && op == Opcodes.GETSTATIC) {
				stack.add(-1);
			} else if (insn instanceof TypeInsnNode type && op == Opcodes.NEW) {
				stack.add(-1);
			} else if (insn instanceof InvokeDynamicInsnNode indy && Type.getArgumentTypes(indy.desc).length == 0
					&& "java/lang/invoke/LambdaMetafactory".equals(indy.bsm.getOwner())
					&& ("metafactory".equals(indy.bsm.getName()) || "altMetafactory".equals(indy.bsm.getName()))) {
				stack.add(-1);   // a non-capturing lambda or method reference: a constant
			} else if (op == Opcodes.DUP) {
				if (stack.isEmpty()) return null;
				stack.add(stack.getLast());
			} else if (op == Opcodes.CHECKCAST) {
				if (stack.isEmpty()) return null;
				stack.set(stack.size() - 1, -1);
			} else if (insn instanceof MethodInsnNode m) {
				Type[] args = Type.getArgumentTypes(m.desc);
				if (m.name.equals("<init>") && op == Opcodes.INVOKESPECIAL) {
					if (stack.size() < args.length + 1) return null;
					for (int k = 0; k <= args.length; k++) stack.removeLast();   // the args and the dup'd instance
					continue;
				}
				if (op == Opcodes.INVOKESTATIC && args.length == 0 && !(m.owner.equals(owner.name) && m.name.equals(stub.name))) {
					stack.add(-1);
					continue;
				}
				boolean delegationCall = m.owner.equals(owner.name) && m.name.equals(stub.name) && !m.desc.equals(stub.desc)
						&& (op == Opcodes.INVOKESTATIC) == isStatic;
				if (!delegationCall) return null;
				int receiver = isStatic ? 0 : 1;
				if (stack.size() != args.length + receiver) return null;
				if (!isStatic && stack.getFirst() != -2) return null;
				int[] positions = new int[stubParams.length];
				Arrays.fill(positions, -1);
				for (int j = 0; j < args.length; j++) {
					int source = stack.get(receiver + j);
					if (source >= 0) {
						if (positions[source] >= 0) return null;   // one stub argument passed twice: ambiguous
						positions[source] = j;
					}
				}
				call = m;
				stack.clear();
				MethodNode delegate = null;
				for (MethodNode candidate : owner.methods) if (candidate.name.equals(m.name) && candidate.desc.equals(m.desc)) delegate = candidate;
				if (delegate == null || (delegate.access & Opcodes.ACC_STATIC) != (stub.access & Opcodes.ACC_STATIC)) return null;
				if ((delegate.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || delegate.instructions == null
						|| delegate.instructions.size() == 0) return null;   // nothing there to inject into
				if (!Type.getReturnType(delegate.desc).equals(Type.getReturnType(stub.desc))) return null;
				mapping = positions;
				found = delegate;
			} else {
				return null;
			}
		}
		if (call == null || after == null || after.getOpcode() < Opcodes.IRETURN || after.getOpcode() > Opcodes.RETURN) return null;
		for (AbstractInsnNode insn = after.getNext(); insn != null; insn = insn.getNext()) if (insn.getOpcode() >= 0) return null;
		return new Delegation(found, mapping);
	}

	private static boolean hasLocal(MethodNode method, String name, Type type) {
		if (method.localVariables == null) return false;
		for (LocalVariableNode local : method.localVariables) if (local.name.equals(name) && local.desc.equals(type.getDescriptor())) return true;
		return false;
	}

	private static AnnotationNode local(MethodNode handler, int parameter) {
		for (List<AnnotationNode>[] all : List.of(nonNull(handler.visibleParameterAnnotations), nonNull(handler.invisibleParameterAnnotations))) {
			if (parameter < all.length && all[parameter] != null) for (AnnotationNode a : all[parameter]) if (LOCAL.equals(a.desc)) return a;
		}
		return null;
	}

	static boolean annotated(MethodNode handler, int parameter) {
		for (List<AnnotationNode>[] all : List.of(nonNull(handler.visibleParameterAnnotations), nonNull(handler.invisibleParameterAnnotations))) {
			if (parameter < all.length && all[parameter] != null && !all[parameter].isEmpty()) return true;
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	private static List<AnnotationNode>[] nonNull(List<AnnotationNode>[] annotations) {
		return annotations == null ? new List[0] : annotations;
	}

	@SuppressWarnings("unchecked")
	private static List<AnnotationNode>[] shifted(List<AnnotationNode>[] original, int from, int shift, int size) {
		if (original == null) return null;
		List<AnnotationNode>[] moved = new List[size];
		for (int i = from; i < original.length; i++) if (original[i] != null && i + shift < size) moved[i + shift] = new ArrayList<>(original[i]);
		return moved;
	}

	private static List<AnnotationNode> without(List<AnnotationNode> annotations, String desc) {
		if (annotations == null) return null;
		List<AnnotationNode> kept = new ArrayList<>();
		for (AnnotationNode a : annotations) if (!desc.equals(a.desc)) kept.add(a);
		return kept;
	}
}
