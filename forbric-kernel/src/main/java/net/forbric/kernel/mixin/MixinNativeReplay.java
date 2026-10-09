/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/**
 * Re-runs a stretch of a native method's own instructions inside a handler a mixin will merge into the same class: the
 * vanilla control flow that decided whether — and with which locals — a call was reached, where a carrier has moved that
 * call out of the method. The copy keeps the native slots (parameters in place, locals shifted past the handler's own
 * parameters), turns every way out of the stretch (a return, a jump past it) into a jump to {@code skip} with an empty
 * stack, and leaves the code at the stretch's end to the caller.
 *
 * <p>Only a stretch that can run twice without being seen is copied: no field, static or array store, no monitor, throw,
 * subroutine or dynamic call, no exception handler over it, and — for a stretch that makes calls — only calls the caller
 * has shown the merged method still makes on the way to the moved call ({@link #replayable}).
 */
final class MixinNativeReplay {
	private final String owner;
	private final MethodNode method;
	private final Frame<BasicValue>[] frames;
	private final int firstLocal, base;
	private final Map<LabelNode, LabelNode> labels = new HashMap<>();
	private final Map<LabelNode, LabelNode> exits = new LinkedHashMap<>();
	final LabelNode skip = new LabelNode();

	/** A replay of {@code method} (declared by {@code owner}) whose own locals start at slot {@code base} of the handler. */
	MixinNativeReplay(String owner, MethodNode method, int base) {
		this.owner = owner;
		this.method = method;
		this.frames = basic(owner, method);
		int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
		for (Type parameter : Type.getArgumentTypes(method.desc)) slot += parameter.getSize();
		this.firstLocal = slot;
		this.base = base;
	}

	boolean analysed() { return frames != null; }

	/** The handler slot that holds native slot {@code slot}. */
	int slot(int slot) { return slot < firstLocal ? slot : base + slot - firstLocal; }

	/** The first native slot past the parameters. */
	int firstLocal() { return firstLocal; }

	/** The native method's frame before instruction {@code index}. */
	Frame<BasicValue> frame(int index) { return frames == null ? null : frames[index]; }

	/**
	 * Whether instructions {@code [from, to)} can be replayed: none of the forbidden kinds, no exception handler covering
	 * them, and every call they make is in {@code repeated} (a call made anyway on the way to the moved call). A null
	 * {@code repeated} allows no call at all.
	 */
	boolean replayable(int from, int to, Set<String> repeated) {
		if (frames == null || from < 0 || to > method.instructions.size() || from > to) return false;
		for (TryCatchBlockNode block : method.tryCatchBlocks) {
			int start = method.instructions.indexOf(block.start), end = method.instructions.indexOf(block.end);
			if (start < to && end > from) return false;
		}
		for (int i = from; i < to; i++) {
			AbstractInsnNode instruction = method.instructions.get(i);
			int opcode = instruction.getOpcode();
			if (opcode < 0) continue;
			switch (opcode) {
				case Opcodes.PUTFIELD, Opcodes.PUTSTATIC, Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE,
						Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE, Opcodes.MONITORENTER, Opcodes.MONITOREXIT, Opcodes.ATHROW,
						Opcodes.JSR, Opcodes.RET, Opcodes.INVOKEDYNAMIC -> { return false; }
				default -> { }
			}
			if (instruction instanceof MethodInsnNode call && (repeated == null || !repeated.contains(MixinPlayerWorldCallbackAdapter.member(call)))) return false;
			if (repeated == null && (opcode == Opcodes.NEW || opcode == Opcodes.NEWARRAY || opcode == Opcodes.ANEWARRAY || opcode == Opcodes.MULTIANEWARRAY)) return false;
		}
		return true;
	}

	/** Whether every jump into {@code (from, to]} comes from inside {@code [from, to)}: the only way to {@code to} is through the stretch. */
	boolean enteredOnlyFrom(int from, int to) {
		for (AbstractInsnNode instruction : method.instructions) {
			int at = method.instructions.indexOf(instruction);
			if (at >= from && at < to) continue;
			for (LabelNode label : targets(instruction)) {
				int target = method.instructions.indexOf(label);
				if (target > from && target <= to) return false;
			}
		}
		for (TryCatchBlockNode block : method.tryCatchBlocks) {
			int handler = method.instructions.indexOf(block.handler);
			if (handler > from && handler <= to) return false;
		}
		return true;
	}

	/**
	 * Appends instructions {@code [from, to)} to {@code code}. A load of the receiver is cast to the declaring class, which
	 * is what the handler's receiver becomes once merged. Every exit leaves through a trampoline that empties the stack.
	 */
	void copy(InsnList code, int from, int to) {
		for (int i = from; i < to; i++) {
			AbstractInsnNode instruction = method.instructions.get(i);
			if (instruction instanceof LabelNode label) { code.add(label(label)); continue; }
			if (instruction instanceof LineNumberNode || instruction instanceof FrameNode) continue;
			int opcode = instruction.getOpcode();
			if (instruction instanceof VarInsnNode variable) {
				code.add(new VarInsnNode(opcode, slot(variable.var)));
				if (opcode == Opcodes.ALOAD && variable.var == 0 && (method.access & Opcodes.ACC_STATIC) == 0)
					code.add(new TypeInsnNode(Opcodes.CHECKCAST, owner));
				continue;
			}
			if (instruction instanceof IincInsnNode increment) { code.add(new IincInsnNode(slot(increment.var), increment.incr)); continue; }
			if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
				pop(code, frames[i]);
				code.add(new JumpInsnNode(Opcodes.GOTO, skip));
				continue;
			}
			if (instruction instanceof JumpInsnNode jump) { code.add(new JumpInsnNode(opcode, target(jump.label, from, to))); continue; }
			if (instruction instanceof TableSwitchInsnNode table) {
				LabelNode[] targets = table.labels.stream().map(l -> target(l, from, to)).toArray(LabelNode[]::new);
				code.add(new TableSwitchInsnNode(table.min, table.max, target(table.dflt, from, to), targets));
				continue;
			}
			if (instruction instanceof LookupSwitchInsnNode lookup) {
				LabelNode[] targets = lookup.labels.stream().map(l -> target(l, from, to)).toArray(LabelNode[]::new);
				code.add(new LookupSwitchInsnNode(target(lookup.dflt, from, to), lookup.keys.stream().mapToInt(Integer::intValue).toArray(), targets));
				continue;
			}
			code.add(instruction.clone(labels));
		}
	}

	/** Appends the trampolines {@link #copy} jumped to: each empties the stack the native method had at that label, then skips. */
	void trampolines(InsnList code) {
		for (var exit : exits.entrySet()) {
			code.add(exit.getValue());
			pop(code, frames[method.instructions.indexOf(exit.getKey())]);
			code.add(new JumpInsnNode(Opcodes.GOTO, skip));
		}
	}

	/** Pops everything on the stack of {@code frame}. */
	static void pop(InsnList code, Frame<BasicValue> frame) {
		for (int i = frame.getStackSize() - 1; i >= 0; i--) code.add(new InsnNode(frame.getStack(i).getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
	}

	/**
	 * The reference type native slot {@code slot} holds before instruction {@code index}: the type its every store put there
	 * (a call's return type, a cast, an allocation, a field, a parameter). Null when the stores disagree, a store is of a
	 * primitive, or the slot is not defined there.
	 */
	Type slotType(int index, int slot) {
		Frame<SourceValue>[] sources = MixinCallbackProofs.sources(owner, method);
		if (sources == null || sources[index] == null || slot >= sources[index].getLocals()) return null;
		SourceValue held = sources[index].getLocal(slot);
		if (held.insns.isEmpty()) return null;
		Type found = null;
		for (AbstractInsnNode store : held.insns) {
			if (store.getOpcode() != Opcodes.ASTORE) return null;
			Frame<SourceValue> at = sources[method.instructions.indexOf(store)];
			SourceValue value = at.getStack(at.getStackSize() - 1);
			if (value.insns.size() != 1) return null;
			AbstractInsnNode producer = value.insns.iterator().next();
			Type type = switch (producer) {
				case MethodInsnNode call -> Type.getReturnType(call.desc);
				case TypeInsnNode typed when typed.getOpcode() == Opcodes.CHECKCAST || typed.getOpcode() == Opcodes.NEW -> Type.getObjectType(typed.desc);
				case FieldInsnNode field -> Type.getType(field.desc);
				default -> null;
			};
			if (type == null || type.getSort() != Type.OBJECT && type.getSort() != Type.ARRAY || found != null && !found.equals(type)) return null;
			found = type;
		}
		return found;
	}

	private LabelNode label(LabelNode original) {
		return labels.computeIfAbsent(original, l -> new LabelNode());
	}

	private LabelNode target(LabelNode original, int from, int to) {
		int at = method.instructions.indexOf(original);
		if (at >= from && at < to) return label(original);
		return exits.computeIfAbsent(original, l -> new LabelNode());
	}

	private static List<LabelNode> targets(AbstractInsnNode instruction) {
		if (instruction instanceof JumpInsnNode jump) return List.of(jump.label);
		if (instruction instanceof TableSwitchInsnNode table) {
			List<LabelNode> out = new java.util.ArrayList<>(table.labels); out.add(table.dflt); return out;
		}
		if (instruction instanceof LookupSwitchInsnNode lookup) {
			List<LabelNode> out = new java.util.ArrayList<>(lookup.labels); out.add(lookup.dflt); return out;
		}
		return List.of();
	}

	private static Frame<BasicValue>[] basic(String owner, MethodNode method) {
		try {
			return new Analyzer<>(new BasicInterpreter()).analyze(owner, method);
		} catch (AnalyzerException | RuntimeException invalid) {
			return null;
		}
	}

	// ---- the merged method still reaches the call -------------------------------------------------------------------

	/**
	 * Whether {@code from} (declared by {@code owner}) reaches a call of {@code member} through the methods it calls or
	 * hands over as lambdas, at most {@code depth} calls deep, reading each callee's class with {@code classes}. Every call
	 * made by a method on the way is added to {@code made}.
	 */
	static boolean reaches(ClassNode owner, MethodNode from, String member, Function<String, ClassNode> classes, int depth, Set<String> made) {
		return reaches(owner, from, member, classes, depth, made, new HashSet<>());
	}

	private static boolean reaches(ClassNode owner, MethodNode from, String member, Function<String, ClassNode> classes, int depth, Set<String> made,
			Set<String> seen) {
		if (from == null || from.instructions == null || !seen.add(owner.name + '.' + from.name + from.desc)) return false;
		boolean found = false;
		for (AbstractInsnNode instruction : from.instructions) {
			String calleeOwner = null, calleeName = null, calleeDesc = null;
			if (instruction instanceof MethodInsnNode call) {
				String called = MixinPlayerWorldCallbackAdapter.member(call);
				made.add(called);
				if (called.equals(member)) { found = true; continue; }
				calleeOwner = call.owner; calleeName = call.name; calleeDesc = call.desc;
			} else if (instruction instanceof InvokeDynamicInsnNode dynamic && dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
					&& dynamic.bsmArgs.length > 1 && dynamic.bsmArgs[1] instanceof Handle handle) {
				calleeOwner = handle.getOwner(); calleeName = handle.getName(); calleeDesc = handle.getDesc();
			}
			if (calleeOwner == null || depth <= 0) continue;
			ClassNode callee;
			try {
				callee = calleeOwner.equals(owner.name) ? owner : classes.apply(calleeOwner);
			} catch (RuntimeException unreadable) {
				callee = null;
			}
			if (callee == null || callee.methods == null) continue;
			MethodNode body = MixinPlayerWorldCallbackAdapter.selector(callee, calleeName + calleeDesc);
			if (reaches(callee, body, member, classes, depth - 1, made, seen)) found = true;
		}
		return found;
	}

	// ---- frames ------------------------------------------------------------------------------------------------------

	/**
	 * Computes {@code method}'s stack map frames and maxima as they stand in {@code mixin}, resolving the common
	 * supertype of two classes through {@code classes} (the merged game) and falling back to {@code Object}.
	 */
	static void frames(ClassNode mixin, MethodNode method, Function<String, ClassNode> classes) {
		ClassNode shell = new ClassNode();
		shell.version = mixin.version;
		shell.access = mixin.access;
		shell.name = mixin.name;
		shell.superName = mixin.superName == null ? "java/lang/Object" : mixin.superName;
		shell.interfaces = mixin.interfaces;
		MethodNode copy = new MethodNode(method.access, method.name, method.desc, method.signature,
				method.exceptions == null ? null : method.exceptions.toArray(String[]::new));
		method.accept(copy);
		copy.visibleAnnotations = null; copy.invisibleAnnotations = null;
		copy.visibleParameterAnnotations = null; copy.invisibleParameterAnnotations = null;
		for (AbstractInsnNode instruction : copy.instructions.toArray()) if (instruction instanceof FrameNode) copy.instructions.remove(instruction);
		shell.methods.add(copy);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
			@Override protected String getCommonSuperClass(String a, String b) { return common(a, b, classes); }
		};
		shell.accept(writer);
		ClassNode computed = new ClassNode();
		new ClassReader(writer.toByteArray()).accept(computed, 0);
		MethodNode result = computed.methods.getFirst();
		method.instructions = result.instructions;
		method.tryCatchBlocks = result.tryCatchBlocks;
		method.maxStack = result.maxStack;
		method.maxLocals = result.maxLocals;
		method.localVariables = null;
	}

	private static String common(String a, String b, Function<String, ClassNode> classes) {
		Set<String> ancestors = new HashSet<>();
		for (String type = a; type != null; type = superOf(type, classes)) ancestors.add(type);
		for (String type = b; type != null; type = superOf(type, classes)) if (ancestors.contains(type)) return type;
		return "java/lang/Object";
	}

	private static String superOf(String type, Function<String, ClassNode> classes) {
		if (type.equals("java/lang/Object")) return null;
		try {
			ClassNode node = classes.apply(type);
			if (node != null) return (node.access & Opcodes.ACC_INTERFACE) != 0 ? "java/lang/Object" : node.superName;
			Class<?> loaded = Class.forName(type.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
			return loaded.isInterface() || loaded.getSuperclass() == null ? "java/lang/Object" : Type.getInternalName(loaded.getSuperclass());
		} catch (Throwable unknown) {
			return "java/lang/Object";
		}
	}
}
