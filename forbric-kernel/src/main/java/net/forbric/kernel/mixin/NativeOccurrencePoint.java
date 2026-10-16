/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.IInjectionPointContext;
import org.spongepowered.asm.mixin.injection.InjectionPoint;
import org.spongepowered.asm.mixin.injection.code.InsnListEx;
import org.spongepowered.asm.mixin.injection.struct.InjectionPointData;
import org.spongepowered.asm.mixin.injection.struct.Target;
import org.spongepowered.asm.mixin.transformer.ClassInfo;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * An injection point that selects, in the merged body, the occurrences of a guest's point that are the occurrences its
 * platform's own body has — however many more or fewer the carriers' code put there.
 *
 * <p>A mod's injection point is written for, and counted in, the method as its platform compiled it. The merged game
 * runs a body a carrier patched: a call, field access, local store or expression the guest points at may now occur more
 * often (NeoForge passes {@code getCameraType().isMirrored()} again as an argument of its detached-camera hook; it reads
 * a picked-up stack's count twice more to compute what was taken; MinecraftForge and NeoForge each store a bow's charge
 * again from their arrow-loose events), so an unnumbered point runs its handler more often than natively, on the same
 * path; or less often, or in another order, so a numbered point names a different occurrence.
 *
 * <p>The guest's own point is kept, unchanged, and asked twice — by Mixin's (or MixinExtras') own code, so whatever the
 * point is, it is read as its injector reads it: once over the merged body Mixin hands it, and once over the native body
 * of the same method (the hash-pinned bytes the merged-base builder emitted for the mod's platform), as a second target
 * of the same injector. Only when the two disagree in number does anything change. The native body and the merged body
 * are then aligned ({@link CodeAlignment}), and each native occurrence the point selects is mapped to the merged
 * instruction that is the same step:
 * <ul>
 * <li>a numbered point selects the counterpart of its native occurrence;</li>
 * <li>an unnumbered point selects the counterparts of every native occurrence, and of the occurrences only the merged body
 * has, every one that some execution can run without running a counterpart — a carrier that copied vanilla's code into
 * two exclusive branches, or decides the value itself on a path vanilla's code is skipped on, still runs the handler
 * there — but none that no execution can run without a counterpart: those would only ever run the handler a second time
 * where the native game runs it once;</li>
 * <li>a counterpart the point cannot bind where it stands (a {@code @ModifyVariable} whose local the merged body's
 * variable table does not yet cover there) is replaced by the one occurrence the point does bind that every path from the
 * counterpart reaches first.</li>
 * </ul>
 * Anything not proved this way — no native body, no counterpart, an unaligned stretch, a point that throws over the native
 * body — leaves the point exactly as Mixin would have applied it. The trigger is the bytecode only: what the point selects
 * in each body and how the two bodies align, never a mod, class, member or config name.
 * {@code -Dforbric.nativeOccurrences=off} turns the rewrite that installs this point off.
 */
@InjectionPoint.AtCode(namespace = NativeOccurrencePoint.NAMESPACE, value = NativeOccurrencePoint.CODE)
public final class NativeOccurrencePoint extends InjectionPoint {
	static final String NAMESPACE = "FORBRIC";
	static final String CODE = "NATIVE";
	static final String VALUE = NAMESPACE + ":" + CODE;
	/** The arg carrying the guest's own point type. */
	static final String POINT = "forbric.point";

	private static final Map<String, Boolean> LOGGED = new ConcurrentHashMap<>();
	private static final Map<String, ClassNode> NATIVE_CLASSES = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<String, ClassNode> eldest) {
			return size() > 48;
		}
	});

	private final IInjectionPointContext context;
	private final InjectionPoint all;
	private final int ordinal;
	private final String point;

	public NativeOccurrencePoint(InjectionPointData data) {
		super(data);
		this.context = data.getContext();
		this.point = data.get(POINT, "");
		this.ordinal = data.getOrdinal();
		this.all = inner(data, point);
	}

	/** The guest's own point, as it was written, but selecting every occurrence: the ordinal is applied here, per body. */
	private static InjectionPoint inner(InjectionPointData data, String point) {
		List<String> args = new ArrayList<>();
		for (Map.Entry<String, String> arg : InjectionPointDataFields.args(data).entrySet()) {
			if (!POINT.equals(arg.getKey())) args.add(arg.getKey() + "=" + arg.getValue());
		}
		return InjectionPoint.parse(data.getContext(), point, At.Shift.NONE, 0, args, InjectionPointDataFields.target(data),
				data.getSlice(), -1, data.getOpcode(), data.getId(), InjectionPointDataFields.flags(data));
	}

	@Override
	public Specifier getSpecifier(Specifier defaultSpecifier) {
		return all.getSpecifier(defaultSpecifier);
	}

	@Override
	public boolean checkPriority(int targetPriority, int mixinPriority) {
		return all.checkPriority(targetPriority, mixinPriority);
	}

	@Override
	public RestrictTargetLevel getTargetRestriction(IInjectionPointContext context) {
		return all.getTargetRestriction(context);
	}

	@Override
	public boolean find(String desc, InsnList insns, Collection<AbstractInsnNode> nodes) {
		List<AbstractInsnNode> merged = new ArrayList<>();
		all.find(desc, insns, merged);
		List<AbstractInsnNode> written = ordinal < 0 ? merged : ordinal < merged.size() ? List.of(merged.get(ordinal)) : List.of();
		List<AbstractInsnNode> chosen = written;
		try {
			chosen = choose(desc, insns, merged, written);
		} catch (Throwable unprovable) {
			ForbricLog.debug("[Forbric/Mixin] %s: native occurrences of %s not compared (%s)", owner(), point, unprovable);
		}
		nodes.addAll(chosen);
		return !chosen.isEmpty();
	}

	private List<AbstractInsnNode> choose(String desc, InsnList insns, List<AbstractInsnNode> merged, List<AbstractInsnNode> written)
			throws ReflectiveOperationException {
		Target target = InsnListExFields.target(insns);
		if (target == null || target.method == null) return written;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(context.getMixin().getClassRef());
		if (ecosystem == null) return written;
		ClassNode nativeClass = nativeClass(ecosystem, target.classNode.name);
		MethodNode cached = nativeClass == null ? null : declared(nativeClass, target.method.name, target.method.desc);
		if (cached == null) return written;
		// A copy per question: a point may expand instructions of the body it searches (MixinExtras' expressions do).
		MethodNode nativeMethod = copy(cached);
		Target nativeTarget = TargetFields.create(target.classNode.name, nativeClass, nativeMethod);
		List<AbstractInsnNode> native_ = nativeOccurrences(desc, insns, nativeTarget);
		// No native occurrence: nothing native to count (the point may name what an adapter moved it to). The same number:
		// Mixin's own count over the merged body is the native one.
		// A numbered point is checked even where the numbers agree: the carrier may have put its own occurrence in front.
		if (native_.isEmpty() || native_.size() == merged.size() && ordinal < 0) {
			ForbricLog.debug("[Forbric/Mixin] %s: %s point in %s.%s: native %d, merged %d — as written", owner(), point,
					target.classNode.name, target.method.name, native_.size(), merged.size());
			return written;
		}
		List<AbstractInsnNode> wanted = ordinal < 0 ? native_ : ordinal < native_.size() ? List.of(native_.get(ordinal)) : List.of();
		if (wanted.isEmpty()) return written;
		Map<AbstractInsnNode, AbstractInsnNode> aligned = CodeAlignment.align(nativeClass.name, nativeMethod, target.classNode.name, target.method);
		if (aligned == null) return written;
		Flow flow = new Flow(target.method);
		// A point may select a node that is not an instruction (a store point selects the label after the store, and Mixin
		// inserts right after the store): what runs is the step of the instruction before it, in either body. The flow is asked
		// about steps — a label after a store can be the join point of a path that never ran the store.
		Map<AbstractInsnNode, AbstractInsnNode> byStep = new IdentityHashMap<>();
		for (AbstractInsnNode occurrence : merged) byStep.putIfAbsent(step(occurrence), occurrence);
		if (byStep.size() != merged.size()) return written;
		Set<AbstractInsnNode> boundSteps = identitySet(byStep.keySet());
		List<AbstractInsnNode> counterpartSteps = new ArrayList<>();
		int moved = 0;
		for (AbstractInsnNode occurrence : wanted) {
			AbstractInsnNode same = aligned.get(step(occurrence));
			if (same == null) {
				dump(nativeClass.name, nativeMethod, target.classNode.name, target.method, aligned, wanted, merged, List.of());
				ForbricLog.debug("[Forbric/Mixin] %s: %s point in %s.%s: native occurrence %d has no aligned counterpart — as written",
						owner(), point, target.classNode.name, target.method.name, nativeMethod.instructions.indexOf(occurrence));
				return written;
			}
			if (!boundSteps.contains(same)) {
				// The same step is there, but the point does not bind it: the one point it binds that every path from the
				// step meets first stands in for it.
				same = flow.firstReached(same, boundSteps);
				if (same == null) {
					ForbricLog.debug("[Forbric/Mixin] %s: %s point in %s.%s: the counterpart of native occurrence %d is not a point it "
							+ "binds and no single bound point follows it — as written", owner(), point, target.classNode.name,
							target.method.name, nativeMethod.instructions.indexOf(occurrence));
					return written;
				}
				moved++;
			}
			if (counterpartSteps.contains(same)) return written;
			counterpartSteps.add(same);
		}
		List<AbstractInsnNode> chosen = new ArrayList<>();
		for (AbstractInsnNode counterpart : counterpartSteps) chosen.add(byStep.get(counterpart));
		int dropped = 0;
		if (ordinal < 0) {
			Set<AbstractInsnNode> covering = identitySet(counterpartSteps);
			for (Map.Entry<AbstractInsnNode, AbstractInsnNode> occurrence : byStep.entrySet()) {
				if (covering.contains(occurrence.getKey())) continue;
				// Dropped only when no execution can run it without also running a counterpart: then it would only ever run
				// the handler a second time. One a path of its own can run without them keeps the handler there.
				if (flow.alwaysWith(occurrence.getKey(), covering)) dropped++; else chosen.add(occurrence.getValue());
			}
		}
		chosen.sort((x, y) -> Integer.compare(target.method.instructions.indexOf(x), target.method.instructions.indexOf(y)));
		if (chosen.size() == written.size() && identitySet(written).containsAll(chosen)) return written;
		String key = owner() + "|" + target.classNode.name + "." + target.method.name + target.method.desc + "|" + point + "|" + ordinal;
		if (LOGGED.putIfAbsent(key, Boolean.TRUE) == null) {
			dump(nativeClass.name, nativeMethod, target.classNode.name, target.method, aligned, wanted, merged, chosen);
			ForbricLog.info("[Forbric/Mixin] %s: %s point in %s.%s selects %d of the merged body's %d occurrence(s), the native body's %d"
					+ "%s%s%s", owner(), point, target.classNode.name.replace('/', '.'), target.method.name, chosen.size(), merged.size(),
					native_.size(), ordinal >= 0 ? " (ordinal " + ordinal + " counted natively)" : "",
					dropped > 0 ? "; " + dropped + " the carrier added on the native path never run it" : "",
					moved > 0 ? "; " + moved + " stand(s) on the first point it binds after the native step" : "");
		}
		return chosen;
	}

	/** {@code -Dforbric.nativeOccurrences.dump=<dir>}: both bodies' instructions and their alignment, for reading a refusal. */
	private void dump(String nativeOwner, MethodNode nativeMethod, String owner, MethodNode method, Map<AbstractInsnNode, AbstractInsnNode> aligned,
			List<AbstractInsnNode> wanted, List<AbstractInsnNode> merged, List<AbstractInsnNode> chosen) {
		String dir = System.getProperty("forbric.nativeOccurrences.dump");
		if (dir == null) return;
		try {
			Map<AbstractInsnNode, String> left = CodeAlignment.Origins.describe(nativeOwner, nativeMethod), right = CodeAlignment.Origins.describe(owner, method);
			StringBuilder out = new StringBuilder("# native ").append(nativeOwner).append('.').append(nativeMethod.name).append(nativeMethod.desc).append('\n');
			for (AbstractInsnNode x : CodeAlignment.real(nativeMethod)) {
				AbstractInsnNode y = aligned.get(x);
				out.append(nativeMethod.instructions.indexOf(x)).append(" -> ").append(y == null ? "-" : String.valueOf(method.instructions.indexOf(y)))
						.append("  ").append(CodeAlignment.token(x)).append(left == null ? "" : left.get(x)).append('\n');
			}
			out.append("# native selected ").append(wanted.stream().map(nativeMethod.instructions::indexOf).toList())
					.append(" merged selected ").append(merged.stream().map(method.instructions::indexOf).toList())
					.append(" chosen ").append(chosen.stream().map(method.instructions::indexOf).toList()).append('\n');
			out.append("# merged\n");
			for (AbstractInsnNode y : CodeAlignment.real(method)) {
				out.append(method.instructions.indexOf(y)).append("  ").append(CodeAlignment.token(y)).append(right == null ? "" : right.get(y)).append('\n');
			}
			java.nio.file.Path file = java.nio.file.Path.of(dir, (owner + "." + method.name + "." + context.getMethod().name).replace('/', '.') + ".txt");
			java.nio.file.Files.createDirectories(file.getParent());
			java.nio.file.Files.writeString(file, out);
		} catch (Exception unwritable) {
			ForbricLog.debug("[Forbric/Mixin] native occurrence dump failed: %s", unwritable);
		}
	}

	/** The guest's point over the native body: the same point object, handed the native method as a second target. */
	private List<AbstractInsnNode> nativeOccurrences(String desc, InsnList insns, Target nativeTarget) throws ReflectiveOperationException {
		InsnListEx view = new InsnListEx(nativeTarget);
		for (Map.Entry<String, Object> decoration : InsnListExFields.decorations(insns).entrySet()) {
			view.decorate(decoration.getKey(), decoration.getValue() instanceof Target ? nativeTarget : decoration.getValue());
		}
		List<AbstractInsnNode> found = new ArrayList<>();
		// MixinExtras' expression point finds the target a list belongs to among the targets it is being applied to.
		List<Object> current = CurrentTargets.of(all);
		if (current != null) current.add(nativeTarget);
		try {
			all.find(desc, view, found);
		} finally {
			if (current != null) current.remove(nativeTarget);
		}
		return found;
	}

	private String owner() {
		try {
			return context.getMixin().getClassName() + "." + context.getMethod().name;
		} catch (RuntimeException unnamed) {
			return String.valueOf(context);
		}
	}

	private static ClassNode nativeClass(Ecosystem ecosystem, String owner) {
		String key = ecosystem + ":" + owner;
		ClassNode cached = NATIVE_CLASSES.get(key);
		if (cached != null) return cached;
		ClassNode read = NativeGameReferences.referenceWithFrames(ecosystem, owner);
		if (read != null) NATIVE_CLASSES.put(key, read);
		return read;
	}

	/** The instruction a selected node stands for: itself, or the nearest instruction before a label, frame or line number. */
	private static AbstractInsnNode step(AbstractInsnNode node) {
		AbstractInsnNode step = node;
		while (step != null && step.getOpcode() < 0) step = step.getPrevious();
		if (step != null) return step;
		for (step = node; step != null && step.getOpcode() < 0; step = step.getNext()) { }
		return step == null ? node : step;
	}

	/** A copy of {@code method}'s code with labels of its own (ASM's own copy would share them with the original list). */
	private static MethodNode copy(MethodNode method) {
		MethodNode copy = new MethodNode(method.access, method.name, method.desc, method.signature,
				method.exceptions == null ? null : method.exceptions.toArray(String[]::new));
		Map<LabelNode, LabelNode> labels = new IdentityHashMap<>();
		for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof LabelNode label) labels.put(label, new LabelNode());
		for (AbstractInsnNode instruction : method.instructions) copy.instructions.add(instruction.clone(labels));
		if (method.tryCatchBlocks != null) for (TryCatchBlockNode block : method.tryCatchBlocks) {
			copy.tryCatchBlocks.add(new TryCatchBlockNode(labels.get(block.start), labels.get(block.end), labels.get(block.handler), block.type));
		}
		if (method.localVariables != null) {
			copy.localVariables = new ArrayList<>();
			for (org.objectweb.asm.tree.LocalVariableNode local : method.localVariables) {
				copy.localVariables.add(new org.objectweb.asm.tree.LocalVariableNode(local.name, local.desc, local.signature,
						labels.get(local.start), labels.get(local.end), local.index));
			}
		}
		copy.maxStack = method.maxStack;
		copy.maxLocals = method.maxLocals;
		return copy;
	}

	private static MethodNode declared(ClassNode owner, String name, String desc) {
		for (MethodNode method : owner.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}

	private static Set<AbstractInsnNode> identitySet(Collection<AbstractInsnNode> nodes) {
		Set<AbstractInsnNode> out = Collections.newSetFromMap(new IdentityHashMap<>());
		out.addAll(nodes);
		return out;
	}

	/** The control flow of a method body, exception edges included, for asking what one instruction can lead to. */
	static final class Flow {
		private final MethodNode method;
		private final Map<AbstractInsnNode, List<AbstractInsnNode>> successors = new IdentityHashMap<>();

		Flow(MethodNode method) {
			this.method = method;
			for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
				List<AbstractInsnNode> next = new ArrayList<>(2);
				int opcode = instruction.getOpcode();
				if (instruction instanceof JumpInsnNode jump) {
					next.add(jump.label);
					if (opcode != Opcodes.GOTO && opcode != Opcodes.JSR && instruction.getNext() != null) next.add(instruction.getNext());
				} else if (instruction instanceof TableSwitchInsnNode table) {
					next.add(table.dflt);
					next.addAll(table.labels);
				} else if (instruction instanceof LookupSwitchInsnNode lookup) {
					next.add(lookup.dflt);
					next.addAll(lookup.labels);
				} else if (!(opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW || opcode == Opcodes.RET)
						&& instruction.getNext() != null) {
					next.add(instruction.getNext());
				}
				successors.put(instruction, next);
			}
			if (method.tryCatchBlocks != null) for (TryCatchBlockNode block : method.tryCatchBlocks) {
				for (AbstractInsnNode instruction = block.start; instruction != null && instruction != block.end; instruction = instruction.getNext()) {
					successors.get(instruction).add(block.handler);
				}
			}
		}

		/**
		 * Whether every execution that runs {@code node} also runs one of {@code others}, before or after it: no path from the
		 * method's entry reaches {@code node} and goes on to leave the method without passing one of them.
		 */
		boolean alwaysWith(AbstractInsnNode node, Set<AbstractInsnNode> others) {
			AbstractInsnNode entry = method.instructions.getFirst();
			if (entry == null || others.contains(node)) return true;
			if (!reachesAvoiding(entry, node, others)) return true;
			// From the node, is any exit reachable without passing them?
			Set<AbstractInsnNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
			Deque<AbstractInsnNode> pending = new ArrayDeque<>(successors.getOrDefault(node, List.of()));
			while (!pending.isEmpty()) {
				AbstractInsnNode instruction = pending.pop();
				if (others.contains(instruction) || !seen.add(instruction)) continue;
				int opcode = instruction.getOpcode();
				if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW) return false;
				pending.addAll(successors.getOrDefault(instruction, List.of()));
			}
			return true;
		}

		private boolean reachesAvoiding(AbstractInsnNode from, AbstractInsnNode to, Set<AbstractInsnNode> avoid) {
			Set<AbstractInsnNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
			Deque<AbstractInsnNode> pending = new ArrayDeque<>();
			pending.push(from);
			while (!pending.isEmpty()) {
				AbstractInsnNode instruction = pending.pop();
				if (instruction == to) return true;
				if (avoid.contains(instruction) || !seen.add(instruction)) continue;
				pending.addAll(successors.getOrDefault(instruction, List.of()));
			}
			return false;
		}

		/**
		 * The one instruction of {@code stops} that every path from {@code from} meets first, or null when paths from it meet
		 * different ones first, or none at all.
		 */
		AbstractInsnNode firstReached(AbstractInsnNode from, Set<AbstractInsnNode> stops) {
			Set<AbstractInsnNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
			Deque<AbstractInsnNode> pending = new ArrayDeque<>(successors.getOrDefault(from, List.of()));
			AbstractInsnNode first = null;
			while (!pending.isEmpty()) {
				AbstractInsnNode instruction = pending.pop();
				if (!seen.add(instruction)) continue;
				if (stops.contains(instruction)) {
					if (first != null && first != instruction) return null;
					first = instruction;
					continue;
				}
				pending.addAll(successors.getOrDefault(instruction, List.of()));
			}
			return first;
		}
	}

	/** The parts of Mixin's point data its public API does not hand out, read as Mixin stores them. */
	private static final class InjectionPointDataFields {
		private static final Field ARGS = field(InjectionPointData.class, "args");
		private static final Field TARGET = field(InjectionPointData.class, "target");
		private static final Field FLAGS = field(InjectionPointData.class, "flags");

		@SuppressWarnings("unchecked")
		static Map<String, String> args(InjectionPointData data) {
			try {
				Object value = ARGS.get(data);
				return value instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
			} catch (IllegalAccessException impossible) {
				throw new IllegalStateException(impossible);
			}
		}

		static String target(InjectionPointData data) {
			try {
				return (String) TARGET.get(data);
			} catch (IllegalAccessException impossible) {
				throw new IllegalStateException(impossible);
			}
		}

		static int flags(InjectionPointData data) {
			try {
				return FLAGS.getInt(data);
			} catch (IllegalAccessException impossible) {
				throw new IllegalStateException(impossible);
			}
		}
	}

	/** The target and decorations of the list Mixin hands a point, which its public API does not hand out. */
	private static final class InsnListExFields {
		private static final Field TARGET = field(InsnListEx.class, "target");
		private static final Field DECORATIONS = field(InsnListEx.class, "decorations");

		static Target target(InsnList insns) throws IllegalAccessException {
			return insns instanceof InsnListEx ex ? (Target) TARGET.get(ex) : null;
		}

		@SuppressWarnings("unchecked")
		static Map<String, Object> decorations(InsnList insns) throws IllegalAccessException {
			if (!(insns instanceof InsnListEx ex)) return Map.of();
			Object value = DECORATIONS.get(ex);
			return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
		}
	}

	/** Mixin's target of a method, made for the native body (its constructor is package-private). */
	private static final class TargetFields {
		private static final Constructor<Target> CREATE;

		static {
			try {
				CREATE = Target.class.getDeclaredConstructor(ClassInfo.class, ClassNode.class, MethodNode.class);
				CREATE.setAccessible(true);
			} catch (NoSuchMethodException absent) {
				throw new ExceptionInInitializerError(absent);
			}
		}

		static Target create(String owner, ClassNode classNode, MethodNode method) throws ReflectiveOperationException {
			return CREATE.newInstance(ClassInfo.forName(owner), classNode, method);
		}
	}

	/** MixinExtras' list of the targets its expression point is being applied to, on the point's own class, if it keeps one. */
	private static final class CurrentTargets {
		private static final Map<Class<?>, java.util.Optional<Field>> FIELDS = new ConcurrentHashMap<>();

		@SuppressWarnings("unchecked")
		static List<Object> of(InjectionPoint point) throws IllegalAccessException {
			java.util.Optional<Field> field = FIELDS.computeIfAbsent(point.getClass(), type -> {
				for (Class<?> c = type; c != null && c != InjectionPoint.class; c = c.getSuperclass()) {
					try {
						Field found = c.getDeclaredField("CURRENT_TARGETS");
						if (java.lang.reflect.Modifier.isStatic(found.getModifiers()) && List.class.isAssignableFrom(found.getType())) {
							found.setAccessible(true);
							return java.util.Optional.of(found);
						}
					} catch (NoSuchFieldException absent) {
						// keep looking up the hierarchy
					}
				}
				return java.util.Optional.empty();
			});
			return field.isPresent() ? (List<Object>) field.get().get(null) : null;
		}
	}

	private static Field field(Class<?> owner, String name) {
		try {
			Field field = owner.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (NoSuchFieldException absent) {
			throw new ExceptionInInitializerError(absent);
		}
	}
}
