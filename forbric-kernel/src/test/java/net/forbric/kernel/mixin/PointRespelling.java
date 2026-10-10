/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Rewrites a mixin's {@code @At} targets and {@code method} selectors into the other ways Mixin reads the same member or
 * method, independently of the code under test: whitespace (Mixin's MemberInfo strips it), a dotted owner, an owner
 * prefix on a selector, an array of two spellings, and — only where the method the handler was written for, in the class
 * the mod was compiled against, then selects exactly that member's instructions — a target without its owner or without
 * its descriptor. Each rewrite says how many it made, so a test can insist its premise was met.
 */
final class PointRespelling {
	private PointRespelling() { }

	/** A rewrite of a fully spelled member target; {@code native} says whether it needs the written method to decide it. */
	record Form(String id, Function<MixinFit.Member, String> spell, boolean dropsOwner, boolean dropsDesc) { }

	private static String sep(MixinFit.Member m) { return m.desc().startsWith("(") ? "" : ":"; }

	static final List<Form> SAME_MEMBER = List.of(
			new Form("with whitespace", m -> "L" + m.owner() + "; " + m.name() + " " + sep(m) + m.desc(), false, false),
			new Form("dotted owner", m -> m.owner().replace('/', '.') + "." + m.name() + sep(m) + m.desc(), false, false));
	static final List<Form> READ_IN_THE_WRITTEN_METHOD = List.of(
			new Form("no owner", m -> m.name() + sep(m) + m.desc(), true, false),
			new Form("no descriptor", m -> "L" + m.owner() + ";" + m.name(), false, true),
			new Form("dotted owner, no descriptor", m -> " " + m.owner().replace('/', '.') + " . " + m.name(), false, true));

	static List<Form> allForms() {
		List<Form> all = new ArrayList<>(SAME_MEMBER);
		all.addAll(READ_IN_THE_WRITTEN_METHOD);
		return all;
	}

	/**
	 * Respells every fully spelled {@code @At} target of the injectors {@code which} accepts. A form that drops the owner
	 * or the descriptor is applied only where every method {@code written} gives (the methods the handler binds in the
	 * class the mod was compiled against; none when that class is missing) accesses that member and nothing else the
	 * shorter target would also select.
	 */
	static int points(ClassNode mixin, Predicate<MethodNode> which, Form form, Function<MethodNode, List<MethodNode>> written) {
		int changed = 0;
		for (MethodNode method : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(method);
			if (injector == null || !which.test(method)) continue;
			List<MethodNode> bodies = written == null ? List.of() : written.apply(method);
			for (AnnotationNode at : MixinFit.atNodes(injector)) changed += point(at, form, bodies);
			Object slice = MixinFit.value(injector, "slice");
			for (Object one : slice instanceof List<?> list ? list : slice == null ? List.of() : List.of(slice)) {
				if (!(one instanceof AnnotationNode s)) continue;
				for (String end : List.of("from", "to"))
					if (MixinFit.value(s, end) instanceof AnnotationNode at) changed += point(at, form, bodies);
			}
		}
		return changed;
	}

	/** The methods {@code handler} binds in {@code nativeClass} as Mixin binds them; none without the class. */
	static List<MethodNode> bound(MethodNode handler, ClassNode nativeClass) {
		List<MethodNode> bound = nativeClass == null ? null : MixinTargetSelectors.bound(handler, nativeClass);
		return bound == null ? List.of() : bound;
	}

	private static int point(AnnotationNode at, Form form, List<MethodNode> written) {
		String target = MixinFit.asString(MixinFit.value(at, "target"));
		MixinFit.Member member = MixinFit.parseMember(target);
		if (target == null || !target.startsWith("L") || member == null || member.owner() == null || member.desc() == null) return 0;
		if (form.dropsOwner() || form.dropsDesc()) {
			if (written == null || written.isEmpty()) return 0;
			for (MethodNode body : written) if (!onlyThatMember(body, member, form.dropsOwner(), form.dropsDesc())) return 0;
		}
		MixinPlayerWorldCallbackAdapter.set(at, "target", form.spell().apply(member));
		return 1;
	}

	/**
	 * Whether {@code body} has at least one access of {@code member} and no other instruction a target with the same name,
	 * but without the owner ({@code anyOwner}) or the descriptor ({@code anyDesc}), would also select.
	 */
	static boolean onlyThatMember(MethodNode body, MixinFit.Member member, boolean anyOwner, boolean anyDesc) {
		if (body == null || body.instructions == null) return false;
		int same = 0;
		for (AbstractInsnNode insn : body.instructions) {
			String owner, name, desc;
			if (insn instanceof MethodInsnNode call) { owner = call.owner; name = call.name; desc = call.desc; }
			else if (insn instanceof FieldInsnNode field) { owner = field.owner; name = field.name; desc = field.desc; }
			else continue;
			if (!name.equals(member.name()) || !anyOwner && !owner.equals(member.owner()) || !anyDesc && !desc.equals(member.desc())) continue;
			if (!owner.equals(member.owner()) || !desc.equals(member.desc())) return false;
			same++;
		}
		return same > 0;
	}

	/** Selector rewrites Mixin binds to the same method of {@code owner}: owner-qualified, dotted, and both spellings at once. */
	static final Map<String, BiFunction<String, String, List<String>>> SELECTORS = new LinkedHashMap<>();
	static {
		SELECTORS.put("owner-qualified selector", (owner, s) -> List.of("L" + owner + ";" + s));
		SELECTORS.put("dotted-owner selector", (owner, s) -> List.of(owner.replace('/', '.') + "." + s));
		SELECTORS.put("selector in two spellings", (owner, s) -> List.of(s, " L" + owner + "; " + s));
	}

	/** Rewrites every plain selector (no owner, pattern or dynamic form) of the injectors {@code which} accepts. */
	static int selectors(ClassNode mixin, Predicate<MethodNode> which, BiFunction<String, String, List<String>> respell) {
		String owner = MixinFit.mixinTargets(mixin).getFirst();
		int changed = 0;
		for (MethodNode method : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(method);
			if (injector == null || injector.values == null || !which.test(method)) continue;
			for (int i = 0; i + 1 < injector.values.size(); i += 2) {
				if (!"method".equals(injector.values.get(i))) continue;
				List<String> out = new ArrayList<>();
				for (String selector : MixinFit.stringList(injector.values.get(i + 1))) {
					if (plain(selector)) { out.addAll(respell.apply(owner, selector)); changed++; }
					else out.add(selector);
				}
				injector.values.set(i + 1, out);
			}
		}
		return changed;
	}

	private static boolean plain(String selector) {
		String s = selector.trim();
		if (s.isEmpty() || s.endsWith("/") || s.startsWith("@") || s.contains("->") || s.indexOf('.') >= 0) return false;
		int semi = s.indexOf(';'), paren = s.indexOf('(');
		return !(s.startsWith("L") && semi >= 0 && (paren < 0 || semi < paren));
	}

	/**
	 * Puts, at the start of every method of {@code nativeClass} that a handler {@code which} accepts binds, an access of
	 * each member its fully spelled points name — under another owner ({@code otherOwner}) or with another descriptor. A
	 * target without that owner or descriptor then selects a second member there: another callback, not another spelling.
	 * Returns how many accesses it added. The instructions are only ever read, never run or verified.
	 */
	static int crowd(ClassNode mixin, Predicate<MethodNode> which, ClassNode nativeClass, boolean otherOwner) {
		int added = 0;
		for (MethodNode method : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(method);
			if (injector == null || !which.test(method)) continue;
			for (MethodNode body : bound(method, nativeClass)) for (AnnotationNode at : MixinFit.atNodes(injector)) {
				MixinFit.Member member = MixinFit.parseMember(MixinFit.asString(MixinFit.value(at, "target")));
				if (member == null || member.owner() == null || member.desc() == null) continue;
				String owner = otherOwner ? "org/example/elsewhere/Crowd" : member.owner();
				boolean call = member.desc().startsWith("(");
				String desc = otherOwner ? member.desc() : call ? (member.desc().equals("()V") ? "(I)V" : "()V") : "Lorg/example/elsewhere/Crowd;";
				// After the method's last instruction, where no path reaches it: Mixin's scan of the instruction list still
				// selects it, and an adapter that analyses the method's data flow sees the method unchanged. Stack-neutral
				// all the same: default arguments, the access, its value dropped.
				org.objectweb.asm.tree.InsnList access = new org.objectweb.asm.tree.InsnList();
				org.objectweb.asm.Type produced;
				if (call) {
					for (org.objectweb.asm.Type argument : org.objectweb.asm.Type.getArgumentTypes(desc)) access.add(new org.objectweb.asm.tree.InsnNode(zero(argument)));
					access.add(new MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, owner, member.name(), desc, false));
					produced = org.objectweb.asm.Type.getReturnType(desc);
				} else {
					access.add(new FieldInsnNode(org.objectweb.asm.Opcodes.GETSTATIC, owner, member.name(), desc));
					produced = org.objectweb.asm.Type.getType(desc);
				}
				if (produced.getSize() > 0) access.add(new org.objectweb.asm.tree.InsnNode(produced.getSize() == 2 ? org.objectweb.asm.Opcodes.POP2 : org.objectweb.asm.Opcodes.POP));
				access.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ACONST_NULL));
				access.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ATHROW));
				body.instructions.add(access);
				body.maxStack += 2 * org.objectweb.asm.Type.getArgumentTypes(call ? desc : "()V").length + 2;
				added++;
			}
		}
		return added;
	}

	private static int zero(org.objectweb.asm.Type type) {
		return switch (type.getSort()) {
			case org.objectweb.asm.Type.LONG -> org.objectweb.asm.Opcodes.LCONST_0;
			case org.objectweb.asm.Type.FLOAT -> org.objectweb.asm.Opcodes.FCONST_0;
			case org.objectweb.asm.Type.DOUBLE -> org.objectweb.asm.Opcodes.DCONST_0;
			case org.objectweb.asm.Type.OBJECT, org.objectweb.asm.Type.ARRAY -> org.objectweb.asm.Opcodes.ACONST_NULL;
			default -> org.objectweb.asm.Opcodes.ICONST_0;
		};
	}

	/** A copy of {@code node}, so a test can crowd one copy without touching another. */
	static ClassNode copy(ClassNode node) {
		ClassNode copy = new ClassNode();
		node.accept(copy);
		return copy;
	}

	/** Every injector handler. */
	static boolean any(MethodNode method) { return true; }
}
