/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.transform.MergedBaseFrameRecomputer;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Caught;

/**
 * Puts a native method's decisions back in front of the carrier hook that replaced them, so a mod's injector on a native call
 * of that stretch binds where its own loader binds it, runs exactly when its own loader runs it, and the carrier still
 * decides everything it decides.
 *
 * <p>A carrier sometimes replaces a whole stretch of a vanilla method with one call into a hook of its own: NeoForge's
 * {@code LivingEntity.baseTick} hands the air supply to {@code CommonHooks.onLivingBreathe}, which decides it its own way (its
 * events, its fluid types). The vanilla stretch asked {@code isEyeInFluid(WATER)}, then — only with the eye in water, outside
 * a bubble column, for an entity that cannot breathe there — {@code MobEffectUtil.hasWaterBreathing(this)}. An injector on
 * either call has nothing to bind in the merged method; inlining the hook does not help either, since the hook asks
 * {@code hasWaterBreathing} with the eye in any fluid (lava too) and never asks {@code isEyeInFluid(WATER)}.
 *
 * <p>The island is the native stretch's decisions, copied from the class the mod was compiled against (its ecosystem's native
 * reference) and run first, at the place the stretch began: the native calls, the native branches between them, nothing that
 * changes state. Mixin then finds each native call in the method as natively, and the injector runs on it under the native
 * conditions, its handler handed what its own loader hands it. Where the carrier's hook asks a static method the island asked
 * too, the island's answer — the mod's wrap included — is what the hook is answered while its stretch runs
 * ({@link net.forbric.kernel.interop.NativeDecisionRelay}): asked with the same operands inside that window, the method returns
 * the island's answer; asked otherwise, it runs as always. A native call the island did not reach (the eye was not in water)
 * leaves the carrier's own answer. A native call no carrier method asks again (a virtual {@code isEyeInFluid}) answers the
 * island alone: its injector runs, and its answer steers the native decisions after it, exactly as natively, but not the
 * carrier's own, which the carrier makes with calls of its own.
 *
 * <p>Nothing here knows a mod, a class or a method by name. An island is planned from one guest injector whose point is a call
 * ({@code INVOKE}, no slice), when:
 * <ul>
 *   <li>the method its selectors bind in the native reference and in the merged class is the same method, and the native
 *       body makes more calls the point selects than the merged body;</li>
 *   <li>the two bodies agree instruction for instruction up to one place and again from another to the end (jumps across that
 *       stretch landing at corresponding instructions), and every native call the point selects lies between;</li>
 *   <li>from the last statement boundary before that place, the native stretch holds, up to the last selected call and to a
 *       statement boundary, only reads, arithmetic, branches, local stores and calls whose answers feed them — no field,
 *       array or static write, no call without an answer, no allocation or lambda, no exception handler; whatever it shares
 *       with the merged body before the place is plain reads;</li>
 *   <li>the merged stretch leaves only by running on into the shared end, no instruction outside it jumps to that end or back
 *       into it, and nothing jumps back before the island;</li>
 *   <li>the injector leaves the call's answer as it is (an {@code @Inject}, or a wrap proved to hand back what the call
 *       answered), or the carrier asks the call again and is relayed its answer: an answer the island would hand nobody is
 *       left to the adapters that move the injector onto the carrier's own call (an overload the carrier calls instead, the
 *       callee the call moved into), where the carrier hears it;</li>
 *   <li>the carrier does not make the selected call itself (directly or in a static method it calls) with operands of its
 *       own: such a call moved rather than went, and the injector is left to the adapters that bind it at the carrier's call,
 *       the one whose answer counts — unless the carrier is relayed the island's answer;</li>
 *   <li>the merged game runs the method, and it is not a carrier's forwarding stub — those are left to the adapters that move
 *       the injector to the method that runs.</li>
 * </ul>
 * An injector of another mod on a call an island already holds binds there as on any present call; where it may change the
 * answer and no carrier method asks the call again, that is said once: its answer steers the native decisions after it.
 * A static method the island asks is relayed when the merged stretch asks it too, directly or in a static method it calls, and
 * the island hands it plain reads of what the method held when the island began (never a value the island computed itself,
 * which the carrier, computing its own, would never ask with); its class must not be defined yet (it is checked again when it
 * is). Every relayed method's class and the host must still be undefined when the plan is registered, or no island is planned
 * and the injector is left unbound, said once. {@code -Dforbric.nativeDecisionIsland=off} plans nothing.
 *
 * <p>A carrier that decides the same thing with questions of its own can still hear a changed answer through a copy of the
 * native predicate that reads the island's answers ({@link #heard}): the cross-host predicate island evaluates the native
 * predicate inside the carrier's hook, where the carrier's own question cannot express it (NeoForge asks a non-water fluid
 * type whether an entity drowns in it; natively a drowning fluid takes water's branch, where breathing under water
 * protects). For such a reader the island also offers, to its frame, what the injector's call answered — after the
 * injector, with the receiver among the operands — and an injector that may change the answer gets its island although no
 * carrier method asks the call again: the reader hears it, the injector itself runs once, at the native call.
 */
public final class NativeDecisionIsland {
	public static final String PROPERTY = "forbric.nativeDecisionIsland";
	/** {@code -Dforbric.nativeDecisionIsland.debug=true}: why each missing call got no island, with the two bodies' alignment. */
	private static final boolean DEBUG = Boolean.getBoolean("forbric.nativeDecisionIsland.debug");
	static final String RELAY = "net/forbric/kernel/interop/NativeDecisionRelay";
	private static final int MAX_ISLAND = 400;
	private static final int REACH_DEPTH = 2;

	/** A static method the island asks and the carrier's stretch asks again. */
	record Relay(String owner, String name, String desc) {
		String key() {
			return "L" + owner + ";" + name + desc;
		}
	}

	/** One injector's island: the host method, the native class it is copied from, the native call it wants there. */
	record Plan(String host, String method, Ecosystem ecosystem, String nativeOwner, String wanted, String source, String handler) {
	}

	/**
	 * Where an island goes in the host method and what it holds, as derived from one native body and one merged body; {@code
	 * carried} are the members the merged stretch asks itself, directly or in a static method it calls.
	 */
	record Island(int start, int end, int resume, Map<Integer, Integer> slots, int fresh, Map<Integer, Relay> relays, Set<String> members,
			Map<String, Relay> relayable, Set<String> carried) {
	}

	/** The island, or why there is none (null for both when nothing is missing or the point is not one this reads). */
	record Outcome(Island island, String declined) {
		static final Outcome NONE = new Outcome(null, null);
	}

	private static final class Registry {
		final List<Plan> plans = new CopyOnWriteArrayList<>();
		final Set<Relay> relays = Collections.synchronizedSet(new LinkedHashSet<>());
		/** Per island (host, method, ecosystem): the static calls it holds that the carrier's stretch asks again. */
		final Map<String, Map<String, Relay>> relayable = new java.util.concurrent.ConcurrentHashMap<>();
		/** Per island: every call it holds. */
		final Map<String, Set<String>> members = new java.util.concurrent.ConcurrentHashMap<>();
		/** Per island: the calls whose answers it offers for a reader of the native predicate ({@link #heard}). */
		final Map<String, Set<String>> heard = new java.util.concurrent.ConcurrentHashMap<>();
	}

	private static String island(Plan plan) {
		return plan.host() + "." + plan.method() + "|" + plan.ecosystem();
	}

	private static final Map<ClassLoader, Registry> REGISTRIES = Collections.synchronizedMap(new WeakHashMap<>());

	private NativeDecisionIsland() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	public static void release(ClassLoader loader) {
		REGISTRIES.remove(loader);
	}

	private static Registry registry(ClassLoader loader) {
		synchronized (REGISTRIES) {
			return REGISTRIES.computeIfAbsent(loader, ignored -> new Registry());
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Planning, from one guest mixin

	/**
	 * Plans and registers the islands {@code mixin}'s injectors need; returns how many it registered. {@code code} returns a
	 * class's current pre-mixin bytes with code; {@code natives} the class an ecosystem's mods were compiled against.
	 */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> code, BiFunction<Ecosystem, String, ClassNode> natives,
			ForbricClassLoader loader) {
		if (!enabled() || mixin == null || loader == null || code == null || natives == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		if (ecosystem == null) return 0;
		Registry registry = registry(loader);
		int planned = 0;
		for (String target : MixinFit.mixinTargets(mixin)) {
			ClassNode original = natives.apply(ecosystem, target);
			if (original == null) continue;
			ClassNode merged = null;
			for (MethodNode handler : List.copyOf(mixin.methods)) {
				AnnotationNode injector = MixinFit.injectorOf(handler);
				if (injector == null || MixinFit.value(injector, "slice") != null) continue;
				for (AnnotationNode at : MixinFit.atNodes(injector)) {
					String kind = MixinFit.asString(MixinFit.value(at, "value"));
					if (!"INVOKE".equals(kind) && !"INVOKE_ASSIGN".equals(kind)) continue;
					if (merged == null) merged = code.apply(target);
					if (merged == null) break;
					MethodNode was = MixinTargetSelectors.one(handler, original), host = MixinTargetSelectors.one(handler, merged);
					if (was == null || host == null || !was.name.equals(host.name) || !was.desc.equals(host.desc) || host.instructions.size() == 0)
						continue;
					List<MethodInsnNode> wanted = MixinCallbackShape.calls(at, was);
					String member = MixinCallbackShape.invoked(at, was);
					if (wanted.isEmpty() || member == null) continue;
					Plan plan = new Plan(target, host.name + host.desc, ecosystem, original.name, member, mixin.name, handler.name);
					if (MixinCallbackShape.calls(at, host).size() >= wanted.size()) {
						// Present: perhaps only because an island another injector planned holds it — then its answer is relayed too.
						Relay relay = registry.relayable.getOrDefault(island(plan), Map.of()).get(member);
						if (relay == null && registry.members.getOrDefault(island(plan), Set.of()).contains(member))
							answerNotice(mixin, handler, injector, member, target, host);
						if (relay != null && !registry.plans.contains(plan)) {
							if (registerAll(loader, List.of(relay.owner()), 0, () -> {
								registry.plans.add(plan);
								registry.relays.add(relay);
							})) {
								planned++;
								ForbricLog.info("[Forbric/Mixin] %s.%s: %s, asked in %s.%s again by its native decision island, answers the carrier what "
										+ "it answered there", mixin.name.replace('/', '.'), handler.name, NativeCallInlining.readable(member),
										target.replace('/', '.'), host.name);
							} else {
								ForbricLog.warn("[Forbric/Mixin] %s.%s: the carrier is not told what %s answered the native decision island — its class is "
										+ "already defined", mixin.name.replace('/', '.'), handler.name, NativeCallInlining.readable(member));
							}
						}
						continue;
					}
					// A method the merged game never runs, or a carrier's forwarding stub, is left to the adapters that move the injector
					// to the method that runs (as NativeCallInlining leaves it).
					if (MergedBaseUncalledMethods.neverRuns(merged, host, ecosystem) != null || MixinStubRebind.isCarrierStub(merged, host)) continue;
					Outcome outcome = derive(merged, host, original, was, union(registry, plan), code);
					// A call the carrier makes itself, with operands the island cannot hand it, moved rather than went: the injector is
					// left to the adapters that bind it at the carrier's call, which is the one whose answer counts.
					if (outcome.island() != null && outcome.island().relayable().get(member) == null && outcome.island().carried().contains(member))
						outcome = new Outcome(null, "the carrier makes that call itself, with operands of its own, so the call moved rather than went");
					// An answer the island hands nobody must not change: a wrap of a call no carrier method asks again either hands back
					// what the call answered, or the injector is left to the adapters that move it onto the carrier's own call.
					if (outcome.island() != null && outcome.island().relayable().get(member) == null && !answerKept(mixin, handler, injector))
						outcome = new Outcome(null, "it may change what the call answers, and no carrier method asks the call again to hear it");
					if (outcome.island() == null) {
						if (outcome.declined() != null && DEBUG)
							ForbricLog.info("[Forbric/Mixin] %s.%s: the merged %s.%s makes fewer calls of %s than %s does, and no native decision island "
									+ "puts them back: %s", mixin.name.replace('/', '.'), handler.name, target.replace('/', '.'), host.name,
									NativeCallInlining.readable(member), ecosystem, outcome.declined());
						continue;
					}
					if (register(loader, registry, plan, outcome.island())) {
						planned++;
						merged = null;
						if (outcome.island().relayable().get(member) == null) answerNotice(mixin, handler, injector, member, target, host);
						ForbricLog.info("[Forbric/Mixin] %s.%s: %s is asked in %s.%s again — the merged game decides that stretch in %s, so "
								+ "the native decisions run first, under %s's conditions%s", mixin.name.replace('/', '.'), handler.name,
								NativeCallInlining.readable(member), target.replace('/', '.'), host.name, carrierOf(host, outcome.island()), ecosystem,
								relayed(outcome.island(), member));
					} else {
						ForbricLog.warn("[Forbric/Mixin] %s.%s: %s stays unasked in %s.%s — a class the native decision island changes is already defined",
								mixin.name.replace('/', '.'), handler.name, NativeCallInlining.readable(member), target.replace('/', '.'), host.name);
					}
				}
			}
		}
		return planned;
	}

	/**
	 * Lets a reader of the native predicate hear what {@code handler}'s call answers, for a {@code handler} whose answer no carrier
	 * method asks again: the island that holds the call — one another injector planned, or one planned now for this injector —
	 * offers that answer to its frame, after the injector. True when an island holds the call and offers its answer; false, with
	 * nothing registered, when no island can hold it (the injector is then left to the adapters that move it, as before), when
	 * the call is there for another reason than an island, or when the host is already defined.
	 *
	 * <p>The call must be the one native call the point selects, made on plain reads (a local, a constant, a final static
	 * field), so the reader, making it on the same operands inside the frame, finds the answer.
	 */
	public static boolean heard(ClassNode mixin, MethodNode handler, Function<String, ClassNode> code, BiFunction<Ecosystem, String, ClassNode> natives,
			ForbricClassLoader loader) {
		if (!enabled() || mixin == null || handler == null || loader == null || code == null || natives == null) return false;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		AnnotationNode injector = MixinFit.injectorOf(handler);
		List<String> targets = MixinFit.mixinTargets(mixin);
		if (ecosystem == null || injector == null || MixinFit.value(injector, "slice") != null || targets.size() != 1) return false;
		List<AnnotationNode> ats = MixinFit.atNodes(injector);
		if (ats.size() != 1 || !"INVOKE".equals(MixinFit.asString(MixinFit.value(ats.getFirst(), "value")))) return false;
		String target = targets.getFirst();
		ClassNode original = natives.apply(ecosystem, target), merged = code.apply(target);
		if (original == null || merged == null) return false;
		MethodNode was = MixinTargetSelectors.one(handler, original), host = MixinTargetSelectors.one(handler, merged);
		if (was == null || host == null || !was.name.equals(host.name) || !was.desc.equals(host.desc) || host.instructions.size() == 0) return false;
		List<MethodInsnNode> wanted = MixinCallbackShape.calls(ats.getFirst(), was);
		String member = MixinCallbackShape.invoked(ats.getFirst(), was);
		if (wanted.size() != 1 || member == null || Type.getReturnType(wanted.getFirst().desc) == Type.VOID_TYPE) return false;
		Registry registry = registry(loader);
		Plan plan = new Plan(target, host.name + host.desc, ecosystem, original.name, member, mixin.name, handler.name);
		String key = island(plan);
		Runnable hear = () -> {
			if (!registry.plans.contains(plan)) registry.plans.add(plan);
			registry.heard.computeIfAbsent(key, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(member);
		};
		if (MixinCallbackShape.calls(ats.getFirst(), host).size() >= wanted.size()) {
			// Present: heard only where an island holds it, whose operands are plain reads the reader repeats.
			if (!registry.members.getOrDefault(key, Set.of()).contains(member) || !plainOperands(was, wanted.getFirst(), code)) return false;
			if (registry.heard.getOrDefault(key, Set.of()).contains(member) && registry.plans.contains(plan)) return true;
			return registerAll(loader, List.of(target), 0, hear);
		}
		if (MergedBaseUncalledMethods.neverRuns(merged, host, ecosystem) != null || MixinStubRebind.isCarrierStub(merged, host)) return false;
		Outcome outcome = derive(merged, host, original, was, union(registry, plan), code);
		if (outcome.island() == null || outcome.island().relayable().get(member) == null && outcome.island().carried().contains(member)
				|| !plainOperands(was, wanted.getFirst(), code)) return false;
		if (!register(loader, registry, plan, outcome.island(), hear)) return false;
		ForbricLog.info("[Forbric/Mixin] %s.%s: %s is asked in %s.%s again — the merged game decides that stretch in %s; the native decisions "
				+ "run first, under %s's conditions, and what the call answered there is what the predicate the carrier decides with reads",
				mixin.name.replace('/', '.'), handler.name, NativeCallInlining.readable(member), target.replace('/', '.'), host.name,
				carrierOf(host, outcome.island()), ecosystem);
		return true;
	}

	/** Every operand of {@code call} in {@code was} is one plain read: a local, a constant, or a final static field of a class at hand. */
	private static boolean plainOperands(MethodNode was, MethodInsnNode call, Function<String, ClassNode> code) {
		Frame<SourceValue>[] sources = sources("island", was);
		if (sources == null) return false;
		Frame<SourceValue> frame = sources[was.instructions.indexOf(call)];
		if (frame == null) return false;
		int count = Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
		for (int k = 0; k < count; k++) {
			SourceValue value = frame.getStack(frame.getStackSize() - count + k);
			if (value.insns.size() != 1) return false;
			AbstractInsnNode producer = value.insns.iterator().next();
			if (producer instanceof VarInsnNode load && load.getOpcode() >= Opcodes.ILOAD && load.getOpcode() <= Opcodes.ALOAD || constant(producer)) continue;
			if (!(producer instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC)) return false;
			ClassNode owner;
			try { owner = code.apply(field.owner); } catch (RuntimeException unreadable) { Caught.warn(unreadable, "[Forbric/Mixin] could not read %s to see whether %s is final; taken as not", field.owner, field.name); owner = null; }
			if (owner == null || owner.fields.stream().noneMatch(f -> f.name.equals(field.name) && f.desc.equals(field.desc)
					&& (f.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))) return false;
		}
		return true;
	}

	/**
	 * Says so where an injector that may change a call's answer binds a call of the island that no carrier method asks again:
	 * its answer steers the native decisions the island repeats, not the carrier's own decision, which the carrier makes with
	 * calls of its own. A wrap proved to hand back what the call answered changes no answer and is not mentioned.
	 */
	private static void answerNotice(ClassNode mixin, MethodNode handler, AnnotationNode injector, String member, String target, MethodNode host) {
		if (answerKept(mixin, handler, injector)) return;
		ForbricLog.info("[Forbric/Mixin] %s.%s may change what %s answers in %s.%s: that answer steers the native decisions the island "
				+ "repeats there, but the carrier decides the same thing with calls of its own, which do not hear it", mixin.name.replace('/', '.'),
				handler.name, NativeCallInlining.readable(member), target.replace('/', '.'), host.name);
	}

	/** The injector leaves the call's answer as it is: an {@code @Inject}, or a wrap proved to hand back what the call answered. */
	private static boolean answerKept(ClassNode mixin, MethodNode handler, AnnotationNode injector) {
		String kind = injector.desc.substring(injector.desc.lastIndexOf('/') + 1, injector.desc.length() - 1);
		return switch (kind) {
			case "Inject" -> true;
			case "WrapOperation" -> MixinCallbackProofs.returnsOriginal(mixin.name, handler);
			default -> false;
		};
	}

	private static Set<String> union(Registry registry, Plan plan) {
		Set<String> wanted = new LinkedHashSet<>();
		for (Plan p : registry.plans) if (p.host().equals(plan.host()) && p.method().equals(plan.method()) && p.ecosystem() == plan.ecosystem()) wanted.add(p.wanted());
		wanted.add(plan.wanted());
		return wanted;
	}

	private static String relayed(Island island, String member) {
		for (Relay relay : island.relays().values()) if (relay.key().equals(member))
			return ", and its answer is the one the carrier is given while it decides";
		return "";
	}

	private static String carrierOf(MethodNode host, Island island) {
		List<AbstractInsnNode> real = real(host);
		Set<String> calls = new LinkedHashSet<>();
		for (int i = island.start(); i < island.resume() && i < real.size(); i++)
			if (real.get(i) instanceof MethodInsnNode call) calls.add(call.owner.replace('/', '.') + "." + call.name);
		return calls.isEmpty() ? "its own code" : String.join(", ", calls);
	}

	private static final Runnable NOTHING_ELSE = () -> { };

	private static boolean register(ForbricClassLoader loader, Registry registry, Plan plan, Island island) {
		return register(loader, registry, plan, island, NOTHING_ELSE);
	}

	/** Registers {@code plan} and its island, and runs {@code also} in the same registration (before any of their classes is defined). */
	private static boolean register(ForbricClassLoader loader, Registry registry, Plan plan, Island island, Runnable also) {
		for (Plan existing : registry.plans) if (existing.equals(plan)) return also == NOTHING_ELSE || registerAll(loader, List.of(plan.host()), 0, also);
		List<String> owners = new ArrayList<>(new TreeSet<>(island.relays().values().stream().map(Relay::owner).toList()));
		if (!owners.contains(plan.host())) owners.add(plan.host());
		Collections.sort(owners);
		return registerAll(loader, owners, 0, () -> {
			also.run();
			if (!registry.plans.contains(plan)) registry.plans.add(plan);
			registry.relays.addAll(island.relays().values());
			registry.members.merge(island(plan), island.members(), (a, b) -> {
				Set<String> both = new LinkedHashSet<>(a);
				both.addAll(b);
				return both;
			});
			registry.relayable.merge(island(plan), island.relayable(), (a, b) -> {
				Map<String, Relay> both = new LinkedHashMap<>(a);
				both.putAll(b);
				return both;
			});
		});
	}

	private static boolean registerAll(ForbricClassLoader loader, List<String> owners, int at, Runnable action) {
		if (at == owners.size()) {
			action.run();
			return true;
		}
		boolean[] done = {false};
		return loader.registerBeforeDefinition(owners.get(at).replace('/', '.'), () -> done[0] = registerAll(loader, owners, at + 1, action)) && done[0];
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Derivation

	/**
	 * The island that asks {@code wanted} (members spelled {@code Lowner;name(desc)}) in {@code host} as {@code was} asks them,
	 * or why there is none.
	 */
	static Outcome derive(ClassNode merged, MethodNode host, ClassNode nativeClass, MethodNode was, Set<String> wanted,
			Function<String, ClassNode> code) {
		List<AbstractInsnNode> n = real(was), m = real(host);
		if (n.isEmpty() || m.isEmpty()) return Outcome.NONE;
		Map<LabelNode, Integer> nl = labels(was, n), ml = labels(host, m);
		int p = 0;
		while (p < n.size() && p < m.size() && same(n.get(p), m.get(p))) p++;
		// The shared end may number its own locals differently (a local the carrier's stretch added shifts them): it is the same
		// code under one consistent renaming of the slots it writes, the slots it reads first being the method's own.
		int q = 0;
		while (q < n.size() - p && q < m.size() - p && sameModuloSlots(n.get(n.size() - 1 - q), m.get(m.size() - 1 - q))) q++;
		for (int bad; q > 0 && (bad = renamed(n, m, q)) >= 0; ) q = q - bad - 1;
		int nEnd = n.size() - q, mEnd = m.size() - q;
		if (p >= nEnd || p >= mEnd) return new Outcome(null, "the native and merged bodies do not differ in one stretch");
		// Jumps in the shared parts land at corresponding instructions, and none lands inside the differing stretch.
		for (int i = 0; i < n.size(); i++) {
			if (i >= p && i < nEnd) continue;
			int j = i < p ? i : i - nEnd + mEnd;
			List<Integer> to = targets(n.get(i), nl), toMerged = targets(m.get(j), ml);
			if (to.size() != toMerged.size()) return new Outcome(null, "a shared jump differs");
			for (int k = 0; k < to.size(); k++) {
				int a = to.get(k), b = toMerged.get(k);
				boolean ok = a < p ? a == b : a >= nEnd && b >= mEnd && a - nEnd == b - mEnd;
				if (!ok) return new Outcome(null, "a shared jump lands inside the stretch the carrier replaced"
						+ (DEBUG ? " (native " + i + "->" + a + ", merged " + j + "->" + b + "; shared start " + p + ", native end " + nEnd + "/" + n.size()
								+ ", merged end " + mEnd + "/" + m.size() + ")" : ""));
			}
		}
		List<Integer> asked = new ArrayList<>();
		for (int i = 0; i < n.size(); i++) if (n.get(i) instanceof MethodInsnNode call && wanted.contains(member(call))) {
			if (i < p || i >= nEnd) return new Outcome(null, "the native call " + NativeCallInlining.readable(member(call))
					+ " lies outside the stretch the carrier replaced");
			asked.add(i);
		}
		if (asked.isEmpty()) return Outcome.NONE;
		Frame<BasicValue>[] nf = frames(nativeClass.name, was), mf = frames(merged.name, host);
		Frame<SourceValue>[] ns = sources(nativeClass.name, was);
		if (nf == null || mf == null || ns == null) return new Outcome(null, "a body cannot be analysed");
		int s = p;
		while (s > 0 && !(empty(nf, was, n, s) && empty(mf, host, m, s))) s--;
		if (!empty(nf, was, n, s) || !empty(mf, host, m, s)) return new Outcome(null, "no statement begins before the stretch");
		for (int i = s; i < p; i++) if (!plainRead(m.get(i))) return new Outcome(null, "the statement the stretch begins in does more than read before it");
		// The native decisions: from the statement boundary, as long as nothing changes state, ending at a statement boundary.
		int e = -1;
		for (int i = s; i < nEnd && i - s < MAX_ISLAND; i++) {
			if (!decision(n.get(i))) break;
			if (i + 1 < n.size() && empty(nf, was, n, i + 1)) e = i;
		}
		int last = asked.getLast();
		if (e < last) return new Outcome(null, "the native call " + NativeCallInlining.readable(member((MethodInsnNode) n.get(last)))
				+ " is asked after native code that changes state, which a second run could not repeat unseen");
		for (TryCatchBlockNode block : was.tryCatchBlocks) {
			int from = index(nl, block.start), to = index(nl, block.end);
			if (from <= e && to > s) return new Outcome(null, "the native decisions are inside an exception handler's range");
		}
		for (TryCatchBlockNode block : host.tryCatchBlocks) {
			int from = index(ml, block.start), to = index(ml, block.end);
			if (from <= mEnd && to > s) return new Outcome(null, "the merged stretch is inside an exception handler's range");
		}
		// Nothing comes back before the island, in either body; the merged stretch leaves only into the shared end, which only it reaches.
		for (int i = s; i < n.size(); i++) for (int t : targets(n.get(i), nl)) if (t <= s) return new Outcome(null, "the native body jumps back before the stretch");
		for (int i = s; i < m.size(); i++) for (int t : targets(m.get(i), ml)) if (t <= s) return new Outcome(null, "the merged body jumps back before the stretch");
		for (int i = p; i < mEnd; i++) {
			int op = m.get(i).getOpcode();
			if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN || op == Opcodes.ATHROW) return new Outcome(null, "the merged stretch returns or throws");
			for (int t : targets(m.get(i), ml)) if (t < p || t > mEnd || t == mEnd && !(m.get(i) instanceof JumpInsnNode))
				return new Outcome(null, "the merged stretch jumps out of itself");
		}
		if (mEnd >= m.size()) return new Outcome(null, "the merged stretch runs to the end of the method");
		// Only the island's own start lets control in: nothing outside jumps past it into the stretch (a jump over the whole
		// stretch, onto its end, never opened the frame and never closes it).
		for (int i = 0; i < m.size(); i++) {
			if (i >= s && i < mEnd) continue;
			for (int t : targets(m.get(i), ml)) if (t > s && t < mEnd) return new Outcome(null, "code outside the merged stretch jumps into it");
		}
		// Locals the decisions read must hold the native values at the island (the shared start guarantees it); those they store get fresh slots.
		Map<Integer, Integer> slots = new HashMap<>();
		int fresh = host.maxLocals;
		Set<Integer> stored = new HashSet<>();
		for (int i = s; i <= e; i++) {
			AbstractInsnNode insn = n.get(i);
			if (!(insn instanceof VarInsnNode variable)) continue;
			boolean store = variable.getOpcode() >= Opcodes.ISTORE && variable.getOpcode() <= Opcodes.ASTORE;
			if (store) {
				if (!slots.containsKey(variable.var)) {
					slots.put(variable.var, fresh);
					fresh += variable.getOpcode() == Opcodes.LSTORE || variable.getOpcode() == Opcodes.DSTORE ? 2 : 1;
				}
				stored.add(variable.var);
			} else if (!stored.contains(variable.var)) {
				BasicValue a = nf[was.instructions.indexOf(n.get(s))].getLocal(variable.var);
				BasicValue b = variable.var < mf[host.instructions.indexOf(m.get(s))].getLocals() ? mf[host.instructions.indexOf(m.get(s))].getLocal(variable.var) : null;
				if (a == null || b == null || a == BasicValue.UNINITIALIZED_VALUE || !a.equals(b))
					return new Outcome(null, "the native decisions read a local the merged method does not hold there");
				slots.put(variable.var, variable.var);
			}
		}
		// Relays: a static call of the decisions that the merged stretch asks again, handed plain reads.
		Map<Integer, Relay> relays = new LinkedHashMap<>();
		Set<String> members = new LinkedHashSet<>();
		Set<String> reached = reached(m, p, mEnd, code);
		Map<String, Relay> relayable = new LinkedHashMap<>();
		Set<String> carried = new LinkedHashSet<>();
		for (int i = s; i <= e; i++) {
			if (!(n.get(i) instanceof MethodInsnNode call)) continue;
			members.add(member(call));
			if (reached.contains(member(call))) carried.add(member(call));
			if (call.getOpcode() != Opcodes.INVOKESTATIC || !reached.contains(member(call))) continue;
			List<AbstractInsnNode> handed = operands(ns, was, call, n, s);
			if (handed == null) continue;
			// A value the island computed itself (a local it stores) is never the one the carrier computes again: the carrier's
			// call, asked with its own, would never be answered. Only what the method held when the island began can match.
			boolean own = false;
			for (AbstractInsnNode operand : handed) own |= operand instanceof VarInsnNode load && stored.contains(load.var);
			if (own) continue;
			Relay relay = new Relay(call.owner, call.name, call.desc);
			relayable.put(member(call), relay);
			if (asked.contains(i)) relays.put(i, relay);
		}
		return new Outcome(new Island(s, e, mEnd, slots, fresh, relays, members, relayable, carried), null);
	}

	/** Every member the merged stretch asks, directly or in a static method of a class at hand it calls, a few calls deep. */
	private static Set<String> reached(List<AbstractInsnNode> m, int from, int to, Function<String, ClassNode> code) {
		Set<String> out = new HashSet<>();
		List<MethodInsnNode> frontier = new ArrayList<>();
		for (int i = from; i < to; i++) if (m.get(i) instanceof MethodInsnNode call) frontier.add(call);
		Set<String> seen = new HashSet<>();
		for (int depth = 0; depth <= REACH_DEPTH && !frontier.isEmpty(); depth++) {
			List<MethodInsnNode> next = new ArrayList<>();
			for (MethodInsnNode call : frontier) {
				out.add(member(call));
				if (depth == REACH_DEPTH || call.getOpcode() != Opcodes.INVOKESTATIC || !seen.add(member(call))) continue;
				ClassNode owner;
				try { owner = code.apply(call.owner); } catch (RuntimeException unreadable) { Caught.warn(unreadable, "[Forbric/Mixin] could not read %s to follow its calls; taken as absent", call.owner); owner = null; }
				MethodNode body = owner == null ? null : NativeCallInlining.method(owner, call.name + call.desc);
				if (body == null || body.instructions == null) continue;
				for (AbstractInsnNode insn : body.instructions) if (insn instanceof MethodInsnNode inner) next.add(inner);
			}
			frontier = next;
		}
		return out;
	}

	/** The instructions that hand {@code call} each operand, when every one is a single plain read in the island; else null. */
	private static List<AbstractInsnNode> operands(Frame<SourceValue>[] sources, MethodNode was, MethodInsnNode call, List<AbstractInsnNode> n, int s) {
		return operands(sources, was, call, n, s, false);
	}

	/** As above; with {@code statics}, a static field read in the island is a plain read too (its finality is checked when planned). */
	private static List<AbstractInsnNode> operands(Frame<SourceValue>[] sources, MethodNode was, MethodInsnNode call, List<AbstractInsnNode> n, int s,
			boolean statics) {
		Frame<SourceValue> frame = sources[was.instructions.indexOf(call)];
		if (frame == null) return null;
		int count = Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
		List<AbstractInsnNode> out = new ArrayList<>();
		for (int k = 0; k < count; k++) {
			SourceValue value = frame.getStack(frame.getStackSize() - count + k);
			if (value.insns.size() != 1) return null;
			AbstractInsnNode producer = value.insns.iterator().next();
			int at = n.indexOf(producer);
			if (at < s || !(producer instanceof VarInsnNode load && load.getOpcode() >= Opcodes.ILOAD && load.getOpcode() <= Opcodes.ALOAD || constant(producer)
					|| statics && producer instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC)) return null;
			out.add(producer);
		}
		return out;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Applying

	/**
	 * The pre-mixin bytes of {@code binary} with its registered islands put in and its relayed methods answering open frames.
	 * Bytes are returned unchanged where no plan names the class.
	 */
	public static byte[] transform(ClassLoader loader, String binary, byte[] bytes) {
		if (bytes == null || !enabled()) return bytes;
		Registry registry = REGISTRIES.get(loader);
		if (registry == null || registry.plans.isEmpty()) return bytes;
		String internal = binary.replace('.', '/');
		Map<String, List<Plan>> hosted = new LinkedHashMap<>();
		for (Plan plan : registry.plans) if (plan.host().equals(internal)) hosted.computeIfAbsent(plan.method() + "|" + plan.ecosystem() + "|" + plan.nativeOwner(), k -> new ArrayList<>()).add(plan);
		List<Relay> relays = new ArrayList<>();
		synchronized (registry.relays) {
			for (Relay relay : registry.relays) if (relay.owner().equals(internal)) relays.add(relay);
		}
		if (hosted.isEmpty() && relays.isEmpty()) return bytes;
		Set<String> busy = TRANSFORMING.get();
		if (!busy.add(internal)) return bytes;
		try {
			return transform(loader, binary, bytes, registry, internal, hosted, relays);
		} finally {
			busy.remove(internal);
		}
	}

	/** Classes this thread is transforming: a class read while its own transform is under way is not transformed again. */
	private static final ThreadLocal<Set<String>> TRANSFORMING = ThreadLocal.withInitial(HashSet::new);

	private static byte[] transform(ClassLoader loader, String binary, byte[] bytes, Registry registry, String internal,
			Map<String, List<Plan>> hosted, List<Relay> relays) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
		boolean changed = false;
		for (List<Plan> plans : hosted.values()) {
			Plan first = plans.getFirst();
			MethodNode host = NativeCallInlining.method(node, first.method());
			ClassNode nativeClass = NativeGameReferences.reference(first.ecosystem(), first.nativeOwner());
			MethodNode was = nativeClass == null ? null : NativeCallInlining.method(nativeClass, first.method());
			if (host == null || was == null) {
				ForbricLog.warn("[Forbric/Mixin] %s.%s: no native decision island in %s — %s", first.source().replace('/', '.'), first.handler(),
						binary, host == null ? "the method is gone" : "the native body is not at hand");
				continue;
			}
			Set<String> wanted = new LinkedHashSet<>();
			for (Plan plan : plans) wanted.add(plan.wanted());
			Function<String, ClassNode> code = owner -> owner.equals(internal) ? node : current(loader, owner);
			Outcome outcome;
			try {
				outcome = derive(node, host, nativeClass, was, wanted, code);
			} catch (RuntimeException unreadable) {
				Caught.warn(unreadable, "[Forbric/Mixin] could not read the bodies a native decision island in %s.%s is derived from", binary, host.name);
				outcome = new Outcome(null, "the bodies could not be read (" + unreadable + ")");
			}
			if (outcome.island() == null) {
				ForbricLog.warn("[Forbric/Mixin] %s.%s: no native decision island in %s.%s — %s", first.source().replace('/', '.'), first.handler(),
						binary, host.name, outcome.declined() == null ? "nothing is missing any more" : outcome.declined());
				continue;
			}
			Island island = outcome.island();
			Map<Integer, Relay> kept = new LinkedHashMap<>();
			for (Map.Entry<Integer, Relay> relay : island.relays().entrySet()) if (registry.relays.contains(relay.getValue())) kept.put(relay.getKey(), relay.getValue());
			MethodNode work = NativeCallInlining.copy(host);
			Set<Integer> heardAt = heardCalls(was, island, registry.heard.getOrDefault(first.host() + "." + first.method() + "|" + first.ecosystem(), Set.of()), binary);
			try {
				apply(work, was, new Island(island.start(), island.end(), island.resume(), island.slots(), island.fresh(), kept, island.members(),
						island.relayable(), island.carried()), heardAt);
			} catch (RuntimeException unappliable) {
				Caught.warn(unappliable, "[Forbric/Mixin] %s.%s: the native decision island for %s.%s could not be put in; the method stays as merged",
						first.source().replace('/', '.'), first.handler(), binary, host.name);
				continue;
			}
			node.methods.set(node.methods.indexOf(host), work);
			changed = true;
		}
		for (Relay relay : relays) {
			MethodNode callee = NativeCallInlining.method(node, relay.name() + relay.desc());
			if (callee == null || (callee.access & Opcodes.ACC_STATIC) == 0 || (callee.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
				ForbricLog.warn("[Forbric/Mixin] %s is not the static method a native decision island relays to; it answers as always", NativeCallInlining.readable(relay.key()));
				continue;
			}
			answering(callee, relay);
			changed = true;
		}
		if (!changed) return bytes;
		try {
			MergedBaseFrameRecomputer hierarchy = new MergedBaseFrameRecomputer(path -> resource(loader, path));
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
				@Override protected String getCommonSuperClass(String a, String b) {
					return hierarchy.commonSuperClass(a, b);
				}
			};
			node.accept(writer);
			return writer.toByteArray();
		} catch (RuntimeException unwritable) {
			Caught.warn(unwritable, "[Forbric/Mixin] could not write %s with its native decisions put back; it stays as merged", binary);
			return bytes;
		}
	}

	/**
	 * The calls of {@code island} (indices among {@code was}'s real instructions) whose answers it offers a reader: each heard
	 * member made exactly once in the island, on operands the offer can repeat. A member made more than once is not offered
	 * (the reader could not tell the answers apart) and is said once; its reader then leaves the decision to the carrier.
	 */
	private static Set<Integer> heardCalls(MethodNode was, Island island, Set<String> heard, String binary) {
		if (heard.isEmpty()) return Set.of();
		List<AbstractInsnNode> n = real(was);
		Map<String, List<Integer>> at = new LinkedHashMap<>();
		for (int i = island.start(); i <= island.end() && i < n.size(); i++)
			if (n.get(i) instanceof MethodInsnNode call && heard.contains(member(call))) at.computeIfAbsent(member(call), k -> new ArrayList<>()).add(i);
		Frame<SourceValue>[] sources = sources("island", was);
		Set<Integer> out = new LinkedHashSet<>();
		for (Map.Entry<String, List<Integer>> entry : at.entrySet()) {
			if (entry.getValue().size() != 1 || sources == null
					|| operands(sources, was, (MethodInsnNode) n.get(entry.getValue().getFirst()), n, island.start(), true) == null) {
				ForbricLog.warn("[Forbric/Mixin] %s: the native decision island does not offer what %s answered — %s", binary.replace('/', '.'),
						NativeCallInlining.readable(entry.getKey()), entry.getValue().size() != 1 ? "it is asked " + entry.getValue().size() + " times there"
								: "its operands are not plain reads");
				continue;
			}
			out.add(entry.getValue().getFirst());
		}
		return out;
	}

	/** Puts {@code island} into {@code host}: the copied decisions before the stretch, the relay frame closed after it. */
	static void apply(MethodNode host, MethodNode was, Island island) {
		apply(host, was, island, Set.of());
	}

	/** As {@link #apply(MethodNode, MethodNode, Island)}, offering what the calls at {@code heard} answered, receiver among the operands. */
	static void apply(MethodNode host, MethodNode was, Island island, Set<Integer> heard) {
		List<AbstractInsnNode> n = real(was), m = real(host);
		Map<LabelNode, Integer> nl = labels(was, n), ml = labels(host, m);
		int token = island.fresh(), thrown = island.fresh() + 1;
		LabelNode done = new LabelNode(), begin = new LabelNode(), close = new LabelNode(), handler = new LabelNode();
		Map<LabelNode, LabelNode> copies = new IdentityHashMap<>();
		AbstractInsnNode first = n.get(island.start()), last = n.get(island.end());
		for (AbstractInsnNode insn = first; insn != null; insn = insn.getNext()) {
			if (insn instanceof LabelNode label) copies.put(label, new LabelNode());
			if (insn == last) break;
		}
		InsnList code = new InsnList();
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RELAY, "enter", "()Ljava/lang/Object;", false));
		code.add(new VarInsnNode(Opcodes.ASTORE, token));
		code.add(begin);
		for (AbstractInsnNode insn = first; insn != null; insn = insn.getNext()) {
			if (insn instanceof LabelNode label) code.add(copies.get(label));
			else if (insn.getOpcode() >= 0) {
				int at = n.indexOf(insn);
				if (insn instanceof VarInsnNode variable) code.add(new VarInsnNode(variable.getOpcode(), island.slots().get(variable.var)));
				else if (insn instanceof JumpInsnNode jump) {
					int target = index(nl, jump.label);
					code.add(new JumpInsnNode(jump.getOpcode(), target >= island.start() && target <= island.end() ? copy(copies, jump.label, nl, n, island) : done));
				} else code.add(insn.clone(copies));
				Relay relay = island.relays().get(at);
				if (relay != null) code.add(offer((MethodInsnNode) insn, relay, was, n, island));
				else if (heard.contains(at)) code.add(offer((MethodInsnNode) insn, member((MethodInsnNode) insn), was, n, island));
			}
			if (insn == last) break;
		}
		code.add(done);
		host.instructions.insertBefore(m.get(island.start()), code);
		// The frame closes where the stretch runs on into the shared end: after its last instruction, before the end's label, so a
		// jump from outside over the whole stretch (which never opened the frame) does not close it; the stretch's own jumps onto
		// the end are pointed at the closing code.
		InsnList exit = new InsnList();
		exit.add(close);
		exit.add(new VarInsnNode(Opcodes.ALOAD, token));
		exit.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RELAY, "exit", "(Ljava/lang/Object;)V", false));
		for (int i = island.start(); i < island.resume(); i++)
			if (m.get(i) instanceof JumpInsnNode jump && index(ml, jump.label) == island.resume()) jump.label = close;
		host.instructions.insert(m.get(island.resume() - 1), exit);
		InsnList rethrow = new InsnList();
		rethrow.add(handler);
		rethrow.add(new VarInsnNode(Opcodes.ASTORE, thrown));
		rethrow.add(new VarInsnNode(Opcodes.ALOAD, token));
		rethrow.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RELAY, "exit", "(Ljava/lang/Object;)V", false));
		rethrow.add(new VarInsnNode(Opcodes.ALOAD, thrown));
		rethrow.add(new InsnNode(Opcodes.ATHROW));
		host.instructions.add(rethrow);
		host.tryCatchBlocks.add(new TryCatchBlockNode(begin, close, handler, null));
		host.maxLocals = Math.max(host.maxLocals, thrown + 1);
	}

	/** The copy of the label a jump inside the island lands on: the label itself, or the one before the instruction it lands on. */
	private static LabelNode copy(Map<LabelNode, LabelNode> copies, LabelNode label, Map<LabelNode, Integer> nl, List<AbstractInsnNode> n, Island island) {
		LabelNode copied = copies.get(label);
		if (copied != null) return copied;
		// A jump onto the island's first instruction from inside it (a loop) lands on a label before the copy began.
		int target = index(nl, label);
		for (Map.Entry<LabelNode, LabelNode> entry : copies.entrySet()) if (index(nl, entry.getKey()) == target) return entry.getValue();
		throw new IllegalStateException("island jump target not copied");
	}

	/**
	 * After {@code call}, whose answer is on the stack — what any injector on the call made of it — offer it under {@code key},
	 * with every operand the call was handed (its receiver first, if it has one), to the open frame.
	 */
	private static InsnList offer(MethodInsnNode call, String key, MethodNode was, List<AbstractInsnNode> n, Island island) {
		InsnList out = new InsnList();
		Type answer = Type.getReturnType(call.desc);
		out.add(new InsnNode(answer.getSize() == 2 ? Opcodes.DUP2 : Opcodes.DUP));
		box(out, answer);
		out.add(new LdcInsnNode(key));
		List<AbstractInsnNode> operands = operands(sources("island", was), was, call, n, island.start(), true);
		List<Type> types = new ArrayList<>();
		if (call.getOpcode() != Opcodes.INVOKESTATIC) types.add(Type.getObjectType(call.owner));
		types.addAll(List.of(Type.getArgumentTypes(call.desc)));
		push(out, types.size());
		out.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
		for (int i = 0; i < types.size(); i++) {
			out.add(new InsnNode(Opcodes.DUP));
			push(out, i);
			AbstractInsnNode producer = operands.get(i);
			if (producer instanceof VarInsnNode load) out.add(new VarInsnNode(load.getOpcode(), island.slots().get(load.var)));
			else out.add(producer.clone(Map.of()));
			box(out, types.get(i));
			out.add(new InsnNode(Opcodes.AASTORE));
		}
		out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RELAY, "offer", "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)V", false));
		return out;
	}

	/** After {@code call}, whose answer is on the stack: offer it, with the operands the call was handed, to the open frame. */
	private static InsnList offer(MethodInsnNode call, Relay relay, MethodNode was, List<AbstractInsnNode> n, Island island) {
		InsnList out = new InsnList();
		Type answer = Type.getReturnType(call.desc);
		out.add(new InsnNode(answer.getSize() == 2 ? Opcodes.DUP2 : Opcodes.DUP));
		box(out, answer);
		out.add(new LdcInsnNode(relay.key()));
		Frame<SourceValue>[] sources = sources("island", was);
		List<AbstractInsnNode> operands = operands(sources, was, call, n, island.start());
		Type[] types = Type.getArgumentTypes(call.desc);
		push(out, types.length);
		out.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
		for (int i = 0; i < types.length; i++) {
			out.add(new InsnNode(Opcodes.DUP));
			push(out, i);
			AbstractInsnNode producer = operands.get(i);
			if (producer instanceof VarInsnNode load) out.add(new VarInsnNode(load.getOpcode(), island.slots().get(load.var)));
			else out.add(producer.clone(Map.of()));
			box(out, types[i]);
			out.add(new InsnNode(Opcodes.AASTORE));
		}
		out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RELAY, "offer", "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)V", false));
		return out;
	}

	/** The relayed static method's entry: while a frame is open, the island's answer for these operands, if it holds one. */
	static void answering(MethodNode callee, Relay relay) {
		Type[] types = Type.getArgumentTypes(callee.desc);
		Type result = Type.getReturnType(callee.desc);
		InsnList entry = new InsnList();
		LabelNode skip = new LabelNode(), pop = new LabelNode();
		entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RELAY, "anyOpen", "()Z", false));
		entry.add(new JumpInsnNode(Opcodes.IFEQ, skip));
		entry.add(new LdcInsnNode(relay.key()));
		push(entry, types.length);
		entry.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
		int slot = 0;
		for (int i = 0; i < types.length; i++) {
			entry.add(new InsnNode(Opcodes.DUP));
			push(entry, i);
			entry.add(new VarInsnNode(types[i].getOpcode(Opcodes.ILOAD), slot));
			box(entry, types[i]);
			entry.add(new InsnNode(Opcodes.AASTORE));
			slot += types[i].getSize();
		}
		entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RELAY, "answer", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", false));
		entry.add(new InsnNode(Opcodes.DUP));
		entry.add(new FieldInsnNode(Opcodes.GETSTATIC, RELAY, "NONE", "Ljava/lang/Object;"));
		entry.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, pop));
		unbox(entry, result);
		entry.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
		entry.add(pop);
		entry.add(new InsnNode(Opcodes.POP));
		entry.add(skip);
		callee.instructions.insert(entry);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Instruction helpers

	static List<AbstractInsnNode> real(MethodNode method) {
		List<AbstractInsnNode> out = new ArrayList<>();
		if (method.instructions != null) for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) out.add(insn);
		return out;
	}

	/** Each label to the index (among {@code real}) of the first real instruction at or after it. */
	private static Map<LabelNode, Integer> labels(MethodNode method, List<AbstractInsnNode> real) {
		Map<LabelNode, Integer> out = new IdentityHashMap<>();
		List<LabelNode> pending = new ArrayList<>();
		int index = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof LabelNode label) pending.add(label);
			else if (insn.getOpcode() >= 0) {
				for (LabelNode label : pending) out.put(label, index);
				pending.clear();
				index++;
			}
		}
		for (LabelNode label : pending) out.put(label, real.size());
		return out;
	}

	private static int index(Map<LabelNode, Integer> labels, LabelNode label) {
		Integer at = labels.get(label);
		return at == null ? -1 : at;
	}

	private static List<Integer> targets(AbstractInsnNode insn, Map<LabelNode, Integer> labels) {
		List<Integer> out = new ArrayList<>();
		if (insn instanceof JumpInsnNode jump) out.add(index(labels, jump.label));
		else if (insn instanceof TableSwitchInsnNode table) {
			out.add(index(labels, table.dflt));
			for (LabelNode label : table.labels) out.add(index(labels, label));
		} else if (insn instanceof LookupSwitchInsnNode lookup) {
			out.add(index(labels, lookup.dflt));
			for (LabelNode label : lookup.labels) out.add(index(labels, label));
		}
		return out;
	}

	/** {@link #same}, a local's slot aside. */
	private static boolean sameModuloSlots(AbstractInsnNode a, AbstractInsnNode b) {
		if (a instanceof VarInsnNode x && b instanceof VarInsnNode y) return x.getOpcode() == y.getOpcode();
		if (a instanceof IincInsnNode x && b instanceof IincInsnNode y) return x.incr == y.incr;
		return same(a, b);
	}

	/**
	 * The first of the last {@code q} instructions, in order, whose slot breaks one consistent renaming (a slot written in the
	 * shared end may be renamed, one read there before any write must be the same slot), or -1.
	 */
	private static int renamed(List<AbstractInsnNode> n, List<AbstractInsnNode> m, int q) {
		Map<Integer, Integer> forward = new HashMap<>(), backward = new HashMap<>();
		for (int k = 0; k < q; k++) {
			AbstractInsnNode a = n.get(n.size() - q + k), b = m.get(m.size() - q + k);
			int x, y;
			boolean store;
			if (a instanceof VarInsnNode va && b instanceof VarInsnNode vb) {
				x = va.var;
				y = vb.var;
				store = va.getOpcode() >= Opcodes.ISTORE && va.getOpcode() <= Opcodes.ASTORE;
			} else if (a instanceof IincInsnNode ia && b instanceof IincInsnNode ib) {
				x = ia.var;
				y = ib.var;
				store = false;
			} else continue;
			Integer mapped = forward.get(x), back = backward.get(y);
			if (mapped != null || back != null) {
				if (mapped == null || back == null || mapped != y || back != x) return k;
			} else if (store || x == y) {
				forward.put(x, y);
				backward.put(y, x);
			} else return k;
		}
		return -1;
	}

	/** Two instructions alike in kind and operands; a jump's or switch's targets are compared by the caller. */
	static boolean same(AbstractInsnNode a, AbstractInsnNode b) {
		if (a.getOpcode() != b.getOpcode() || a.getType() != b.getType()) return false;
		if (a instanceof VarInsnNode x) return x.var == ((VarInsnNode) b).var;
		if (a instanceof IincInsnNode x) return x.var == ((IincInsnNode) b).var && x.incr == ((IincInsnNode) b).incr;
		if (a instanceof IntInsnNode x) return x.operand == ((IntInsnNode) b).operand;
		if (a instanceof LdcInsnNode x) return x.cst.equals(((LdcInsnNode) b).cst);
		if (a instanceof TypeInsnNode x) return x.desc.equals(((TypeInsnNode) b).desc);
		if (a instanceof FieldInsnNode x) return x.owner.equals(((FieldInsnNode) b).owner) && x.name.equals(((FieldInsnNode) b).name) && x.desc.equals(((FieldInsnNode) b).desc);
		if (a instanceof MethodInsnNode x) return member(x).equals(member((MethodInsnNode) b)) && x.itf == ((MethodInsnNode) b).itf;
		if (a instanceof InvokeDynamicInsnNode x) {
			// The same call site: its name, its type and its bootstrap. A lambda's implementation handle may be another method of
			// the same shape where a kernel transform gave the body a seam of its own; the site still makes one functional object.
			InvokeDynamicInsnNode y = (InvokeDynamicInsnNode) b;
			return x.name.equals(y.name) && x.desc.equals(y.desc) && x.bsm.equals(y.bsm) && x.bsmArgs.length == y.bsmArgs.length;
		}
		if (a instanceof MultiANewArrayInsnNode x) return x.desc.equals(((MultiANewArrayInsnNode) b).desc) && x.dims == ((MultiANewArrayInsnNode) b).dims;
		if (a instanceof TableSwitchInsnNode x) return x.min == ((TableSwitchInsnNode) b).min && x.max == ((TableSwitchInsnNode) b).max;
		if (a instanceof LookupSwitchInsnNode x) return x.keys.equals(((LookupSwitchInsnNode) b).keys);
		return true;
	}

	/** An instruction a native decision may hold: it reads, computes, branches, stores a local, or asks a call that answers. */
	private static boolean decision(AbstractInsnNode insn) {
		int op = insn.getOpcode();
		if (insn instanceof MethodInsnNode call) return !call.name.equals("<init>") && Type.getReturnType(call.desc) != Type.VOID_TYPE;
		if (insn instanceof VarInsnNode) return op != Opcodes.RET;
		if (insn instanceof JumpInsnNode) return op != Opcodes.JSR;
		if (insn instanceof FieldInsnNode) return op == Opcodes.GETFIELD || op == Opcodes.GETSTATIC;
		if (insn instanceof TypeInsnNode) return op == Opcodes.INSTANCEOF || op == Opcodes.CHECKCAST;
		if (insn instanceof LdcInsnNode || insn instanceof IntInsnNode && op != Opcodes.NEWARRAY) return true;
		if (insn instanceof InsnNode) {
			if (op >= Opcodes.IASTORE && op <= Opcodes.SASTORE) return false;
			if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) return false;
			return op != Opcodes.ATHROW && op != Opcodes.MONITORENTER && op != Opcodes.MONITOREXIT;
		}
		return false;
	}

	private static boolean plainRead(AbstractInsnNode insn) {
		return insn instanceof VarInsnNode load && load.getOpcode() >= Opcodes.ILOAD && load.getOpcode() <= Opcodes.ALOAD || constant(insn)
				|| insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC;
	}

	private static boolean constant(AbstractInsnNode insn) {
		int op = insn.getOpcode();
		return op >= Opcodes.ACONST_NULL && op <= Opcodes.DCONST_1 || op == Opcodes.BIPUSH || op == Opcodes.SIPUSH || insn instanceof LdcInsnNode;
	}

	/** The operand stack is empty before the {@code at}-th real instruction of {@code method} (or the instruction is unreachable). */
	private static boolean empty(Frame<BasicValue>[] frames, MethodNode method, List<AbstractInsnNode> real, int at) {
		if (at >= real.size()) return true;
		Frame<BasicValue> frame = frames[method.instructions.indexOf(real.get(at))];
		return frame == null || frame.getStackSize() == 0;
	}

	@SuppressWarnings("unchecked")
	private static Frame<BasicValue>[] frames(String owner, MethodNode method) {
		try {
			return new Analyzer<>(new BasicInterpreter()).analyze(owner, method);
		} catch (AnalyzerException | RuntimeException unanalysable) {
			Caught.expected(unanalysable);
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private static Frame<SourceValue>[] sources(String owner, MethodNode method) {
		try {
			return new Analyzer<>(new SourceInterpreter()).analyze(owner, method);
		} catch (AnalyzerException | RuntimeException unanalysable) {
			Caught.expected(unanalysable);
			return null;
		}
	}

	static String member(MethodInsnNode call) {
		return "L" + call.owner + ";" + call.name + call.desc;
	}

	private static void push(InsnList out, int value) {
		if (value >= -1 && value <= 5) out.add(new InsnNode(Opcodes.ICONST_0 + value));
		else out.add(new IntInsnNode(Opcodes.BIPUSH, value));
	}

	private static void box(InsnList out, Type type) {
		String owner = switch (type.getSort()) {
			case Type.BOOLEAN -> "java/lang/Boolean";
			case Type.BYTE -> "java/lang/Byte";
			case Type.CHAR -> "java/lang/Character";
			case Type.SHORT -> "java/lang/Short";
			case Type.INT -> "java/lang/Integer";
			case Type.LONG -> "java/lang/Long";
			case Type.FLOAT -> "java/lang/Float";
			case Type.DOUBLE -> "java/lang/Double";
			default -> null;
		};
		if (owner != null) out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "valueOf", "(" + type.getDescriptor() + ")L" + owner + ";", false));
	}

	private static void unbox(InsnList out, Type type) {
		switch (type.getSort()) {
			case Type.BOOLEAN -> unbox(out, "java/lang/Boolean", "booleanValue", "Z");
			case Type.BYTE -> unbox(out, "java/lang/Byte", "byteValue", "B");
			case Type.CHAR -> unbox(out, "java/lang/Character", "charValue", "C");
			case Type.SHORT -> unbox(out, "java/lang/Short", "shortValue", "S");
			case Type.INT -> unbox(out, "java/lang/Integer", "intValue", "I");
			case Type.LONG -> unbox(out, "java/lang/Long", "longValue", "J");
			case Type.FLOAT -> unbox(out, "java/lang/Float", "floatValue", "F");
			case Type.DOUBLE -> unbox(out, "java/lang/Double", "doubleValue", "D");
			case Type.VOID -> out.add(new InsnNode(Opcodes.POP));
			default -> out.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
		}
	}

	private static void unbox(InsnList out, String owner, String name, String desc) {
		out.add(new TypeInsnNode(Opcodes.CHECKCAST, owner));
		out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, name, "()" + desc, false));
	}

	private static ClassNode current(ClassLoader loader, String owner) {
		if (!(loader instanceof ForbricClassLoader forbric) || TRANSFORMING.get().contains(owner)) return null;
		byte[] bytes = forbric.getPreMixinClassBytes(owner.replace('/', '.'));
		if (bytes == null) return null;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] resource(ClassLoader loader, String path) {
		try {
			if (loader instanceof ForbricClassLoader forbric) {
				try (InputStream in = forbric.getGameResourceAsStream(path)) {
					if (in != null) return in.readAllBytes();
				}
			}
			try (InputStream in = ClassLoader.getSystemResourceAsStream(path)) {
				return in == null ? null : in.readAllBytes();
			}
		} catch (IOException unreadable) {
			Caught.warn(unreadable, "[Forbric/Mixin] could not read resource %s; taken as absent", path);
			return null;
		}
	}
}
