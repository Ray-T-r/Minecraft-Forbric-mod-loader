/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * A game method as its own code reads, without the delivery bookkeeping {@link NativeDualEventInjector} writes into it.
 *
 * <p>The injector leaves every instruction of the game where it stands and only adds its own around them: the invocation
 * a caller opens at its entry ({@code enterNative}), a scope or recording in front of a hook call and its close right
 * behind it, the handlers after the method's end that close one on an exception out of the call, the settlements on a
 * bypass edge (in place, or in a trampoline that then jumps on to the edge's target), and in a hook's own body the
 * reports of what it constructs and dispatches. A guest mixin is written against the game's code; whatever reads a
 * target body to decide where a guest's injector lands (the straight-line run in front of a hook, which locals hold
 * what at a call, how a return is reached) reads it here, without the kernel's bookkeeping in the way. Since the
 * bookkeeping never removes, reorders or duplicates a game instruction, every point decided on this view (a call and
 * its occurrence, a return and its ordinal, a field access, a local's slot) is the same point in the instrumented body
 * Mixin applies to.
 *
 * <p>Only the shapes the injector writes are taken out, each recognised whole; a method that still mentions the
 * bookkeeping afterwards (a shape this does not know) is left exactly as it was.
 */
public final class DeliveryBookkeeping {
	private static final String API = NativeDualEventInjector.API, SCOPE = NativeDualEventInjector.SCOPE,
			RECORDING = NativeDualEventInjector.RECORDING, INVOCATION = NativeDualEventInjector.INVOCATION;
	private static final Set<String> REPORTS = Set.of("constructed", "nativeConstructed");
	private static final Set<String> MARKS = Set.of("dispatching", "dispatched", "nativeDispatching", "nativeDispatched");

	private DeliveryBookkeeping() { }

	/** Takes the bookkeeping out of every method of {@code node} that has it; how many methods changed. */
	public static int strip(ClassNode node) {
		int changed = 0;
		if (node == null || node.methods == null) return 0;
		for (MethodNode method : node.methods) if (strip(method)) changed++;
		return changed;
	}

	/** Whether {@code method} carries any of the bookkeeping: a call into it, or a frame that declares one of its values. */
	public static boolean mentions(MethodNode method) {
		if (method.instructions == null) return false;
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof MethodInsnNode call && own(call.owner)) return true;
			if (instruction instanceof TypeInsnNode type && own(type.desc)) return true;
			if (instruction instanceof FrameNode frame && (declares(frame.local) || declares(frame.stack))) return true;
		}
		return false;
	}

	/** Takes the bookkeeping out of {@code method} in place; false, and {@code method} untouched, when it has none or an unknown shape. */
	public static boolean strip(MethodNode method) {
		if (!mentions(method)) return false;
		MethodNode copy = new MethodNode(Opcodes.ASM9, method.access, method.name, method.desc, method.signature,
				method.exceptions == null ? null : method.exceptions.toArray(new String[0]));
		method.accept(copy);
		if (!stripCopy(copy) || mentions(copy)) return false;
		method.instructions = copy.instructions;
		method.tryCatchBlocks = copy.tryCatchBlocks;
		method.localVariables = copy.localVariables;
		method.maxStack = copy.maxStack;
		method.maxLocals = copy.maxLocals;
		return true;
	}

	private static boolean stripCopy(MethodNode method) {
		InsnList code = method.instructions;
		Set<AbstractInsnNode> drop = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<LabelNode> handlers = Collections.newSetFromMap(new IdentityHashMap<>());
		Map<LabelNode, LabelNode> trampolines = new IdentityHashMap<>();

		// Close-and-rethrow handlers after the method's end: label, frame, aload k, close, athrow.
		Set<LabelNode> handlerLabels = Collections.newSetFromMap(new IdentityHashMap<>());
		if (method.tryCatchBlocks != null) for (TryCatchBlockNode block : method.tryCatchBlocks) handlerLabels.add(block.handler);
		for (LabelNode label : handlerLabels) {
			List<AbstractInsnNode> body = executables(label, 3);
			if (body.size() == 3 && body.get(0) instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
					&& close(body.get(1)) && body.get(2).getOpcode() == Opcodes.ATHROW) {
				handlers.add(label);
				for (AbstractInsnNode at = label.getNext(); at != null; at = at.getNext()) {
					if (at instanceof LabelNode || at instanceof LineNumberNode) continue;
					drop.add(at);
					if (at == body.get(2)) break;
				}
			}
		}

		// Trampolines: a jump target no instruction falls into (they follow the method's last instruction), then a frame,
		// settlements and a goto to the edge's own target.
		Set<LabelNode> targets = Collections.newSetFromMap(new IdentityHashMap<>());
		for (AbstractInsnNode at = code.getFirst(); at != null; at = at.getNext()) {
			if (at instanceof JumpInsnNode jump) targets.add(jump.label);
			else if (at instanceof TableSwitchInsnNode table) { targets.add(table.dflt); targets.addAll(table.labels); }
			else if (at instanceof LookupSwitchInsnNode lookup) { targets.add(lookup.dflt); targets.addAll(lookup.labels); }
		}
		for (AbstractInsnNode at = code.getFirst(); at != null; at = at.getNext()) {
			if (!(at instanceof LabelNode label) || !targets.contains(label) || !terminal(previous(label))) continue;
			List<AbstractInsnNode> run = new ArrayList<>();
			AbstractInsnNode next = label.getNext();
			while (next instanceof FrameNode || next instanceof LineNumberNode) { run.add(next); next = next.getNext(); }
			int settled = 0;
			while (settlement(next)) {
				AbstractInsnNode end = next;
				for (int i = 0; i < 4; i++) { run.add(end); end = end.getNext(); }
				next = end;
				settled++;
			}
			if (settled > 0 && next instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO) {
				run.add(jump);
				trampolines.put(label, jump.label);
				drop.addAll(run);
			}
		}

		// The rest, instruction by instruction.
		for (AbstractInsnNode at = code.getFirst(); at != null; at = at.getNext()) {
			if (drop.contains(at) || !(at instanceof MethodInsnNode call)) continue;
			if (call.owner.equals(API) && call.name.equals("enterNative")) {
				AbstractInsnNode group = previous(call), store = next(call);
				if (!(group instanceof LdcInsnNode) || !(store instanceof VarInsnNode stored) || stored.getOpcode() != Opcodes.ASTORE) return false;
				drop.add(group);
				drop.add(call);
				drop.add(store);
			} else if (call.owner.equals(API) && (call.name.equals("begin") || call.name.equals("record"))) {
				if (!open(call, drop)) return false;
			} else if (call.owner.equals(API) && call.name.equals("bypassed")) {
				AbstractInsnNode verdict = previous(call), events = verdict == null ? null : previous(verdict),
						invocation = events == null ? null : previous(events);
				if (!(invocation instanceof VarInsnNode) || !(events instanceof LdcInsnNode)
						|| !(verdict.getOpcode() == Opcodes.ICONST_0 || verdict.getOpcode() == Opcodes.ICONST_1)) return false;
				drop.add(invocation);
				drop.add(events);
				drop.add(verdict);
				drop.add(call);
			} else if (call.owner.equals(API) && REPORTS.contains(call.name) && call.desc.equals("(Ljava/lang/Object;)V")) {
				AbstractInsnNode dup = previous(call);
				if (dup == null || dup.getOpcode() != Opcodes.DUP) return false;
				drop.add(dup);
				drop.add(call);
			} else if (call.owner.equals(API) && MARKS.contains(call.name) && call.desc.equals("()V")) {
				drop.add(call);
			} else if (close(call)) {
				AbstractInsnNode load = previous(call);
				if (!(load instanceof VarInsnNode variable) || variable.getOpcode() != Opcodes.ALOAD) return false;
				drop.add(load);
				drop.add(call);
			}
		}

		// Edges into a trampoline go straight to its target again; its handlers' ranges go with them.
		for (AbstractInsnNode at = code.getFirst(); at != null; at = at.getNext()) {
			if (at instanceof JumpInsnNode jump && trampolines.containsKey(jump.label)) jump.label = trampolines.get(jump.label);
			else if (at instanceof TableSwitchInsnNode table) {
				if (trampolines.containsKey(table.dflt)) table.dflt = trampolines.get(table.dflt);
				table.labels.replaceAll(label -> trampolines.getOrDefault(label, label));
			} else if (at instanceof LookupSwitchInsnNode lookup) {
				if (trampolines.containsKey(lookup.dflt)) lookup.dflt = trampolines.get(lookup.dflt);
				lookup.labels.replaceAll(label -> trampolines.getOrDefault(label, label));
			}
		}
		if (method.tryCatchBlocks != null) {
			method.tryCatchBlocks.removeIf(block -> handlers.contains(block.handler) || handlers.contains(block.start)
					|| trampolines.containsKey(block.handler));
		}
		for (AbstractInsnNode instruction : drop) code.remove(instruction);
		for (AbstractInsnNode at = code.getFirst(); at != null; at = at.getNext()) {
			if (at instanceof FrameNode frame) {
				frame.local = forget(frame.local, frame.type == Opcodes.F_FULL || frame.type == Opcodes.F_NEW);
				if (declares(frame.stack)) return false;
			}
		}
		return true;
	}

	/**
	 * A scope or recording opened in front of a hook call: its argument loads, the open, the store of the guard, and, for
	 * an inline post, the hand-over of the posted event (the arguments above it stored aside, the event handed to the
	 * guard, the arguments loaded back).
	 */
	private static boolean open(MethodInsnNode open, Set<AbstractInsnNode> drop) {
		int arguments = Type.getArgumentTypes(open.desc).length;
		List<AbstractInsnNode> loads = new ArrayList<>();
		AbstractInsnNode at = open;
		for (int i = 0; i < arguments; i++) {
			at = previous(at);
			if (!(at instanceof LdcInsnNode || at instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
					|| at != null && at.getOpcode() == Opcodes.ACONST_NULL)) return false;
			loads.add(at);
		}
		AbstractInsnNode store = next(open);
		if (!(store instanceof VarInsnNode guard) || guard.getOpcode() != Opcodes.ASTORE) return false;
		drop.addAll(loads);
		drop.add(open);
		drop.add(store);
		// The hand-over, when the guard is handed the posted event: [xSTORE]* DUP ALOAD guard SWAP INVOKEVIRTUAL hand-over [xLOAD]*.
		List<AbstractInsnNode> handOver = new ArrayList<>();
		AbstractInsnNode next = next(store);
		int stored = 0;
		while (next instanceof VarInsnNode aside && aside.getOpcode() >= Opcodes.ISTORE && aside.getOpcode() <= Opcodes.ASTORE) {
			handOver.add(next);
			stored++;
			next = next(next);
		}
		if (next != null && next.getOpcode() == Opcodes.DUP) {
			AbstractInsnNode load = next(next), swap = load == null ? null : next(load), give = swap == null ? null : next(swap);
			if (load instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ALOAD && variable.var == guard.var
					&& swap.getOpcode() == Opcodes.SWAP && give instanceof MethodInsnNode handed
					&& (handed.owner.equals(SCOPE) || handed.owner.equals(RECORDING)) && handed.desc.equals("(Ljava/lang/Object;)V")) {
				handOver.add(next);
				handOver.add(load);
				handOver.add(swap);
				handOver.add(give);
				AbstractInsnNode back = next(give);
				for (int i = 0; i < stored; i++) {
					if (!(back instanceof VarInsnNode restore) || restore.getOpcode() < Opcodes.ILOAD || restore.getOpcode() > Opcodes.ALOAD) return false;
					handOver.add(back);
					back = next(back);
				}
				drop.addAll(handOver);
			} else if (stored > 0) {
				return false;
			}
		} else if (stored > 0) {
			return false;
		}
		return true;
	}

	/** An instruction after which nothing falls through: a goto, a switch, a return or a throw. */
	private static boolean terminal(AbstractInsnNode at) {
		if (at == null) return false;
		int opcode = at.getOpcode();
		return opcode == Opcodes.GOTO || opcode == Opcodes.ATHROW || opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN
				|| at instanceof TableSwitchInsnNode || at instanceof LookupSwitchInsnNode;
	}

	private static boolean settlement(AbstractInsnNode at) {
		if (!(at instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) return false;
		AbstractInsnNode events = at.getNext(), verdict = events == null ? null : events.getNext(), call = verdict == null ? null : verdict.getNext();
		return events instanceof LdcInsnNode && verdict != null && (verdict.getOpcode() == Opcodes.ICONST_0 || verdict.getOpcode() == Opcodes.ICONST_1)
				&& call instanceof MethodInsnNode settle && settle.owner.equals(API) && settle.name.equals("bypassed");
	}

	private static boolean close(AbstractInsnNode instruction) {
		return instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
				&& (call.owner.equals(SCOPE) || call.owner.equals(RECORDING)) && call.name.equals("close") && call.desc.equals("()V");
	}

	private static boolean own(String internalName) {
		return internalName != null && (internalName.equals(API) || internalName.startsWith(API + "$"));
	}

	private static boolean declares(List<Object> types) {
		if (types == null) return false;
		for (Object type : types) if (type instanceof String name && own(name)) return true;
		return false;
	}

	/**
	 * A frame's locals with every bookkeeping value as {@code TOP}; in a full frame, trailing {@code TOP}s dropped too (an
	 * appended frame keeps its count, which the chops after it rely on).
	 */
	private static List<Object> forget(List<Object> local, boolean full) {
		if (local == null || !declares(local)) return local;
		List<Object> out = new ArrayList<>(local);
		out.replaceAll(type -> type instanceof String name && own(name) ? Opcodes.TOP : type);
		if (full) while (!out.isEmpty() && out.getLast() == Opcodes.TOP) out.removeLast();
		return out;
	}

	/** The first {@code count} executable instructions after {@code from}. */
	private static List<AbstractInsnNode> executables(AbstractInsnNode from, int count) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode at = from.getNext(); at != null && out.size() < count; at = at.getNext()) {
			if (at.getOpcode() >= 0) out.add(at);
		}
		return out;
	}

	private static AbstractInsnNode previous(AbstractInsnNode from) {
		AbstractInsnNode at = from.getPrevious();
		while (at != null && at.getOpcode() < 0) at = at.getPrevious();
		return at;
	}

	private static AbstractInsnNode next(AbstractInsnNode from) {
		AbstractInsnNode at = from.getNext();
		while (at != null && at.getOpcode() < 0) at = at.getNext();
		return at;
	}
}
