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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
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
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.transform.MergedBaseFrameRecomputer;
import net.forbric.kernel.util.ForbricLog;

/**
 * Puts a call back into the method a mod's injector names, where the surviving carrier moved it into a helper of its own:
 * the helper is inlined at its call there, so the injector binds where the mod's own loader binds it and the mod's code
 * runs as it wrote it, with its own semantics, while the helper's body still does everything else it did.
 *
 * <p>A carrier often keeps a vanilla call but makes it from a helper the patched method calls in its place — an event
 * hook that posts something, then makes vanilla's call, or a layered-drawing method that draws every layer and then the
 * screen vanilla drew. An injector anchored on that call in the vanilla method finds nothing there: a {@code @Redirect},
 * {@code @WrapOperation} or {@code @Inject} written for {@code Gui.extractRenderState}'s screen draw has no call to bind
 * when {@code extractRenderState} asks {@code ForgeLayerInstance.drawScreen}, which asks {@code drawScreenInternal},
 * which draws the screen. Inlined, the call is in the method again, exactly once per native occurrence, and the guest's
 * point selects it as Mixin reads the point.
 *
 * <p>Nothing here knows a mod, a class or a method by name. An inline is planned from one guest injector, when:
 * <ul>
 *   <li>its point is a call ({@code INVOKE}), without a slice; the method its selectors bind in the class the mod was
 *       compiled against (the native reference of its ecosystem) and in the merged class are the same method, and the
 *       native body makes more calls the point selects than the merged body does;</li>
 *   <li>a sequence of at most {@value #MAX_DEPTH} inlines — each of a call the native method does not make (a call the
 *       carrier introduced), the second one inside what the first put in — makes the merged body select exactly as many
 *       calls as the native one, and only one such sequence of the fewest inlines does, or only one whose selected calls
 *       are handed every local and parameter of the method the native calls are handed (read by name and type);</li>
 *   <li>each inlined callee is a concrete, non-synchronized method no larger than {@value #MAX_CALLEE_INSNS} instructions,
 *       whose dispatch is known: a static call; a call to a private method of the class itself; or an instance call whose
 *       receiver's exact class is proved — a new instance, the result of a static method every return of which is a new
 *       instance of one class, or the method's own receiver, guarded at run time by {@code getClass()} so an instance of a
 *       subclass still makes the call as merged;</li>
 *   <li>everything the inlined code names is reachable from the class it moves into, or is a field or a static method of a
 *       class this loader still has to define, which is then made public (never a private instance method, whose widening
 *       would turn a same-named method of a subclass into an override);</li>
 *   <li>no other mixin anchors an injector on a call this inline removes, nor declares in that class a method an inlined
 *       instance call would dispatch to (checked again when the class is transformed, with every mixin prepared by then).</li>
 * </ul>
 *
 * <p>An argument the call site hands over as a plain read of a local, which the callee never writes, is read from that
 * local itself rather than copied — so the method gains no second local of the same type beside a mod's {@code @Local}.
 * An inlined instance call on the method's own receiver names the method's class as its owner, as a call written there
 * would (dispatch is unchanged). The class's frames are recomputed from the class hierarchy read as resources.
 *
 * <p>The plan is registered before the target is defined, so every adapter after this one and Mixin itself read the
 * inlined method. {@code -Dforbric.nativeCallInlining=off} plans nothing.
 */
public final class NativeCallInlining {
	public static final String PROPERTY = "forbric.nativeCallInlining";
	static final int MAX_DEPTH = 2;
	/** {@code -Dforbric.nativeCallInlining.debug=true}: one line per inline tried while planning. */
	private static final boolean DEBUG = Boolean.getBoolean("forbric.nativeCallInlining.debug");
	static final int MAX_CALLEE_INSNS = 600;
	private static final int MAX_SOLUTIONS = 32;

	/** One inline: the {@code occurrence}-th call of {@code member} in the body as it stands then, replaced by the callee. */
	record Step(String member, int opcode, int occurrence, String calleeOwner, String calleeMethod, String calleeHash,
			String exactType, boolean guarded) {
	}

	/** Inlines of one method, in order, and the members (by class) the inlined code needs made public. */
	record Plan(String host, String method, List<Step> steps, Map<String, Set<String>> widen, String source, String handler,
			String anchor) {
		boolean sameInlines(Plan other) {
			return host.equals(other.host) && method.equals(other.method) && steps.equals(other.steps);
		}
	}

	/** Why a missing point could not be put back, or the plan that does. */
	record Outcome(Plan plan, String declined) {
		static final Outcome NONE = new Outcome(null, null);
	}

	private record Anchor(String mixin, String target, String nameAndDesc) {
	}

	private record Added(String mixin, String target, String nameAndDesc) {
	}

	/** A method an injector's selector names in a target: its name, and its descriptor where the selector gives one. */
	private record Bound(String mixin, String target, String name, String desc) {
	}

	private static final class Registry {
		final List<Plan> plans = new CopyOnWriteArrayList<>();
		final Set<Anchor> anchors = ConcurrentHashMap.newKeySet();
		final Set<Added> added = ConcurrentHashMap.newKeySet();
		final Set<Bound> bound = ConcurrentHashMap.newKeySet();
	}

	private static final Map<ClassLoader, Registry> REGISTRIES = Collections.synchronizedMap(new WeakHashMap<>());

	private NativeCallInlining() {
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
	 * Plans and registers the inlines {@code mixin}'s injectors need. {@code code} returns a class's current pre-mixin
	 * bytes WITH code (so it reflects a plan registered a moment ago); {@code natives} the class an ecosystem's mods were
	 * compiled against. Returns how many plans this call registered.
	 */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> code, BiFunction<Ecosystem, String, ClassNode> natives,
			ForbricClassLoader loader) {
		if (!enabled() || mixin == null || loader == null || code == null || natives == null) return 0;
		Registry registry = registry(loader);
		remember(registry, mixin);
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		if (ecosystem == null) return 0;
		int planned = 0;
		for (String target : MixinFit.mixinTargets(mixin)) {
			ClassNode original = natives.apply(ecosystem, target);
			if (original == null) continue;
			ClassNode merged = null;
			for (MethodNode handler : List.copyOf(mixin.methods)) {
				AnnotationNode injector = MixinFit.injectorOf(handler);
				if (injector == null || MixinFit.value(injector, "slice") != null) continue;
				for (AnnotationNode at : MixinFit.atNodes(injector)) {
					if (merged == null) merged = code.apply(target);
					if (merged == null) break;
					Outcome outcome = derive(merged, original, handler, at, code, others(registry, mixin.name), mixin.name, ecosystem,
							owner -> loader.findResource(owner + ".class") != null, owner -> raw(loader, owner));
					if (outcome.plan() != null) {
						if (register(loader, registry, outcome.plan())) {
							planned++;
							merged = null;  // read again: the plan just changed what the class's methods hold
							ForbricLog.info("[Forbric/Mixin] %s.%s: %s is made in %s.%s again — the merged game makes it inside %s, now "
									+ "inlined at its call there, so the injector binds where %s binds it", mixin.name.replace('/', '.'),
									handler.name, outcome.plan().anchor(), target.replace('/', '.'), outcome.plan().method(),
									chain(outcome.plan()), ecosystem);
						} else {
							ForbricLog.warn("[Forbric/Mixin] %s.%s: %s stays inside %s — a class it needs changed is already defined",
									mixin.name.replace('/', '.'), handler.name, outcome.plan().anchor(), chain(outcome.plan()));
						}
					} else if (outcome.declined() != null) {
						ForbricLog.info("[Forbric/Mixin] %s.%s: the merged %s.%s makes fewer calls of %s than %s does, and none is "
								+ "put back: %s", mixin.name.replace('/', '.'), handler.name, target.replace('/', '.'),
								MixinFit.asString(MixinFit.value(at, "target")), MixinFit.asString(MixinFit.value(at, "target")),
								ecosystem, outcome.declined());
					}
				}
			}
		}
		return planned;
	}

	private static String chain(Plan plan) {
		List<String> names = new ArrayList<>();
		for (Step step : plan.steps()) names.add(step.calleeOwner().replace('/', '.') + "." + step.calleeMethod().substring(0, step.calleeMethod().indexOf('(')));
		return String.join(" → ", names);
	}

	/** Anchors and added methods of every mixin but {@code mixin} recorded so far. */
	private static Conflicts others(Registry registry, String mixin) {
		return new Conflicts(registry, mixin);
	}

	/** What other mixins would lose or change if a call of a method were inlined away. */
	static final class Conflicts {
		private final Registry registry;
		private final String self;

		private Conflicts(Registry registry, String self) {
			this.registry = registry;
			this.self = self;
		}

		static Conflicts none() {
			return new Conflicts(new Registry(), "");
		}

		/** Another mixin's injector point names a call of {@code nameAndDesc} in {@code target}. */
		String anchoredBy(String target, String nameAndDesc) {
			for (Anchor anchor : registry.anchors)
				if (!anchor.mixin().equals(self) && anchor.target().equals(target) && anchor.nameAndDesc().equals(nameAndDesc)) return anchor.mixin();
			return null;
		}

		/** Another mixin injects into, or merges into, the method {@code nameAndDesc} of {@code target}: inlining it would bypass that. */
		String changes(String target, String nameAndDesc) {
			String name = nameAndDesc.substring(0, nameAndDesc.indexOf('(')), desc = nameAndDesc.substring(nameAndDesc.indexOf('('));
			for (Bound bound : registry.bound)
				if (!bound.mixin().equals(self) && bound.target().equals(target) && bound.name().equals(name)
						&& (bound.desc() == null || bound.desc().equals(desc))) return bound.mixin();
			return addedBy(target, nameAndDesc);
		}

		/** Another mixin merges a method {@code nameAndDesc} into {@code target}. */
		String addedBy(String target, String nameAndDesc) {
			for (Added added : registry.added)
				if (!added.mixin().equals(self) && added.target().equals(target) && added.nameAndDesc().equals(nameAndDesc)) return added.mixin();
			return null;
		}
	}

	private static void remember(Registry registry, ClassNode mixin) {
		for (String target : MixinFit.mixinTargets(mixin)) {
			for (MethodNode method : mixin.methods) {
				AnnotationNode injector = MixinFit.injectorOf(method);
				if (injector != null) {
					for (String selector : MixinFit.stringList(MixinFit.value(injector, "method"))) {
						String spelled = selector.replace(" ", "");
						if (spelled.startsWith("L") && spelled.contains(";")) spelled = spelled.substring(spelled.indexOf(';') + 1);
						int paren = spelled.indexOf('(');
						String name = paren < 0 ? spelled : spelled.substring(0, paren), desc = paren < 0 ? null : spelled.substring(paren);
						if (!name.isEmpty()) registry.bound.add(new Bound(mixin.name, target, name, desc));
					}
					for (AnnotationNode at : MixinFit.atNodes(injector)) {
						MixinFit.Member member = MixinFit.parseMember(MixinFit.asString(MixinFit.value(at, "target")));
						if (member == null || member.name() == null) continue;
						registry.anchors.add(new Anchor(mixin.name, target, member.name() + (member.desc() == null ? "" : member.desc())));
						if (member.desc() == null) registry.anchors.add(new Anchor(mixin.name, target, member.name()));
					}
					continue;
				}
				if (method.name.startsWith("<") || (method.access & Opcodes.ACC_ABSTRACT) != 0 || annotated(method, "Lorg/spongepowered/asm/mixin/Shadow;"))
					continue;
				registry.added.add(new Added(mixin.name, target, method.name + method.desc));
			}
		}
	}

	private static boolean annotated(MethodNode method, String desc) {
		for (List<AnnotationNode> list : java.util.Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations))
			if (list != null) for (AnnotationNode annotation : list) if (annotation.desc.equals(desc)) return true;
		return false;
	}

	/**
	 * The inlines that put back the calls {@code at} selects natively in the method {@code handler} binds, or why there are
	 * none (null for both when nothing is missing or the point is not one this reads).
	 */
	static Outcome derive(ClassNode merged, ClassNode original, MethodNode handler, AnnotationNode at, Function<String, ClassNode> code,
			Conflicts conflicts, String source, Ecosystem ecosystem, java.util.function.Predicate<String> owned, Function<String, ClassNode> raw) {
		String kind = MixinFit.asString(MixinFit.value(at, "value"));
		if (!"INVOKE".equals(kind) && !"INVOKE_ASSIGN".equals(kind)) return Outcome.NONE;
		MethodNode was = MixinTargetSelectors.one(handler, original), host = MixinTargetSelectors.one(handler, merged);
		if (was == null || host == null || !was.name.equals(host.name) || !was.desc.equals(host.desc) || host.instructions.size() == 0)
			return Outcome.NONE;
		List<MethodInsnNode> wanted = MixinCallbackShape.calls(at, was);
		if (wanted.isEmpty() || MixinCallbackShape.calls(at, host).size() >= wanted.size()) return Outcome.NONE;
		// A method the merged game never runs (a carrier's forwarding stub, a vanilla overload nothing calls) gains nothing from
		// the call: the adapters that move an injector to the method that runs handle it.
		if (ecosystem != null && MergedBaseUncalledMethods.neverRuns(merged, host, ecosystem) != null) return Outcome.NONE;
		// A carrier's forwarding stub is left to MixinStubRebind, which moves the injector onto the overload that does the work,
		// so callers of either signature run it; inlining into the stub would serve the old signature's callers only.
		if (MixinStubRebind.isCarrierStub(merged, host)) return Outcome.NONE;
		Set<String> nativeCalls = new HashSet<>();
		for (AbstractInsnNode instruction : was.instructions) if (instruction instanceof MethodInsnNode call) nativeCalls.add(call.name + call.desc);
		Search search = new Search(merged, at, wanted.size(), nativeCalls, code, owned, raw);
		search.from(copy(host), null, List.of(), 0);
		if (search.solutions.isEmpty()) return new Outcome(null, search.reason());
		// Each call put back must be the native one: handed every local and parameter the native call is handed (a local
		// the inlines added is read only as what was stored in it, its debug name being the callee's), and made under every
		// condition the native one is made under — an inline that makes it on paths the native method does not (the
		// carrier's helper asks it in other cases) would run the injector there too.
		List<Set<String>> wantedInputs = inputs(original.name, was, MixinCallbackShape.calls(at, was), ordinal(at), Integer.MAX_VALUE);
		int fresh = host.maxLocals;
		Set<String> refused = new LinkedHashSet<>();
		List<Solution> kept = new ArrayList<>();
		for (Solution solution : search.solutions) {
			if (!covers(inputs(merged.name, solution.body(), MixinCallbackShape.calls(at, solution.body()), ordinal(at), fresh), wantedInputs)) {
				refused.add("the call put back is not handed the locals the native one is handed");
				continue;
			}
			String unguarded = unguarded(original.name, was, merged.name, solution.body(), at, fresh);
			if (unguarded != null) { refused.add(unguarded); continue; }
			kept.add(solution);
		}
		if (kept.isEmpty()) return new Outcome(null, String.join("; ", refused));
		int fewest = kept.stream().mapToInt(s -> s.steps().size()).min().orElseThrow();
		List<Solution> best = kept.stream().filter(s -> s.steps().size() == fewest).toList();
		if (best.size() != 1) return new Outcome(null, best.size() + " different inlines would each put the call back");
		Solution solution = best.getFirst();
		for (Step step : solution.steps()) {
			String member = step.member().substring(step.member().indexOf(';') + 1);
			String other = conflicts.anchoredBy(merged.name, member);
			if (other == null) other = conflicts.anchoredBy(merged.name, member.substring(0, member.indexOf('(')));
			if (other != null) return new Outcome(null, "mixin " + other.replace('/', '.') + " anchors an injector on " + member + ", which the inline removes");
			if (step.guarded()) {
				String overriding = conflicts.addedBy(merged.name, step.calleeMethod());
				if (overriding != null)
					return new Outcome(null, "mixin " + overriding.replace('/', '.') + " adds " + step.calleeMethod() + " to the class");
			}
			String changing = conflicts.changes(step.calleeOwner(), step.calleeMethod());
			if (changing != null)
				return new Outcome(null, "mixin " + changing.replace('/', '.') + " changes " + step.calleeOwner().replace('/', '.') + "."
						+ step.calleeMethod() + ", which the inline would bypass");
		}
		String anchor = MixinCallbackShape.invoked(at, was);
		return new Outcome(new Plan(merged.name, host.name + host.desc, solution.steps(), solution.widen(), source, handler.name,
				anchor == null ? MixinFit.asString(MixinFit.value(at, "target")) : anchor), null);
	}

	/**
	 * Why the calls {@code at} selects in {@code inlined} are not made under every branch condition the native ones are,
	 * or null. A call's conditions are the branches before it of which exactly one way out reaches it, each read as an
	 * expression over the method's own values (its parameters, its locals by debug name, field reads, calls and
	 * constants); every condition of a native call must also hold the inlined call at the same position. The carrier may
	 * add conditions of its own (its events, the exact-class check).
	 */
	private static String unguarded(String nativeOwner, MethodNode was, String owner, MethodNode inlined, AnnotationNode at, int fresh) {
		List<MethodInsnNode> before = MixinCallbackShape.calls(at, was), after = MixinCallbackShape.calls(at, inlined);
		if (before.size() != after.size()) return "the inline makes another number of calls";
		Conditions nativeConditions = Conditions.of(nativeOwner, was, Integer.MAX_VALUE), mergedConditions = Conditions.of(owner, inlined, fresh);
		if (nativeConditions == null || mergedConditions == null) return "the conditions of the call cannot be read";
		int ordinal = ordinal(at);
		for (int i = 0; i < before.size(); i++) {
			if (ordinal >= 0 && i != ordinal) continue;
			Set<String> wanted = nativeConditions.of(before.get(i)), found = mergedConditions.of(after.get(i));
			for (String guard : wanted)
				if (!found.contains(guard)) return "the call put back is not made under the native condition " + guard;
		}
		return null;
	}

	/**
	 * The branch conditions that decide whether each instruction of a method is reached: the branches every path from the
	 * method's entry to the instruction passes (exception edges included), of which exactly one way out reaches it.
	 */
	static final class Conditions {
		private final MethodNode method;
		private final Frame<SourceValue>[] frames;
		private final List<AbstractInsnNode> real;
		private final Map<AbstractInsnNode, Integer> index = new IdentityHashMap<>();
		private final List<List<Integer>> successors = new ArrayList<>();
		private final int fresh;

		private Conditions(MethodNode method, Frame<SourceValue>[] frames, int fresh) {
			this.method = method;
			this.frames = frames;
			this.fresh = fresh;
			this.real = new ArrayList<>();
			for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) { index.put(instruction, real.size()); real.add(instruction); }
			for (int i = 0; i < real.size(); i++) successors.add(successors(real.get(i)));
			// An instruction inside a try block may leave for its handler: that is a way in to the handler, and a way around
			// every branch after it in the block.
			if (method.tryCatchBlocks != null) for (TryCatchBlockNode block : method.tryCatchBlocks) {
				int handler = target(block.handler), from = target(block.start), to = target(block.end);
				if (handler < 0 || from < 0) continue;
				for (int i = from; i < (to < 0 ? real.size() : to); i++) if (!successors.get(i).contains(handler)) successors.get(i).add(handler);
			}
		}

		static Conditions of(String owner, MethodNode method, int fresh) {
			Frame<SourceValue>[] frames = frames(owner, method);
			return frames == null ? null : new Conditions(method, frames, fresh);
		}

		private List<Integer> successors(AbstractInsnNode instruction) {
			List<Integer> out = new ArrayList<>();
			int opcode = instruction.getOpcode();
			if (instruction instanceof JumpInsnNode jump) {
				out.add(target(jump.label));
				if (opcode != Opcodes.GOTO) out.add(next(instruction));
			} else if (instruction instanceof TableSwitchInsnNode table) {
				out.add(target(table.dflt));
				for (LabelNode label : table.labels) out.add(target(label));
			} else if (instruction instanceof LookupSwitchInsnNode lookup) {
				out.add(target(lookup.dflt));
				for (LabelNode label : lookup.labels) out.add(target(label));
			} else if (!(opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW)) {
				out.add(next(instruction));
			}
			out.removeIf(i -> i < 0);
			return out;
		}

		private int next(AbstractInsnNode instruction) {
			for (AbstractInsnNode at = instruction.getNext(); at != null; at = at.getNext()) if (at.getOpcode() >= 0) return index.get(at);
			return -1;
		}

		private int target(LabelNode label) {
			for (AbstractInsnNode at = label; at != null; at = at.getNext()) if (at.getOpcode() >= 0) return index.get(at);
			return -1;
		}

		/** Whether a path leads from {@code from} to {@code to} without passing {@code avoid} (-1: none). */
		private boolean reaches(int from, int to, int avoid) {
			java.util.BitSet seen = new java.util.BitSet();
			java.util.ArrayDeque<Integer> work = new java.util.ArrayDeque<>();
			work.add(from);
			while (!work.isEmpty()) {
				int at = work.poll();
				if (at == avoid) continue;
				if (at == to) return true;
				if (seen.get(at)) continue;
				seen.set(at);
				work.addAll(successors.get(at));
			}
			return false;
		}

		/** The conditions that decide whether {@code instruction} is reached, as strings comparable across methods. */
		Set<String> of(AbstractInsnNode instruction) {
			Set<String> out = new TreeSet<>();
			Integer at = index.get(instruction);
			if (at == null || real.isEmpty()) return out;
			for (int i = 0; i < real.size(); i++) {
				if (i == at || !(real.get(i) instanceof JumpInsnNode branch) || branch.getOpcode() == Opcodes.GOTO || branch.getOpcode() == Opcodes.JSR) continue;
				List<Integer> ways = List.of(target(branch.label), next(branch)); // its own two ways out, not the handlers'
				if (ways.contains(-1) || ways.get(0).equals(ways.get(1))) continue;
				// Only a branch every way to the instruction passes decides it: one that a path from the entry goes around
				// holds on some ways in and not on others.
				if (reaches(0, at, i)) continue;
				// Each way is followed without coming back through the branch, which would only ask it again.
				boolean taken = reaches(ways.get(0), at, i), other = reaches(ways.get(1), at, i);
				if (taken == other) continue;
				int opcode = branch.getOpcode();
				boolean positive = taken;
				// The same condition written with the inverse test reads the same: IFNE x reaching = IFEQ x not reaching.
				if (opcode == Opcodes.IFNE || opcode == Opcodes.IFGE || opcode == Opcodes.IFLE || opcode == Opcodes.IF_ICMPNE
						|| opcode == Opcodes.IF_ICMPGE || opcode == Opcodes.IF_ICMPLE || opcode == Opcodes.IF_ACMPNE || opcode == Opcodes.IFNONNULL) {
					opcode--;
					positive = !positive;
				}
				Frame<SourceValue> frame = frames[method.instructions.indexOf(branch)];
				if (frame == null) continue;
				int operands = opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ACMPNE ? 2 : 1;
				StringBuilder text = new StringBuilder().append(opcode).append(positive ? "+" : "-").append('(');
				for (int k = operands - 1; k >= 0; k--) text.append(expression(frame.getStack(frame.getStackSize() - 1 - k), 0)).append(k == 0 ? "" : ",");
				out.add(text.append(')').toString());
			}
			return out;
		}

		/** The leaves of the operands {@code call} is handed: see {@link NativeCallInlining#inputs}. */
		Set<String> leaves(MethodInsnNode call) {
			Set<String> out = new TreeSet<>();
			Frame<SourceValue> frame = frames[method.instructions.indexOf(call)];
			if (frame == null) return Set.of("?");
			int operands = Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
			for (int k = 0; k < operands && k < frame.getStackSize(); k++)
				leaves(frame.getStack(frame.getStackSize() - 1 - k), out, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
			return out;
		}

		private void leaves(SourceValue value, Set<String> out, Set<AbstractInsnNode> seen, int depth) {
			if (value == null || depth > 12) return;
			if (value.insns.size() != 1) { if (!value.insns.isEmpty()) out.add("join"); return; }
			AbstractInsnNode producer = value.insns.iterator().next();
			if (!seen.add(producer)) return;
			Frame<SourceValue> frame = frames[method.instructions.indexOf(producer)];
			if (frame == null) return;
			if (producer instanceof VarInsnNode load && load.getOpcode() >= Opcodes.ILOAD && load.getOpcode() <= Opcodes.ALOAD) {
				SourceValue local = frame.getLocal(load.var);
				if (local.insns.isEmpty()) { out.add(parameter(load.var)); return; }
				if (load.var < fresh) { out.add("local " + expression(value, 0)); return; }
				for (AbstractInsnNode store : local.insns) {
					Frame<SourceValue> before = frames[method.instructions.indexOf(store)];
					if (before != null && store instanceof VarInsnNode written && written.getOpcode() >= Opcodes.ISTORE && written.getOpcode() <= Opcodes.ASTORE)
						leaves(before.getStack(before.getStackSize() - 1), out, seen, depth + 1);
				}
				return;
			}
			int consumed = consumed(producer);
			for (int k = 0; k < consumed && k < frame.getStackSize(); k++) leaves(frame.getStack(frame.getStackSize() - 1 - k), out, seen, depth + 1);
		}

		/** A slot that holds a parameter on entry: {@code this}, or the parameter's position. */
		private String parameter(int slot) {
			if ((method.access & Opcodes.ACC_STATIC) == 0 && slot == 0) return "this";
			int at = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0, position = 0;
			for (Type type : Type.getArgumentTypes(method.desc)) {
				if (at == slot) return "parameter " + position;
				at += type.getSize();
				position++;
			}
			return "slot " + slot;
		}

		private String expression(SourceValue value, int depth) {
			if (value == null || depth > 8) return "?";
			if (value.insns.size() != 1) return value.insns.isEmpty() ? "?" : "join";
			AbstractInsnNode producer = value.insns.iterator().next();
			Frame<SourceValue> frame = frames[method.instructions.indexOf(producer)];
			if (producer instanceof VarInsnNode load && load.getOpcode() >= Opcodes.ILOAD && load.getOpcode() <= Opcodes.ALOAD) {
				SourceValue local = frame == null ? null : frame.getLocal(load.var);
				if (local != null && local.insns.size() == 1 && local.insns.iterator().next() instanceof VarInsnNode store
						&& store.getOpcode() >= Opcodes.ISTORE && store.getOpcode() <= Opcodes.ASTORE) {
					Frame<SourceValue> before = frames[method.instructions.indexOf(store)];
					if (before != null) return expression(before.getStack(before.getStackSize() - 1), depth + 1);
				}
				return local != null && local.insns.isEmpty() ? parameter(load.var) : "local";
			}
			if (frame == null) return "?";
			if (producer instanceof MethodInsnNode call) {
				int count = Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
				StringBuilder text = new StringBuilder(call.name).append(call.desc).append('(');
				for (int k = count - 1; k >= 0; k--) text.append(expression(frame.getStack(frame.getStackSize() - 1 - k), depth + 1)).append(k == 0 ? "" : ",");
				return text.append(')').toString();
			}
			if (producer instanceof FieldInsnNode field) {
				if (field.getOpcode() == Opcodes.GETSTATIC) return field.owner + "." + field.name;
				if (field.getOpcode() == Opcodes.GETFIELD) return field.name + "(" + expression(frame.getStack(frame.getStackSize() - 1), depth + 1) + ")";
			}
			if (producer instanceof LdcInsnNode ldc) return String.valueOf(ldc.cst);
			if (producer instanceof org.objectweb.asm.tree.IntInsnNode number) return String.valueOf(number.operand);
			if (producer.getOpcode() >= Opcodes.ACONST_NULL && producer.getOpcode() <= Opcodes.DCONST_1) return "const" + producer.getOpcode();
			if (producer instanceof TypeInsnNode type) return type.getOpcode() + type.desc + (type.getOpcode() == Opcodes.NEW ? "" : "(" + expression(frame.getStack(frame.getStackSize() - 1), depth + 1) + ")");
			return "op" + producer.getOpcode();
		}
	}

	private static int ordinal(AnnotationNode at) {
		return MixinFit.value(at, "ordinal") instanceof Number number ? number.intValue() : -1;
	}

	private record Solution(List<Step> steps, MethodNode body, Map<String, Set<String>> widen) {
	}

	/** Breadth of inline sequences tried from one method, recording those that leave the point selecting enough calls. */
	private static final class Search {
		final ClassNode owner;
		final AnnotationNode at;
		final int wanted;
		final Set<String> nativeCalls;
		final Function<String, ClassNode> code;
		final java.util.function.Predicate<String> owned;
		final Function<String, ClassNode> raw;
		final List<Solution> solutions = new ArrayList<>();
		final Set<String> refusals = new LinkedHashSet<>();
		int tried;

		Search(ClassNode owner, AnnotationNode at, int wanted, Set<String> nativeCalls, Function<String, ClassNode> code,
				java.util.function.Predicate<String> owned, Function<String, ClassNode> raw) {
			this.raw = raw;
			this.owner = owner;
			this.at = at;
			this.wanted = wanted;
			this.nativeCalls = nativeCalls;
			this.code = code;
			this.owned = owned;
		}

		String reason() {
			if (tried == 0) return "the merged method makes no call of its own a known body could stand in for";
			return refusals.isEmpty() ? "no inline of the merged method's own calls makes the native number of calls"
					: String.join("; ", refusals);
		}

		void from(MethodNode body, Region region, List<Step> steps, int depth) {
			if (solutions.size() >= MAX_SOLUTIONS) return;
			List<AbstractInsnNode> instructions = List.of(body.instructions.toArray());
			int from = region == null ? 0 : instructions.indexOf(region.first()), to = region == null ? instructions.size() - 1 : instructions.indexOf(region.last());
			if (from < 0 || to < 0) return;
			for (int i = from; i <= to; i++) {
				if (!(instructions.get(i) instanceof MethodInsnNode call) || call.name.startsWith("<") || nativeCalls.contains(call.name + call.desc))
					continue;
				Callee callee = resolve(owner, body, call, code);
				// Only the game's and its carriers' own code is inlined, never a library's or the JDK's.
				if (callee == null || owned != null && !owned.test(callee.owner().name)) continue;
				tried++;
				String refused = refusal(callee);
				// The calls the callee makes must be its own: a call the kernel's transforms put into it (an instrumentation
				// argument, a seam's selected wrapper) is not the native call, and a method they added has no body of its own.
				if (refused == null && raw != null) {
					ClassNode original = raw.apply(callee.owner().name);
					MethodNode own = original == null ? null : method(original, callee.method().name + callee.method().desc);
					if (own == null) refused = callee.owner().name.replace('/', '.') + "." + callee.method().name + " is not the carrier's own method";
					else if (MixinCallbackShape.calls(at, own).size() != MixinCallbackShape.calls(at, callee.method()).size())
						refused = callee.owner().name.replace('/', '.') + "." + callee.method().name + " makes the call only as the kernel instrumented it";
				}
				if (refused != null) {
					refusals.add(refused);
					continue;
				}
				String member = member(call);
				int occurrence = occurrence(body, call);
				MethodNode next = copy(body);
				MethodInsnNode same = nth(next, member, call.getOpcode(), occurrence);
				Inlined inlined = same == null ? null : inline(owner, next, same, callee, code);
				if (inlined == null || inlined.refused() != null) {
					if (inlined != null) refusals.add(inlined.refused());
					continue;
				}
				List<Step> path = new ArrayList<>(steps);
				path.add(new Step(member, call.getOpcode(), occurrence, callee.owner().name, callee.method().name + callee.method().desc,
						MixinInstructionFingerprint.hash(callee.method()), callee.exact(), callee.guarded()));
				int count = MixinCallbackShape.calls(at, next).size();
				if (DEBUG) ForbricLog.info("[Forbric/Mixin] native call inlining: depth %d, %s#%d in %s → %d of %d call(s)", depth, member, occurrence,
						callee.owner().name + "." + callee.method().name, count, wanted);
				if (count == wanted) {
					Map<String, Set<String>> widen = new HashMap<>();
					String blocked = access(owner, next, code, widen);
					if (blocked != null) refusals.add(blocked);
					else solutions.add(new Solution(List.copyOf(path), next, freeze(widen)));
				} else if (count < wanted && depth + 1 < MAX_DEPTH) {
					from(next, inlined.region(), path, depth + 1);
				}
			}
		}
	}

	private static Map<String, Set<String>> freeze(Map<String, Set<String>> widen) {
		Map<String, Set<String>> out = new LinkedHashMap<>();
		for (var entry : widen.entrySet()) out.put(entry.getKey(), Set.copyOf(new TreeSet<>(entry.getValue())));
		return Map.copyOf(out);
	}

	/**
	 * What each selected call (all of them, or the one {@code ordinal} picks) is handed, as its leaves: the method's own
	 * receiver and parameters, and each local of the method the operands read, identified by the expression stored in
	 * it — never by its slot or debug name, which differ between two builds of a method. A local the inlines added
	 * (a slot from {@code fresh} on) is no leaf: what was stored in it is read instead.
	 */
	private static List<Set<String>> inputs(String owner, MethodNode method, List<MethodInsnNode> calls, int ordinal, int fresh) {
		List<MethodInsnNode> picked = ordinal >= 0 ? (ordinal < calls.size() ? List.of(calls.get(ordinal)) : List.of()) : calls;
		Conditions values = Conditions.of(owner, method, fresh);
		List<Set<String>> out = new ArrayList<>();
		for (MethodInsnNode call : picked) out.add(values == null ? Set.of("?") : values.leaves(call));
		return out;
	}

	/** Each wanted call's leaves are among the inlined call's at the same position. */
	private static boolean covers(List<Set<String>> found, List<Set<String>> wanted) {
		if (found.size() != wanted.size()) return false;
		for (int i = 0; i < wanted.size(); i++) if (wanted.get(i).contains("?") || !found.get(i).containsAll(wanted.get(i))) return false;
		return true;
	}

	/** How many stack values an instruction that produces a value reads (calls, field reads, casts; others read none here). */
	private static int consumed(AbstractInsnNode producer) {
		if (producer instanceof MethodInsnNode call)
			return Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
		if (producer instanceof FieldInsnNode field) return field.getOpcode() == Opcodes.GETFIELD ? 1 : 0;
		if (producer instanceof TypeInsnNode type) return type.getOpcode() == Opcodes.CHECKCAST ? 1 : 0;
		int opcode = producer.getOpcode();
		if (opcode >= Opcodes.IADD && opcode <= Opcodes.DREM || opcode >= Opcodes.ISHL && opcode <= Opcodes.LXOR
				|| opcode >= Opcodes.LCMP && opcode <= Opcodes.DCMPG) return 2;
		if (opcode >= Opcodes.INEG && opcode <= Opcodes.DNEG || opcode >= Opcodes.I2L && opcode <= Opcodes.I2S) return 1;
		return 0;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Dispatch: which body a call runs

	/** The body a call runs, the class it is declared in, and how its dispatch is known. */
	record Callee(ClassNode owner, MethodNode method, String exact, boolean guarded) {
	}

	/** The concrete body {@code call} runs in {@code host}, when its dispatch is proved; null otherwise. */
	static Callee resolve(ClassNode hostClass, MethodNode host, MethodInsnNode call, Function<String, ClassNode> code) {
		switch (call.getOpcode()) {
			case Opcodes.INVOKESTATIC: {
				Declared declared = declaredMethod(code, hostClass, call.owner, call.name, call.desc, true);
				return declared == null ? null : new Callee(declared.owner(), declared.method(), null, false);
			}
			case Opcodes.INVOKESPECIAL: {
				if (!call.owner.equals(hostClass.name)) return null;
				MethodNode method = method(hostClass, call.name + call.desc);
				return method == null || (method.access & Opcodes.ACC_PRIVATE) == 0 ? null : new Callee(hostClass, method, null, false);
			}
			case Opcodes.INVOKEVIRTUAL:
			case Opcodes.INVOKEINTERFACE: {
				Exact exact = exactReceiver(hostClass, host, call, code);
				if (exact == null) return null;
				Declared declared = virtualTarget(code, hostClass, exact.type(), call.name, call.desc);
				if (declared == null) return null;
				return new Callee(declared.owner(), declared.method(), exact.type(), exact.guarded());
			}
			default:
				return null;
		}
	}

	/** Why a resolved callee cannot be inlined, or null. */
	private static String refusal(Callee callee) {
		MethodNode method = callee.method();
		if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || method.instructions.size() == 0)
			return callee.owner().name.replace('/', '.') + "." + method.name + " has no body";
		if ((method.access & Opcodes.ACC_SYNCHRONIZED) != 0)
			return callee.owner().name.replace('/', '.') + "." + method.name + " is synchronized";
		int real = 0;
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction.getOpcode() < 0) continue;
			real++;
			if (instruction.getOpcode() == Opcodes.JSR || instruction.getOpcode() == Opcodes.RET)
				return callee.owner().name.replace('/', '.') + "." + method.name + " uses subroutines";
			if (instruction instanceof MethodInsnNode inner && inner.getOpcode() == Opcodes.INVOKESPECIAL && !inner.name.equals("<init>"))
				return callee.owner().name.replace('/', '.') + "." + method.name + " makes a non-virtual call of its own class";
		}
		if (real > MAX_CALLEE_INSNS) return callee.owner().name.replace('/', '.') + "." + method.name + " is too large to inline";
		return null;
	}

	private record Declared(ClassNode owner, MethodNode method) {
	}

	private record Exact(String type, boolean guarded) {
	}

	private static ClassNode node(Function<String, ClassNode> code, ClassNode current, String name) {
		if (name == null) return null;
		if (current != null && current.name.equals(name)) return current;
		try {
			return code.apply(name);
		} catch (RuntimeException unreadable) {
			return null;
		}
	}

	/** A static method as {@code invokestatic owner.name desc} resolves it: declared in owner or a superclass. */
	private static Declared declaredMethod(Function<String, ClassNode> code, ClassNode current, String owner, String name, String desc,
			boolean statics) {
		String at = owner;
		for (int hop = 0; at != null && hop < 32; hop++) {
			ClassNode node = node(code, current, at);
			if (node == null) return null;
			MethodNode method = method(node, name + desc);
			if (method != null) return ((method.access & Opcodes.ACC_STATIC) != 0) == statics ? new Declared(node, method) : null;
			at = node.superName;
		}
		return null;
	}

	/** The method an instance of exactly {@code type} runs for {@code name desc}: its own, an inherited one, or one default. */
	private static Declared virtualTarget(Function<String, ClassNode> code, ClassNode current, String type, String name, String desc) {
		String at = type;
		List<String> interfaces = new ArrayList<>();
		for (int hop = 0; at != null && hop < 32; hop++) {
			ClassNode node = node(code, current, at);
			if (node == null) return null;
			MethodNode method = method(node, name + desc);
			if (method != null) {
				if ((method.access & Opcodes.ACC_STATIC) != 0) return null;
				return (method.access & Opcodes.ACC_ABSTRACT) != 0 ? null : new Declared(node, method);
			}
			if (node.interfaces != null) interfaces.addAll(node.interfaces);
			at = node.superName;
		}
		Declared found = null;
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < interfaces.size() && i < 256; i++) {
			String face = interfaces.get(i);
			if (!seen.add(face)) continue;
			ClassNode node = node(code, current, face);
			if (node == null) return null;
			MethodNode method = method(node, name + desc);
			if (method != null && (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0) {
				if (found != null && found.owner() != node) return null;
				found = new Declared(node, method);
			}
			if (node.interfaces != null) interfaces.addAll(node.interfaces);
		}
		return found;
	}

	/** The exact class of {@code call}'s receiver, when it is proved, and whether it must still be checked at run time. */
	private static Exact exactReceiver(ClassNode hostClass, MethodNode host, MethodInsnNode call, Function<String, ClassNode> code) {
		Frame<SourceValue>[] frames = frames(hostClass.name, host);
		int index = host.instructions.indexOf(call);
		if (frames == null || index < 0 || frames[index] == null) return null;
		Frame<SourceValue> frame = frames[index];
		int operands = Type.getArgumentTypes(call.desc).length + 1;
		if (frame.getStackSize() < operands) return null;
		AbstractInsnNode origin = origin(host, frames, frame.getStack(frame.getStackSize() - operands), 0);
		if (origin == THIS) {
			boolean guarded = (hostClass.access & Opcodes.ACC_FINAL) == 0;
			return new Exact(hostClass.name, guarded);
		}
		if (origin instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW) return new Exact(allocation.desc, false);
		if (origin instanceof MethodInsnNode factory && factory.getOpcode() == Opcodes.INVOKESTATIC) {
			Declared declared = declaredMethod(code, hostClass, factory.owner, factory.name, factory.desc, true);
			String made = declared == null ? null : madeType(declared.owner().name, declared.method());
			if (made != null) return new Exact(made, false);
		}
		return null;
	}

	/** The one class every return of {@code method} returns a new instance of; null when that is not proved. */
	private static String madeType(String owner, MethodNode method) {
		if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return null;
		Frame<SourceValue>[] frames = frames(owner, method);
		if (frames == null) return null;
		String made = null;
		int returns = 0;
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction.getOpcode() != Opcodes.ARETURN) continue;
			returns++;
			Frame<SourceValue> frame = frames[method.instructions.indexOf(instruction)];
			if (frame == null) continue;
			AbstractInsnNode origin = origin(method, frames, frame.getStack(frame.getStackSize() - 1), 0);
			if (!(origin instanceof TypeInsnNode allocation) || allocation.getOpcode() != Opcodes.NEW) return null;
			if (made != null && !made.equals(allocation.desc)) return null;
			made = allocation.desc;
		}
		return returns == 0 ? null : made;
	}

	private static final AbstractInsnNode THIS = new InsnNode(Opcodes.NOP);

	/**
	 * Where a value comes from, through copies, local stores and loads, and casts: the producing instruction, {@link #THIS}
	 * for a method's own receiver that is never stored over, or null where several paths meet or the trail is lost.
	 */
	private static AbstractInsnNode origin(MethodNode method, Frame<SourceValue>[] frames, SourceValue value, int depth) {
		if (value == null || depth > 24) return null;
		if (value.insns.size() != 1) return null;
		AbstractInsnNode producer = value.insns.iterator().next();
		int index = method.instructions.indexOf(producer);
		Frame<SourceValue> frame = index < 0 ? null : frames[index];
		if (frame == null) return null;
		switch (producer.getOpcode()) {
			case Opcodes.DUP:
				return origin(method, frames, frame.getStack(frame.getStackSize() - 1), depth + 1);
			case Opcodes.CHECKCAST:
				return origin(method, frames, frame.getStack(frame.getStackSize() - 1), depth + 1);
			case Opcodes.ALOAD: {
				int slot = ((VarInsnNode) producer).var;
				SourceValue local = frame.getLocal(slot);
				if (local.insns.isEmpty()) return (method.access & Opcodes.ACC_STATIC) == 0 && slot == 0 && !stores(method, 0) ? THIS : null;
				if (local.insns.size() != 1) return null;
				AbstractInsnNode store = local.insns.iterator().next();
				int storeIndex = method.instructions.indexOf(store);
				Frame<SourceValue> before = storeIndex < 0 ? null : frames[storeIndex];
				if (before == null || store.getOpcode() != Opcodes.ASTORE) return null;
				return origin(method, frames, before.getStack(before.getStackSize() - 1), depth + 1);
			}
			default:
				return producer;
		}
	}

	private static boolean stores(MethodNode method, int slot) {
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof VarInsnNode variable && variable.var == slot && variable.getOpcode() >= Opcodes.ISTORE
					&& variable.getOpcode() <= Opcodes.ASTORE) return true;
			if (instruction instanceof IincInsnNode increment && increment.var == slot) return true;
		}
		return false;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The inline itself

	/** The inserted stretch of a body. */
	private record Region(AbstractInsnNode first, AbstractInsnNode last) {
	}

	private record Inlined(Region region, String refused) {
		static Inlined refuse(String why) {
			return new Inlined(null, why);
		}
	}

	/**
	 * Replaces {@code call} in {@code host} (of class {@code hostClass}) with {@code callee}'s body. Returns the inserted
	 * region, a refusal, or null when the call is unreachable.
	 */
	static Inlined inline(ClassNode hostClass, MethodNode host, MethodInsnNode call, Callee callee, Function<String, ClassNode> code) {
		Frame<SourceValue>[] frames = frames(hostClass.name, host);
		int index = host.instructions.indexOf(call);
		if (frames == null || index < 0 || frames[index] == null) return null;
		Frame<SourceValue> frame = frames[index];
		MethodNode body = callee.method();
		boolean instance = call.getOpcode() != Opcodes.INVOKESTATIC;
		Type[] arguments = Type.getArgumentTypes(call.desc);
		int count = arguments.length + (instance ? 1 : 0);
		Type[] parameters = new Type[count];
		for (int i = 0; i < count; i++) parameters[i] = instance ? (i == 0 ? Type.getObjectType(call.owner) : arguments[i - 1]) : arguments[i];
		int below = frame.getStackSize() - count;
		if (below < 0) return null;
		if (!body.tryCatchBlocks.isEmpty() && below != 0)
			return Inlined.refuse(callee.owner().name.replace('/', '.') + "." + body.name + " catches exceptions where values wait on the stack");
		int[] slots = new int[count];
		for (int i = 0, slot = 0; i < count; i++) {
			slots[i] = slot;
			slot += parameters[i].getSize();
		}
		// A parameter the call site hands as a plain read of a local that nothing writes before the call, and that the
		// callee never writes, is read from that local.
		int[] alias = new int[count];
		boolean hostInstance = (host.access & Opcodes.ACC_STATIC) == 0;
		boolean[] isThis = new boolean[count];
		for (int i = 0; i < count; i++) {
			alias[i] = -1;
			SourceValue handed = frame.getStack(below + i);
			if (handed.insns.size() != 1 || !(handed.insns.iterator().next() instanceof VarInsnNode load)) continue;
			if (load.getOpcode() != parameters[i].getOpcode(Opcodes.ILOAD) || stores(body, slots[i])) continue;
			if (!straight(host, load, call, load.var)) continue;
			alias[i] = load.var;
			isThis[i] = hostInstance && load.var == 0 && !stores(host, 0);
		}
		// The callee's instance calls on a parameter that is the host's own receiver name the host's class (dispatch is the same).
		Map<AbstractInsnNode, Integer> receivers = receiverParameters(callee.owner().name, body, slots);
		int base = host.maxLocals;
		IntMap remap = slot -> {
			for (int i = 0; i < count; i++) if (slots[i] == slot && alias[i] >= 0) return alias[i];
			return base + slot;
		};
		InsnList code0 = new InsnList();
		for (int i = count - 1; i >= 0; i--) {
			if (alias[i] >= 0) code0.add(new InsnNode(parameters[i].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
			else code0.add(new VarInsnNode(parameters[i].getOpcode(Opcodes.ISTORE), base + slots[i]));
		}
		LabelNode end = new LabelNode(), slow = callee.guarded() ? new LabelNode() : null;
		if (callee.guarded()) {
			code0.add(new VarInsnNode(Opcodes.ALOAD, alias[0] >= 0 ? alias[0] : base + slots[0]));
			code0.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
			code0.add(new LdcInsnNode(Type.getObjectType(callee.exact())));
			code0.add(new JumpInsnNode(Opcodes.IF_ACMPNE, slow));
		}
		Map<LabelNode, LabelNode> labels = new IdentityHashMap<>();
		for (AbstractInsnNode instruction : body.instructions) if (instruction instanceof LabelNode label) labels.put(label, new LabelNode());
		LabelNode begin = new LabelNode();
		code0.add(begin);
		for (AbstractInsnNode instruction : body.instructions) {
			if (instruction instanceof LineNumberNode || instruction instanceof FrameNode) continue;
			int opcode = instruction.getOpcode();
			if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
				code0.add(new JumpInsnNode(Opcodes.GOTO, end));
				continue;
			}
			AbstractInsnNode copy = instruction.clone(labels);
			if (copy instanceof VarInsnNode variable) variable.var = remap.apply(variable.var);
			else if (copy instanceof IincInsnNode increment) increment.var = remap.apply(increment.var);
			else if (copy instanceof MethodInsnNode inner && inner.getOpcode() == Opcodes.INVOKEVIRTUAL && receivers.containsKey(instruction)) {
				int parameter = receivers.get(instruction);
				if (parameter >= 0 && isThis[parameter] && !inner.owner.equals(hostClass.name) && subtype(code, hostClass, hostClass.name, inner.owner))
					inner.owner = hostClass.name;
			}
			code0.add(copy);
		}
		if (callee.guarded()) {
			code0.add(slow);
			for (int i = 0; i < count; i++) code0.add(new VarInsnNode(parameters[i].getOpcode(Opcodes.ILOAD), alias[i] >= 0 ? alias[i] : base + slots[i]));
			code0.add(new MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf));
		}
		code0.add(end);
		List<TryCatchBlockNode> handlers = new ArrayList<>();
		for (TryCatchBlockNode block : body.tryCatchBlocks)
			handlers.add(new TryCatchBlockNode(labels.get(block.start), labels.get(block.end), labels.get(block.handler), block.type));
		if (body.localVariables != null) {
			if (host.localVariables == null) host.localVariables = new ArrayList<>();
			for (LocalVariableNode local : body.localVariables) {
				boolean aliased = false;
				for (int i = 0; i < count; i++) if (slots[i] == local.index && alias[i] >= 0) aliased = true;
				LabelNode start = labels.get(local.start), stop = labels.get(local.end);
				if (aliased || start == null || stop == null) continue;
				host.localVariables.add(new LocalVariableNode(local.name, local.desc, local.signature, start, stop, base + local.index));
			}
		}
		host.instructions.insert(call, code0);
		host.instructions.remove(call);
		host.tryCatchBlocks.addAll(0, handlers);
		host.maxLocals = Math.max(host.maxLocals, base + Math.max(body.maxLocals, slots.length == 0 ? 0 : slots[count - 1] + parameters[count - 1].getSize()));
		int argumentSize = 0;
		for (Type parameter : parameters) argumentSize += parameter.getSize();
		host.maxStack = Math.max(host.maxStack, below + Math.max(body.maxStack, argumentSize + 2) + 2);
		return new Inlined(new Region(begin, end), null);
	}

	private interface IntMap {
		int apply(int slot);
	}

	/** Whether {@code from} reaches {@code to} straight, with no write of {@code slot} and no jump target in between. */
	private static boolean straight(MethodNode method, AbstractInsnNode from, AbstractInsnNode to, int slot) {
		Set<LabelNode> targets = jumpTargets(method);
		for (AbstractInsnNode at = from.getNext(); at != null && at != to; at = at.getNext()) {
			if (at instanceof LabelNode label && targets.contains(label)) return false;
			if (at instanceof JumpInsnNode || at instanceof TableSwitchInsnNode || at instanceof LookupSwitchInsnNode) return false;
			if (at instanceof VarInsnNode variable && variable.var == slot && variable.getOpcode() >= Opcodes.ISTORE && variable.getOpcode() <= Opcodes.ASTORE)
				return false;
			if (at instanceof IincInsnNode increment && increment.var == slot) return false;
			int opcode = at.getOpcode();
			if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW) return false;
		}
		return true;
	}

	private static Set<LabelNode> jumpTargets(MethodNode method) {
		Set<LabelNode> targets = Collections.newSetFromMap(new IdentityHashMap<>());
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof JumpInsnNode jump) targets.add(jump.label);
			else if (instruction instanceof TableSwitchInsnNode table) { targets.add(table.dflt); targets.addAll(table.labels); }
			else if (instruction instanceof LookupSwitchInsnNode lookup) { targets.add(lookup.dflt); targets.addAll(lookup.labels); }
		}
		for (TryCatchBlockNode block : method.tryCatchBlocks) targets.add(block.handler);
		return targets;
	}

	/** For each instance call of {@code body}, the parameter its receiver is (its value on entry, never written), else -1. */
	private static Map<AbstractInsnNode, Integer> receiverParameters(String owner, MethodNode body, int[] slots) {
		Map<AbstractInsnNode, Integer> out = new IdentityHashMap<>();
		Frame<SourceValue>[] frames = frames(owner, body);
		if (frames == null) return out;
		for (AbstractInsnNode instruction : body.instructions) {
			if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
			Frame<SourceValue> frame = frames[body.instructions.indexOf(instruction)];
			if (frame == null) continue;
			int operands = Type.getArgumentTypes(call.desc).length + 1;
			SourceValue receiver = frame.getStack(frame.getStackSize() - operands);
			int parameter = -1;
			if (receiver.insns.size() == 1 && receiver.insns.iterator().next() instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
				Frame<SourceValue> at = frames[body.instructions.indexOf(load)];
				if (at != null && at.getLocal(load.var).insns.isEmpty() && !stores(body, load.var))
					for (int i = 0; i < slots.length; i++) if (slots[i] == load.var) parameter = i;
			}
			out.put(instruction, parameter);
		}
		return out;
	}

	private static boolean subtype(Function<String, ClassNode> code, ClassNode current, String type, String ancestor) {
		String at = type;
		for (int hop = 0; at != null && hop < 32; hop++) {
			if (at.equals(ancestor)) return true;
			ClassNode node = node(code, current, at);
			if (node == null) return false;
			at = node.superName;
		}
		return false;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Access: what the inlined code names, as seen from its new class

	/**
	 * Checks every member and class the body of {@code method} names from {@code owner}; records in {@code widen} the
	 * fields and static methods to make public, and returns why it cannot be inlined, or null.
	 */
	private static String access(ClassNode owner, MethodNode method, Function<String, ClassNode> code, Map<String, Set<String>> widen) {
		for (AbstractInsnNode instruction : method.instructions) {
			String blocked = null;
			if (instruction instanceof FieldInsnNode field) blocked = field(owner, field.owner, field.name, field.desc, code, widen);
			else if (instruction instanceof MethodInsnNode call) blocked = method(owner, call.owner, call.name, call.desc, call.getOpcode(), code, widen);
			else if (instruction instanceof TypeInsnNode type) blocked = type(owner, type.desc, code);
			else if (instruction instanceof MultiANewArrayInsnNode array) blocked = type(owner, array.desc, code);
			else if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Type type && type.getSort() == Type.OBJECT)
				blocked = type(owner, type.getInternalName(), code);
			else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
				for (Object argument : dynamic.bsmArgs) {
					if (!(argument instanceof Handle handle)) continue;
					int tag = handle.getTag();
					if (tag == Opcodes.H_INVOKESTATIC || tag == Opcodes.H_INVOKEVIRTUAL || tag == Opcodes.H_INVOKEINTERFACE)
						blocked = method(owner, handle.getOwner(), handle.getName(), handle.getDesc(),
								tag == Opcodes.H_INVOKESTATIC ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL, code, widen);
					else if (tag == Opcodes.H_NEWINVOKESPECIAL)
						blocked = method(owner, handle.getOwner(), handle.getName(), handle.getDesc(), Opcodes.INVOKESPECIAL, code, widen);
					else if (!handle.getOwner().equals(owner.name)) blocked = "a method handle of " + handle.getOwner().replace('/', '.') + " it cannot reach";
					if (blocked != null) break;
				}
			}
			if (blocked != null) return blocked;
		}
		return null;
	}

	private static String type(ClassNode from, String desc, Function<String, ClassNode> code) {
		String internal = desc;
		while (internal.startsWith("[")) internal = internal.substring(1);
		if (internal.startsWith("L") && internal.endsWith(";")) internal = internal.substring(1, internal.length() - 1);
		if (internal.length() == 1) return null;
		if (internal.startsWith("java/")) return null;
		ClassNode node = node(code, from, internal);
		if (node == null) return null;  // a library class: public API by construction, or the verifier says so later
		return classReachable(from, node) ? null : internal.replace('/', '.') + " is not public";
	}

	private static boolean classReachable(ClassNode from, ClassNode node) {
		return (node.access & Opcodes.ACC_PUBLIC) != 0 || pkg(node.name).equals(pkg(from.name));
	}

	private static String field(ClassNode from, String owner, String name, String desc, Function<String, ClassNode> code,
			Map<String, Set<String>> widen) {
		String at = owner;
		for (int hop = 0; at != null && hop < 32; hop++) {
			if (at.startsWith("java/")) return null;
			ClassNode node = node(code, from, at);
			if (node == null) return null;
			for (FieldNode field : node.fields) {
				if (!field.name.equals(name) || !field.desc.equals(desc)) continue;
				if (reachable(from, node, field.access, code)) return null;
				widen.computeIfAbsent(node.name, ignored -> new TreeSet<>()).add("F " + name + " " + desc);
				return null;
			}
			at = node.superName;
		}
		return null;
	}

	private static String method(ClassNode from, String owner, String name, String desc, int opcode, Function<String, ClassNode> code,
			Map<String, Set<String>> widen) {
		String at = owner;
		for (int hop = 0; at != null && hop < 32; hop++) {
			if (at.startsWith("java/")) return null;
			ClassNode node = node(code, from, at);
			if (node == null) return null;
			MethodNode method = method(node, name + desc);
			if (method != null) {
				if (reachable(from, node, method.access, code)) return null;
				if (name.equals("<init>") || (method.access & Opcodes.ACC_STATIC) == 0)
					return node.name.replace('/', '.') + "." + name + " is not reachable from " + from.name.replace('/', '.');
				widen.computeIfAbsent(node.name, ignored -> new TreeSet<>()).add("M " + name + desc);
				return null;
			}
			if (opcode == Opcodes.INVOKESPECIAL || name.equals("<init>")) return null;
			at = node.superName;
		}
		return null;
	}

	private static boolean reachable(ClassNode from, ClassNode declaring, int access, Function<String, ClassNode> code) {
		if (declaring.name.equals(from.name)) return true;
		if (!classReachable(from, declaring)) return false;
		if ((access & Opcodes.ACC_PUBLIC) != 0) return true;
		if ((access & Opcodes.ACC_PRIVATE) != 0) return false;
		if (pkg(declaring.name).equals(pkg(from.name))) return true;
		return (access & Opcodes.ACC_PROTECTED) != 0 && subtype(code, from, from.name, declaring.name);
	}

	private static String pkg(String internal) {
		int slash = internal.lastIndexOf('/');
		return slash < 0 ? "" : internal.substring(0, slash);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Registration and the class transform

	private static boolean register(ForbricClassLoader loader, Registry registry, Plan plan) {
		for (Plan existing : registry.plans) if (existing.sameInlines(plan)) return true;
		List<String> owners = new ArrayList<>(new TreeSet<>(plan.widen().keySet()));
		if (!owners.contains(plan.host())) owners.add(plan.host());
		java.util.Collections.sort(owners);
		return registerAll(loader, owners, 0, () -> registry.plans.add(plan));
	}

	private static boolean registerAll(ForbricClassLoader loader, List<String> owners, int at, Runnable action) {
		if (at == owners.size()) {
			action.run();
			return true;
		}
		boolean[] done = {false};
		return loader.registerBeforeDefinition(owners.get(at).replace('/', '.'), () -> done[0] = registerAll(loader, owners, at + 1, action)) && done[0];
	}

	/**
	 * The pre-mixin bytes of {@code binary} with every registered inline of its methods applied and its members another
	 * class's inline needs made public. Bytes are returned unchanged where no plan names the class.
	 */
	public static byte[] transform(ClassLoader loader, String binary, byte[] bytes) {
		if (bytes == null) return null;
		Registry registry = REGISTRIES.get(loader);
		if (registry == null || registry.plans.isEmpty()) return bytes;
		String internal = binary.replace('.', '/');
		List<Plan> hosted = new ArrayList<>(), widening = new ArrayList<>();
		for (Plan plan : registry.plans) {
			if (plan.host().equals(internal)) hosted.add(plan);
			if (plan.widen().containsKey(internal)) widening.add(plan);
		}
		if (hosted.isEmpty() && widening.isEmpty()) return bytes;
		Set<String> busy = TRANSFORMING.get();
		if (!busy.add(internal)) return bytes;
		try {
			return transform(loader, binary, bytes, registry, internal, hosted, widening);
		} finally {
			busy.remove(internal);
		}
	}

	/** Classes this thread is transforming: a callee read while its own transform is under way is not read again. */
	private static final ThreadLocal<Set<String>> TRANSFORMING = ThreadLocal.withInitial(HashSet::new);

	private static byte[] transform(ClassLoader loader, String binary, byte[] bytes, Registry registry, String internal, List<Plan> hosted,
			List<Plan> widening) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, hosted.isEmpty() ? 0 : ClassReader.SKIP_FRAMES);
		for (Plan plan : widening) widen(node, plan.widen().get(internal));
		boolean inlined = false;
		Function<String, ClassNode> code = owner -> owner.equals(internal) ? node : current(loader, owner);
		for (Plan plan : hosted) {
			String declined = apply(node, plan, code, others(registry, plan.source()));
			if (declined == null) inlined = true;
			else ForbricLog.warn("[Forbric/Mixin] %s.%s: left %s inside %s — %s", plan.source().replace('/', '.'), plan.handler(),
					plan.anchor(), chain(plan), declined);
		}
		if (!inlined && widening.isEmpty()) return bytes;
		try {
			ClassWriter writer;
			if (inlined) {
				MergedBaseFrameRecomputer hierarchy = new MergedBaseFrameRecomputer(path -> resource(loader, path));
				writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
					@Override protected String getCommonSuperClass(String a, String b) {
						return hierarchy.commonSuperClass(a, b);
					}
				};
			} else {
				writer = new ClassWriter(0);
			}
			node.accept(writer);
			return writer.toByteArray();
		} catch (RuntimeException unwritable) {
			ForbricLog.warn("[Forbric/Mixin] could not write " + binary + " with its native calls put back; it stays as merged", unwritable);
			return bytes;
		}
	}

	/** Applies one plan's inlines to {@code node}, all or none; returns why not, or null. */
	private static String apply(ClassNode node, Plan plan, Function<String, ClassNode> code, Conflicts conflicts) {
		MethodNode method = method(node, plan.method());
		if (method == null) return "its method is gone";
		MethodNode work = copy(method);
		for (Step step : plan.steps()) {
			String member = step.member().substring(step.member().indexOf(';') + 1);
			String other = conflicts.anchoredBy(node.name, member);
			if (other == null) other = conflicts.anchoredBy(node.name, member.substring(0, member.indexOf('(')));
			if (other != null) return "mixin " + other.replace('/', '.') + " anchors an injector on " + member + ", which the inline would remove";
			if (step.guarded() && conflicts.addedBy(node.name, step.calleeMethod()) != null)
				return "mixin " + conflicts.addedBy(node.name, step.calleeMethod()).replace('/', '.') + " adds " + step.calleeMethod() + " to the class";
			if (conflicts.changes(step.calleeOwner(), step.calleeMethod()) != null)
				return "mixin " + conflicts.changes(step.calleeOwner(), step.calleeMethod()).replace('/', '.') + " changes "
						+ step.calleeOwner().replace('/', '.') + "." + step.calleeMethod() + ", which the inline would bypass";
			MethodInsnNode call = nth(work, step.member(), step.opcode(), step.occurrence());
			if (call == null) return "the call of " + member + " is gone";
			ClassNode calleeOwner = code.apply(step.calleeOwner());
			MethodNode callee = calleeOwner == null ? null : method(calleeOwner, step.calleeMethod());
			if (callee == null || !MixinInstructionFingerprint.hash(callee).equals(step.calleeHash()))
				return step.calleeOwner().replace('/', '.') + "." + step.calleeMethod() + " is not the body the plan read";
			Inlined inlined = inline(node, work, call, new Callee(calleeOwner, callee, step.exactType(), step.guarded()), code);
			if (inlined == null || inlined.refused() != null) return inlined == null ? "the call is unreachable" : inlined.refused();
		}
		node.methods.set(node.methods.indexOf(method), work);
		return null;
	}

	private static void widen(ClassNode node, Set<String> members) {
		if (members == null) return;
		for (String member : members) {
			if (member.startsWith("F ")) {
				String[] parts = member.split(" ");
				for (FieldNode field : node.fields)
					if (field.name.equals(parts[1]) && field.desc.equals(parts[2])) field.access = publicAccess(field.access);
			} else if (member.startsWith("M ")) {
				MethodNode method = method(node, member.substring(2));
				if (method != null && (method.access & Opcodes.ACC_STATIC) != 0) method.access = publicAccess(method.access);
			}
		}
	}

	private static int publicAccess(int access) {
		return (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
	}

	private static ClassNode current(ClassLoader loader, String owner) {
		if (!(loader instanceof ForbricClassLoader forbric) || TRANSFORMING.get().contains(owner)) return null;
		byte[] bytes = forbric.getPreMixinClassBytes(owner.replace('/', '.'));
		if (bytes == null) return null;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	/** A class as its jar has it, before any kernel transform; null when the loader's own jars do not have it. */
	private static ClassNode raw(ForbricClassLoader loader, String owner) {
		try (InputStream in = loader.getGameResourceAsStream(owner + ".class")) {
			if (in == null) return null;
			ClassNode node = new ClassNode();
			new ClassReader(in.readAllBytes()).accept(node, 0);
			return node;
		} catch (IOException | RuntimeException unreadable) {
			return null;
		}
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
			return null;
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Small helpers

	static MethodNode method(ClassNode node, String nameAndDesc) {
		if (node == null || node.methods == null) return null;
		for (MethodNode method : node.methods) if ((method.name + method.desc).equals(nameAndDesc)) return method;
		return null;
	}

	private static String member(MethodInsnNode call) {
		return "L" + call.owner + ";" + call.name + call.desc;
	}

	private static int occurrence(MethodNode body, MethodInsnNode call) {
		int n = 0;
		for (AbstractInsnNode instruction : body.instructions) {
			if (instruction == call) return n;
			if (instruction instanceof MethodInsnNode other && other.getOpcode() == call.getOpcode() && member(other).equals(member(call))) n++;
		}
		return -1;
	}

	private static MethodInsnNode nth(MethodNode body, String member, int opcode, int occurrence) {
		int n = 0;
		for (AbstractInsnNode instruction : body.instructions) {
			if (instruction instanceof MethodInsnNode call && call.getOpcode() == opcode && member(call).equals(member)) {
				if (n == occurrence) return call;
				n++;
			}
		}
		return null;
	}

	static MethodNode copy(MethodNode method) {
		MethodNode copy = new MethodNode(Opcodes.ASM9, method.access, method.name, method.desc, method.signature,
				method.exceptions == null ? null : method.exceptions.toArray(String[]::new));
		method.accept(copy);
		copy.maxLocals = method.maxLocals;
		copy.maxStack = method.maxStack;
		return copy;
	}

	@SuppressWarnings("unchecked")
	private static Frame<SourceValue>[] frames(String owner, MethodNode method) {
		try {
			return new Analyzer<>(new SourceInterpreter()).analyze(owner, method);
		} catch (AnalyzerException | RuntimeException unanalysable) {
			return null;
		}
	}
}
