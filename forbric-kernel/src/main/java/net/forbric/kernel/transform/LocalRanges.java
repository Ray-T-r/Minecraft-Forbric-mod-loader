/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import net.forbric.kernel.util.ForbricLog;

/**
 * Gives a merged method's local back the variable-table range that covers its own uses.
 *
 * <p>A compiler starts a local's range right after the store that declares it, so every read of the local is inside its
 * range. The merged base splices a carrier's statements into vanilla's body, and where it puts them between a local's
 * declaring store and the label its range starts at, the carrier's code reads and writes the local outside its range:
 * {@code ServerLevel.explode} stores {@code explosion}, then MinecraftForge's {@code if (onExplosionStart(this, explosion))
 * return;} and only then the label {@code explosion}'s range starts at; {@code BowItem.releaseUsing} stores
 * {@code timeHeld}, then both carriers' arrow-loose hooks store it again and test it, and only then its range begins.
 * Mixin and MixinExtras read locals off that table: a {@code @Local(name = "explosion")} of a {@code RETURN} handler is not
 * there at MinecraftForge's early return, and a {@code @ModifyVariable} at {@code timeHeld}'s store cannot bind the store
 * vanilla's own code makes — where, natively, the local is in scope.
 *
 * <p>A range is moved back to just after a store only when that is exactly the local's own stretch of code: the range's
 * first label is preceded by reads of its slot that no other entry of the slot covers; every value those reads, and the
 * range's first instruction, can see was stored in the stretch from the earliest such store on; every store in the stretch
 * stores the local's type; no other entry of the slot overlaps the stretch; and nothing jumps into the stretch, nor does a
 * frame inside it re-declare the locals. Then the stretch already holds the local as the verifier and every frame see it;
 * only the debug table said otherwise, and no instruction changes. Vanilla's own classes, compiled with whole ranges, never
 * meet the condition. The trigger is the method's bytecode alone; {@code -Dforbric.localRanges=off} leaves every table as
 * merged.
 */
public final class LocalRanges implements ClassTransformer {
	public static final String PROPERTY = "forbric.localRanges";

	private final Predicate<String> mergedGameClass;

	public LocalRanges() {
		this(owner -> {
			for (net.forbric.api.Ecosystem ecosystem : net.forbric.api.Ecosystem.values()) {
				if (net.forbric.kernel.mixin.NativeGameReferences.has(ecosystem, owner)) return true;
			}
			return false;
		});
	}

	/** {@code mergedGameClass}: whether an internal name is a class of the merged base (one with a native body). */
	LocalRanges(Predicate<String> mergedGameClass) {
		this.mergedGameClass = mergedGameClass;
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric:local-ranges";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.scanned(enabled() ? "every merged game class whose variable table starts a local after its uses"
				: "switched off by -D" + PROPERTY);
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!enabled() || classBytes == null || classBytes.length == 0) return classBytes;
		if (context != null && (context.getEcosystem() != null || context.getSourceModId() != null)) return classBytes;
		if (!mergedGameClass.test(className.replace('.', '/'))) return classBytes;
		try {
			ClassNode node = new ClassNode();
			new ClassReader(classBytes).accept(node, 0);
			int widened = 0;
			for (MethodNode method : node.methods) widened += widen(node.name, method);
			if (widened == 0) return classBytes;
			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			ForbricLog.debug("[Forbric/LocalRanges] %s: %d local range(s) now start at the local's declaring store", className, widened);
			return writer.toByteArray();
		} catch (RuntimeException unreadable) {
			ForbricLog.debug("[Forbric/LocalRanges] %s left as merged (%s)", className, unreadable);
			return classBytes;
		}
	}

	/** Moves each range of {@code method} that starts after uses of its slot back to its declaring store; returns how many. */
	static int widen(String owner, MethodNode method) {
		if (method.localVariables == null || method.localVariables.isEmpty() || method.instructions.size() == 0) return 0;
		Frame<SourceValue>[] frames = null;
		int widened = 0;
		for (LocalVariableNode local : new ArrayList<>(method.localVariables)) {
			int start = method.instructions.indexOf(local.start);
			Type type = Type.getType(local.desc);
			int load = type.getOpcode(Opcodes.ILOAD), store = type.getOpcode(Opcodes.ISTORE);
			List<Integer> outside = new ArrayList<>();
			for (int i = 0; i < start; i++) {
				AbstractInsnNode instruction = method.instructions.get(i);
				if (instruction instanceof VarInsnNode variable && variable.var == local.index && variable.getOpcode() == load
						&& !covered(method, local.index, i)) outside.add(i);
			}
			if (outside.isEmpty()) continue;
			if (frames == null) {
				try {
					frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, method);
				} catch (AnalyzerException unreadable) {
					return widened;
				}
			}
			int from = Integer.MAX_VALUE;
			for (int read : outside) {
				for (int source : stores(method, frames, read, local.index)) from = Math.min(from, source);
			}
			if (from >= start || !(method.instructions.get(from) instanceof VarInsnNode declaring) || declaring.getOpcode() != store) continue;
			if (!ownStretch(method, frames, local, from, start, load, store)) continue;
			AbstractInsnNode after = declaring.getNext();
			LabelNode label;
			if (after instanceof LabelNode existing) label = existing;
			else method.instructions.insert(declaring, label = new LabelNode());
			local.start = label;
			widened++;
		}
		return widened;
	}

	/** The stretch [from, start) is the local's own: its stores, its reads and the value at its range's start all stay in it. */
	private static boolean ownStretch(MethodNode method, Frame<SourceValue>[] frames, LocalVariableNode local, int from, int start, int load,
			int store) {
		Set<AbstractInsnNode> targets = targets(method);
		for (int i = from; i < start; i++) {
			AbstractInsnNode instruction = method.instructions.get(i);
			if (i > from && (instruction instanceof FrameNode || targets.contains(instruction))) return false;
			if (instruction instanceof IincInsnNode increment && increment.var == local.index) return false;
			if (!(instruction instanceof VarInsnNode variable) || variable.var != local.index) continue;
			int opcode = variable.getOpcode();
			if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) {
				// Every store in the stretch stores a value made as exactly the local's type: a slot the compiler reused for
				// another variable (a pattern-matching temporary) is not the local.
				if (opcode != store || !local.desc.equals(producedType(method, frames, i, new java.util.HashSet<>()))) return false;
				// …and each store after the first updates the local: what it stores is computed from the local's value. A slot
				// the compiler reused for an independent value right before the declaration is another variable.
				if (i > from && !readsSlot(method, frames, i, local.index, 8)) return false;
			}
			if (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) {
				if (opcode != load) return false;
				for (int source : stores(method, frames, i, local.index)) if (source < from || source >= start) return false;
			}
		}
		// The value the range begins with was stored in the stretch.
		int first = start;
		while (first < method.instructions.size() && method.instructions.get(first).getOpcode() < 0) first++;
		if (first < method.instructions.size()) {
			for (int source : stores(method, frames, first, local.index)) if (source < from || source >= start) return false;
		}
		// No other entry of the slot overlaps the stretch.
		for (LocalVariableNode other : method.localVariables) {
			if (other == local || other.index != local.index) continue;
			int s = method.instructions.indexOf(other.start), e = method.instructions.indexOf(other.end);
			if (s < start && e > from) return false;
		}
		return true;
	}

	/**
	 * The descriptor of the value instruction {@code at} consumes from the top of the stack, read off the one instruction
	 * that made it (followed through stack copies and local stores and loads); null when it is made in more than one way,
	 * or by an instruction whose result type it cannot tell exactly.
	 */
	private static String producedType(MethodNode method, Frame<SourceValue>[] frames, int at, Set<Integer> seen) {
		Frame<SourceValue> frame = frames[at];
		if (frame == null || frame.getStackSize() == 0 || !seen.add(at)) return null;
		String type = null;
		for (AbstractInsnNode producer : frame.getStack(frame.getStackSize() - 1).insns) {
			String one = typeOf(method, frames, producer, seen);
			if (one == null || type != null && !type.equals(one)) return null;
			type = one;
		}
		return type;
	}

	private static String typeOf(MethodNode method, Frame<SourceValue>[] frames, AbstractInsnNode producer, Set<Integer> seen) {
		int opcode = producer.getOpcode(), index = method.instructions.indexOf(producer);
		if (producer instanceof org.objectweb.asm.tree.MethodInsnNode call) return call.name.equals("<init>") ? null : Type.getReturnType(call.desc).getDescriptor();
		if (producer instanceof org.objectweb.asm.tree.FieldInsnNode field) return opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC ? field.desc : null;
		if (producer instanceof org.objectweb.asm.tree.TypeInsnNode created) {
			if (opcode == Opcodes.NEW || opcode == Opcodes.CHECKCAST) return Type.getObjectType(created.desc).getDescriptor();
			return opcode == Opcodes.ANEWARRAY ? "[" + Type.getObjectType(created.desc).getDescriptor() : null;
		}
		if (opcode == Opcodes.DUP || opcode == Opcodes.DUP_X1 || opcode == Opcodes.DUP_X2) return producedType(method, frames, index, seen);
		if (producer instanceof VarInsnNode variable && opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) {
			List<Integer> sources = stores(method, frames, index, variable.var);
			if (sources.isEmpty()) return parameterType(method, variable.var);
			String type = null;
			for (int source : sources) {
				String one = method.instructions.get(source) instanceof VarInsnNode ? producedType(method, frames, source, seen) : null;
				if (one == null || type != null && !type.equals(one)) return null;
				type = one;
			}
			return type;
		}
		if (opcode >= Opcodes.IADD && opcode <= Opcodes.DREM || opcode >= Opcodes.INEG && opcode <= Opcodes.DNEG
				|| opcode >= Opcodes.ISHL && opcode <= Opcodes.LXOR) {
			return switch ((opcode - Opcodes.IADD) % 4) { case 0 -> "I"; case 1 -> "J"; case 2 -> "F"; default -> "D"; };
		}
		return null;
	}

	/** The declared type of the parameter in {@code slot}, as the descriptor gives it; null for a slot that is no parameter. */
	private static String parameterType(MethodNode method, int slot) {
		int at = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
		if (at == 1 && slot == 0) return null;
		for (Type argument : Type.getArgumentTypes(method.desc)) {
			if (at == slot) return argument.getDescriptor();
			at += argument.getSize();
		}
		return null;
	}

	/** Whether the value instruction {@code at} consumes from the top of the stack is computed from a read of {@code slot}. */
	private static boolean readsSlot(MethodNode method, Frame<SourceValue>[] frames, int at, int slot, int depth) {
		Frame<SourceValue> frame = frames[at];
		if (frame == null || frame.getStackSize() == 0) return false;
		return computedFrom(method, frames, frame.getStack(frame.getStackSize() - 1), slot, depth);
	}

	private static boolean computedFrom(MethodNode method, Frame<SourceValue>[] frames, SourceValue value, int slot, int depth) {
		if (depth <= 0) return false;
		for (AbstractInsnNode producer : value.insns) {
			if (producer instanceof VarInsnNode variable && variable.var == slot && variable.getOpcode() >= Opcodes.ILOAD
					&& variable.getOpcode() <= Opcodes.ALOAD) return true;
			int index = method.instructions.indexOf(producer);
			Frame<SourceValue> frame = index < 0 ? null : frames[index];
			if (frame == null) continue;
			int operands = operands(producer);
			for (int k = Math.max(0, frame.getStackSize() - operands); k < frame.getStackSize(); k++) {
				if (computedFrom(method, frames, frame.getStack(k), slot, depth - 1)) return true;
			}
		}
		return false;
	}

	/** How many stack values an instruction that produces a value consumes: its call arguments, or its arithmetic operands. */
	private static int operands(AbstractInsnNode instruction) {
		int opcode = instruction.getOpcode();
		if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
			return Type.getArgumentTypes(call.desc).length + (opcode == Opcodes.INVOKESTATIC ? 0 : 1);
		}
		if (instruction instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode dynamic) return Type.getArgumentTypes(dynamic.desc).length;
		if (opcode >= Opcodes.IADD && opcode <= Opcodes.DREM || opcode >= Opcodes.ISHL && opcode <= Opcodes.LXOR) return 2;
		if (opcode >= Opcodes.INEG && opcode <= Opcodes.DNEG || opcode >= Opcodes.I2L && opcode <= Opcodes.I2S || opcode == Opcodes.CHECKCAST
				|| opcode == Opcodes.GETFIELD || opcode == Opcodes.DUP) return 1;
		return 0;
	}

	/** The instructions that stored the value {@code slot} holds before instruction {@code at}; empty for a parameter or none. */
	private static List<Integer> stores(MethodNode method, Frame<SourceValue>[] frames, int at, int slot) {
		Frame<SourceValue> frame = frames[at];
		List<Integer> out = new ArrayList<>();
		if (frame == null || slot >= frame.getLocals()) return out;
		for (AbstractInsnNode source : frame.getLocal(slot).insns) out.add(method.instructions.indexOf(source));
		return out;
	}

	private static boolean covered(MethodNode method, int slot, int at) {
		for (LocalVariableNode local : method.localVariables) {
			if (local.index == slot && method.instructions.indexOf(local.start) <= at && at < method.instructions.indexOf(local.end)) return true;
		}
		return false;
	}

	/** Every label something jumps, switches or hands an exception to. */
	private static Set<AbstractInsnNode> targets(MethodNode method) {
		Set<AbstractInsnNode> out = Collections.newSetFromMap(new IdentityHashMap<>());
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof JumpInsnNode jump) out.add(jump.label);
			else if (instruction instanceof TableSwitchInsnNode table) { out.add(table.dflt); out.addAll(table.labels); }
			else if (instruction instanceof LookupSwitchInsnNode lookup) { out.add(lookup.dflt); out.addAll(lookup.labels); }
		}
		if (method.tryCatchBlocks != null) for (TryCatchBlockNode block : method.tryCatchBlocks) out.add(block.handler);
		return out;
	}
}
