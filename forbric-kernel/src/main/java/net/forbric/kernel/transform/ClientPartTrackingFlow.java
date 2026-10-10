/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

/**
 * Where the values of a client {@code onTrackingStart} go, which is what {@link ClientPartTrackingInjector#CLAIM} is about.
 * Two questions, both answered by abstract interpretation of the method rather than by the shape of its instructions:
 * <ul>
 *   <li>Does what NeoForge's {@code getParts()} returns reach an {@code add}/{@code addAll} into
 *       {@code ClientLevel.dragonParts}? The parts may be handed over directly, kept in a local, wrapped or copied by
 *       the collection libraries the game ships ({@code Arrays.asList}, {@code List.of}, a stream, a copying
 *       constructor, {@code requireNonNullElse}), walked element by element, or added by a function applied to them
 *       ({@code forEach(dragonParts::add)}, or a lambda of this class whose body adds what it is given). The list may be
 *       read from the field or through {@code ClientLevel.dragonParts()}. Reading {@code dragonParts} somewhere else in
 *       the method — vanilla's EnderDragon case always does — proves nothing about NeoForge's parts.</li>
 *   <li>Is every array MinecraftForge's {@code getParts()} returns tested for null on every path before anything
 *       consumes it? That array is null for a NeoForge mod's entity, and dereferencing it is what disconnected the
 *       client. A test is {@code IFNULL}/{@code IFNONNULL} on the value or any copy of it (the branch that knows it is
 *       not null is refined, the other is not); {@code requireNonNullElse}/{@code requireNonNullElseGet} replace it by
 *       a value that is not null. Moving it (locals, {@code DUP}, {@code CHECKCAST}) or comparing its identity is not a
 *       use; anything else is, conservatively.</li>
 * </ul>
 */
final class ClientPartTrackingFlow {
	private static final String ENTITY = ClientPartTrackingInjector.ENTITY;
	private static final String LEVEL = ClientPartTrackingInjector.LEVEL;
	private static final String OBJECTS = "java/util/Objects";
	/** The kernel's own repair hands NeoForge's parts to this helper, which adds those dragonParts does not hold yet. */
	private static final String KERNEL_VIEWS = "net/forbric/kernel/runtime/KernelMultipartViews";
	/** Whose calls make a view, copy, element or stream of a collection they are given: the libraries the game ships. */
	private static final List<String> COLLECTION_LIBRARIES = List.of("java/util/", "com/google/common/collect/", "it/unimi/dsi/fastutil/");
	/** How deep into this class's own lambdas the question follows NeoForge's parts. */
	private static final int MAX_DEPTH = 3;

	private ClientPartTrackingFlow() {
	}

	/** Whether {@code start} adds NeoForge's parts into dragonParts and null-tests every MinecraftForge part array it reads. */
	static boolean tracksNeoForgeParts(ClassNode owner, MethodNode start) {
		Flows flows = new Flows(owner, 0, null);
		try {
			new FlowAnalyzer(flows).analyze(owner.name, start);
		} catch (AnalyzerException | RuntimeException unreadable) {
			return false;
		}
		return flows.registers && !flows.unchecked;
	}

	/**
	 * One abstract value.
	 *
	 * @param origins the instructions that made it; loads, stores, {@code DUP}s and {@code CHECKCAST}s keep it
	 * @param neo     NeoForge's parts: the array {@code getParts()} returned, a collection view, copy or stream of it, or
	 *                one of its elements
	 * @param dragon  {@code ClientLevel.dragonParts} itself
	 * @param adder   a function that adds what it is applied to into dragonParts
	 * @param forge   the MinecraftForge {@code getParts()} reads this may be, not yet tested for null on this path
	 */
	record Flow(int size, Set<AbstractInsnNode> origins, boolean neo, boolean dragon, boolean adder, Set<AbstractInsnNode> forge)
			implements Value {
		static Flow plain(int size, AbstractInsnNode origin) {
			return new Flow(size, origin == null ? Set.of() : Set.of(origin), false, false, false, Set.of());
		}

		static Flow parts(int size) {
			return new Flow(size, Set.of(), true, false, false, Set.of());
		}

		@Override public int getSize() {
			return size;
		}

		Flow withNeo() {
			return neo ? this : new Flow(size, origins, true, dragon, adder, forge);
		}

		Flow tested(Set<AbstractInsnNode> reads) {
			Set<AbstractInsnNode> left = new LinkedHashSet<>(forge);
			left.removeAll(reads);
			return left.size() == forge.size() ? this : new Flow(size, origins, neo, dragon, adder, Set.copyOf(left));
		}
	}

	private static final class FlowAnalyzer extends Analyzer<Flow> {
		FlowAnalyzer(Flows flows) {
			super(flows);
		}

		@Override protected Frame<Flow> newFrame(int locals, int stack) {
			return new TrackedFrame(locals, stack);
		}

		@Override protected Frame<Flow> newFrame(Frame<? extends Flow> frame) {
			return new TrackedFrame(frame);
		}
	}

	/** A frame that refines the branch of a null test that knows the value is not null, and fills collections it sees filled. */
	private static final class TrackedFrame extends Frame<Flow> {
		private Flow tested;
		private Frame<Flow> unrefined;

		TrackedFrame(int locals, int stack) {
			super(locals, stack);
		}

		TrackedFrame(Frame<? extends Flow> frame) {
			super(frame);
		}

		@Override public void execute(AbstractInsnNode insn, Interpreter<Flow> interpreter) throws AnalyzerException {
			int opcode = insn.getOpcode();
			tested = opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL ? top(0) : null;
			unrefined = null;
			// A collection or array that takes NeoForge's parts in carries them from then on.
			Flow filled = null;
			boolean fills = false;
			if (insn instanceof MethodInsnNode call && fills(call)) {
				int arguments = Type.getArgumentCount(call.desc);
				int target = opcode == Opcodes.INVOKESTATIC ? arguments - 1 : arguments;
				if (target >= 0 && target < getStackSize()) {
					filled = top(target);
					for (int i = 0; i < target; i++) fills |= top(i).neo();
				}
			} else if (opcode == Opcodes.AASTORE) {
				filled = top(2);
				fills = top(0).neo();
			}
			super.execute(insn, interpreter);
			if (fills && !filled.neo() && !filled.origins().isEmpty()) {
				for (int i = 0; i < getLocals(); i++) if (shares(getLocal(i), filled)) setLocal(i, getLocal(i).withNeo());
				for (int i = 0; i < getStackSize(); i++) if (shares(getStack(i), filled)) setStack(i, getStack(i).withNeo());
			}
		}

		/**
		 * Called for the fall-through of a conditional jump (target null) and then for its target. Only the side of a null
		 * test that knows the value is not null is refined: what falls through {@code IFNULL}, what jumps on {@code IFNONNULL}.
		 */
		@Override public void initJumpTarget(int opcode, LabelNode target) {
			if (tested == null || tested.forge().isEmpty()) return;
			if (opcode == Opcodes.IFNULL && target == null) {
				unrefined = new Frame<>(this);
				refine(tested.forge());
			} else if (opcode == Opcodes.IFNULL) {
				if (unrefined != null) init(unrefined);
			} else if (opcode == Opcodes.IFNONNULL && target != null) {
				refine(tested.forge());
			}
		}

		private void refine(Set<AbstractInsnNode> reads) {
			for (int i = 0; i < getLocals(); i++) if (getLocal(i) != null) setLocal(i, getLocal(i).tested(reads));
			for (int i = 0; i < getStackSize(); i++) setStack(i, getStack(i).tested(reads));
		}

		private Flow top(int depth) {
			return getStack(getStackSize() - 1 - depth);
		}

		private static boolean shares(Flow value, Flow filled) {
			if (value == null) return false;
			for (AbstractInsnNode origin : filled.origins()) if (value.origins().contains(origin)) return true;
			return false;
		}

		/** A constructor, an {@code add}/{@code addAll} on its receiver, or {@code Collections.addAll} on its first argument. */
		private static boolean fills(MethodInsnNode call) {
			if (call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("<init>")) return true;
			if (call.getOpcode() == Opcodes.INVOKESTATIC) return call.owner.equals("java/util/Collections") && call.name.equals("addAll");
			return call.name.equals("add") || call.name.equals("addAll");
		}
	}

	private static final class Flows extends Interpreter<Flow> {
		private final ClassNode owner;
		private final int depth;
		private final Map<Integer, Flow> parameters;
		boolean registers;
		boolean unchecked;

		Flows(ClassNode owner, int depth, Map<Integer, Flow> parameters) {
			super(Opcodes.ASM9);
			this.owner = owner;
			this.depth = depth;
			this.parameters = parameters;
		}

		@Override public Flow newValue(Type type) {
			if (type == Type.VOID_TYPE) return null;
			return Flow.plain(type == null ? 1 : type.getSize(), null);
		}

		@Override public Flow newParameterValue(boolean instance, int local, Type type) {
			Flow given = parameters == null ? null : parameters.get(local);
			return given != null ? given : newValue(type);
		}

		@Override public Flow newOperation(AbstractInsnNode insn) {
			int size = switch (insn.getOpcode()) {
				case Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.DCONST_0, Opcodes.DCONST_1 -> 2;
				case Opcodes.LDC -> switch (((LdcInsnNode) insn).cst) {
					case Long wide -> 2;
					case Double wide -> 2;
					case ConstantDynamic constant -> Type.getType(constant.getDescriptor()).getSize();
					default -> 1;
				};
				case Opcodes.GETSTATIC -> Type.getType(((FieldInsnNode) insn).desc).getSize();
				default -> 1;
			};
			return Flow.plain(size, insn);
		}

		@Override public Flow copyOperation(AbstractInsnNode insn, Flow value) {
			return value;
		}

		@Override public Flow unaryOperation(AbstractInsnNode insn, Flow value) {
			switch (insn.getOpcode()) {
				case Opcodes.CHECKCAST:
					return value;
				case Opcodes.IFNULL, Opcodes.IFNONNULL, Opcodes.INSTANCEOF:
					return Flow.plain(1, insn);
				case Opcodes.GETFIELD: {
					use(value);
					FieldInsnNode field = (FieldInsnNode) insn;
					if (field.owner.equals(LEVEL) && field.name.equals("dragonParts") && field.desc.equals("Ljava/util/List;")) {
						return new Flow(1, Set.of(insn), false, true, false, Set.of());
					}
					return Flow.plain(Type.getType(field.desc).getSize(), insn);
				}
				case Opcodes.LNEG, Opcodes.DNEG, Opcodes.I2L, Opcodes.I2D, Opcodes.L2D, Opcodes.F2L, Opcodes.F2D, Opcodes.D2L:
					use(value);
					return Flow.plain(2, insn);
				default:
					use(value);
					return Flow.plain(1, insn);
			}
		}

		@Override public Flow binaryOperation(AbstractInsnNode insn, Flow first, Flow second) {
			switch (insn.getOpcode()) {
				case Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE:
					return null;
				case Opcodes.AALOAD:
					use(first);
					return new Flow(1, Set.of(insn), first.neo(), false, false, Set.of());
				case Opcodes.LALOAD, Opcodes.DALOAD, Opcodes.LADD, Opcodes.DADD, Opcodes.LSUB, Opcodes.DSUB, Opcodes.LMUL,
						Opcodes.DMUL, Opcodes.LDIV, Opcodes.DDIV, Opcodes.LREM, Opcodes.DREM, Opcodes.LSHL, Opcodes.LSHR,
						Opcodes.LUSHR, Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR:
					use(first);
					use(second);
					return Flow.plain(2, insn);
				default:
					use(first);
					use(second);
					return Flow.plain(1, insn);
			}
		}

		@Override public Flow ternaryOperation(AbstractInsnNode insn, Flow first, Flow second, Flow third) {
			use(first);
			use(second);
			use(third);
			return null;
		}

		@Override public Flow naryOperation(AbstractInsnNode insn, List<? extends Flow> values) {
			if (insn instanceof InvokeDynamicInsnNode indy) {
				values.forEach(this::use);
				return invokeDynamic(indy, values);
			}
			if (!(insn instanceof MethodInsnNode call)) {
				values.forEach(this::use);
				return Flow.plain(1, insn);
			}
			boolean isStatic = call.getOpcode() == Opcodes.INVOKESTATIC;
			boolean orElse = isStatic && call.owner.equals(OBJECTS) && (call.name.equals("requireNonNullElse") || call.name.equals("requireNonNullElseGet"));
			for (int i = 0; i < values.size(); i++) if (!(orElse && i == 0)) use(values.get(i));
			if (adds(call.owner, call.name, isStatic, values)) registers = true;
			if (values.stream().anyMatch(Flow::adder) && values.stream().anyMatch(Flow::neo)) registers = true;

			Type result = Type.getReturnType(call.desc);
			if (result.getSort() == Type.VOID) return null;
			if (call.owner.equals(ENTITY) && call.name.equals("getParts")) {
				if (call.desc.equals(ClientPartTrackingInjector.NEO_GET_PARTS)) return new Flow(1, Set.of(insn), true, false, false, Set.of());
				if (call.desc.equals(ClientPartTrackingInjector.FORGE_GET_PARTS)) return new Flow(1, Set.of(insn), false, false, false, Set.of(insn));
			}
			if (call.owner.equals(LEVEL) && call.name.equals("dragonParts") && Type.getArgumentCount(call.desc) == 0 && !isStatic) {
				return new Flow(1, Set.of(insn), false, true, false, Set.of());
			}
			// Objects.requireNonNull*(x, ...) is x itself (or, for the OrElse forms, a value that is not null).
			boolean same = isStatic && call.owner.equals(OBJECTS) && call.name.startsWith("requireNonNull") && !values.isEmpty();
			boolean neo = carriesParts(call.owner, result) && values.stream().anyMatch(Flow::neo);
			return new Flow(result.getSize(), Set.of(insn), neo, same && values.get(0).dragon(), false, Set.of());
		}

		@Override public void returnOperation(AbstractInsnNode insn, Flow value, Flow expected) {
			use(value);
		}

		@Override public Flow merge(Flow first, Flow second) {
			if (first.equals(second)) return first;
			Flow merged = new Flow(first.size() == second.size() ? first.size() : 1, union(first.origins(), second.origins()),
					first.neo() || second.neo(), first.dragon() || second.dragon(), first.adder() || second.adder(),
					union(first.forge(), second.forge()));
			return merged.equals(first) ? first : merged;
		}

		/** Something consumes {@code value}; that is the dereference the null test must come before. */
		private void use(Flow value) {
			if (value != null && !value.forge().isEmpty()) unchecked = true;
		}

		/** A bound {@code dragonParts::add}, or a lambda of this class whose body adds what it is applied to into dragonParts. */
		private Flow invokeDynamic(InvokeDynamicInsnNode indy, List<? extends Flow> captured) {
			boolean adder = indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") && indy.bsmArgs.length >= 3
					&& indy.bsmArgs[1] instanceof Handle implementation && addsWhatItIsGiven(implementation, captured);
			Type result = Type.getReturnType(indy.desc);
			return new Flow(result.getSize(), Set.of(indy), false, false, adder, Set.of());
		}

		private boolean addsWhatItIsGiven(Handle implementation, List<? extends Flow> captured) {
			int tag = implementation.getTag();
			boolean instance = tag == Opcodes.H_INVOKEVIRTUAL || tag == Opcodes.H_INVOKEINTERFACE || tag == Opcodes.H_INVOKESPECIAL;
			if (!instance && tag != Opcodes.H_INVOKESTATIC) return false;
			List<Type> parameters = new ArrayList<>();
			if (instance) parameters.add(Type.getObjectType(implementation.getOwner()));
			parameters.addAll(List.of(Type.getArgumentTypes(implementation.getDesc())));
			if (captured.size() > parameters.size()) return false;
			// What the function is applied to follows what it captured; the question is what it does when that is NeoForge's parts.
			List<Flow> given = new ArrayList<>(captured);
			for (int i = captured.size(); i < parameters.size(); i++) given.add(Flow.parts(parameters.get(i).getSize()));
			if (!implementation.getOwner().equals(owner.name)) return adds(implementation.getOwner(), implementation.getName(), !instance, given);
			MethodNode body = null;
			for (MethodNode method : owner.methods) {
				if (method.name.equals(implementation.getName()) && method.desc.equals(implementation.getDesc())) body = method;
			}
			if (body == null || body.instructions.size() == 0 || depth >= MAX_DEPTH) return false;
			Map<Integer, Flow> locals = new HashMap<>();
			int local = 0;
			for (int i = 0; i < given.size(); i++) {
				locals.put(local, given.get(i));
				local += parameters.get(i).getSize();
			}
			Flows inner = new Flows(owner, depth + 1, locals);
			try {
				new FlowAnalyzer(inner).analyze(owner.name, body);
			} catch (AnalyzerException | RuntimeException unreadable) {
				return false;
			}
			return inner.registers;
		}

		/** An add into dragonParts with NeoForge's parts among what is added. */
		private static boolean adds(String owner, String name, boolean isStatic, List<? extends Flow> values) {
			boolean into = isStatic
					? (owner.equals("java/util/Collections") && name.equals("addAll")) || (owner.equals(KERNEL_VIEWS) && name.equals("addDistinct"))
					: name.equals("add") || name.equals("addAll");
			if (!into || values.isEmpty() || !values.get(0).dragon()) return false;
			for (int i = 1; i < values.size(); i++) if (values.get(i).neo()) return true;
			return false;
		}

		/** A reference the collection libraries derive from what they are given: a view, copy, stream, iterator or element. */
		private static boolean carriesParts(String owner, Type result) {
			if (result.getSort() != Type.ARRAY && result.getSort() != Type.OBJECT) return false;
			if (result.getSort() == Type.OBJECT && result.getInternalName().equals("java/lang/String")) return false;
			if (owner.startsWith("[")) return true;
			for (String library : COLLECTION_LIBRARIES) if (owner.startsWith(library)) return true;
			return false;
		}

		private static Set<AbstractInsnNode> union(Set<AbstractInsnNode> first, Set<AbstractInsnNode> second) {
			if (first.containsAll(second)) return first;
			Set<AbstractInsnNode> union = new LinkedHashSet<>(first);
			union.addAll(second);
			return Set.copyOf(union);
		}
	}
}
