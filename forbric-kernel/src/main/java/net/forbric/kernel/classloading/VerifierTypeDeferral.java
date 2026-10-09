/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.classloading;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
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
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

/**
 * Keeps the JVM's verifier from defining classes while Mixin is weaving another one.
 *
 * <p>Mixin weaves one class at a time on a thread. It selects and prepares every registered config — constructing each
 * config plugin, then asking the plugins which mixins apply — inside the weave of the first class it is handed, and a
 * class defined on that thread before Mixin returns gets no mixin at all: no config is prepared yet, so there is nothing
 * to apply (after preparation, a class with mixins defined inside another weave is refused as re-entrance). A plugin that initialises its mod's platform abstraction there links that abstraction's
 * implementation, and linking VERIFIES it. HotSpot's verifier settles an assignability check it cannot answer from the
 * two names alone by loading the declared type: a provider that hands a value of one interface to a parameter declared
 * as its super-interface makes the verifier define that super-interface, and with it every type it extends — classes
 * the provider's code would only touch if the method ever ran. They are then defined before any mixin exists for
 * them, and every mixin aimed at them is lost: Mixin reports it as "loaded too early" for a config prepared after the
 * definition, and says nothing at all for one prepared before. Measured on Iris' NeoForge build, whose two plugins both
 * reach its platform provider: BlockGetter lost fabric-block-getter-api-v2's interface, and Lithium's raycast mixin.
 * Genuine NeoForge 26.2.0.88 loses Lithium's the same way, with no message.
 *
 * <p>Only the verification needs those types, so a class defined while a weave is open has its checks discharged where
 * the code can afford them: in each method whose verification would resolve a type that has not been defined yet,
 * every reference type in the stack map frames becomes {@code Object}, and every typed use of a value gets the
 * {@code checkcast} that gives the verifier exactly the type the use declares (or the type the value already had, when
 * that resolves nothing new). A {@code checkcast} is resolved when it executes, not when its class is verified, so
 * those types are first defined when the code really runs — after Mixin has finished, through the normal pipeline,
 * with their mixins. The casts are to types the original verifier already proved the values have, so they cannot fail
 * where the original code would not.
 *
 * <p>Nothing here knows a mod, a method name or a call shape. What it does not cover, because no rewrite can: a catch
 * type (the verifier proves it is a Throwable by loading it), and code that really executes a game type during the
 * weave — that class has to exist at that moment and does on every Mixin platform. A class of version 50 or older,
 * or one with subroutines, is left alone.
 */
public final class VerifierTypeDeferral {
	private VerifierTypeDeferral() {
	}

	/**
	 * The outcome for one class.
	 *
	 * @param bytes    the class to define: the input array itself when nothing needed to change
	 * @param deferred the internal names whose resolution the original verification needed and the rewrite removed
	 * @param methods  how many methods were rewritten
	 * @param casts    how many checkcasts were inserted
	 */
	public record Result(byte[] bytes, Set<String> deferred, int methods, int casts) {
		public boolean changed() {
			return methods > 0;
		}
	}

	/**
	 * Rewrites {@code bytes} so that verifying it resolves none of the types {@code undefined} accepts.
	 *
	 * @param undefined  internal names of the types that must not be resolved yet (the defining loader would define
	 *                   them, and has not). Array types are judged by their element type.
	 * @param supertypes the direct supertypes (superclass first, then interfaces) of an internal name, or null when
	 *                   unknown. The class's own ancestry is defined by the JVM before verification starts anyway, so
	 *                   it is never treated as undefined.
	 */
	public static Result rewrite(byte[] bytes, Predicate<String> undefined, Function<String, String[]> supertypes) {
		Result unchanged = new Result(bytes, Set.of(), 0, 0);
		if (bytes == null || bytes.length < 8) return unchanged;
		ClassNode type = new ClassNode();
		try {
			ClassReader reader = new ClassReader(bytes);
			if (reader.readUnsignedShort(6) <= Opcodes.V1_6) return unchanged; // no mandatory stack map frames
			reader.accept(type, ClassReader.EXPAND_FRAMES);
		} catch (RuntimeException unreadable) {
			return unchanged;
		}
		Set<String> ancestry = ancestry(type, supertypes);
		Predicate<String> deferrable = name -> name != null && !ancestry.contains(name) && undefined.test(name);
		Set<String> deferred = new TreeSet<>();
		int methods = 0, casts = 0;
		try {
			for (MethodNode method : type.methods) {
				if (method.instructions.size() == 0) continue;
				Set<String> resolved = new Walk(type, method, deferrable).detect();
				if (resolved == null || resolved.isEmpty()) continue;
				int inserted = new Walk(type, method, deferrable).rewrite();
				if (inserted < 0) return unchanged;
				deferred.addAll(resolved);
				methods++;
				casts += inserted;
			}
			if (methods == 0) return unchanged;
			ClassWriter writer = new ClassWriter(0);
			type.accept(writer);
			byte[] out = writer.toByteArray();
			if (!structurallySound(out)) return unchanged;
			return new Result(out, Collections.unmodifiableSet(deferred), methods, casts);
		} catch (AnalyzerException | RuntimeException unsupported) {
			return unchanged;
		}
	}

	/** This class and every supertype the JVM must define before it can verify the class. */
	private static Set<String> ancestry(ClassNode type, Function<String, String[]> supertypes) {
		Set<String> seen = new TreeSet<>();
		List<String> pending = new ArrayList<>();
		pending.add(type.name);
		if (type.superName != null) pending.add(type.superName);
		pending.addAll(type.interfaces);
		while (!pending.isEmpty()) {
			String next = pending.removeLast();
			if (next == null || !seen.add(next) || next.equals(type.name)) continue;
			String[] up = supertypes.apply(next);
			if (up != null) Collections.addAll(pending, up);
		}
		seen.add(type.name);
		return seen;
	}

	/** Stack heights, local indices and value categories, checked without loading a single class. */
	private static boolean structurallySound(byte[] bytes) {
		ClassNode check = new ClassNode();
		new ClassReader(bytes).accept(check, 0);
		for (MethodNode method : check.methods) {
			if (method.instructions.size() == 0) continue;
			try {
				new Analyzer<>(new BasicVerifier()).analyze(check.name, method);
			} catch (AnalyzerException broken) {
				return false;
			}
		}
		return true;
	}

	// ---- verification types ----

	/** A verification type: a primitive category, a reference, null, top, or an uninitialized object. */
	static final class Slot implements Value {
		static final Slot TOP = new Slot(null, 1, null, "top");
		static final Slot INT = new Slot(Type.INT_TYPE, 1, null, "int");
		static final Slot FLOAT = new Slot(Type.FLOAT_TYPE, 1, null, "float");
		static final Slot LONG = new Slot(Type.LONG_TYPE, 2, null, "long");
		static final Slot DOUBLE = new Slot(Type.DOUBLE_TYPE, 2, null, "double");
		static final Slot NULL = new Slot(null, 1, null, "null");
		private static final Object THIS = new Object();

		final Type type;
		final int size;
		/** The NEW instruction (or {@link #THIS}) of an uninitialized object; null once initialized. */
		final Object uninit;
		private final String label;

		private Slot(Type type, int size, Object uninit, String label) {
			this.type = type;
			this.size = size;
			this.uninit = uninit;
			this.label = label;
		}

		static Slot of(Type type) {
			return new Slot(type, 1, null, null);
		}

		static Slot uninitialized(Type type, Object site) {
			return new Slot(type, 1, site, null);
		}

		boolean isReference() {
			return uninit == null && type != null && (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY);
		}

		@Override public int getSize() {
			return size;
		}

		@Override public boolean equals(Object other) {
			if (this == other) return true;
			if (!(other instanceof Slot that) || label != null || that.label != null) return false;
			return size == that.size && uninit == that.uninit && java.util.Objects.equals(type, that.type);
		}

		@Override public int hashCode() {
			return java.util.Objects.hash(type, size, uninit == null ? 0 : System.identityHashCode(uninit), label);
		}

		@Override public String toString() {
			return label != null ? label : (uninit != null ? "uninitialized " : "") + type;
		}
	}

	private static final Type OBJECT = Type.getObjectType("java/lang/Object");
	private static final Type THROWABLE = Type.getObjectType("java/lang/Throwable");
	/** A use that needs some array, whichever: the array the value already was is the type to restore. */
	private static final Type ANY_ARRAY = Type.getType("[Ljava/lang/Object;");

	/** Instruction semantics over {@link Slot}s, with no class ever loaded and no merging (frames are read, not computed). */
	private static final class Types extends Interpreter<Slot> {
		Types() {
			super(Opcodes.ASM9);
		}

		@Override public Slot newValue(Type type) {
			if (type == null) return Slot.TOP;
			return switch (type.getSort()) {
				case Type.VOID -> null;
				case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> Slot.INT;
				case Type.FLOAT -> Slot.FLOAT;
				case Type.LONG -> Slot.LONG;
				case Type.DOUBLE -> Slot.DOUBLE;
				case Type.ARRAY, Type.OBJECT -> Slot.of(type);
				default -> throw new IllegalArgumentException("unsupported value type " + type);
			};
		}

		@Override public Slot newOperation(AbstractInsnNode insn) {
			switch (insn.getOpcode()) {
				case Opcodes.ACONST_NULL: return Slot.NULL;
				case Opcodes.ICONST_M1: case Opcodes.ICONST_0: case Opcodes.ICONST_1: case Opcodes.ICONST_2:
				case Opcodes.ICONST_3: case Opcodes.ICONST_4: case Opcodes.ICONST_5: case Opcodes.BIPUSH:
				case Opcodes.SIPUSH:
					return Slot.INT;
				case Opcodes.LCONST_0: case Opcodes.LCONST_1: return Slot.LONG;
				case Opcodes.FCONST_0: case Opcodes.FCONST_1: case Opcodes.FCONST_2: return Slot.FLOAT;
				case Opcodes.DCONST_0: case Opcodes.DCONST_1: return Slot.DOUBLE;
				case Opcodes.LDC: return constant(((LdcInsnNode) insn).cst);
				case Opcodes.GETSTATIC: return newValue(Type.getType(((FieldInsnNode) insn).desc));
				case Opcodes.NEW: return Slot.uninitialized(Type.getObjectType(((TypeInsnNode) insn).desc), insn);
				default: throw new IllegalArgumentException("unsupported instruction " + insn.getOpcode());
			}
		}

		private Slot constant(Object value) {
			if (value instanceof Integer) return Slot.INT;
			if (value instanceof Float) return Slot.FLOAT;
			if (value instanceof Long) return Slot.LONG;
			if (value instanceof Double) return Slot.DOUBLE;
			if (value instanceof String) return Slot.of(Type.getObjectType("java/lang/String"));
			if (value instanceof Type type) {
				return Slot.of(Type.getObjectType(type.getSort() == Type.METHOD ? "java/lang/invoke/MethodType" : "java/lang/Class"));
			}
			if (value instanceof Handle) return Slot.of(Type.getObjectType("java/lang/invoke/MethodHandle"));
			if (value instanceof ConstantDynamic dynamic) return newValue(Type.getType(dynamic.getDescriptor()));
			throw new IllegalArgumentException("unsupported constant " + value);
		}

		@Override public Slot copyOperation(AbstractInsnNode insn, Slot value) {
			return value;
		}

		@Override public Slot unaryOperation(AbstractInsnNode insn, Slot value) {
			switch (insn.getOpcode()) {
				case Opcodes.INEG: case Opcodes.IINC: case Opcodes.L2I: case Opcodes.F2I: case Opcodes.D2I:
				case Opcodes.I2B: case Opcodes.I2C: case Opcodes.I2S: case Opcodes.ARRAYLENGTH: case Opcodes.INSTANCEOF:
					return Slot.INT;
				case Opcodes.FNEG: case Opcodes.I2F: case Opcodes.L2F: case Opcodes.D2F: return Slot.FLOAT;
				case Opcodes.LNEG: case Opcodes.I2L: case Opcodes.F2L: case Opcodes.D2L: return Slot.LONG;
				case Opcodes.DNEG: case Opcodes.I2D: case Opcodes.L2D: case Opcodes.F2D: return Slot.DOUBLE;
				case Opcodes.GETFIELD: return newValue(Type.getType(((FieldInsnNode) insn).desc));
				case Opcodes.NEWARRAY: return Slot.of(Type.getType("[" + primitiveArrayElement(((IntInsnNode) insn).operand)));
				case Opcodes.ANEWARRAY: return Slot.of(Type.getType("[" + Type.getObjectType(((TypeInsnNode) insn).desc).getDescriptor()));
				case Opcodes.CHECKCAST: return Slot.of(Type.getObjectType(((TypeInsnNode) insn).desc));
				default: return null; // branches, returns, stores to fields, monitors, athrow
			}
		}

		private static String primitiveArrayElement(int code) {
			return switch (code) {
				case Opcodes.T_BOOLEAN -> "Z";
				case Opcodes.T_CHAR -> "C";
				case Opcodes.T_BYTE -> "B";
				case Opcodes.T_SHORT -> "S";
				case Opcodes.T_INT -> "I";
				case Opcodes.T_FLOAT -> "F";
				case Opcodes.T_DOUBLE -> "D";
				case Opcodes.T_LONG -> "J";
				default -> throw new IllegalArgumentException("newarray " + code);
			};
		}

		@Override public Slot binaryOperation(AbstractInsnNode insn, Slot first, Slot second) {
			switch (insn.getOpcode()) {
				case Opcodes.IALOAD: case Opcodes.BALOAD: case Opcodes.CALOAD: case Opcodes.SALOAD:
				case Opcodes.IADD: case Opcodes.ISUB: case Opcodes.IMUL: case Opcodes.IDIV: case Opcodes.IREM:
				case Opcodes.ISHL: case Opcodes.ISHR: case Opcodes.IUSHR: case Opcodes.IAND: case Opcodes.IOR:
				case Opcodes.IXOR: case Opcodes.LCMP: case Opcodes.FCMPL: case Opcodes.FCMPG: case Opcodes.DCMPL:
				case Opcodes.DCMPG:
					return Slot.INT;
				case Opcodes.FALOAD: case Opcodes.FADD: case Opcodes.FSUB: case Opcodes.FMUL: case Opcodes.FDIV:
				case Opcodes.FREM:
					return Slot.FLOAT;
				case Opcodes.LALOAD: case Opcodes.LADD: case Opcodes.LSUB: case Opcodes.LMUL: case Opcodes.LDIV:
				case Opcodes.LREM: case Opcodes.LSHL: case Opcodes.LSHR: case Opcodes.LUSHR: case Opcodes.LAND:
				case Opcodes.LOR: case Opcodes.LXOR:
					return Slot.LONG;
				case Opcodes.DALOAD: case Opcodes.DADD: case Opcodes.DSUB: case Opcodes.DMUL: case Opcodes.DDIV:
				case Opcodes.DREM:
					return Slot.DOUBLE;
				case Opcodes.AALOAD:
					if (first.isReference() && first.type.getSort() == Type.ARRAY) {
						return newValue(Type.getType(first.type.getDescriptor().substring(1)));
					}
					return first == Slot.NULL ? Slot.NULL : Slot.of(OBJECT);
				default: return null; // compares-and-branch, putfield
			}
		}

		@Override public Slot ternaryOperation(AbstractInsnNode insn, Slot first, Slot second, Slot third) {
			return null;
		}

		@Override public Slot naryOperation(AbstractInsnNode insn, List<? extends Slot> values) {
			if (insn.getOpcode() == Opcodes.MULTIANEWARRAY) return newValue(Type.getType(((MultiANewArrayInsnNode) insn).desc));
			if (insn.getOpcode() == Opcodes.INVOKEDYNAMIC) return newValue(Type.getReturnType(((InvokeDynamicInsnNode) insn).desc));
			return newValue(Type.getReturnType(((MethodInsnNode) insn).desc));
		}

		@Override public void returnOperation(AbstractInsnNode insn, Slot value, Slot expected) {
		}

		@Override public Slot merge(Slot value, Slot other) {
			return value; // never asked: frames are read from the class file, as the verifier reads them
		}
	}

	/** An operand an instruction pops and the type the verifier requires of it. */
	private record Use(int stackIndex, Type required) {
	}

	/** One linear pass over a method, the way the split verifier walks it: frames replace the state, nothing merges. */
	private static final class Walk {
		private final ClassNode owner;
		private final MethodNode method;
		private final Predicate<String> deferrable;
		private final Type self;
		private final Types types = new Types();
		private final Map<LabelNode, FrameNode> frameAt = new IdentityHashMap<>();
		private final Map<LabelNode, AbstractInsnNode> newAt = new IdentityHashMap<>();

		Walk(ClassNode owner, MethodNode method, Predicate<String> deferrable) {
			this.owner = owner;
			this.method = method;
			this.deferrable = deferrable;
			this.self = Type.getObjectType(owner.name);
			// Labels stand before the frame and the instruction they mark: a jump target is resolved to its frame, an
			// uninitialized type to the NEW it names.
			List<LabelNode> pending = new ArrayList<>();
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof LabelNode label) {
					pending.add(label);
				} else if (insn instanceof FrameNode frame) {
					for (LabelNode label : pending) frameAt.put(label, frame);
				} else if (insn.getOpcode() >= 0) {
					for (LabelNode label : pending) newAt.put(label, insn);
					pending.clear();
				}
			}
		}

		/**
		 * The types this method's verification would resolve that are still deferrable, or null when the method uses a
		 * construct this pass does not model.
		 */
		Set<String> detect() throws AnalyzerException {
			Set<String> resolved = new TreeSet<>();
			Frame<Slot> state = entry();
			boolean reachable = true;
			List<TryCatchBlockNode> handlers = method.tryCatchBlocks == null ? List.of() : method.tryCatchBlocks;
			Map<TryCatchBlockNode, int[]> ranges = ranges(handlers);
			int index = -1;
			for (AbstractInsnNode insn : method.instructions) {
				index++;
				if (insn instanceof FrameNode frame) {
					if (reachable) edge(state, frame, resolved);
					state = fromFrame(frame);
					reachable = true;
					continue;
				}
				if (insn.getOpcode() < 0) continue;
				if (insn.getOpcode() == Opcodes.JSR || insn.getOpcode() == Opcodes.RET) return null;
				for (TryCatchBlockNode handler : handlers) {
					int[] range = ranges.get(handler);
					if (index < range[0] || index >= range[1]) continue;
					handlerEdge(state, handler, resolved);
				}
				for (Use use : uses(insn, state)) {
					Slot operand = state.getStack(use.stackIndex);
					if (use.required != ANY_ARRAY && operand.isReference()) resolved.addAll(assignability(use.required, operand.type));
				}
				for (LabelNode target : targets(insn)) {
					FrameNode frame = frameAt.get(target);
					if (frame == null) return null;
					Frame<Slot> after = new Frame<>(state);
					popBranchOperands(insn, after);
					edge(after, frame, resolved);
				}
				state = execute(insn, state);
				if (insn instanceof VarInsnNode && insn.getOpcode() >= Opcodes.ISTORE) {
					// A store inside a protected range is checked against the handler again, with the stored type.
					for (TryCatchBlockNode handler : handlers) {
						int[] range = ranges.get(handler);
						if (index >= range[0] && index < range[1]) handlerEdge(state, handler, resolved);
					}
				}
				reachable = !endsFlow(insn);
			}
			for (TryCatchBlockNode handler : handlers) {
				if (handler.type != null) resolved.addAll(assignability(THROWABLE, Type.getObjectType(handler.type)));
			}
			resolved.removeIf(name -> !deferrable.test(name));
			// A catch type is proved a Throwable by loading it, whatever this rewrite does; it is not counted as deferred.
			for (TryCatchBlockNode handler : handlers) if (handler.type != null) resolved.remove(handler.type);
			return resolved;
		}

		/**
		 * Erases every reference type in this method's frames to Object and gives each typed use the checkcast it then
		 * needs. Returns the number of casts inserted, or -1 when the method cannot be rewritten soundly.
		 */
		int rewrite() throws AnalyzerException {
			Map<AbstractInsnNode, Slot[]> original = new IdentityHashMap<>();
			if (!record(original)) return -1;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FrameNode frame) {
					erase(frame.local);
					erase(frame.stack);
				}
			}
			Map<AbstractInsnNode, List<Type>> plans = new LinkedHashMap<>();
			Map<AbstractInsnNode, Integer> deepest = new HashMap<>();
			Frame<Slot> state = entry();
			int casts = 0;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FrameNode frame) {
					state = fromFrame(frame);
					continue;
				}
				if (insn.getOpcode() < 0) continue;
				Slot[] before = original.get(insn);
				List<Use> uses = uses(insn, state);
				if (!uses.isEmpty()) {
					int height = state.getStackSize();
					List<Type> plan = new ArrayList<>(Collections.nCopies(height, (Type) null));
					int lowest = Integer.MAX_VALUE;
					for (Use use : uses) {
						Slot now = state.getStack(use.stackIndex);
						Slot was = before == null ? null : before[use.stackIndex];
						Type cast = castFor(use.required, now, was);
						if (cast == null) continue;
						plan.set(use.stackIndex, cast);
						lowest = Math.min(lowest, use.stackIndex);
						state.setStack(use.stackIndex, Slot.of(cast));
						casts++;
					}
					if (lowest != Integer.MAX_VALUE) {
						plans.put(insn, plan);
						deepest.put(insn, lowest);
					}
				}
				state = execute(insn, state);
			}
			if (casts == 0) return 0; // the erased frames alone settle it
			int spillBase = method.maxLocals, spillMax = 0;
			Map<AbstractInsnNode, Slot[]> heights = new IdentityHashMap<>();
			if (!record(heights)) return -1;
			for (Map.Entry<AbstractInsnNode, List<Type>> entry : plans.entrySet()) {
				AbstractInsnNode insn = entry.getKey();
				Slot[] stack = heights.get(insn);
				if (stack == null) return -1;
				int used = insertCasts(insn, entry.getValue(), deepest.get(insn), stack, spillBase);
				spillMax = Math.max(spillMax, used);
			}
			method.maxLocals = spillBase + spillMax;
			return casts;
		}

		/**
		 * The cast that lets the verifier accept {@code now} where {@code required} is declared without resolving a
		 * deferrable type, or null when none is needed.
		 */
		private Type castFor(Type required, Slot now, Slot was) {
			if (now == null || !now.isReference()) return null;
			if (required == ANY_ARRAY) {
				if (now.type.getSort() == Type.ARRAY) return null;
				if (was != null && was.isReference() && was.type.getSort() == Type.ARRAY) return was.type;
				// No array type to restore: the rewrite cannot be made sound, so the class is left as written.
				throw new IllegalStateException("array use without an array type");
			}
			if (required.equals(OBJECT) || required.equals(now.type)) return null;
			boolean erased = now.type.equals(OBJECT);
			Set<String> needs = assignability(required, now.type);
			boolean defers = needs.stream().anyMatch(deferrable);
			if (!erased && !defers) return null;
			// The value's own type when that resolves nothing new: the verifier then sees exactly what it saw before.
			if (was != null && was.isReference() && !was.type.equals(OBJECT)
					&& assignability(required, was.type).stream().noneMatch(deferrable)) {
				return was.type;
			}
			return required;
		}

		/** Inserts the casts {@code plan} asks for before {@code insn}; returns the spill slots it used. */
		private int insertCasts(AbstractInsnNode insn, List<Type> plan, int lowest, Slot[] stack, int base) {
			InsnList code = new InsnList();
			int top = stack.length - 1;
			int[] slotOf = new int[stack.length];
			int next = 0;
			for (int i = top; i > lowest; i--) {
				slotOf[i] = base + next;
				code.add(new VarInsnNode(store(stack[i]), slotOf[i]));
				next += stack[i].getSize();
			}
			if (plan.get(lowest) != null) code.add(new TypeInsnNode(Opcodes.CHECKCAST, plan.get(lowest).getInternalName()));
			for (int i = lowest + 1; i <= top; i++) {
				code.add(new VarInsnNode(load(stack[i]), slotOf[i]));
				if (plan.get(i) != null) code.add(new TypeInsnNode(Opcodes.CHECKCAST, plan.get(i).getInternalName()));
			}
			method.instructions.insertBefore(insn, code);
			return next;
		}

		private static int store(Slot slot) {
			if (slot == Slot.INT) return Opcodes.ISTORE;
			if (slot == Slot.FLOAT) return Opcodes.FSTORE;
			if (slot == Slot.LONG) return Opcodes.LSTORE;
			if (slot == Slot.DOUBLE) return Opcodes.DSTORE;
			if (slot.uninit != null) throw new IllegalStateException("an uninitialized object cannot be spilled");
			return Opcodes.ASTORE;
		}

		private static int load(Slot slot) {
			return store(slot) - (Opcodes.ISTORE - Opcodes.ILOAD);
		}

		/** Records the stack before every instruction; false when the walk meets something it does not model. */
		private boolean record(Map<AbstractInsnNode, Slot[]> into) throws AnalyzerException {
			Frame<Slot> state = entry();
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FrameNode frame) {
					state = fromFrame(frame);
					continue;
				}
				if (insn.getOpcode() < 0) continue;
				if (insn.getOpcode() == Opcodes.JSR || insn.getOpcode() == Opcodes.RET) return false;
				Slot[] stack = new Slot[state.getStackSize()];
				for (int i = 0; i < stack.length; i++) stack[i] = state.getStack(i);
				into.put(insn, stack);
				state = execute(insn, state);
			}
			return true;
		}

		private static void erase(List<Object> types) {
			if (types == null) return;
			for (int i = 0; i < types.size(); i++) {
				if (types.get(i) instanceof String) types.set(i, "java/lang/Object");
			}
		}

		/** The operands {@code insn} pops whose type the verifier checks, with the type it requires of each. */
		private List<Use> uses(AbstractInsnNode insn, Frame<Slot> state) {
			int top = state.getStackSize() - 1;
			List<Use> uses = new ArrayList<>();
			switch (insn.getOpcode()) {
				case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKESTATIC -> {
					MethodInsnNode call = (MethodInsnNode) insn;
					Type[] arguments = Type.getArgumentTypes(call.desc);
					int first = top - arguments.length + 1;
					for (int i = 0; i < arguments.length; i++) reference(uses, first + i, arguments[i]);
					if (insn.getOpcode() != Opcodes.INVOKESTATIC) {
						Slot receiver = state.getStack(first - 1);
						if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {
							if (!call.name.equals("<init>")) uses.add(new Use(first - 1, self));
						} else if (receiver.uninit == null) {
							uses.add(new Use(first - 1, Type.getObjectType(call.owner)));
						}
					}
				}
				case Opcodes.INVOKEDYNAMIC -> {
					Type[] arguments = Type.getArgumentTypes(((InvokeDynamicInsnNode) insn).desc);
					int first = top - arguments.length + 1;
					for (int i = 0; i < arguments.length; i++) reference(uses, first + i, arguments[i]);
				}
				case Opcodes.GETFIELD -> {
					if (state.getStack(top).uninit == null) uses.add(new Use(top, Type.getObjectType(((FieldInsnNode) insn).owner)));
				}
				case Opcodes.PUTFIELD -> {
					FieldInsnNode field = (FieldInsnNode) insn;
					if (state.getStack(top - 1).uninit == null) uses.add(new Use(top - 1, Type.getObjectType(field.owner)));
					reference(uses, top, Type.getType(field.desc));
				}
				case Opcodes.PUTSTATIC -> reference(uses, top, Type.getType(((FieldInsnNode) insn).desc));
				case Opcodes.ARETURN -> reference(uses, top, Type.getReturnType(method.desc));
				case Opcodes.ATHROW -> uses.add(new Use(top, THROWABLE));
				case Opcodes.AALOAD, Opcodes.IALOAD, Opcodes.LALOAD, Opcodes.FALOAD, Opcodes.DALOAD, Opcodes.BALOAD,
						Opcodes.CALOAD, Opcodes.SALOAD -> uses.add(new Use(top - 1, ANY_ARRAY));
				case Opcodes.AASTORE, Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE,
						Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE -> uses.add(new Use(top - 2, ANY_ARRAY));
				case Opcodes.ARRAYLENGTH -> uses.add(new Use(top, ANY_ARRAY));
				default -> { }
			}
			return uses;
		}

		private static void reference(List<Use> uses, int index, Type declared) {
			if (declared.getSort() == Type.OBJECT || declared.getSort() == Type.ARRAY) uses.add(new Use(index, declared));
		}

		/**
		 * The class names HotSpot's verifier may load to decide that {@code from} is assignable to {@code to}: none when
		 * the names settle it, otherwise the target (it must learn whether that is an interface) and the source (to walk
		 * its superclasses when it is not), and the element types for two reference arrays.
		 */
		static Set<String> assignability(Type to, Type from) {
			if (to.equals(from) || to.equals(OBJECT)) return Set.of();
			if (to.getSort() == Type.ARRAY) {
				if (from.getSort() != Type.ARRAY) return Set.of();
				Type toElement = Type.getType(to.getDescriptor().substring(1));
				Type fromElement = Type.getType(from.getDescriptor().substring(1));
				boolean references = (toElement.getSort() == Type.OBJECT || toElement.getSort() == Type.ARRAY)
						&& (fromElement.getSort() == Type.OBJECT || fromElement.getSort() == Type.ARRAY);
				return references ? assignability(toElement, fromElement) : Set.of();
			}
			if (to.getSort() != Type.OBJECT) return Set.of();
			if (from.getSort() == Type.ARRAY) return Set.of(); // Object, Cloneable and Serializable are decided by name
			return Set.of(to.getInternalName(), from.getInternalName());
		}

		private void edge(Frame<Slot> incoming, FrameNode frame, Set<String> resolved) {
			Frame<Slot> declared = fromFrame(frame);
			for (int i = 0; i < Math.min(incoming.getLocals(), declared.getLocals()); i++) {
				compare(declared.getLocal(i), incoming.getLocal(i), resolved);
			}
			int height = Math.min(incoming.getStackSize(), declared.getStackSize());
			for (int i = 0; i < height; i++) compare(declared.getStack(i), incoming.getStack(i), resolved);
		}

		private void handlerEdge(Frame<Slot> incoming, TryCatchBlockNode handler, Set<String> resolved) {
			FrameNode frame = frameAt.get(handler.handler);
			if (frame == null) return;
			Frame<Slot> declared = fromFrame(frame);
			for (int i = 0; i < Math.min(incoming.getLocals(), declared.getLocals()); i++) {
				compare(declared.getLocal(i), incoming.getLocal(i), resolved);
			}
			if (declared.getStackSize() == 1) {
				Type caught = handler.type == null ? THROWABLE : Type.getObjectType(handler.type);
				compare(declared.getStack(0), Slot.of(caught), resolved);
			}
		}

		private static void compare(Slot declared, Slot incoming, Set<String> resolved) {
			if (declared != null && incoming != null && declared.isReference() && incoming.isReference()) {
				resolved.addAll(assignability(declared.type, incoming.type));
			}
		}

		/** Each handler's protected range as instruction indices, end exclusive. */
		private Map<TryCatchBlockNode, int[]> ranges(List<TryCatchBlockNode> handlers) {
			Map<TryCatchBlockNode, int[]> ranges = new IdentityHashMap<>();
			for (TryCatchBlockNode handler : handlers) {
				ranges.put(handler, new int[] {method.instructions.indexOf(handler.start), method.instructions.indexOf(handler.end)});
			}
			return ranges;
		}

		private Frame<Slot> entry() {
			Frame<Slot> frame = new Frame<>(method.maxLocals, method.maxStack);
			int local = 0;
			if ((method.access & Opcodes.ACC_STATIC) == 0) {
				frame.setLocal(local++, method.name.equals("<init>") ? Slot.uninitialized(self, Slot.THIS) : Slot.of(self));
			}
			for (Type argument : Type.getArgumentTypes(method.desc)) {
				Slot value = types.newValue(argument);
				frame.setLocal(local++, value);
				if (value.getSize() == 2) frame.setLocal(local++, Slot.TOP);
			}
			while (local < method.maxLocals) frame.setLocal(local++, Slot.TOP);
			frame.setReturn(types.newValue(Type.getReturnType(method.desc)));
			return frame;
		}

		private Frame<Slot> fromFrame(FrameNode node) {
			Frame<Slot> frame = new Frame<>(method.maxLocals, method.maxStack);
			int local = 0;
			if (node.local != null) {
				for (Object type : node.local) {
					Slot value = slot(type);
					frame.setLocal(local++, value);
					if (value.getSize() == 2) frame.setLocal(local++, Slot.TOP);
				}
			}
			while (local < method.maxLocals) frame.setLocal(local++, Slot.TOP);
			if (node.stack != null) for (Object type : node.stack) frame.push(slot(type));
			frame.setReturn(types.newValue(Type.getReturnType(method.desc)));
			return frame;
		}

		private Slot slot(Object type) {
			if (type instanceof String name) return Slot.of(Type.getObjectType(name));
			if (type instanceof LabelNode label) {
				AbstractInsnNode site = newAt.get(label);
				if (!(site instanceof TypeInsnNode created) || site.getOpcode() != Opcodes.NEW) {
					throw new IllegalStateException("uninitialized type without its NEW");
				}
				return Slot.uninitialized(Type.getObjectType(created.desc), site);
			}
			if (type == Opcodes.TOP) return Slot.TOP;
			if (type == Opcodes.INTEGER) return Slot.INT;
			if (type == Opcodes.FLOAT) return Slot.FLOAT;
			if (type == Opcodes.LONG) return Slot.LONG;
			if (type == Opcodes.DOUBLE) return Slot.DOUBLE;
			if (type == Opcodes.NULL) return Slot.NULL;
			if (type == Opcodes.UNINITIALIZED_THIS) return Slot.uninitialized(self, Slot.THIS);
			throw new IllegalStateException("unknown frame type " + type);
		}

		/** Executes {@code insn} on a copy of {@code state}, initializing every copy of an object its constructor ran on. */
		private Frame<Slot> execute(AbstractInsnNode insn, Frame<Slot> state) throws AnalyzerException {
			Frame<Slot> next = new Frame<>(state);
			Slot constructed = null;
			if (insn.getOpcode() == Opcodes.INVOKESPECIAL && ((MethodInsnNode) insn).name.equals("<init>")) {
				int arguments = Type.getArgumentTypes(((MethodInsnNode) insn).desc).length;
				constructed = state.getStack(state.getStackSize() - arguments - 1);
			}
			next.execute(insn, types);
			if (constructed != null && constructed.uninit != null) {
				Slot ready = Slot.of(constructed.type);
				for (int i = 0; i < next.getLocals(); i++) if (next.getLocal(i).equals(constructed)) next.setLocal(i, ready);
				for (int i = 0; i < next.getStackSize(); i++) if (next.getStack(i).equals(constructed)) next.setStack(i, ready);
			}
			return next;
		}

		private static boolean endsFlow(AbstractInsnNode insn) {
			int opcode = insn.getOpcode();
			return opcode == Opcodes.GOTO || opcode == Opcodes.ATHROW || opcode == Opcodes.TABLESWITCH
					|| opcode == Opcodes.LOOKUPSWITCH || (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN);
		}

		private static List<LabelNode> targets(AbstractInsnNode insn) {
			if (insn instanceof JumpInsnNode jump) return List.of(jump.label);
			if (insn instanceof TableSwitchInsnNode table) {
				List<LabelNode> all = new ArrayList<>(table.labels);
				all.add(table.dflt);
				return all;
			}
			if (insn instanceof LookupSwitchInsnNode lookup) {
				List<LabelNode> all = new ArrayList<>(lookup.labels);
				all.add(lookup.dflt);
				return all;
			}
			return List.of();
		}

		/** The state a branch target sees: the branch's own operands are gone. */
		private static void popBranchOperands(AbstractInsnNode insn, Frame<Slot> frame) {
			int operands = switch (insn.getOpcode()) {
				case Opcodes.GOTO, Opcodes.JSR -> 0;
				case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT,
						Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> 2;
				default -> 1; // the single-operand ifs and both switches
			};
			for (int i = 0; i < operands; i++) frame.pop();
		}
	}
}
