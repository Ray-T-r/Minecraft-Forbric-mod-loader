/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import net.forbric.kernel.mixin.HandlerFlow;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The roles a component-tooltip dispatcher's calls play, read from the guest's own helper by its data flow — never by the
 * names of the class it calls or of the methods it calls there.
 *
 * <p>The helper walks the vanilla component order, one shared index at a time, and hands a provider registry five
 * moments: whether there is anything to draw at all, before the first component, before and after each component, and
 * after the last. Each is recognised by what decides it: the guard is the one argument-less boolean the helper asks of the
 * registry; "first" is the argument-free tooltip call the helper makes only while its shared index reads zero; "last"
 * the one it makes only when it was handed no component; "before" and "after" the per-component calls, handed the
 * order's entry at the index and at the index less one. The order is the one argument-less {@code List} it reads.
 * fabric-item-api's {@code ItemStackMixin.preAppendTooltip} is such a helper; what it calls is how the kernel's bridge
 * draws the same providers from NeoForge's appenders.
 */
public final class TooltipDispatchRoles {
	static final String STACK = "Lnet/minecraft/world/item/ItemStack;", COMPONENT = "Lnet/minecraft/core/component/DataComponentType;";
	static final String TAIL = "Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;"
			+ "Ljava/util/function/Consumer;Lnet/minecraft/world/item/TooltipFlag;";
	/** A first/last moment: the stack and the tooltip's context, display, line sink and flag. */
	public static final String ENDS = "(" + STACK + TAIL + ")V";
	/** A before/after moment: the stack, the component, the tooltip's context, display, line sink and flag, the visited set. */
	public static final String SIDES = "(" + STACK + COMPONENT + TAIL + "Ljava/util/Set;)V";
	static final String INDEX = "com/llamalad7/mixinextras/sugar/ref/LocalIntRef";

	/** One static member, as {@code owner.name desc} (internal owner name). */
	public record Member(String owner, String name, String desc) {
		static Member of(MethodInsnNode call) { return new Member(call.owner, call.name, call.desc); }
	}

	/** The dispatcher's five registry moments and the order it walks. */
	public record Dispatch(Member guard, Member first, Member last, Member before, Member after, Member order) {
		/** The registry the five moments are asked of. */
		public String registry() { return guard.owner(); }
	}

	private TooltipDispatchRoles() { }

	/**
	 * The helper's dispatch, or null when {@code helper} (a method of {@code owner}) is not such a dispatcher: it must take
	 * a component and a shared index, read no field, and call nothing but its registry's five moments, the order, the
	 * index's get/set, the order's size/get and a visited set's construction and add.
	 */
	public static Dispatch of(String owner, MethodNode helper) {
		if (helper == null || (helper.access & Opcodes.ACC_STATIC) != 0 || !helper.desc.endsWith("L" + INDEX + ";)V")) return null;
		Type[] args = Type.getArgumentTypes(helper.desc);
		int component = -1, index = -1, slot = 1;
		for (Type arg : args) {
			if (arg.getDescriptor().equals(COMPONENT)) { if (component >= 0) return null; component = slot; }
			if (arg.getInternalName().equals(INDEX)) { if (index >= 0) return null; index = slot; }
			slot += arg.getSize();
		}
		if (component < 0 || index < 0) return null;
		HandlerFlow flow = HandlerFlow.of(owner, helper);
		if (flow == null) return null;
		for (AbstractInsnNode instruction : helper.instructions) if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode) return null;
		List<MethodInsnNode> statics = flow.calls(c -> c.getOpcode() == Opcodes.INVOKESTATIC && !c.owner.equals(owner));
		List<MethodInsnNode> guards = only(statics, "()Z"), orders = only(statics, "()Ljava/util/List;"),
				ends = only(statics, ENDS), sides = only(statics, SIDES);
		if (guards.size() != 1 || orders.size() != 1 || ends.size() != 2 || sides.size() != 2
				|| statics.size() != guards.size() + orders.size() + ends.size() + sides.size()) return null;
		String registry = guards.getFirst().owner;
		for (MethodInsnNode call : List.of(ends.get(0), ends.get(1), sides.get(0), sides.get(1))) if (!call.owner.equals(registry)) return null;
		MethodInsnNode order = orders.getFirst();
		int indexSlot = index, componentSlot = component;
		Predicate<MethodInsnNode> indexRead = c -> c.owner.equals(INDEX) && c.name.equals("get") && c.desc.equals("()I")
				&& HandlerFlow.onlyParameter(flow.argument(c, 0), indexSlot);
		boolean closed = flow.onlyCalls(c -> statics.contains(c) || c.owner.equals(INDEX) && HandlerFlow.onlyParameter(flow.argument(c, 0), indexSlot)
				&& (c.name.equals("get") && c.desc.equals("()I") || c.name.equals("set") && c.desc.equals("(I)V"))
				|| c.owner.equals("java/util/List") && (c.name.equals("size") || c.name.equals("get")) && flow.argument(c, 0).contains(order)
				|| c.owner.equals("java/util/HashSet") && (c.name.equals("<init>") && c.desc.equals("()V") || c.name.equals("add")));
		if (!closed) return null;
		// first: decided by the shared index read against zero; last: decided by the component being null.
		MethodInsnNode first = null, last = null;
		for (MethodInsnNode call : ends) {
			boolean onIndex = false, onComponent = false;
			for (JumpInsnNode jump : controlling(flow, call)) {
				Set<AbstractInsnNode> tested = flow.operand(jump, 0, 1);
				if ((jump.getOpcode() == Opcodes.IFEQ || jump.getOpcode() == Opcodes.IFNE)
						&& HandlerFlow.only(tested) instanceof MethodInsnNode read && indexRead.test(read)) onIndex = true;
				if ((jump.getOpcode() == Opcodes.IFNULL || jump.getOpcode() == Opcodes.IFNONNULL) && HandlerFlow.onlyParameter(tested, componentSlot))
					onComponent = true;
			}
			if (onIndex == onComponent) return null;
			if (onIndex) { if (first != null) return null; first = call; } else { if (last != null) return null; last = call; }
		}
		// before: handed the order's entry at the index; after: the entry at the index less one.
		MethodInsnNode before = null, after = null;
		for (MethodInsnNode call : sides) {
			if (!(HandlerFlow.only(flow.argument(call, 1)) instanceof MethodInsnNode entry) || !entry.owner.equals("java/util/List")
					|| !entry.name.equals("get") || !flow.argument(entry, 0).contains(order)) return null;
			AbstractInsnNode position = HandlerFlow.only(flow.argument(entry, 1));
			if (position instanceof MethodInsnNode read && indexRead.test(read)) { if (before != null) return null; before = call; }
			else if (position != null && position.getOpcode() == Opcodes.ISUB && HandlerFlow.only(flow.operand(position, 0, 2)) instanceof MethodInsnNode read
					&& indexRead.test(read) && HandlerFlow.intConstant(HandlerFlow.only(flow.operand(position, 1, 2))) instanceof Integer one && one == 1) {
				if (after != null) return null; after = call;
			} else return null;
		}
		if (first == null || last == null || before == null || after == null) return null;
		return new Dispatch(Member.of(guards.getFirst()), Member.of(first), Member.of(last), Member.of(before), Member.of(after), Member.of(order));
	}

	private static List<MethodInsnNode> only(List<MethodInsnNode> calls, String desc) {
		return calls.stream().filter(c -> c.desc.equals(desc)).toList();
	}

	/** The conditional jumps one of whose ways reaches {@code call} and the other does not. */
	private static List<JumpInsnNode> controlling(HandlerFlow flow, MethodInsnNode call) {
		List<JumpInsnNode> out = new ArrayList<>();
		var instructions = flow.method.instructions;
		for (AbstractInsnNode instruction : instructions) {
			if (!(instruction instanceof JumpInsnNode jump) || jump.getOpcode() == Opcodes.GOTO || jump.getOpcode() == Opcodes.JSR) continue;
			Set<AbstractInsnNode> avoid = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
			avoid.add(jump);
			boolean taken = flow.reaches(instructions.indexOf(jump.label), i -> i == call, avoid);
			boolean fallen = flow.reaches(instructions.indexOf(jump) + 1, i -> i == call, avoid);
			if (taken != fallen) out.add(jump);
		}
		return out;
	}
}
