/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.CarrierMethodReferences.Symbolic;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * A mod's {@code HEAD} injector on a static decision method the merged game no longer calls, moved around the decision the
 * carrier makes in its place, at each place the native game asked the method — so it runs where its own loader runs it, on the
 * values its own loader hands it, and its answer is the answer there.
 *
 * <p>Vanilla's {@code RedStoneWireBlock.getConnectingSide} asks the static {@code shouldConnectTo(state, direction)} whether
 * the wire connects to a neighbour — once directly and twice through {@code shouldConnectTo(state)}, which only forwards
 * {@code (state, null)}. NeoForge asks the neighbour's {@code state.canRedstoneConnectTo(level, pos, direction)} at those three
 * places instead, and nothing in the merged game calls {@code shouldConnectTo} any more. A cancellable {@code @Inject} at its
 * {@code HEAD} (Create's redstone-connection hook, which answers for every block of its own that connects) binds and never
 * runs: no Create block ever connects to redstone.
 *
 * <p>Moved, each such place runs the injector first, handed exactly the values the native method was handed there, and then
 * — unless it cancelled, which answers the place with its value — the carrier's own decision, as before. With no mod's
 * injector nothing changes; with one, the place answers what natively the method would have answered for it.
 *
 * <p>Nothing here knows a mod, a class or a method by name. An injector moves when, read off the class the mod was compiled
 * against (the native reference of its ecosystem) and the merged one:
 * <ul>
 *   <li>it is an {@code @Inject} whose one point is {@code HEAD}, with no slice, no {@code @Group} and no captured locals, its
 *       handler static and taking the method's arguments and its callback (or the callback alone);</li>
 *   <li>its selectors bind one static, value-returning method, the same natively and merged, that the census of uncalled
 *       methods lists for the mod's ecosystem ({@link MergedBaseUncalledMethods}) and that no installed mod calls;</li>
 *   <li>every native caller the census lists is in the same class: either itself listed as uncalled (followed to its own
 *       callers — a method that only forwards to the decision with constants, read through when the callers are), or a
 *       method the merged class still has, which is a host;</li>
 *   <li>in each host, the merged body makes calls of one method the native body never makes (the carrier's decision, of the
 *       decision's return type), and reads, path for path, exactly as the native body reads with every native call of the
 *       decision (inlined forwarders included) replaced by a call of the carrier's — the native call's every argument being
 *       one operand of the carrier's call, the same value on every path ({@link Symbolic}: both bodies as trees of tests over
 *       their inputs, compared whole) — and no call of the carrier's decision discards its answer.</li>
 * </ul>
 * The handler keeps its body under its name with {@code $forbricOriginal} appended (and, once every rule has run, its mixin's
 * mark: {@link MixinHandlerShim#markAsides}), without the annotation. A new handler per
 * host, a MixinExtras {@code @WrapOperation} of every call of the carrier's decision there, hands it the native arguments from
 * the call's operands and a fresh callback (cancellable as the injector was); a cancelled callback answers the call with its
 * value, otherwise the carrier's call is made as it was. {@code -Dforbric.uncalledDecisionEntries=off} moves nothing.
 */
public final class UncalledDecisionEntries {
	public static final String PROPERTY = "forbric.uncalledDecisionEntries";
	static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
	static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
	static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
	static final String CIR = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
	static final String SUFFIX = "$forbricentry";
	/** How many uncalled callers deep the native callers are followed. */
	private static final int DEPTH = 4;
	/** The most assignments of the decision's arguments to the carrier's operands that are tried. */
	private static final int ASSIGNMENTS = 64;

	/** The census, as this rule reads it: the native callers of a method that never runs for the ecosystem's mods; null when it may run. */
	interface Census {
		List<String> uncalledCallers(ClassNode merged, MethodNode method, Ecosystem ecosystem);
	}

	private UncalledDecisionEntries() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** The shipped census, re-checked against the installed mods and the live bytes. */
	static final Census CENSUS = (merged, method, ecosystem) -> {
		if (MergedBaseUncalledMethods.neverRuns(merged, method, ecosystem) == null) return null;
		MergedBaseUncalledMethods.Row row = MergedBaseUncalledMethods.find(merged.name, method.name + method.desc, ecosystem);
		return row == null ? null : row.callers();
	};

	/** Moves every eligible injector of {@code mixin}; returns how many. {@code code} must return nodes with code. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> code, BiFunction<Ecosystem, String, ClassNode> natives) {
		return adapt(mixin, code, natives, CENSUS);
	}

	static int adapt(ClassNode mixin, Function<String, ClassNode> code, BiFunction<Ecosystem, String, ClassNode> natives, Census census) {
		if (!enabled() || mixin == null || mixin.methods == null || code == null || natives == null || census == null) return 0;
		if ((mixin.access & Opcodes.ACC_INTERFACE) != 0) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		if (ecosystem == null) return 0;
		int moved = 0;
		for (String target : MixinFit.mixinTargets(mixin)) {
			ClassNode original = natives.apply(ecosystem, target), merged = null;
			if (original == null) continue;
			for (MethodNode handler : List.copyOf(mixin.methods)) {
				AnnotationNode injector = MixinFit.injectorOf(handler);
				if (injector == null || !INJECT.equals(injector.desc) || annotated(handler, CarrierDecisions.GROUP)
						|| MixinFit.value(injector, "slice") != null || MixinFit.value(injector, "locals") != null) continue;
				List<AnnotationNode> points = MixinFit.atNodes(injector);
				if (points.size() != 1 || !"HEAD".equals(MixinFit.asString(MixinFit.value(points.getFirst(), "value")))) continue;
				if (merged == null) merged = code.apply(target);
				if (merged == null) break;
				MethodNode decision = MixinTargetSelectors.one(handler, merged), was = MixinTargetSelectors.one(handler, original);
				if (decision == null || was == null || !decision.name.equals(was.name) || !decision.desc.equals(was.desc)) continue;
				if ((decision.access & Opcodes.ACC_STATIC) == 0 || Type.getReturnType(decision.desc).getSort() == Type.VOID) continue;
				if (!shaped(handler, decision)) continue;
				List<String> callers = census.uncalledCallers(merged, decision, ecosystem);
				if (callers == null) continue;
				StringBuilder why = new StringBuilder();
				List<Entry> entries = plan(target, original, merged, decision, callers, code, natives, ecosystem, census, why);
				if (entries == null) {
					ForbricLog.info("[Forbric/Mixin] %s.%s: %s.%s never runs for this mod's ecosystem, and its HEAD injector is not moved to "
									+ "where the native game asked it — %s", mixin.name.replace('/', '.'), handler.name, target.replace('/', '.'),
							decision.name, why);
					continue;
				}
				String name = handler.name;
				String failed = apply(mixin, handler, injector, decision, entries, code);
				if (failed != null) {
					// The plan proved the injector never runs where it binds and must move; a move that cannot be made loses a hook.
					ForbricLog.warn("[Forbric/Mixin] %s.%s: %s.%s never runs for this mod's ecosystem, and its HEAD injector was to wrap the "
									+ "carrier's decision in %s, but the move could not be made — %s; it stays where it binds, where nothing runs it",
							mixin.name.replace('/', '.'), name, target.replace('/', '.'), decision.name,
							String.join(", ", entries.stream().map(entry -> entry.host().name + entry.host().desc).toList()), failed);
					continue;
				}
				moved++;
				for (Entry entry : entries) {
					ForbricLog.info("[Forbric/Mixin] %s.%s: %s.%s is no longer called; the carrier decides with %s.%s in %s where the native %s "
									+ "asked it — the HEAD injector now wraps that call (%d place(s)), handed the native arguments, and answers it "
									+ "when it cancels", mixin.name.replace('/', '.'), name, target.replace('/', '.'), decision.name,
							entry.carrier().owner.replace('/', '.'), entry.carrier().name, entry.host().name, entry.host().name, entry.places());
				}
			}
		}
		return moved;
	}

	/** One host: the merged method, the carrier's call standing for the decision there, and which of its operands is which argument. */
	record Entry(MethodNode host, MethodInsnNode carrier, int[] operands, int places) {
	}

	/** A static handler taking the decision's arguments then its callback, or the callback alone, with no sugar after. */
	private static boolean shaped(MethodNode handler, MethodNode decision) {
		if ((handler.access & Opcodes.ACC_STATIC) == 0) return false;
		Type[] parameters = Type.getArgumentTypes(handler.desc), arguments = Type.getArgumentTypes(decision.desc);
		if (!Type.getReturnType(handler.desc).equals(Type.VOID_TYPE) || parameters.length == 0
				|| !parameters[parameters.length - 1].getInternalName().equals(CIR)) return false;
		if (parameters.length != 1 && parameters.length != arguments.length + 1) return false;
		for (int i = 0; i + 1 < parameters.length; i++) if (!parameters[i].equals(arguments[i])) return false;
		for (List<AnnotationNode>[] table : Arrays.asList(handler.visibleParameterAnnotations, handler.invisibleParameterAnnotations))
			if (table != null) for (List<AnnotationNode> annotations : table) if (annotations != null && !annotations.isEmpty()) return false;
		return true;
	}

	/** Every host and its carrier decision; null, with {@code why} told, when any native caller cannot be read so. */
	static List<Entry> plan(String target, ClassNode original, ClassNode merged, MethodNode decision, List<String> callers,
			Function<String, ClassNode> code, BiFunction<Ecosystem, String, ClassNode> natives, Ecosystem ecosystem, Census census, StringBuilder why) {
		// The native callers, through every caller that is itself uncalled: those are read through (inlined) where their own
		// callers are compared; the rest are the hosts.
		Set<String> hosts = new LinkedHashSet<>(), seen = new HashSet<>();
		seen.add(decision.name + decision.desc);
		if (!hosts(target, original, merged, callers, ecosystem, census, hosts, seen, 0, why)) return null;
		if (hosts.isEmpty()) return declined(why, "the census lists no native caller of it");
		Function<String, ClassNode> nativeCode = name -> {
			ClassNode node = natives.apply(ecosystem, name);
			return node != null ? node : code.apply(name);
		};
		List<Entry> entries = new ArrayList<>();
		for (String key : hosts) {
			MethodNode nativeHost = declared(original, key), mergedHost = declared(merged, key);
			if (nativeHost == null || mergedHost == null) return declined(why, "the native caller " + key + " is not in both classes");
			Entry entry = entry(target, decision, nativeHost, mergedHost, nativeCode, code, why);
			if (entry == null) return null;
			entries.add(entry);
		}
		return entries;
	}

	private static boolean hosts(String target, ClassNode original, ClassNode merged, List<String> callers, Ecosystem ecosystem, Census census,
			Set<String> hosts, Set<String> seen, int depth, StringBuilder why) {
		for (String caller : callers) {
			int hash = caller.indexOf('#');
			if (hash < 0 || !caller.substring(0, hash).equals(target)) {
				declined(why, "it is also asked from " + caller + ", outside the class the mixin targets");
				return false;
			}
			String key = caller.substring(hash + 1);
			if (!seen.add(key)) continue;
			MethodNode mergedCaller = declared(merged, key);
			List<String> further = mergedCaller == null ? null : census.uncalledCallers(merged, mergedCaller, ecosystem);
			if (further == null) {
				hosts.add(key);
				continue;
			}
			if (depth >= DEPTH) {
				declined(why, "its uncalled callers go deeper than " + DEPTH);
				return false;
			}
			if (!hosts(target, original, merged, further, ecosystem, census, hosts, seen, depth + 1, why)) return false;
		}
		return true;
	}

	/**
	 * The carrier's decision in {@code mergedHost}: the one method (and the one assignment of the decision's arguments to its
	 * operands) under which the merged body reads as the native one with the decision replaced; null, with {@code why} told,
	 * when there is none or more than one.
	 */
	private static Entry entry(String target, MethodNode decision, MethodNode nativeHost, MethodNode mergedHost, Function<String, ClassNode> nativeCode,
			Function<String, ClassNode> code, StringBuilder why) {
		if (((nativeHost.access ^ mergedHost.access) & Opcodes.ACC_STATIC) != 0) return declined(why, "the native and merged " + mergedHost.name + " differ in staticness");
		Type answer = Type.getReturnType(decision.desc);
		Type[] arguments = Type.getArgumentTypes(decision.desc);
		Set<String> nativeMembers = new HashSet<>();
		for (AbstractInsnNode instruction : nativeHost.instructions) if (instruction instanceof MethodInsnNode call) nativeMembers.add(member(call));
		Map<String, MethodInsnNode> candidates = new LinkedHashMap<>();
		for (AbstractInsnNode instruction : mergedHost.instructions) {
			if (!(instruction instanceof MethodInsnNode call) || call.name.startsWith("<") || nativeMembers.contains(member(call))
					|| !Type.getReturnType(call.desc).equals(answer)) continue;
			if (call.owner.equals(target) && call.name.equals(decision.name) && call.desc.equals(decision.desc)) continue;
			candidates.putIfAbsent(member(call), call);
		}
		if (candidates.isEmpty()) return declined(why, "the merged " + mergedHost.name + " makes no call of its own answering " + answer.getClassName());
		List<Symbolic.Val> inputs = inputs(target, mergedHost);
		Symbolic.Tree nativeTree = new Symbolic(nativeCode, false, null, (call, values) -> {
			if (call.getOpcode() != Opcodes.INVOKESTATIC || !call.owner.equals(target) || !call.name.equals(decision.name) || !call.desc.equals(decision.desc)) return null;
			return marked(values);
		}).run(nativeHost, inputs, 0);
		if (nativeTree == null) return declined(why, "the native " + nativeHost.name + " cannot be read as a tree of tests");
		Entry found = null;
		for (MethodInsnNode carrier : candidates.values()) {
			List<Type> operands = CarrierDecisions.operandTypes(carrier);
			for (int[] assignment : assignments(arguments, operands)) {
				Set<AbstractInsnNode> reached = new HashSet<>();
				Symbolic.Tree mergedTree = new Symbolic(code, false, null, (call, values) -> {
					if (!member(call).equals(member(carrier)) || call.getOpcode() != carrier.getOpcode()) return null;
					reached.add(call);
					List<Symbolic.Val> picked = new ArrayList<>();
					for (int index : assignment) picked.add(values.get(index));
					return marked(picked);
				}).run(mergedHost, inputs, 0);
				if (mergedTree == null || !mergedTree.equals(nativeTree)) continue;
				// Every call of the carrier's decision there is one the comparison read, and each one's answer is used.
				int places = 0;
				for (AbstractInsnNode instruction : mergedHost.instructions) {
					if (!(instruction instanceof MethodInsnNode call) || !member(call).equals(member(carrier))) continue;
					AbstractInsnNode next = call.getNext();
					while (next != null && next.getOpcode() < 0) next = next.getNext();
					if (!reached.contains(call) || next == null || next.getOpcode() == Opcodes.POP) return declined(why, "a call of "
							+ carrier.name + " in the merged " + mergedHost.name + " stands for no native call of the decision");
					places++;
				}
				if (found != null) return declined(why, "the merged " + mergedHost.name + " reads as the native one under more than one carrier call or assignment");
				found = new Entry(mergedHost, carrier, assignment, places);
			}
		}
		if (found == null) return declined(why, "the merged " + mergedHost.name + " does not read as the native one with the decision replaced by any call of its own");
		return found;
	}

	/** The host's inputs: its receiver (as its class), then its parameters. */
	private static List<Symbolic.Val> inputs(String owner, MethodNode host) {
		List<Symbolic.Val> out = new ArrayList<>();
		if ((host.access & Opcodes.ACC_STATIC) == 0) out.add(new Symbolic.Val(new Symbolic.E("input", "this", List.of()), owner));
		Type[] parameters = Type.getArgumentTypes(host.desc);
		for (int i = 0; i < parameters.length; i++) out.add(new Symbolic.Val(Symbolic.input(i), CarrierMethodReferences.typeName(parameters[i])));
		return out;
	}

	private static Symbolic.E marked(List<Symbolic.Val> values) {
		List<Symbolic.E> in = new ArrayList<>();
		for (Symbolic.Val value : values) in.add(value.e());
		return new Symbolic.E("decision", "", in);
	}

	/** Every injective assignment of the decision's arguments to the carrier call's operands of the same type, up to a bound. */
	static List<int[]> assignments(Type[] arguments, List<Type> operands) {
		List<int[]> out = new ArrayList<>();
		assign(arguments, operands, 0, new int[arguments.length], new boolean[operands.size()], out);
		return out;
	}

	private static void assign(Type[] arguments, List<Type> operands, int at, int[] chosen, boolean[] used, List<int[]> out) {
		if (out.size() >= ASSIGNMENTS) return;
		if (at == arguments.length) {
			out.add(chosen.clone());
			return;
		}
		for (int j = 0; j < operands.size(); j++) {
			if (used[j] || !operands.get(j).equals(arguments[at])) continue;
			used[j] = true;
			chosen[at] = j;
			assign(arguments, operands, at + 1, chosen, used, out);
			used[j] = false;
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The move

	/** Moves the injector; null when it moved, otherwise why it could not (and nothing of the mixin changed). */
	private static String apply(ClassNode mixin, MethodNode handler, AnnotationNode injector, MethodNode decision, List<Entry> entries,
			Function<String, ClassNode> code) {
		// The body keeps the handler's name with the rule's suffix; MixinHandlerShim.markAsides gives it the mixin's mark once
		// every rule has run, so a second mixin's same-named handler keeps a body of its own in the target.
		String name = handler.name, original = name + CarrierDecisions.SUFFIX;
		for (MethodNode other : mixin.methods)
			if (other.name.equals(original) || other.name.startsWith(name + SUFFIX)) return "the mixin already declares " + other.name;
		boolean cancellable = Boolean.TRUE.equals(MixinFit.value(injector, "cancellable"));
		List<MethodNode> wrappers = new ArrayList<>();
		for (int i = 0; i < entries.size(); i++) {
			StringBuilder why = new StringBuilder();
			MethodNode wrapper = wrapper(mixin, handler, original, decision, entries.get(i), cancellable, name + SUFFIX + (i == 0 ? "" : Integer.toString(i)), code, why);
			if (wrapper == null) return "the wrapper of " + entries.get(i).carrier().name + " in " + entries.get(i).host().name + " " + why;
			wrappers.add(wrapper);
		}
		boolean visible = handler.visibleAnnotations != null && handler.visibleAnnotations.remove(injector);
		if (!visible && handler.invisibleAnnotations != null) handler.invisibleAnnotations.remove(injector);
		handler.name = original;
		for (int i = 0; i < wrappers.size(); i++) {
			Entry entry = entries.get(i);
			AnnotationNode wrap = new AnnotationNode(WRAP), at = new AnnotationNode(AT);
			at.values = new ArrayList<>(List.of("value", "INVOKE", "target", member(entry.carrier())));
			wrap.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(entry.host().name + entry.host().desc)), "at", at));
			MethodNode wrapper = wrappers.get(i);
			if (visible) wrapper.visibleAnnotations = new ArrayList<>(List.of(wrap));
			else wrapper.invisibleAnnotations = new ArrayList<>(List.of(wrap));
			CurrentBodyOrdinals.mark(wrapper);
			mixin.methods.add(wrapper);
		}
		return null;
	}

	/**
	 * {@code (operands…, Operation) answer}: a fresh callback, the original handler handed the native arguments from the operands
	 * the assignment names, the callback's value when it was cancelled, else the carrier's call through the operation.
	 */
	private static MethodNode wrapper(ClassNode mixin, MethodNode handler, String original, MethodNode decision, Entry entry, boolean cancellable,
			String name, Function<String, ClassNode> code, StringBuilder why) {
		MethodInsnNode carrier = entry.carrier();
		Type answer = Type.getReturnType(decision.desc);
		List<Type> operands = CarrierDecisions.operandTypes(carrier);
		List<Type> parameters = new ArrayList<>(operands);
		parameters.add(Type.getObjectType(OPERATION));
		boolean isStatic = (entry.host().access & Opcodes.ACC_STATIC) != 0;
		MethodNode wrapper = new MethodNode(Opcodes.ACC_PRIVATE | (isStatic ? Opcodes.ACC_STATIC : 0), name,
				Type.getMethodDescriptor(answer, parameters.toArray(Type[]::new)), null, null);
		int[] slot = new int[parameters.size()];
		int next = isStatic ? 0 : 1;
		for (int i = 0; i < slot.length; i++) {
			slot[i] = next;
			next += parameters.get(i).getSize();
		}
		int callback = next++;
		InsnList body = wrapper.instructions;
		body.add(new TypeInsnNode(Opcodes.NEW, CIR));
		body.add(new InsnNode(Opcodes.DUP));
		body.add(new LdcInsnNode(decision.name));
		body.add(new InsnNode(cancellable ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
		body.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CIR, "<init>", "(Ljava/lang/String;Z)V", false));
		body.add(new VarInsnNode(Opcodes.ASTORE, callback));
		Type[] handed = Type.getArgumentTypes(handler.desc);
		if (handed.length > 1) for (int index : entry.operands()) body.add(new VarInsnNode(operands.get(index).getOpcode(Opcodes.ILOAD), slot[index]));
		body.add(new VarInsnNode(Opcodes.ALOAD, callback));
		body.add(MixinHandlerShim.callOwn(mixin, true, original, handler.desc));
		LabelNode call = new LabelNode();
		body.add(new VarInsnNode(Opcodes.ALOAD, callback));
		body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CIR, "isCancelled", "()Z", false));
		body.add(new JumpInsnNode(Opcodes.IFEQ, call));
		body.add(new VarInsnNode(Opcodes.ALOAD, callback));
		if (answer.getSort() == Type.OBJECT || answer.getSort() == Type.ARRAY) {
			body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CIR, "getReturnValue", "()Ljava/lang/Object;", false));
			body.add(new TypeInsnNode(Opcodes.CHECKCAST, answer.getInternalName()));
		} else body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CIR, "getReturnValue" + answer.getDescriptor(), "()" + answer.getDescriptor(), false));
		body.add(new InsnNode(answer.getOpcode(Opcodes.IRETURN)));
		body.add(call);
		body.add(new VarInsnNode(Opcodes.ALOAD, slot[operands.size()]));
		body.add(intConstant(operands.size()));
		body.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
		for (int i = 0; i < operands.size(); i++) {
			body.add(new InsnNode(Opcodes.DUP));
			body.add(intConstant(i));
			body.add(new VarInsnNode(operands.get(i).getOpcode(Opcodes.ILOAD), slot[i]));
			box(body, operands.get(i));
			body.add(new InsnNode(Opcodes.AASTORE));
		}
		body.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, OPERATION, "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true));
		unbox(body, answer);
		body.add(new InsnNode(answer.getOpcode(Opcodes.IRETURN)));
		wrapper.maxLocals = next;
		wrapper.maxStack = 8 + operands.size();
		try {
			new Analyzer<>(new BasicVerifier()).analyze(mixin.name, wrapper);
		} catch (AnalyzerException | RuntimeException unverifiable) {
			// The kernel's own generated code failing its verifier is a defect of this rule, not a shape it declines.
			Caught.warn(unverifiable, "[Forbric/Mixin] %s.%s: the wrapper this rule generated for %s in %s does not verify",
					mixin.name.replace('/', '.'), handler.name, entry.carrier().name, entry.host().name);
			why.append("does not verify (").append(unverifiable.getMessage()).append(')');
			return null;
		}
		if (CarrierDefaultChains.framed(mixin, wrapper, code)) return wrapper;
		why.append("could not be given stack map frames");
		return null;
	}

	private static AbstractInsnNode intConstant(int value) {
		if (value >= -1 && value <= 5) return new InsnNode(Opcodes.ICONST_0 + value);
		return new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, value);
	}

	private static void box(InsnList body, Type type) {
		Type boxed = boxed(type);
		if (boxed == null) return;
		body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, boxed.getInternalName(), "valueOf", "(" + type.getDescriptor() + ")" + boxed.getDescriptor(), false));
	}

	private static void unbox(InsnList body, Type type) {
		Type boxed = boxed(type);
		if (boxed == null) {
			body.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
			return;
		}
		body.add(new TypeInsnNode(Opcodes.CHECKCAST, boxed.getInternalName()));
		body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, boxed.getInternalName(), type.getClassName() + "Value", "()" + type.getDescriptor(), false));
	}

	private static Type boxed(Type type) {
		return switch (type.getSort()) {
			case Type.BOOLEAN -> Type.getObjectType("java/lang/Boolean");
			case Type.BYTE -> Type.getObjectType("java/lang/Byte");
			case Type.CHAR -> Type.getObjectType("java/lang/Character");
			case Type.SHORT -> Type.getObjectType("java/lang/Short");
			case Type.INT -> Type.getObjectType("java/lang/Integer");
			case Type.LONG -> Type.getObjectType("java/lang/Long");
			case Type.FLOAT -> Type.getObjectType("java/lang/Float");
			case Type.DOUBLE -> Type.getObjectType("java/lang/Double");
			default -> null;
		};
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static <T> T declined(StringBuilder why, String reason) {
		if (why != null && why.isEmpty()) why.append(reason);
		return null;
	}

	private static MethodNode declared(ClassNode type, String nameAndDesc) {
		for (MethodNode method : type.methods) if ((method.name + method.desc).equals(nameAndDesc)) return method;
		return null;
	}

	private static String member(MethodInsnNode call) {
		return "L" + call.owner + ";" + call.name + call.desc;
	}

	private static boolean annotated(MethodNode method, String desc) {
		for (List<AnnotationNode> list : Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations))
			if (list != null) for (AnnotationNode annotation : list) if (annotation.desc.equals(desc)) return true;
		return false;
	}
}
