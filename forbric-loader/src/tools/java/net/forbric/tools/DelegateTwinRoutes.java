/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * A call into one side's private implementation of a vanilla method follows the merge's choice of that method.
 *
 * <p>An ecosystem sometimes changes what a vanilla method {@code C(args)} does by giving it a mode: it adds an overload
 * {@code C(args, extra)} that holds the real body, turns {@code C(args)} into a delegate that passes the vanilla mode as
 * constants, and has one caller {@code M} pass a different constant instead. That caller now switches part of
 * {@code C} off, because the same ecosystem does that part somewhere else — in its own version of a method that calls
 * {@code M}. The two edits are one mechanism; the per-method merge sees them separately.
 *
 * <p>When the other ecosystem also edited {@code C} and the merge kept that other body for {@code C}, the merge has
 * decided whose implementation of {@code C} the game runs. {@code M}, taken whole from the first ecosystem because the
 * other left it vanilla, still calls the first ecosystem's overload, so on {@code M}'s path the decision is reversed:
 * the kept implementation of {@code C} and every hook in it are skipped, and the part the constant switched off is
 * done by nobody unless the first ecosystem's compensating caller survived the merge too. That is how the merged
 * {@code Block.playerDestroy} ended up calling MinecraftForge's {@code dropResources(..., false)} while the merged
 * {@code ServerPlayerGameMode.destroyBlock} is NeoForge's, which does not pop the experience MinecraftForge's does,
 * and NeoForge's {@code dropResources} — whose {@code handleBlockDrops} posts {@code BlockDropsEvent} and pops the
 * experience — was never reached from a player's break: no experience from mining at all.
 *
 * <p>So such a call is pointed back at {@code C(args)}, the constants dropped, when all of this holds, read from the
 * three games alone:
 * <ul>
 *   <li>one side's {@code C(args)} is a pure delegate: its parameters in order, then constants, then
 *       {@code C(args, extra)} of the same class, then return; vanilla has {@code C(args)} and lacks the overload;</li>
 *   <li>the other side edited {@code C(args)} (its body differs from vanilla's), and the merged {@code C(args)} is not
 *       the first side's delegate;</li>
 *   <li>the merged call pushes only constants for the extra parameters, differing from the delegate's (otherwise it is
 *       {@code C(args)} spelled out, which needs no change), and the vanilla body of the method holding it calls
 *       {@code C(args)} and not the overload;</li>
 *   <li>the first side's compensation is not in the merged game: at least one method of the first side calls the
 *       method holding the site and was changed by that side, and none of those merged callers makes every call its
 *       first-side body added. Where one of them does, the first side's mechanism is whole on that path and the call
 *       is left alone (pointing it back would do the switched-off part twice).</li>
 *   <li>and none of those merged callers was handed a PART of the edit (an edit splice carries runs, not methods) that does
 *       the switched-off part: of the calls the first side's edit added and the other side's version of the caller does
 *       not make, the ones the merged caller makes are each followed into what they do (the void calls their body makes,
 *       two deep, on the first side and its runtime), and compared with what the twin does only in the delegate's mode — the
 *       calls its overload makes under a test of a mode parameter whose constant the site changes, followed into every
 *       declaration a mode parameter is handed to, and what those do in turn. A carried call that shares one of those is
 *       the compensation, and the call is left. When the switched-off part cannot be read that way at all and some of the
 *       edit was carried, the call is left too, for judgment, rather than risk doing the part twice.</li>
 * </ul>
 * On 26.2 the switched-off part of MinecraftForge's {@code dropResources(…, false)} is {@code ForgeHooks.dropXpForBlock}
 * (under {@code BlockBehaviour.spawnAfterBreak}'s test of {@code dropXp}) and the {@code popExperience} it makes; the
 * merged {@code destroyBlock} carries MinecraftForge's {@code onBlockStartBreak} and {@code onPlayerDestroyItem}, which do
 * neither, and not its {@code popExperience} — so the site is pointed back.
 * Anything else stays as merged, and every candidate is reported with what decided it, a retargeted one with the side
 * whose edit (its constants) is given up there. Names of classes, methods or mods decide nothing.
 */
final class DelegateTwinRoutes {
	private DelegateTwinRoutes() {
	}

	/** One rewritten or left call site, for the report. */
	record Decision(String site, String twin, String entry, String side, boolean retargeted, String why) {
		String line() {
			return site + " -> " + twin + (retargeted ? " RETARGETED to " + entry + " (" + side + " edit given up here): " : " LEFT: ") + why;
		}
	}

	/** A side's {@code C(args)} that only forwards to its own {@code C(args, extra)} with constants. */
	private record Twin(String owner, MethodNode entry, MethodInsnNode overload, List<AbstractInsnNode> constants,
			String side) {
	}

	/**
	 * Rewrites qualifying call sites in {@code merged} (class name to node) in place and returns every decision.
	 * {@code changed} receives the names of the classes it rewrote.
	 */
	static List<Decision> route(Map<String, ClassNode> merged, Map<String, byte[]> vanilla, Map<String, byte[]> forge,
			Map<String, byte[]> neo, Set<String> changed) {
		return route(merged, vanilla, forge, neo, Map.of(), Map.of(), changed);
	}

	/**
	 * As above, with each side's runtime classes (its hooks), which the pass reads only to follow a call into what it does:
	 * the part a twin's constants switch off, and a caller's kept edit, are compared by the calls they make there too.
	 */
	static List<Decision> route(Map<String, ClassNode> merged, Map<String, byte[]> vanilla, Map<String, byte[]> forge,
			Map<String, byte[]> neo, Map<String, byte[]> forgeRuntime, Map<String, byte[]> neoRuntime, Set<String> changed) {
		Function<String, ClassNode> v = parser(vanilla), f = parser(forge), n = parser(neo);
		Function<String, ClassNode> fAll = either(f, parser(forgeRuntime)), nAll = either(n, parser(neoRuntime));
		List<Twin> twins = new ArrayList<>();
		for (String side : List.of("forge", "neo")) {
			Map<String, byte[]> own = side.equals("forge") ? forge : neo;
			Function<String, ClassNode> mine = side.equals("forge") ? f : n, theirs = side.equals("forge") ? n : f;
			for (String name : own.keySet()) {
				ClassNode vanillaOwner = v.apply(name), mergedOwner = merged.get(name);
				if (vanillaOwner == null || mergedOwner == null) continue;
				ClassNode sideOwner = mine.apply(name), otherOwner = theirs.apply(name);
				if (otherOwner == null) continue;
				for (MethodNode entry : sideOwner.methods) {
					MethodNode vanillaEntry = method(vanillaOwner, entry.name, entry.desc);
					if (vanillaEntry == null) continue;
					Twin twin = delegate(sideOwner, entry, side);
					if (twin == null || method(vanillaOwner, twin.overload().name, twin.overload().desc) != null) continue;
					if (method(mergedOwner, twin.overload().name, twin.overload().desc) == null) continue;
					MethodNode otherEntry = method(otherOwner, entry.name, entry.desc);
					MethodNode mergedEntry = method(mergedOwner, entry.name, entry.desc);
					if (otherEntry == null || mergedEntry == null || text(otherEntry).equals(text(vanillaEntry))) continue;
					if (text(mergedEntry).equals(text(entry))) continue;   // the merge kept this side's delegate: nothing to follow
					twins.add(twin);
				}
			}
		}
		if (twins.isEmpty()) return List.of();

		List<Decision> decisions = new ArrayList<>();
		Map<String, Map<String, List<String[]>>> callerIndex = new HashMap<>();
		for (Twin twin : twins) {
			Map<String, byte[]> own = twin.side().equals("forge") ? forge : neo;
			Function<String, ClassNode> mine = twin.side().equals("forge") ? f : n, theirs = twin.side().equals("forge") ? n : f;
			Function<String, ClassNode> mineAll = twin.side().equals("forge") ? fAll : nAll;
			Map<String, byte[]> ownAll = new HashMap<>(twin.side().equals("forge") ? forgeRuntime : neoRuntime);
			ownAll.putAll(own);
			for (ClassNode holder : merged.values()) {
				for (MethodNode method : holder.methods) {
					for (AbstractInsnNode insn : method.instructions.toArray()) {
						if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(twin.owner())
								|| !call.name.equals(twin.overload().name) || !call.desc.equals(twin.overload().desc)) continue;
						if (holder.name.equals(twin.owner()) && method.name.equals(twin.entry().name) && method.desc.equals(twin.entry().desc)) continue;
						String site = holder.name + "#" + method.name + method.desc;
						String twinName = twin.owner() + "." + twin.overload().name + twin.overload().desc;
						String entryName = twin.owner() + "." + twin.entry().name + twin.entry().desc;
						List<AbstractInsnNode> pushed = constantsBefore(call, twin.constants().size());
						if (pushed == null) {
							decisions.add(new Decision(site, twinName, entryName, twin.side(), false, "the extra arguments are not constants pushed right before the call"));
							continue;
						}
						if (sameConstants(pushed, twin.constants())) {
							decisions.add(new Decision(site, twinName, entryName, twin.side(), false, "the call passes the delegate's own constants"));
							continue;
						}
						ClassNode vanillaHolder = v.apply(holder.name);
						MethodNode vanillaMethod = vanillaHolder == null ? null : method(vanillaHolder, method.name, method.desc);
						if (vanillaMethod == null || !calls(vanillaMethod, twin.owner(), twin.entry().name, twin.entry().desc)
								|| calls(vanillaMethod, twin.owner(), twin.overload().name, twin.overload().desc)) {
							decisions.add(new Decision(site, twinName, entryName, twin.side(), false, "vanilla's body does not call the entry here"));
							continue;
						}
						String key = holder.name + "#" + method.name + method.desc;
						List<String[]> callers = callerIndex.computeIfAbsent(twin.side(), k -> new HashMap<>())
								.computeIfAbsent(key, k -> callersOf(own, mine, v, holder.name, method.name, method.desc));
						Set<String> switchedOff = switchedOff(twin, pushed, ownAll, mineAll);
						StringBuilder unrelated = new StringBuilder();
						String compensation = compensation(callers, mine, theirs, v, merged, mineAll, switchedOff, unrelated);
						if (compensation != null) {
							decisions.add(new Decision(site, twinName, entryName, twin.side(), false, compensation));
							continue;
						}
						for (AbstractInsnNode constant : pushed) method.instructions.remove(constant);
						call.desc = twin.entry().desc;
						call.name = twin.entry().name;
						changed.add(holder.name);
						decisions.add(new Decision(site, twinName, entryName, twin.side(), true, "the merged " + entryName + " is the "
								+ (twin.side().equals("forge") ? "neo" : "forge") + " body, and no merged caller of this method keeps the "
								+ twin.side() + " edit that the constants " + describe(pushed) + " leave the switched-off part to ("
								+ String.join(", ", callers.stream().map(c -> c[0]).toList()) + ")" + unrelated));
					}
				}
			}
		}
		return decisions;
	}

	/**
	 * Null when the side's compensation is missing from the merged game on every path; otherwise why the call stays.
	 * Each caller is {@code {owner#name(desc)}}: a method of the side that calls the site's method and differs from
	 * vanilla's version of itself.
	 */
	private static String compensation(List<String[]> callers, Function<String, ClassNode> side, Function<String, ClassNode> other,
			Function<String, ClassNode> vanilla, Map<String, ClassNode> merged, Function<String, ClassNode> sideAll, Set<String> switchedOff,
			StringBuilder unrelated) {
		if (callers.isEmpty()) return "no method of that side calls it with an edit of its own, so the constants are the whole change";
		for (String[] caller : callers) {
			String owner = caller[1], name = caller[2], desc = caller[3];
			MethodNode sideBody = method(side.apply(owner), name, desc), vanillaBody = method(vanilla.apply(owner), name, desc);
			ClassNode mergedOwner = merged.get(owner);
			MethodNode mergedBody = mergedOwner == null ? null : method(mergedOwner, name, desc);
			if (sideBody == null || mergedBody == null) continue;
			Map<String, Integer> added = new LinkedHashMap<>(callCounts(sideBody));
			if (vanillaBody != null) callCounts(vanillaBody).forEach((call, count) -> added.computeIfPresent(call, (k, mine) -> mine - count > 0 ? mine - count : null));
			Map<String, Integer> kept = callCounts(mergedBody);
			boolean whole = !added.isEmpty();
			for (Map.Entry<String, Integer> call : added.entrySet()) if (kept.getOrDefault(call.getKey(), 0) < call.getValue()) whole = false;
			if (whole) return caller[0] + " keeps every call that side's edit of it added, so its mechanism is whole on that path";
			// Part of the edit may have been carried into the merged caller (an edit splice). The calls that are that side's own
			// — added by its edit and not also made by the other side's version of the caller, where a shared hook would be a
			// coincidence of names — are the candidates for its compensation.
			MethodNode otherBody = method(other.apply(owner), name, desc);
			Set<String> others = otherBody == null ? Set.of() : callCounts(otherBody).keySet();
			List<String> carried = new ArrayList<>();
			for (String call : added.keySet()) if (!others.contains(call) && kept.containsKey(call)) carried.add(call);
			if (carried.isEmpty()) continue;
			if (switchedOff.isEmpty()) {
				return caller[0] + " keeps part of that side's edit of it (" + String.join(", ", carried) + "), and what the constants switch off "
						+ "cannot be read from the twin: left for judgment rather than risk doing that part twice";
			}
			for (String call : carried) {
				Set<String> does = new LinkedHashSet<>(List.of(call));
				effects(call, sideAll, EFFECT_DEPTH, does, new java.util.HashSet<>());
				does.retainAll(switchedOff);
				if (!does.isEmpty()) {
					return caller[0] + " keeps " + call + " of that side's edit, which does what the part the constants switch off does ("
							+ String.join(", ", does) + "): its compensation survived there, and pointing the call back would do that part twice";
				}
			}
			unrelated.append("; ").append(caller[0]).append(" keeps ").append(String.join(", ", carried))
					.append(" of that side's edit, none of which does what the switched-off part does (").append(String.join(", ", switchedOff)).append(")");
		}
		return null;
	}

	/** How deep a call is followed into what it does, and how deep a twin's mode parameter is followed into the calls it reaches. */
	private static final int EFFECT_DEPTH = 2, FLOW_DEPTH = 4;

	/**
	 * What the twin does only in the delegate's mode: every call the twin's overload — or a method it hands a mode parameter to,
	 * followed into every declaration of that method on the side — makes under a test of a mode parameter whose constant the
	 * call site changes, and the void calls those make in turn (the actions they take). Empty when no such call is found.
	 */
	private static Set<String> switchedOff(Twin twin, List<AbstractInsnNode> pushed, Map<String, byte[]> sideClasses, Function<String, ClassNode> side) {
		MethodNode overload = method(side.apply(twin.owner()), twin.overload().name, twin.overload().desc);
		if (overload == null) return Set.of();
		Type[] parameters = Type.getArgumentTypes(twin.overload().desc);
		int entryArguments = Type.getArgumentTypes(twin.entry().desc).length;
		int slot = (overload.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
		Set<Integer> modes = new java.util.HashSet<>();
		for (int i = 0; i < parameters.length; i++) {
			if (i >= entryArguments && !text(pushed.get(i - entryArguments)).equals(text(twin.constants().get(i - entryArguments)))) modes.add(slot);
			slot += parameters[i].getSize();
		}
		Set<String> guarded = new LinkedHashSet<>();
		guardedCalls(twin.owner(), overload, modes, sideClasses, side, FLOW_DEPTH, guarded, new java.util.HashSet<>());
		Set<String> out = new LinkedHashSet<>(guarded);
		for (String call : guarded) effects(call, side, EFFECT_DEPTH, out, new java.util.HashSet<>());
		return out;
	}

	private static void guardedCalls(String owner, MethodNode method, Set<Integer> modes, Map<String, byte[]> sideClasses,
			Function<String, ClassNode> side, int depth, Set<String> out, Set<String> seen) {
		if (depth < 0 || method.instructions == null || method.instructions.size() == 0
				|| !seen.add(owner + "#" + method.name + method.desc + modes)) return;
		org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames;
		try {
			frames = new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.SourceInterpreter()).analyze(owner, method);
		} catch (org.objectweb.asm.tree.analysis.AnalyzerException | RuntimeException unreadable) {
			return;   // a body the analyzer cannot read adds nothing: the decision then falls to the cautious side
		}
		AbstractInsnNode[] code = method.instructions.toArray();
		for (int i = 0; i < code.length; i++) {
			var frame = frames[i];
			if (frame == null) continue;
			if (code[i] instanceof JumpInsnNode jump && jump.getOpcode() != Opcodes.GOTO && jump.getOpcode() != Opcodes.JSR) {
				int operands = jump.getOpcode() >= Opcodes.IF_ICMPEQ && jump.getOpcode() <= Opcodes.IF_ACMPNE ? 2 : 1;
				boolean tested = false;
				for (int k = 0; k < operands; k++) tested |= loads(frame.getStack(frame.getStackSize() - 1 - k), modes);
				int target = method.instructions.indexOf(jump.label);
				if (tested && target > i) {
					for (int j = i + 1; j < target; j++) if (code[j] instanceof MethodInsnNode call && !call.name.startsWith("<")) out.add(key(call));
				}
			} else if (code[i] instanceof MethodInsnNode call) {
				Type[] arguments = Type.getArgumentTypes(call.desc);
				boolean isStatic = call.getOpcode() == Opcodes.INVOKESTATIC;
				Set<Integer> handed = new java.util.HashSet<>();
				int calleeSlot = isStatic ? 0 : 1;
				for (int a = 0; a < arguments.length; a++) {
					if (loads(frame.getStack(frame.getStackSize() - arguments.length + a), modes)) handed.add(calleeSlot);
					calleeSlot += arguments[a].getSize();
				}
				if (handed.isEmpty()) continue;
				for (Map.Entry<String, MethodNode> declaration : declarations(call, sideClasses, side).entrySet())
					guardedCalls(declaration.getKey(), declaration.getValue(), handed, sideClasses, side, depth - 1, out, seen);
			}
		}
	}

	/** Whether {@code value} was pushed by a load of one of {@code slots}. */
	private static boolean loads(org.objectweb.asm.tree.analysis.SourceValue value, Set<Integer> slots) {
		for (AbstractInsnNode source : value.insns) if (source instanceof VarInsnNode load && load.getOpcode() >= Opcodes.ILOAD
				&& load.getOpcode() <= Opcodes.ALOAD && slots.contains(load.var)) return true;
		return false;
	}

	/**
	 * Every body on the side a call may run: the declaration it resolves to (through superclasses and interfaces), and, for a
	 * virtual call, every override of it in a class below the call's owner.
	 */
	private static Map<String, MethodNode> declarations(MethodInsnNode call, Map<String, byte[]> sideClasses, Function<String, ClassNode> side) {
		Map<String, MethodNode> out = new LinkedHashMap<>();
		String resolved = resolve(call.owner, call.name, call.desc, side, 0);
		if (resolved != null) out.put(resolved, method(side.apply(resolved), call.name, call.desc));
		if (call.getOpcode() != Opcodes.INVOKEVIRTUAL && call.getOpcode() != Opcodes.INVOKEINTERFACE) return out;
		for (String name : sideClasses.keySet()) {
			if (out.containsKey(name)) continue;
			ClassNode node = side.apply(name);
			MethodNode override = node == null ? null : method(node, call.name, call.desc);
			if (override == null || (override.access & Opcodes.ACC_ABSTRACT) != 0 || !below(node, call.owner, side)) continue;
			out.put(name, override);
		}
		return out;
	}

	private static boolean below(ClassNode node, String owner, Function<String, ClassNode> side) {
		for (int guard = 0; node != null && guard < 32; guard++) {
			if (node.name.equals(owner) || (node.interfaces != null && node.interfaces.contains(owner))) return true;
			node = node.superName == null ? null : side.apply(node.superName);
		}
		return false;
	}

	/** The class whose body a call of {@code owner.name(desc)} runs, read from the side's classes; null when none has one. */
	private static String resolve(String owner, String name, String desc, Function<String, ClassNode> side, int depth) {
		ClassNode node = depth > 32 ? null : side.apply(owner);
		if (node == null) return null;
		MethodNode declared = method(node, name, desc);
		if (declared != null && declared.instructions != null && declared.instructions.size() > 0) return owner;
		if (node.superName != null) {
			String up = resolve(node.superName, name, desc, side, depth + 1);
			if (up != null) return up;
		}
		if (node.interfaces != null) for (String face : node.interfaces) {
			String up = resolve(face, name, desc, side, depth + 1);
			if (up != null) return up;
		}
		return null;
	}

	/** The void calls (actions) the body {@code call} runs makes, followed {@code depth} deep, into {@code out}. */
	private static void effects(String call, Function<String, ClassNode> side, int depth, Set<String> out, Set<String> seen) {
		if (depth <= 0 || !seen.add(call)) return;
		int dot = call.indexOf('.'), paren = call.indexOf('(');
		if (dot < 0 || paren < dot) return;
		String owner = call.substring(0, dot), name = call.substring(dot + 1, paren), desc = call.substring(paren);
		String resolved = resolve(owner, name, desc, side, 0);
		MethodNode body = resolved == null ? null : method(side.apply(resolved), name, desc);
		if (body == null || body.instructions == null) return;
		for (AbstractInsnNode insn : body.instructions) {
			if (!(insn instanceof MethodInsnNode inner) || inner.name.startsWith("<") || Type.getReturnType(inner.desc).getSort() != Type.VOID) continue;
			String next = key(inner);
			out.add(next);
			effects(next, side, depth - 1, out, seen);
		}
	}

	private static String key(MethodInsnNode call) {
		return call.owner + "." + call.name + call.desc;
	}

	private static Function<String, ClassNode> either(Function<String, ClassNode> first, Function<String, ClassNode> second) {
		return name -> {
			ClassNode found = first.apply(name);
			return found != null ? found : second.apply(name);
		};
	}

	/**
	 * The side's methods that call {@code owner#name(desc)} and carry an edit of their own (their body differs from
	 * vanilla's, or vanilla has no such method), as {@code {display, owner, name, desc}}. A vanilla-identical caller
	 * carries no compensation.
	 */
	private static List<String[]> callersOf(Map<String, byte[]> side, Function<String, ClassNode> parsed, Function<String, ClassNode> vanilla,
			String owner, String name, String desc) {
		List<String[]> callers = new ArrayList<>();
		for (String cls : side.keySet()) {
			ClassNode node = parsed.apply(cls);
			if (node == null) continue;
			for (MethodNode method : node.methods) {
				if (method.name.equals(name) && method.desc.equals(desc) && cls.equals(owner)) continue;
				if (!calls(method, owner, name, desc)) continue;
				MethodNode vanillaBody = method(vanilla.apply(cls), method.name, method.desc);
				if (vanillaBody != null && text(vanillaBody).equals(text(method))) continue;
				callers.add(new String[] { cls + "#" + method.name + method.desc, cls, method.name, method.desc });
			}
		}
		return callers;
	}

	private static Twin delegate(ClassNode owner, MethodNode entry, String side) {
		List<AbstractInsnNode> code = real(entry);
		if (code.isEmpty() || (entry.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return null;
		boolean isStatic = (entry.access & Opcodes.ACC_STATIC) != 0;
		int i = 0, slot = 0;
		if (!isStatic) {
			if (!(code.get(i) instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0) return null;
			i++;
			slot = 1;
		}
		for (Type argument : Type.getArgumentTypes(entry.desc)) {
			if (i >= code.size() || !(code.get(i) instanceof VarInsnNode load) || load.var != slot
					|| load.getOpcode() != argument.getOpcode(Opcodes.ILOAD)) return null;
			slot += argument.getSize();
			i++;
		}
		List<AbstractInsnNode> constants = new ArrayList<>();
		while (i < code.size() && constant(code.get(i))) constants.add(code.get(i++));
		if (constants.isEmpty() || i >= code.size() || !(code.get(i) instanceof MethodInsnNode call)) return null;
		if (!call.owner.equals(owner.name) || !call.name.equals(entry.name)) return null;
		Type[] mine = Type.getArgumentTypes(entry.desc), theirs = Type.getArgumentTypes(call.desc);
		if (theirs.length != mine.length + constants.size() || !Type.getReturnType(call.desc).equals(Type.getReturnType(entry.desc))) return null;
		for (int k = 0; k < mine.length; k++) if (!mine[k].equals(theirs[k])) return null;
		int expected = isStatic ? Opcodes.INVOKESTATIC : -1;
		if (isStatic ? call.getOpcode() != expected : call.getOpcode() == Opcodes.INVOKESTATIC) return null;
		if (i + 2 != code.size()) return null;
		int ret = code.get(i + 1).getOpcode();
		if (ret != Type.getReturnType(entry.desc).getOpcode(Opcodes.IRETURN)) return null;
		return new Twin(owner.name, entry, call, constants, side);
	}

	/** The {@code count} instructions right before {@code call}, when each pushes a constant; null otherwise. */
	private static List<AbstractInsnNode> constantsBefore(MethodInsnNode call, int count) {
		List<AbstractInsnNode> pushed = new ArrayList<>();
		AbstractInsnNode at = call.getPrevious();
		while (pushed.size() < count) {
			while (at != null && at.getOpcode() < 0) {
				if (at instanceof org.objectweb.asm.tree.FrameNode) return null;   // a join between them: the stack there would change
				at = at.getPrevious();
			}
			if (at == null || !constant(at)) return null;
			pushed.add(0, at);
			at = at.getPrevious();
		}
		return pushed;
	}

	private static boolean sameConstants(List<AbstractInsnNode> a, List<AbstractInsnNode> b) {
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) if (!text(a.get(i)).equals(text(b.get(i)))) return false;
		return true;
	}

	private static String describe(List<AbstractInsnNode> pushed) {
		List<String> parts = new ArrayList<>();
		for (AbstractInsnNode insn : pushed) parts.add(value(insn));
		return "[" + String.join(", ", parts) + "]";
	}

	/** A constant instruction's value as source would spell it. */
	private static String value(AbstractInsnNode insn) {
		int op = insn.getOpcode();
		if (op == Opcodes.ACONST_NULL) return "null";
		if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return String.valueOf(op - Opcodes.ICONST_0);
		if (op == Opcodes.LCONST_0 || op == Opcodes.LCONST_1) return (op - Opcodes.LCONST_0) + "L";
		if (op >= Opcodes.FCONST_0 && op <= Opcodes.FCONST_2) return (op - Opcodes.FCONST_0) + ".0f";
		if (op == Opcodes.DCONST_0 || op == Opcodes.DCONST_1) return (op - Opcodes.DCONST_0) + ".0";
		if (insn instanceof IntInsnNode push) return String.valueOf(push.operand);
		if (insn instanceof LdcInsnNode ldc) return String.valueOf(ldc.cst);
		return text(insn);
	}

	private static boolean constant(AbstractInsnNode insn) {
		int op = insn.getOpcode();
		return (op >= Opcodes.ACONST_NULL && op <= Opcodes.DCONST_1) || op == Opcodes.BIPUSH || op == Opcodes.SIPUSH
				|| insn instanceof LdcInsnNode;
	}

	private static boolean calls(MethodNode method, String owner, String name, String desc) {
		if (method.instructions == null) return false;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc)) return true;
		}
		return false;
	}

	private static Map<String, Integer> callCounts(MethodNode method) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call) counts.merge(call.owner + "." + call.name + call.desc, 1, Integer::sum);
		}
		return counts;
	}

	private static List<AbstractInsnNode> real(MethodNode method) {
		List<AbstractInsnNode> code = new ArrayList<>();
		if (method.instructions == null) return code;
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) code.add(insn);
		return code;
	}

	static MethodNode method(ClassNode owner, String name, String desc) {
		if (owner == null) return null;
		for (MethodNode method : owner.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}

	/** A body as opcodes and operands, for equality across two compiles of the same source. */
	static String text(MethodNode method) {
		StringBuilder out = new StringBuilder();
		for (AbstractInsnNode insn : real(method)) out.append(text(insn)).append(';');
		return out.toString();
	}

	private static String text(AbstractInsnNode insn) {
		StringBuilder out = new StringBuilder().append(insn.getOpcode()).append(' ');
		if (insn instanceof MethodInsnNode call) out.append(call.owner).append('.').append(call.name).append(call.desc);
		else if (insn instanceof FieldInsnNode field) out.append(field.owner).append('.').append(field.name).append(field.desc);
		else if (insn instanceof LdcInsnNode ldc) out.append(ldc.cst);
		else if (insn instanceof IntInsnNode value) out.append(value.operand);
		else if (insn instanceof VarInsnNode local) out.append(local.var);
		else if (insn instanceof TypeInsnNode type) out.append(type.desc);
		else if (insn instanceof IincInsnNode inc) out.append(inc.var).append(',').append(inc.incr);
		else if (insn instanceof JumpInsnNode) out.append("jump");
		else if (insn instanceof InsnNode) out.append("");
		return out.toString();
	}

	private static Function<String, ClassNode> parser(Map<String, byte[]> classes) {
		Map<String, ClassNode> cache = new HashMap<>();
		Set<String> absent = new LinkedHashSet<>();
		return name -> {
			if (absent.contains(name)) return null;
			ClassNode cached = cache.get(name);
			if (cached != null) return cached;
			byte[] bytes = classes.get(name);
			if (bytes == null) {
				absent.add(name);
				return null;
			}
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
			cache.put(name, node);
			return node;
		};
	}
}
