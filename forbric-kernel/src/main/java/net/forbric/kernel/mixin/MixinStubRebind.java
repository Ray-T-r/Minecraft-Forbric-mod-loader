/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
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

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Moves a mod's injector off a merge-added delegating stub onto the method that carries the body, when the mod was
 * compiled against a class where that signature was the body.
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
 *   <li>only for a mod whose own platform ran that selector on code. A Fabric mod was compiled against vanilla, so
 *       it moves along every row. A MinecraftForge or NeoForge mod moves only where its carrier's own patched class
 *       has vanilla's signature as the body (or, for a name-only selector, only the widened overload) — the row's
 *       {@code forge=}/{@code neo=} column. Most rows are NeoForge's stubs over a signature MinecraftForge kept as the
 *       body: fusion's sprite capture on {@code ModelManager.loadModels} sat on the forwarding stub nothing calls,
 *       its static stayed null, and every block and item model on the client failed to bake. A mod whose carrier
 *       keeps the same stub itself (NeoForge mods on NeoForge's stubs) gets what it would get natively.
 *       {@code -Dforbric.mixinStubRebind.forgeFamily=off} moves Fabric mods' injectors only, as before;</li>
 *   <li>only a PURE stub: loads, constants, static fields, zero-argument static factories, non-capturing lambdas and
 *       method references (a constant, like a static field — NeoForge's {@code Language.loadFromJson(InputStream,
 *       BiConsumer)} passes a no-op component consumer) and argument construction, then one call to a same-name
 *       overload of the same class and static-ness, returning its result unchanged; the overload must have a body
 *       (an interface default forwarding to an abstract overload is no stub);</li>
 *   <li>every {@code INVOKE}/{@code FIELD}/{@code NEW} anchor absent from the stub and present in the delegate
 *       ({@code HEAD}, {@code RETURN} and {@code TAIL} are equivalent on both: the stub returns what the delegate
 *       returns);</li>
 *   <li>{@code @Inject} capturing nothing or exactly the stub's arguments, no locals capture; a MixinExtras
 *       {@code @Local} by a name the delegate's local variable table has in that type, or by its type alone (no name,
 *       ordinal, index or argsOnly) when, at every {@code INVOKE}/{@code FIELD} anchor in the delegate, exactly one
 *       local slot is of that type as Mixin's own walk down the method types it, the table names it there and that
 *       walk still holds it — MixinExtras then picks that one and nothing else. fusion's overlay-model hook takes the {@code ModelDiscovery} of
 *       {@code ModelManager.discoverModelDependencies} that way: the body is NeoForge's four-argument overload, where
 *       {@code result} is the only one ({@code -Dforbric.mixinStubRebind.typedLocal=off} leaves these where they
 *       are); an argsOnly {@code @Local} (by type, or type and ordinal) when the stub argument it picks is passed
 *       through as the delegate argument the same rule picks; a {@code @Share} in the mixin's own namespace only when
 *       every handler sharing that key on the stub moves to the same body, since MixinExtras allocates one value per
 *       target method. owo's lang hooks (a de-nesting wrap on {@code JsonObject.entrySet}, a rich-text wrap on
 *       {@code GsonHelper.convertToString} and a skip on {@code BiConsumer.accept}) pass three flags between them that
 *       way on {@code Language.loadFromJson}, whose body is NeoForge's three-argument overload; left on the stub,
 *       none of them attached and NeoForge rejected owo's nested keys, dropping every owo-based mod's whole lang file
 *       ({@code -Dforbric.mixinStubRebind.shared=off} leaves these where they are). No {@code @Group}, and never more
 *       anchors in the body than the injector's {@code allow};</li>
 *   <li>the {@code @At}-driven kinds only when every parameter past the injector's own contract — the value it
 *       modifies, or the receiver and arguments of the call it replaces or wraps — is a capture of the stub's LEADING
 *       arguments that the stub passes to the delegate at the same positions. Mixin lets any of these kinds take a
 *       prefix of the target's arguments after its own, and the rule used to read every one of them as part of the
 *       call: torrential's {@code @ModifyReturnValue} on {@code FuelValues.vanillaBurnTimes(Provider, FeatureFlagSet,
 *       int)} captures all three, the stub feeds the first two into a {@code Builder}, and moving it to
 *       {@code (Builder, int)} made MixinExtras reject the handler and the whole required mixin with it. It now
 *       stays on the stub, where it binds, and is reported SUSPECTED: it then runs only where something calls the
 *       stub, and the dedicated server never does ({@code -Dforbric.mixinStubRebind.stubFinding=off} drops the
 *       finding); puzzleslib's {@code getDestroySpeed(float, BlockState)} still moves,
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
	/**
	 * {@code -Dforbric.mixinStubRebind.sugarBoundary=off}: any parameter annotation ends a handler's call part (here and
	 * in MixinRetarget's R1), and one in that part keeps the injector on the stub, as before.
	 */
	public static final String SUGAR_BOUNDARY_PROPERTY = "forbric.mixinStubRebind.sugarBoundary";
	/** {@code -Dforbric.mixinStubRebind.stubFinding=off}: a handler its captures keep on a stub is not reported. */
	public static final String STUB_FINDING_PROPERTY = "forbric.mixinStubRebind.stubFinding";
	/** {@code -Dforbric.mixinStubRebind.forgeFamily=off}: only Fabric mods' injectors move, as before the carrier columns. */
	public static final String FORGE_FAMILY_PROPERTY = "forbric.mixinStubRebind.forgeFamily";
	/** {@code -Dforbric.mixinStubRebind.typedLocal=off}: a handler with a by-type-only {@code @Local} stays on the stub. */
	public static final String TYPED_LOCAL_PROPERTY = "forbric.mixinStubRebind.typedLocal";
	/** {@code -Dforbric.mixinStubRebind.shared=off}: a handler with a {@code @Share} or an argsOnly {@code @Local} stays on the stub. */
	public static final String SHARED_PROPERTY = "forbric.mixinStubRebind.shared";
	/** {@code -Dforbric.mixinStubRebind.allow=off}: an injector moves whatever its {@code allow} says, as before (A/B only). */
	public static final String ALLOW_PROPERTY = "forbric.mixinStubRebind.allow";

	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String CALLBACK_INFO = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	private static final String CALLBACK_INFO_RETURNABLE = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
	private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
	private static final String SHARE = "Lcom/llamalad7/mixinextras/sugar/Share;";
	private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
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
			REDIRECT,
			"Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
			"Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
			WRAP_OPERATION);
	private static final Set<String> LOCAL_POINTS = Set.of("LOAD", "STORE");
	private static final Set<String> CALL_POINTS = Set.of("INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING", "FIELD", "NEW");
	private static final Set<String> EDGE_POINTS = Set.of("HEAD", "RETURN", "TAIL");

	/** The shipped table of carrier-added stubs; CarrierStubCensusTest pins it to the staged artifacts. */
	static final String TABLE = "/net/forbric/kernel/mixin/carrier-stubs.txt";
	/** {@code owner#stubNameDesc -> delegateDesc} → what each Forge family was compiled against there. */
	private static volatile Map<String, Row> carrierStubs;

	/** Mixin class (internal name) → the ecosystem of the mod whose config declares it; filled as configs are read. */
	private static final Map<String, Ecosystem> ECOSYSTEMS = new ConcurrentHashMap<>();
	/** Mixin class (internal name) → the config that declares it, so a finding can name the mod. */
	private static final Map<String, String> CONFIGS = new ConcurrentHashMap<>();

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

	static boolean forgeFamilyEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(FORGE_FAMILY_PROPERTY, "on"));
	}

	static boolean typedLocalEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(TYPED_LOCAL_PROPERTY, "on"));
	}

	static boolean sharedEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SHARED_PROPERTY, "on"));
	}

	/** Records which family's mod declared {@code mixinInternalName}; null when the config's owner is ambiguous. */
	public static void noteEcosystem(String mixinInternalName, Ecosystem ecosystem) {
		if (mixinInternalName != null && ecosystem != null) ECOSYSTEMS.put(mixinInternalName, ecosystem);
	}

	/** Which family's mod declared {@code mixinInternalName}; null when not recorded or the config's owner is ambiguous. */
	static Ecosystem ecosystemOf(String mixinInternalName) {
		return mixinInternalName == null ? null : ECOSYSTEMS.get(mixinInternalName);
	}

	/** {@link #noteEcosystem}, and the config that declared it, which is how a finding about it names the mod. */
	public static void noteEcosystem(String mixinInternalName, Ecosystem ecosystem, String configName) {
		noteEcosystem(mixinInternalName, ecosystem);
		if (mixinInternalName != null && configName != null) CONFIGS.put(mixinInternalName, configName);
	}

	/** Test seam. */
	static void forget() {
		ECOSYSTEMS.clear();
		CONFIGS.clear();
	}

	/** Moves every eligible injector of {@code mixin}; returns how many. {@code targets} must return nodes WITH code. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!enabled() || mixin == null || mixin.methods == null || targets == null) return 0;
		Ecosystem ecosystem = ECOSYSTEMS.get(mixin.name);
		if (ecosystem == null) return 0;   // no known owner: nothing says what it was compiled against
		List<String> targetNames = MixinOverloadPin.targetsOf(mixin);
		if (targetNames.size() != 1) return 0;   // one target: a selector means one method
		ClassNode target = targets.apply(targetNames.getFirst());
		if (target == null || target.methods == null) return 0;
		int moved = 0;
		for (Map.Entry<MethodNode, Plan> planned : plans(mixin, target, ecosystem,
				(handler, stub, delegate) -> staysOnStub(mixin, handler, target, stub, delegate)).entrySet()) {
			if (planned.getValue() == null) continue;
			MethodNode handler = planned.getKey();
			MethodNode outer = move(mixin, handler, target, ecosystem, planned.getValue());
			if (outer != handler) mixin.methods.add(outer);
			moved++;
		}
		return moved;
	}

	/**
	 * The body an injector of {@code mixin} bound to a carrier stub will move to, or null when it will not move: every
	 * check {@link #adapt} makes — the handler's own, and its {@code @Share} group's — and nothing changed. MixinFit asks
	 * this so its verdict and the rebind cannot disagree.
	 */
	public static MethodNode destination(ClassNode mixin, MethodNode handler, ClassNode target) {
		if (!enabled() || mixin == null || mixin.methods == null || handler == null || target == null || target.methods == null) return null;
		Ecosystem ecosystem = ECOSYSTEMS.get(mixin.name);
		if (ecosystem == null) return null;
		if (MixinOverloadPin.targetsOf(mixin).size() != 1) return null;   // adapt moves nothing in a mixin of several targets
		Plan plan = plans(mixin, target, ecosystem, null).get(handler);
		return plan == null ? null : plan.delegation().delegate();
	}

	/**
	 * Every handler's plan, with every {@code @Share} group that cannot move whole dropped. MixinExtras gives each key
	 * one value per target method (in the mixin's own namespace), so handlers sharing a key in the method Mixin bound them
	 * to must all land in the same body, or each would get a value of its own: owo's three lang hooks pass the "skip the
	 * next key" and "rich translations" flags between them that way.
	 *
	 * @param capturesLost told {@code (handler, stub, delegate)} for a handler that would have moved but for its trailing
	 *                     captures of the stub's arguments; {@code null} when the caller only wants the answer
	 */
	private static Map<MethodNode, Plan> plans(ClassNode mixin, ClassNode target, Ecosystem ecosystem,
			CapturesLost capturesLost) {
		Map<MethodNode, Plan> plans = new java.util.LinkedHashMap<>();
		for (MethodNode handler : new ArrayList<>(mixin.methods)) {
			if (handler.name.endsWith(MixinHandlerShim.INNER_SUFFIX)) continue;
			plans.put(handler, plan(handler, target, ecosystem, capturesLost == null ? null
					: (stub, delegate) -> capturesLost.accept(handler, stub, delegate)));
		}
		for (boolean dropped = true; dropped; ) {   // dropping one sharer can leave another group incomplete
			dropped = false;
			for (Map.Entry<MethodNode, Plan> entry : plans.entrySet()) {
				Plan plan = entry.getValue();
				if (plan == null || plan.shares().isEmpty()) continue;
				for (MethodNode other : plans.keySet()) {
					if (other == entry.getKey() || java.util.Collections.disjoint(shareKeys(other), plan.shares())) continue;
					if (!mayBind(other, target, plan.stub())) continue;   // a key is shared within one target method only
					Plan theirs = plans.get(other);
					if (theirs == null || theirs.delegation().delegate() != plan.delegation().delegate()) {
						entry.setValue(null);
						dropped = true;
						break;
					}
				}
			}
		}
		return plans;
	}

	/** The keys {@code handler} shares in its mixin's own namespace. */
	private static Set<String> shareKeys(MethodNode handler) {
		Set<String> keys = new java.util.HashSet<>();
		for (int i = 0; i < Type.getArgumentTypes(handler.desc).length; i++) {
			AnnotationNode share = sugar(handler, i, SHARE);
			if (share != null && MixinFit.value(share, "namespace") == null && MixinFit.value(share, "value") instanceof String key) keys.add(key);
		}
		return keys;
	}

	/**
	 * Whether {@code handler} may be injected into {@code method}: any selector of any annotation on it that names
	 * target methods — not only the injectors MixinFit reads; MixinExtras' {@code @ModifyReceiver} or a library's own
	 * injector shares values too — binds it, or cannot be read (a wildcard, a regex, a {@code @Desc} target). Left on
	 * the stub, such a sharer would keep a value of its own while the rest of its group moved.
	 */
	private static boolean mayBind(MethodNode handler, ClassNode target, MethodNode method) {
		List<AnnotationNode> annotations = new ArrayList<>();
		if (handler.visibleAnnotations != null) annotations.addAll(handler.visibleAnnotations);
		if (handler.invisibleAnnotations != null) annotations.addAll(handler.invisibleAnnotations);
		for (AnnotationNode annotation : annotations) {
			Object selectors = MixinFit.value(annotation, "method");
			if (selectors == null && MixinFit.value(annotation, "target") != null) return true;   // @Desc: not read here
			for (String selector : MixinFit.stringList(selectors)) {
				if (plainSelector(selector) == null || bound(target, selector) == method) return true;
			}
		}
		return false;
	}

	/** The owners (internal names) that head any row of carrier-stubs.txt. */
	private static volatile Set<String> stubOwners;

	/** Whether any row of carrier-stubs.txt is headed by a method of {@code owner}: most classes head none. */
	public static boolean ownsCarrierStub(String owner) {
		Set<String> owners = stubOwners;
		if (owners == null) {
			Set<String> collected = new java.util.HashSet<>();
			for (String row : carrierStubs().keySet()) collected.add(row.substring(0, row.indexOf('#')));
			stubOwners = owners = Set.copyOf(collected);
		}
		return owners.contains(owner);
	}

	/** Whether {@code method} of {@code target} heads a row of carrier-stubs.txt — cheap, for callers deciding whether to look closer. */
	public static boolean isCarrierStub(ClassNode target, MethodNode method) {
		if (target == null || method == null) return false;
		String head = target.name + "#" + method.name + method.desc + " -> ";
		for (String row : carrierStubs().keySet()) if (row.startsWith(head)) return true;
		return false;
	}

	/**
	 * Whether {@code method} of {@code target} heads a carrier-stubs.txt row where a mod of {@code ecosystem} was compiled
	 * against code, by either selector form: an injector of that mod attached only there runs just for callers of the
	 * old signature, which on the merged base are often none (FinalMixinApplications reports it).
	 */
	public static boolean isStubOverBody(ClassNode target, MethodNode method, Ecosystem ecosystem) {
		if (target == null || method == null || ecosystem == null) return false;
		String head = target.name + "#" + method.name + method.desc + " -> ";
		for (Map.Entry<String, Row> row : carrierStubs().entrySet()) {
			if (row.getKey().startsWith(head)) return row.getValue().ranOnCode(ecosystem);
		}
		return false;
	}

	/**
	 * What one move needs: the injector, the stub it is bound to, where that forwards, whether it captures the stub's
	 * arguments, and the {@code @Share} keys it holds in its mixin's namespace.
	 */
	private record Plan(AnnotationNode injector, MethodNode stub, Delegation delegation, boolean captures, List<String> shares) {
	}

	/** Told which handler its captures keep on which stub, and the body it would have moved to. */
	@FunctionalInterface
	private interface CapturesLost {
		void accept(MethodNode handler, MethodNode stub, MethodNode delegate);
	}

	/** The handler to carry the injector after {@code plan}'s move: the same one, or a new outer. */
	private static MethodNode move(ClassNode mixin, MethodNode handler, ClassNode target, Ecosystem ecosystem, Plan plan) {
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
				+ "which only forwards to it%s%s", mixin.name.replace('/', '.'), handler.name, target.name.replace('/', '.'),
				delegate.name, delegate.desc, stub.desc, captures ? "; the handler still receives the stub's arguments" : "",
				ecosystem == Ecosystem.FABRIC ? "" : "; a " + ecosystem.displayName() + " mod, and " + ecosystem.displayName()
						+ "'s own class runs that selector on the body");
		return carrier;
	}

	/**
	 * @param capturesLost told {@code (stub, delegate)} when the handler would have moved but for its trailing captures
	 *                     of the stub's arguments; {@code null} when the caller only wants the answer
	 */
	private static Plan plan(MethodNode handler, ClassNode target, Ecosystem ecosystem,
			BiConsumer<MethodNode, MethodNode> capturesLost) {
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
		Row row = carrierStubs().get(target.name + "#" + stub.name + stub.desc + " -> " + delegate.desc);
		// And only where the mod's own platform ran this selector on code: a NeoForge mod on NeoForge's stub was
		// compiled against the stub and gets exactly that.
		if (row == null || !row.moves(ecosystem, selectorDescriptor(selectors.getFirst()) == null)) return null;

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
		boolean lost = false;
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
			for (int i = 0; i < params.length; i++) if (trailingSugar(handler, i)) { plain = i; break; }
			// Past the injector's own contract, un-annotated parameters are captures of the target's arguments: they
			// must still be the delegate's, in the same places, carrying what the stub was handed.
			int own = capturesGuarded() ? intrinsicArity(injector, params, plain, delegate) : plain;
			if (own < 0 || own > plain) return null;
			// Decided last: everything below must also hold for "only its captures kept it here" to be true.
			lost = !capturesSurvive(injector, params, own, plain, stub, delegation);
		}
		for (int i = 0; i < plain; i++) if (trailingSugar(handler, i)) return null;
		List<String> shares = new ArrayList<>();
		for (int i = plain; i < params.length; i++) {
			AnnotationNode share = sugar(handler, i, SHARE);
			if (share != null) {
				// Only the default namespace, which is the mixin class itself: an explicit one can be shared with injectors
				// this cannot see. Whether every sharer moves too is decided over the whole mixin (plans).
				String key = MixinFit.asString(MixinFit.value(share, "value"));
				if (!sharedEnabled() || key == null || MixinFit.value(share, "namespace") != null) return null;
				// Not a @Redirect's: one that shares state with its siblings replaces the call as part of a takeover set up
				// elsewhere in the method, and on the merged body that call can be a carrier's own pipeline.
				// fabric-renderer-api's SectionCompilerMixin hands every block of a chunk section to the FRAPI renderer
				// that way, and on NeoForge's compile overload the call it replaces is NeoForge's per-block renderer.
				// Moving it would switch chunk meshing for every client without Sodium: a separate, measured decision.
				if (REDIRECT.equals(injector.desc)) return null;
				shares.add(key);
				continue;
			}
			AnnotationNode local = local(handler, i);
			if (local == null) return null;   // a trailing capture of the stub's arguments, or other sugar
			List<String> names = MixinFit.stringList(MixinFit.value(local, "name"));
			if (Boolean.TRUE.equals(MixinFit.value(local, "argsOnly"))) {
				if (!sharedEnabled() || !names.isEmpty() || MixinFit.value(local, "index") != null
						|| !argumentSurvives(params[i], MixinFit.value(local, "ordinal"), stub, delegation)) return null;
				continue;
			}
			if (names.isEmpty() && byTypeOnly(local)) {
				if (!typedLocalEnabled() || !theOnlyLocalOfItsType(target, delegate, params[i], points)) return null;
				continue;
			}
			if (names.size() != 1 || MixinFit.value(local, "argsOnly") != null || !hasLocal(delegate, names.getFirst(), params[i])) return null;
		}
		if (!withinAllow(injector, handler, points, delegate)) return null;
		if (lost) {
			if (capturesLost != null) capturesLost.accept(stub, delegate);
			return null;
		}

		boolean captures = inject && plain - 1 == stubParams.length && stubParams.length > 0;
		if (captures) {
			for (int i = 0; i < stubParams.length; i++) if (delegation.positions()[i] < 0) return null;
		}
		return new Plan(injector, stub, delegation, captures, List.copyOf(shares));
	}

	/**
	 * Whether an {@code argsOnly} {@code @Local} of {@code type} — the {@code ordinal}-th argument of that type, or the
	 * only one — reads the same value on the delegate: the stub's argument it picks there is passed through unchanged as
	 * the argument the same rule picks on the delegate. owo's lang hook takes the {@code InputStream}, first on both.
	 */
	static boolean argumentSurvives(Type type, Object ordinal, MethodNode stub, Delegation delegation) {
		int from = pick(Type.getArgumentTypes(stub.desc), type, ordinal);
		int to = pick(Type.getArgumentTypes(delegation.delegate().desc), type, ordinal);
		return from >= 0 && to >= 0 && delegation.positions()[from] == to;
	}

	/** Which argument an argsOnly {@code @Local} of {@code type} picks: the {@code ordinal}-th of that type, or the only one; -1 otherwise. */
	private static int pick(Type[] arguments, Type type, Object ordinal) {
		List<Integer> ofType = new ArrayList<>();
		for (int i = 0; i < arguments.length; i++) if (arguments[i].equals(type)) ofType.add(i);
		if (ordinal == null) return ofType.size() == 1 ? ofType.getFirst() : -1;
		return ordinal instanceof Integer n && n >= 0 && n < ofType.size() ? ofType.get(n) : -1;
	}

	/**
	 * Whether the delegate stays within the injector's {@code allow}: the body can hold an anchor more often than
	 * vanilla's method did (NeoForge's {@code Language.loadFromJson} calls {@code BiConsumer.accept} three times where
	 * vanilla called it once), and past {@code allow} Mixin fails the injection outright. Each point is counted from
	 * above: a {@code LOAD}/{@code STORE} by every access of its named slot ({@link #localAccesses}), an
	 * {@code INVOKE_STRING} by every call of its member, whatever constant it is passed.
	 */
	private static boolean withinAllow(AnnotationNode injector, MethodNode handler, List<AnnotationNode> points, MethodNode delegate) {
		if ("off".equalsIgnoreCase(System.getProperty(ALLOW_PROPERTY, "on"))) return true;
		if (!(MixinFit.value(injector, "allow") instanceof Integer allow) || allow < 0) return true;
		int matches = 0;
		for (AnnotationNode at : points) {
			String value = MixinFit.asString(MixinFit.value(at, "value"));
			int found;
			if ("HEAD".equals(value) || "TAIL".equals(value)) {
				found = 1;
			} else if ("RETURN".equals(value)) {
				found = 0;
				for (AbstractInsnNode insn : delegate.instructions) {
					if (insn.getOpcode() >= Opcodes.IRETURN && insn.getOpcode() <= Opcodes.RETURN) found++;
				}
			} else if (LOCAL_POINTS.contains(value)) {
				found = localAccesses(injector, handler, delegate, "STORE".equals(value));
				if (found < 0) return false;
			} else {
				String point = "INVOKE_STRING".equals(value) ? "INVOKE" : value;
				List<AbstractInsnNode> hits = anchors(delegate, point, MixinFit.asString(MixinFit.value(at, "target")));
				// A point this cannot count: the bound cannot be shown to hold. (No NEW gets here: plan finds none in a body.)
				if (hits == null) return false;
				found = hits.size();
			}
			if (MixinFit.value(at, "ordinal") instanceof Integer ordinal) found = found > ordinal ? 1 : 0;
			matches += found;
		}
		return matches <= allow;
	}

	/**
	 * How often a {@code @ModifyVariable}'s one named local is loaded (or stored) in {@code body}: every access of the
	 * one slot the table gives that name, which is all its discriminator can match there; -1 when no one slot is named.
	 */
	private static int localAccesses(AnnotationNode injector, MethodNode handler, MethodNode body, boolean store) {
		List<String> names = MixinFit.stringList(MixinFit.value(injector, "name"));
		Type[] params = Type.getArgumentTypes(handler.desc);
		if (names.size() != 1 || params.length == 0 || body.localVariables == null || body.instructions == null) return -1;
		int slot = -1;
		for (LocalVariableNode local : body.localVariables) {
			if (!local.name.equals(names.getFirst())) continue;
			if (slot >= 0 && slot != local.index) return -1;
			slot = local.index;
		}
		if (slot < 0) return -1;
		int opcode = params[0].getOpcode(store ? Opcodes.ISTORE : Opcodes.ILOAD), found = 0;
		for (AbstractInsnNode insn : body.instructions) {
			if (insn instanceof VarInsnNode access && access.getOpcode() == opcode && access.var == slot) found++;
		}
		return found;
	}

	/** The instructions an {@code INVOKE}/{@code INVOKE_ASSIGN} or {@code FIELD} point names in {@code body}; null for other points. */
	private static List<AbstractInsnNode> anchors(MethodNode body, String value, String member) {
		boolean call = "INVOKE".equals(value) || "INVOKE_ASSIGN".equals(value);
		if (!call && !"FIELD".equals(value) || member == null || body.instructions == null) return null;
		MixinFit.Member want = MixinFit.parseMember(member);
		if (want == null) return null;
		List<AbstractInsnNode> found = new ArrayList<>();
		for (AbstractInsnNode insn : body.instructions) {
			String name, owner, desc;
			if (call && insn instanceof MethodInsnNode invoke) {
				name = invoke.name; owner = invoke.owner; desc = invoke.desc;
			} else if (!call && insn instanceof FieldInsnNode field) {
				name = field.name; owner = field.owner; desc = field.desc;
			} else {
				continue;
			}
			if (name.equals(want.name()) && (want.owner() == null || owner.equals(want.owner())) && (want.desc() == null || desc.equals(want.desc()))) {
				found.add(insn);
			}
		}
		return found;
	}

	/**
	 * A SUSPECTED finding for a handler that stays on a carrier-added stub only because the body does not receive the
	 * stub's arguments it captures.
	 *
	 * <p>It binds there -- that is why it stays -- but it now runs only where something calls the stub. The game's own
	 * code can call the body directly, and then the handler silently never runs: torrential's
	 * {@code FuelValuesMixin} stays on {@code FuelValues.vanillaBurnTimes(Provider, FeatureFlagSet, int)}; the client
	 * reaches it through {@code ClientPacketListener}, the dedicated server builds its fuel through NeoForge's
	 * {@code DataMapHooks.populateFuelValues} and never does. The Angling Table burns on the client and not on the
	 * server. The old outcome was a visible "did not finish loading"; this keeps the quiet one from being silent.
	 */
	private static void staysOnStub(ClassNode mixin, MethodNode handler, ClassNode target, MethodNode stub,
			MethodNode delegate) {
		if ("off".equalsIgnoreCase(System.getProperty(STUB_FINDING_PROPERTY, "on"))) return;
		String config = CONFIGS.get(mixin.name);
		String dotted = mixin.name.replace('/', '.');
		String owner = target.name.replace('/', '.');
		String detail = handler.name + " stays on " + owner + "." + stub.name + stub.desc + ", a stub the merged base "
				+ "added that only forwards to " + delegate.name + delegate.desc + ": the handler captures stub "
				+ "arguments the body is not handed, so it cannot move. It runs only where the game calls the stub; "
				+ "code that calls the body directly skips it (a side that never calls the stub never runs it)";
		ForbricLog.info("[Forbric/Mixin] %s: %s", dotted, detail);
		MixinCompatibility.recordAs("mixin-stub-bound:" + config + ":" + dotted + "#" + handler.name + handler.desc,
				config, dotted, detail, CompatibilityFinding.Confidence.SUSPECTED, false,
				List.of("stub=" + owner + "." + stub.name + stub.desc, "body=" + owner + "." + delegate.name + delegate.desc,
						"handler=" + handler.name + handler.desc));
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

	/**
	 * What a mod of one Forge family was compiled against where a row's stub now stands: how that carrier's OWN patched
	 * class binds the two selectors that land on the merged stub — vanilla's name alone, and vanilla's descriptor.
	 * CarrierStubCensusTest reads it off patched-mc-forge and patched-mc-neoforge with {@link #of}.
	 */
	enum Shape {
		/** Vanilla's signature is a body there, the first of its name: both selectors ran on code. */
		BODY("body", true, true),
		/** A body, but another overload of that name is declared before it: only the descriptor ran on code. */
		DESCRIPTOR_BODY("descriptor-body", false, true),
		/**
		 * Vanilla's signature is gone there and the widened overload, first of its name, is a body: only the name ran on
		 * code. MinecraftForge widened {@code ServerExplosion.hurtEntities} and kept no stub; NeoForge kept one.
		 */
		OVERLOAD_BODY("overload-body", true, false),
		/** The carrier keeps a forwarding stub there itself: the mod was compiled against the stub-first shape. */
		STUB("stub", false, false),
		/** Nothing there a selector of vanilla's could have landed on. */
		ABSENT("absent", false, false);

		final String token;
		private final boolean byName, byDescriptor;

		Shape(String token, boolean byName, boolean byDescriptor) {
			this.token = token;
			this.byName = byName;
			this.byDescriptor = byDescriptor;
		}

		/** Whether a selector of that form ran on code on this platform. */
		boolean ranOnCode(boolean byName) {
			return byName ? this.byName : byDescriptor;
		}

		static Shape parse(String token) {
			for (Shape shape : values()) if (shape.token.equals(token)) return shape;
			return null;
		}

		/** How {@code platform} (a carrier's own patched class, with code) holds vanilla's {@code name+stubDesc}. */
		static Shape of(ClassNode platform, String name, String stubDesc, String delegateDesc) {
			if (platform == null || platform.methods == null) return ABSENT;
			MethodNode first = null, stub = null, overload = null;
			for (MethodNode m : platform.methods) {
				if (!m.name.equals(name)) continue;
				if (first == null) first = m;
				if (m.desc.equals(stubDesc)) stub = m;
				if (m.desc.equals(delegateDesc)) overload = m;
			}
			if (stub != null) {
				if (stub.instructions == null || stub.instructions.size() == 0) return ABSENT;   // abstract: nothing ran there
				if (delegation(platform, stub) != null) return STUB;
				return first == stub ? BODY : DESCRIPTOR_BODY;
			}
			if (overload != null && first == overload && overload.instructions != null && overload.instructions.size() > 0) {
				return delegation(platform, overload) != null ? STUB : OVERLOAD_BODY;
			}
			return ABSENT;
		}
	}

	/** One row's columns: what a MinecraftForge and a NeoForge mod were compiled against at that stub. */
	record Row(Shape forge, Shape neo) {
		/** Whether a mod of {@code ecosystem} with a name-only ({@code byName}) or descriptor selector moves along this row. */
		boolean moves(Ecosystem ecosystem, boolean byName) {
			if (ecosystem == Ecosystem.FABRIC) return true;   // compiled against vanilla, where the row's signature is the body
			if (!forgeFamilyEnabled()) return false;
			Shape shape = ecosystem == Ecosystem.FORGE ? forge : ecosystem == Ecosystem.NEOFORGE ? neo : null;
			return shape != null && shape.ranOnCode(byName);
		}

		/** Whether a selector of either form ran on code on {@code ecosystem}'s own platform, whatever the switches say. */
		boolean ranOnCode(Ecosystem ecosystem) {
			if (ecosystem == Ecosystem.FABRIC) return true;
			Shape shape = ecosystem == Ecosystem.FORGE ? forge : ecosystem == Ecosystem.NEOFORGE ? neo : null;
			return shape != null && (shape.ranOnCode(true) || shape.ranOnCode(false));
		}
	}

	/** The rows by {@code owner#stubNameDesc -> delegateDesc}; a row without columns moves Fabric mods only. */
	static Map<String, Row> carrierStubs() {
		Map<String, Row> rows = carrierStubs;
		if (rows != null) return rows;
		Map<String, Row> loaded = new java.util.HashMap<>();
		try (java.io.InputStream in = MixinStubRebind.class.getResourceAsStream(TABLE)) {
			if (in != null) {
				for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
					if (line.isBlank() || line.startsWith("#")) continue;
					String[] parts = line.trim().split(" ");
					if (parts.length < 3 || !"->".equals(parts[1])) continue;
					Shape forge = Shape.STUB, neo = Shape.STUB;
					for (int i = 3; i < parts.length; i++) {
						if (parts[i].startsWith("forge=")) forge = java.util.Objects.requireNonNullElse(Shape.parse(parts[i].substring(6)), Shape.STUB);
						if (parts[i].startsWith("neo=")) neo = java.util.Objects.requireNonNullElse(Shape.parse(parts[i].substring(4)), Shape.STUB);
					}
					loaded.put(parts[0] + " -> " + parts[2], new Row(forge, neo));
				}
			}
		} catch (java.io.IOException unreadable) {
			ForbricLog.warn("[Forbric/Mixin] could not read %s; no injector moves off a stub", TABLE);
		}
		carrierStubs = Map.copyOf(loaded);
		return carrierStubs;
	}

	/** The target method Mixin binds {@code selector} to: the first declared of that name, or the one with that descriptor. */
	static MethodNode bound(ClassNode target, String selector) {
		String s = plainSelector(selector);
		if (s == null) return null;
		int paren = s.indexOf('(');
		String name = paren < 0 ? s : s.substring(0, paren), desc = paren < 0 ? null : s.substring(paren);
		for (MethodNode m : target.methods) if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return m;
		return null;
	}

	/** The descriptor {@code selector} spells, or null for a name-only one. */
	private static String selectorDescriptor(String selector) {
		String s = plainSelector(selector);
		int paren = s == null ? -1 : s.indexOf('(');
		return paren < 0 ? null : s.substring(paren);
	}

	/** {@code selector} without an owner prefix; null for the forms this does not reason about (wildcards, regexes). */
	private static String plainSelector(String selector) {
		String s = selector.trim();
		if (s.indexOf('*') >= 0 || s.startsWith("/") || s.indexOf(' ') >= 0 || s.indexOf('=') >= 0) return null;
		int semi = s.indexOf(';');
		if (s.startsWith("L") && semi > 0) s = s.substring(semi + 1);
		return s;
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

	/** A {@code @Local} that names nothing — no ordinal or index, not limited to the arguments: MixinExtras' implicit mode. */
	private static boolean byTypeOnly(AnnotationNode local) {
		return MixinFit.value(local, "ordinal") == null && MixinFit.value(local, "index") == null
				&& !Boolean.TRUE.equals(MixinFit.value(local, "argsOnly"));
	}

	/**
	 * Whether a by-type-only {@code @Local} of {@code type} is decided on {@code delegate}: at every {@code INVOKE} or
	 * {@code FIELD} anchor of {@code points}, exactly one local slot past {@code this} can hold that type there as
	 * MixinExtras counts ({@link #typedAbove}) — its implicit mode fails the injection on none or on two — the
	 * delegate's local variable table names that slot there, and Mixin still holds it there
	 * ({@link #loadedOrStoredSinceAFrame}).
	 */
	static boolean theOnlyLocalOfItsType(ClassNode owner, MethodNode delegate, Type type, List<AnnotationNode> points) {
		if (delegate.localVariables == null || delegate.instructions == null) return false;
		if (type.getSort() != Type.OBJECT && type.getSort() != Type.ARRAY) return false;
		List<AbstractInsnNode> anchors = new ArrayList<>();
		for (AnnotationNode at : points) {
			String value = MixinFit.asString(MixinFit.value(at, "value"));
			String member = MixinFit.asString(MixinFit.value(at, "target"));
			if (!"INVOKE".equals(value) && !"FIELD".equals(value) || member == null) return false;
			// BEFORE and AFTER one call or field access leave the locals as they are; BY walks past other instructions.
			if (MixinFit.value(at, "shift") instanceof String[] shift && !"BEFORE".equals(shift[1]) && !"AFTER".equals(shift[1])) return false;
			List<AbstractInsnNode> found = anchors(delegate, value, member);
			if (found == null || found.isEmpty()) return false;
			anchors.addAll(found);
		}
		org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.BasicValue>[] frames;
		try {
			frames = new org.objectweb.asm.tree.analysis.Analyzer<>(new TypedValues(type)).analyze(owner.name, delegate);
		} catch (org.objectweb.asm.tree.analysis.AnalyzerException | RuntimeException unanalysable) {
			return false;
		}
		int base = (delegate.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;   // Mixin's baseArgIndex: never `this`
		for (AbstractInsnNode anchor : anchors) {
			int at = delegate.instructions.indexOf(anchor);
			if (frames[at] == null) return false;   // unreachable: nothing to prove it by
			java.util.BitSet candidates = typedAbove(delegate, frames, type, at);
			candidates.clear(0, base);
			if (candidates.cardinality() != 1) return false;   // none, or two: MixinExtras would refuse the injection
			int slot = candidates.nextSetBit(0);
			boolean named = false;
			for (LocalVariableNode local : delegate.localVariables) {
				if (local.index == slot && local.desc.equals(type.getDescriptor()) && delegate.instructions.indexOf(local.start) <= at
						&& at < delegate.instructions.indexOf(local.end)) named = true;
			}
			if (!named || !loadedOrStoredSinceAFrame(delegate, slot, anchor)) return false;
		}
		return true;
	}

	/**
	 * Whether {@code slot} is loaded or stored between the last frame before {@code anchor} and it. Mixin's walk can
	 * lose a slot at a frame: it sizes a {@code CHOP} or {@code APPEND} frame as at least the method's arguments, so
	 * the {@code CHOP} a loop's exit leaves drops every local past them (Player.doSweepAttack's {@code serverLevel}
	 * right after its entity loop), and MixinExtras then finds none. Only an access after it is sure to bring the slot
	 * back. A frame stands wherever one must — a jump, switch or handler target — so this reads the same with frames
	 * skipped (MixinFit's read) as with them. An argument's slot is never lost: no frame is sized below them.
	 */
	private static boolean loadedOrStoredSinceAFrame(MethodNode method, int slot, AbstractInsnNode anchor) {
		if (slot < (Type.getArgumentsAndReturnSizes(method.desc) >> 2) - ((method.access & Opcodes.ACC_STATIC) != 0 ? 1 : 0)) return true;
		Set<org.objectweb.asm.tree.LabelNode> targets = new java.util.HashSet<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof org.objectweb.asm.tree.JumpInsnNode jump) targets.add(jump.label);
			if (insn instanceof org.objectweb.asm.tree.TableSwitchInsnNode table) { targets.add(table.dflt); targets.addAll(table.labels); }
			if (insn instanceof org.objectweb.asm.tree.LookupSwitchInsnNode lookup) { targets.add(lookup.dflt); targets.addAll(lookup.labels); }
		}
		if (method.tryCatchBlocks != null) for (var block : method.tryCatchBlocks) targets.add(block.handler);
		for (AbstractInsnNode insn = anchor.getPrevious(); insn != null; insn = insn.getPrevious()) {
			if (insn instanceof VarInsnNode access && access.var == slot) return true;
			if (insn instanceof org.objectweb.asm.tree.FrameNode || targets.contains(insn)) return false;
		}
		return false;
	}

	/**
	 * Every slot that can be of {@code type} to MixinExtras at instruction {@code at}. It types each slot by Mixin's
	 * {@code Locals.getLocalsAt}, which reads the method top to bottom, not along its branches: a slot keeps the table
	 * entry it was last stored or loaded under, and a frame that drops it can keep it as a zombie a later access revives. So
	 * a {@code List x = new ArrayList()} is a {@code List}, not the {@code ArrayList} the data flow sees, and one declared
	 * in a block, a loop, a catch or a switch case above the anchor still is after the join the data flow calls dead.
	 * Counted, then: every slot the table declares that type in anywhere above {@code at}, and every slot the data flow
	 * gives exactly that type anywhere above it (a temp the table never names, which Mixin types by its own analysis).
	 */
	private static java.util.BitSet typedAbove(MethodNode method, org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.BasicValue>[] frames,
			Type type, int at) {
		java.util.BitSet typed = new java.util.BitSet();
		for (LocalVariableNode local : method.localVariables) {
			if (local.desc.equals(type.getDescriptor()) && method.instructions.indexOf(local.start) <= at) typed.set(local.index);
		}
		for (int i = 0; i <= at; i++) {
			if (frames[i] == null) continue;
			for (int s = 0; s < frames[i].getLocals(); s++) {
				org.objectweb.asm.tree.analysis.BasicValue held = frames[i].getLocal(s);
				if (held != null && (type.equals(held.getType()) || held.equals(TypedValues.PERHAPS))) typed.set(s);
			}
		}
		return typed;
	}

	/**
	 * ASM's {@code BasicInterpreter}, keeping each reference's declared type: a slot is a candidate for a by-type
	 * {@code @Local} when it holds that type — or, after two paths meet with different references and one of them was
	 * that type, perhaps holds it, which counts too.
	 */
	private static final class TypedValues extends org.objectweb.asm.tree.analysis.BasicInterpreter {
		/** Its own type, so no real value compares equal to it and a frame merge cannot drop it. */
		static final org.objectweb.asm.tree.analysis.BasicValue PERHAPS =
				new org.objectweb.asm.tree.analysis.BasicValue(Type.getObjectType("net/forbric/kernel/mixin/PerhapsTheWantedType"));
		private final Type wanted;

		TypedValues(Type wanted) {
			super(Opcodes.ASM9);
			this.wanted = wanted;
		}

		@Override
		public org.objectweb.asm.tree.analysis.BasicValue newValue(Type type) {
			if (type != null && (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY)) {
				return new org.objectweb.asm.tree.analysis.BasicValue(type);
			}
			return super.newValue(type);
		}

		@Override
		public org.objectweb.asm.tree.analysis.BasicValue binaryOperation(AbstractInsnNode insn, org.objectweb.asm.tree.analysis.BasicValue array,
				org.objectweb.asm.tree.analysis.BasicValue index) throws org.objectweb.asm.tree.analysis.AnalyzerException {
			if (insn.getOpcode() == Opcodes.AALOAD && array.getType() != null && array.getType().getSort() == Type.ARRAY) {
				return newValue(Type.getType(array.getType().getDescriptor().substring(1)));   // an element of a T[] is a T
			}
			return super.binaryOperation(insn, array, index);
		}

		@Override
		public org.objectweb.asm.tree.analysis.BasicValue merge(org.objectweb.asm.tree.analysis.BasicValue a,
				org.objectweb.asm.tree.analysis.BasicValue b) {
			if (a.equals(b)) return a;
			if (a.isReference() && b.isReference()) {
				return a.equals(PERHAPS) || b.equals(PERHAPS) || wanted.equals(a.getType()) || wanted.equals(b.getType())
						? PERHAPS : org.objectweb.asm.tree.analysis.BasicValue.REFERENCE_VALUE;
			}
			return org.objectweb.asm.tree.analysis.BasicValue.UNINITIALIZED_VALUE;
		}
	}

	private static boolean hasLocal(MethodNode method, String name, Type type) {
		if (method.localVariables == null) return false;
		for (LocalVariableNode local : method.localVariables) if (local.name.equals(name) && local.desc.equals(type.getDescriptor())) return true;
		return false;
	}

	private static AnnotationNode local(MethodNode handler, int parameter) {
		return sugar(handler, parameter, LOCAL);
	}

	/** The annotation {@code desc} on {@code handler}'s {@code parameter}, visible or not; null when absent. */
	private static AnnotationNode sugar(MethodNode handler, int parameter, String desc) {
		for (List<AnnotationNode>[] all : List.of(nonNull(handler.visibleParameterAnnotations), nonNull(handler.invisibleParameterAnnotations))) {
			if (parameter < all.length && all[parameter] != null) for (AnnotationNode a : all[parameter]) if (desc.equals(a.desc)) return a;
		}
		return null;
	}

	/**
	 * Whether a handler's trailing part — what MixinExtras fills from the target, not the call — begins at
	 * {@code parameter}: a MixinExtras sugar parameter ({@code @Local}, {@code @Share}, …).
	 *
	 * <p>Any annotation used to count. A {@code @Coerce} receiver is part of the call's shape, and Kotlin (and some Java
	 * mods) put an invisible {@code @NotNull} on every handler parameter; with the call's part ending at parameter 0,
	 * the injector's own contract read as longer than the handler, and both this rebind and R1 left such an injector on
	 * the stub, where its anchors miss. {@code -Dforbric.mixinStubRebind.sugarBoundary=off} counts any annotation again.
	 */
	static boolean trailingSugar(MethodNode handler, int parameter) {
		if ("off".equalsIgnoreCase(System.getProperty(SUGAR_BOUNDARY_PROPERTY, "on"))) return annotated(handler, parameter);
		return MixinFit.sugar(handler, parameter);
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
