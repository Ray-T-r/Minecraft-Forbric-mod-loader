/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Caught;

/**
 * Moves a mod's injector at a vanilla decision onto the decision the surviving carrier makes in its place, with the
 * polarity mapped: where the carrier asks "should the break reset" in the place vanilla asked "is it the same item", a
 * mod's answer to vanilla's question is turned into the carrier's answer, and the carrier's answer, wherever the mod
 * asks vanilla's question of the very values it was handed, is turned back.
 *
 * <p>Vanilla's {@code MultiPlayerGameMode.sameDestroyTarget} asks {@code ItemStack.isSameItemSameComponents(held,
 * destroying)} and keeps breaking when it answers true; NeoForge asks {@code destroying.shouldCauseBlockBreakReset(held)}
 * there and keeps breaking when it answers false. Vanilla's {@code EnchantCommand.enchant} asks
 * {@code enchantment.canEnchant(stack)}; NeoForge asks {@code stack.supportsEnchantment(holder)}. Vanilla's
 * {@code LivingEntity.forceAddEffect} returns early unless {@code canBeAffected(effect)}; NeoForge returns at the end
 * unless {@code CommonHooks.canMobEffectBeApplied(this, effect, source)}. A mod's {@code @Redirect} or {@code @Inject} at
 * vanilla's call binds nothing: the call is gone.
 *
 * <p>Nothing here knows a mod, a class or a method by name. The correspondence is read from the method as the mod's own
 * loader has it (the native reference of its ecosystem) and the merged one:
 * <ul>
 *   <li>the injector's one point is a call ({@code INVOKE}) the native method makes once (or the occurrence its ordinal
 *       names) and the merged method never — the method itself or, where the merged method hands the native body to one
 *       private helper of the class that nothing else calls, that helper;</li>
 *   <li>the native call's value decides a branch (a conditional jump right after it), or is stored straight into a local
 *       the method stores only there;</li>
 *   <li>the merged method makes exactly one call the native method never makes (or, for a stored value, one producer) that
 *       stands in its place: for a decision, a call of the same type followed by a conditional jump whose two ways on run
 *       the same straight-line code as the native call's two ways on — the same way for the same answer, or the opposite
 *       ways (then the polarity is inverted); for a stored value, a producer stored straight into its one store of the
 *       local the table names alike (same debug name and type);</li>
 *   <li>for a {@code @Redirect}, every value the native call is handed is there at the carrier's call: one of the values
 *       the carrier's call is handed (the same expression over the method's own values), or a local of the merged method
 *       holding that very expression at the call ({@link NativeCallChanges#localsAt}).</li>
 * </ul>
 *
 * <p>An {@code @Inject} (before or after the call, with no captured locals) names the carrier's call (its occurrence in
 * the merged method). A {@code @Redirect} keeps its handler as written, under its name with {@code $forbricOriginal}
 * appended and without the annotation; a new handler of the carrier's call's shape (and the same captured arguments, and a
 * MixinExtras {@code @Local} for each value read from a local) carries the annotation, hands the original the native
 * call's values, and returns its answer (negated when the polarity is inverted). Inside the original handler, a call of
 * the very member it redirects, handed exactly the values it was handed (its own parameters, or the same field of the
 * instance read again before anything else happens), is the call it stands in for: it becomes the carrier's call, its
 * answer mapped back — so the carrier's own decision (a carrier mod's override of it) is what the mod falls back on, as
 * natively the mod falls back on vanilla's. {@code -Dforbric.carrierDecisions=off} leaves every injector as compiled.
 */
public final class CarrierDecisions {
	public static final String PROPERTY = "forbric.carrierDecisions";
	static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
	static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	static final String GROUP = "Lorg/spongepowered/asm/mixin/injection/Group;";
	static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
	static final String SUFFIX = "$forbricOriginal";
	private static final int RUN = 32;

	private CarrierDecisions() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * The merged method's stand-in for the native call: the call in {@code host} (the selected method or its helper),
	 * which occurrence of its member it is there, whether its answer is the native one's opposite, and, for each value
	 * the native call is handed, the carrier's operand ({@code carried}) or merged local ({@code locals}) holding it.
	 */
	record Decision(MethodNode host, MethodInsnNode call, int occurrence, boolean inverted, boolean stored, int[] carried, int[] locals,
			List<NativeCallChanges.Expr> values, int[] derivedFrom, MethodInsnNode[] getters, TypeInsnNode allocation) {
		boolean moved(MethodNode selected) {
			return host != selected;
		}
	}

	/** Moves every eligible injector of {@code mixin}; returns how many. {@code code} must return nodes WITH code. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> code, BiFunction<Ecosystem, String, ClassNode> natives) {
		if (!enabled() || mixin == null || mixin.methods == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		if (ecosystem == null) return 0;
		int moved = 0;
		for (String target : MixinFit.mixinTargets(mixin)) {
			ClassNode original = natives.apply(ecosystem, target), merged = null;
			if (original == null) continue;
			for (MethodNode handler : List.copyOf(mixin.methods)) {
				AnnotationNode injector = MixinFit.injectorOf(handler);
				if (injector == null || !REDIRECT.equals(injector.desc) && !INJECT.equals(injector.desc)) continue;
				if (annotated(handler, GROUP) || MixinFit.value(injector, "slice") != null) continue;
				if (merged == null) merged = code.apply(target);
				if (merged == null) break;
				List<AnnotationNode> points = MixinFit.atNodes(injector);
				if (points.size() != 1 || !"INVOKE".equals(MixinFit.asString(MixinFit.value(points.getFirst(), "value")))) continue;
				AnnotationNode at = points.getFirst();
				MethodNode was = MixinTargetSelectors.one(handler, original), selected = MixinTargetSelectors.one(handler, merged);
				if (was == null || selected == null || !was.name.equals(selected.name) || !was.desc.equals(selected.desc)) continue;
				if (!MixinCallbackShape.calls(at, selected).isEmpty()) continue;
				List<MethodInsnNode> nativeCalls = MixinCallbackShape.calls(at, was);
				int ordinal = MixinFit.value(at, "ordinal") instanceof Number number ? number.intValue() : -1;
				MethodInsnNode call = ordinal >= 0 ? (ordinal < nativeCalls.size() ? nativeCalls.get(ordinal) : null)
						: nativeCalls.size() == 1 ? nativeCalls.getFirst() : null;
				if (call == null) continue;
				boolean redirect = REDIRECT.equals(injector.desc);
				if (!redirect && !plainInject(injector, at)) continue;
				Decision decision = derive(original, was, merged, selected, call, redirect);
				if (decision == null) {
					if (redirect && keepsOperand(mixin, handler, injector, at, call, original, was, merged, selected)) moved++;
					continue;
				}
				String was0 = MixinFit.asString(MixinFit.value(at, "target"));
				MethodNode marked = redirect ? relocateRedirect(mixin, handler, injector, at, call, decision, selected, original, was, merged)
						: relocateInject(injector, at, decision, selected) ? handler : null;
				if (marked == null) continue;
				// The point now counts the merged body's calls; no later pass may read it as a native count.
				CurrentBodyOrdinals.mark(marked);
				moved++;
				String standIn = decision.call() != null ? decision.call().owner.replace('/', '.') + "." + decision.call().name
						: "new " + decision.allocation().desc.replace('/', '.');
				ForbricLog.info("[Forbric/Mixin] %s.%s: %s is no longer called in %s; the carrier %s in its place with %s%s%s — the %s moves there",
						mixin.name.replace('/', '.'), handler.name.endsWith(SUFFIX) ? handler.name.substring(0, handler.name.length() - SUFFIX.length()) : handler.name,
						readable(was0), target.replace('/', '.') + "." + selected.name, decision.stored() ? "computes the same local" : "decides", standIn,
						decision.moved(selected) ? " in " + decision.host().name : "", decision.inverted() ? ", answering the opposite question" : "",
						redirect ? "redirect" : "injector");
			}
		}
		return moved;
	}

	/** An {@code @Inject} that only runs at the call: before or after it, no {@code by}, no captured locals. */
	private static boolean plainInject(AnnotationNode injector, AnnotationNode at) {
		if (MixinFit.value(injector, "locals") != null) return false;
		if (MixinFit.value(at, "by") != null) return false;
		Object shift = MixinFit.value(at, "shift");
		String shifted = MixinFit.asString(shift);
		return shift == null || "BEFORE".equals(shifted) || "AFTER".equals(shifted) || "NONE".equals(shifted);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The correspondence

	/**
	 * The carrier's stand-in for the native {@code call} of {@code was}, in {@code selected} or the one helper it hands the
	 * native body to; null when none is proved. {@code operands}: every native value must be located too.
	 */
	static Decision derive(ClassNode original, MethodNode was, ClassNode merged, MethodNode selected, MethodInsnNode call, boolean operands) {
		Set<String> nativeMembers = new HashSet<>();
		for (AbstractInsnNode instruction : was.instructions) if (instruction instanceof MethodInsnNode other) nativeMembers.add(other.name + other.desc);
		Decision found = null;
		for (MethodNode host : hosts(original, merged, selected, was, call)) {
			if (!MixinCallbackShape.selected("L" + call.owner + ";" + call.name + call.desc, host).isEmpty()) continue;
			Decision decision = standIn(original, was, merged, host, call, nativeMembers, operands);
			// An injector that only runs there (no value to hand over) may also go where the carrier replaced the very statement
			// the native call opens, at the same place in the method.
			if (decision == null && !operands && host == selected) decision = replacedStatement(original, was, merged, host, call);
			if (decision == null) continue;
			if (found != null) return null;
			found = decision;
		}
		return found;
	}

	/**
	 * The selected method, and the one helper of the class it calls that is the native body's new home: a method the native
	 * class does not have, of the same staticness, that nothing else in the class calls or names, and that makes most of
	 * the calls the native method makes (four in five of the members it invokes, other than the decision itself).
	 */
	static List<MethodNode> hosts(ClassNode original, ClassNode merged, MethodNode selected, MethodNode was, MethodInsnNode decision) {
		List<MethodNode> hosts = new ArrayList<>();
		hosts.add(selected);
		MethodNode helper = null;
		for (AbstractInsnNode instruction : selected.instructions) {
			if (!(instruction instanceof MethodInsnNode call) || !call.owner.equals(merged.name) || call.name.startsWith("<")) continue;
			MethodNode callee = NativeCallChanges.method(merged, call.name + call.desc);
			if (callee == null || callee == selected || ((callee.access ^ selected.access) & Opcodes.ACC_STATIC) != 0
					|| (callee.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
			// A helper the native class already has is the native class's own method, not the body's new home.
			if (NativeCallChanges.method(original, callee.name + callee.desc) != null) continue;
			if (incoming(merged, callee) != 1 || !home(was, callee, decision)) continue;
			if (helper != null && helper != callee) return hosts;
			helper = callee;
		}
		if (helper != null) hosts.add(helper);
		return hosts;
	}

	/** Whether {@code helper} makes four in five of the distinct calls {@code was} makes, other than {@code decision}'s. */
	private static boolean home(MethodNode was, MethodNode helper, MethodInsnNode decision) {
		Set<String> wanted = new HashSet<>(), found = new HashSet<>();
		for (AbstractInsnNode instruction : was.instructions)
			if (instruction instanceof MethodInsnNode call && !(call.name.equals(decision.name) && call.desc.equals(decision.desc))) wanted.add(member(call));
		for (AbstractInsnNode instruction : helper.instructions) if (instruction instanceof MethodInsnNode call) found.add(member(call));
		if (wanted.size() < 5) return false;
		int kept = 0;
		for (String member : wanted) if (found.contains(member)) kept++;
		return kept * 5 >= wanted.size() * 4;
	}

	private static int incoming(ClassNode owner, MethodNode method) {
		int n = 0;
		for (MethodNode m : owner.methods) for (AbstractInsnNode instruction : m.instructions) {
			if (instruction instanceof MethodInsnNode call && call.owner.equals(owner.name) && call.name.equals(method.name) && call.desc.equals(method.desc)) n++;
			else if (instruction instanceof InvokeDynamicInsnNode dynamic) for (Object argument : dynamic.bsmArgs)
				if (argument instanceof org.objectweb.asm.Handle handle && handle.getOwner().equals(owner.name) && handle.getName().equals(method.name)
						&& handle.getDesc().equals(method.desc)) n += 2;
		}
		return n;
	}

	private static Decision standIn(ClassNode original, MethodNode was, ClassNode merged, MethodNode host, MethodInsnNode call,
			Set<String> nativeMembers, boolean operands) {
		Type value = Type.getReturnType(call.desc);
		if (value.getSort() == Type.VOID) return null;
		AbstractInsnNode consumer = nextReal(call);
		MethodInsnNode standIn = null;
		TypeInsnNode allocation = null;
		boolean inverted = false, stored = false;
		if (consumer instanceof JumpInsnNode decided && (decided.getOpcode() == Opcodes.IFEQ || decided.getOpcode() == Opcodes.IFNE)
				&& value.getSort() == Type.BOOLEAN) {
			List<String> yes = run(was, outcome(was, decided, true)), no = run(was, outcome(was, decided, false));
			if (yes == null || no == null || yes.equals(no)) return null;
			for (MethodInsnNode candidate : novel(host, nativeMembers, value, call.name)) {
				if (!(nextReal(candidate) instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFEQ && jump.getOpcode() != Opcodes.IFNE) continue;
				List<String> mergedYes = run(host, outcome(host, jump, true)), mergedNo = run(host, outcome(host, jump, false));
				boolean same = yes.equals(mergedYes) && no.equals(mergedNo), opposite = yes.equals(mergedNo) && no.equals(mergedYes);
				if (!same && !opposite) continue;
				if (standIn != null) return null;
				standIn = candidate;
				inverted = opposite;
			}
		} else if (consumer instanceof VarInsnNode kept && kept.getOpcode() == value.getOpcode(Opcodes.ISTORE)) {
			String name = localName(was, kept, value);
			if (name == null) return null;
			Set<String> nativeUses = uses(was, kept);
			for (MethodInsnNode candidate : novel(host, nativeMembers, value, call.name)) {
				if (!(nextReal(candidate) instanceof VarInsnNode store) || store.getOpcode() != kept.getOpcode()
						|| !name.equals(localName(host, store, value)) || !nativeUses.equals(uses(host, store))) continue;
				if (standIn != null) return null;
				standIn = candidate;
			}
			// Or an object of the native value's very class, made there and stored straight into that local: its allocation
			// stands where the native call stood (an @Inject goes before it; nothing replaces a construction).
			if (!operands && value.getSort() == Type.OBJECT) for (AbstractInsnNode instruction : host.instructions) {
				if (!(instruction instanceof MethodInsnNode constructor) || constructor.getOpcode() != Opcodes.INVOKESPECIAL || !constructor.name.equals("<init>")
						|| !constructor.owner.equals(value.getInternalName()) || !(nextReal(constructor) instanceof VarInsnNode store)
						|| store.getOpcode() != Opcodes.ASTORE || !name.equals(localName(host, store, value))) continue;
				TypeInsnNode made = allocationOf(merged.name, host, constructor);
				if (made == null) continue;
				if (standIn != null) return null;
				standIn = constructor;
				allocation = made;
			}
			stored = true;
		}
		if (standIn == null) return null;
		int[] carried = null, locals = null, derivedFrom = null;
		MethodInsnNode[] getters = null;
		List<NativeCallChanges.Expr> before = null;
		if (operands) {
			before = operandsOf(original, was, call);
			List<NativeCallChanges.Expr> after = operandsOf(merged, host, standIn);
			Map<Integer, NativeCallChanges.Expr> held = NativeCallChanges.localsAt(merged.name, host, standIn);
			if (before == null || after == null || held == null) return null;
			List<Type> nativeTypes = operandTypes(call);
			carried = new int[before.size()];
			locals = new int[before.size()];
			derivedFrom = new int[before.size()];
			getters = new MethodInsnNode[before.size()];
			Arrays.fill(carried, -1);
			Arrays.fill(locals, -1);
			Arrays.fill(derivedFrom, -1);
			List<AbstractInsnNode> inline = inlineProducers(original, was, call);
			for (int i = 0; i < before.size(); i++) {
				NativeCallChanges.Expr wanted = before.get(i);
				for (int j = 0; j < after.size(); j++) if (wanted.equals(after.get(j))) {
					if (carried[i] >= 0) return null;
					carried[i] = j;
				}
				if (carried[i] >= 0) {
					if (!operandTypes(standIn).get(carried[i]).equals(nativeTypes.get(i))) return null;
					continue;
				}
				for (var entry : held.entrySet()) {
					if (!wanted.equals(entry.getValue()) || !declared(host, entry.getKey(), standIn, nativeTypes.get(i))) continue;
					if (locals[i] >= 0) return null;
					locals[i] = entry.getKey();
				}
				if (locals[i] >= 0) continue;
				// A value the native call is handed straight from a getter of one of the carrier's values, evaluated there for
				// that call alone: the new handler asks the same getter of that value, once, where the native call would.
				if (inline != null && inline.get(i) instanceof MethodInsnNode getter && getter.getOpcode() != Opcodes.INVOKESTATIC
						&& Type.getArgumentTypes(getter.desc).length == 0 && wanted.kind().equals("call") && wanted.inputs().size() == 1) {
					for (int j = 0; j < after.size(); j++) if (wanted.inputs().getFirst().equals(after.get(j))
							&& assignable(operandTypes(standIn).get(j), Type.getObjectType(getter.owner))) {
						if (derivedFrom[i] >= 0) return null;
						derivedFrom[i] = j;
						getters[i] = getter;
					}
				}
				if (derivedFrom[i] < 0) return null;
			}
		}
		return new Decision(host, standIn, allocation != null ? allocations(host, allocation) : occurrence(host, standIn), inverted, stored, carried,
				locals, before, derivedFrom, getters, allocation);
	}

	/**
	 * The carrier's statement standing where the native statement that {@code call} is part of stood: the native method and
	 * the merged one run the same instructions up to the native statement (the instruction before it is the same step in
	 * both, read by {@link CodeAlignment}, and every jump to the statement is the same step as a jump to the carrier's), the
	 * native statement — from the first instruction computing a value the call is handed up to the store of its answer —
	 * runs nowhere in the merged method, and the merged method runs, in its place, a statement of its own, storing into the
	 * same local the native statement stores into (same slot and declared type). The injector goes before the carrier
	 * statement's first call, allocation or field access, which only plain reads may precede.
	 */
	private static Decision replacedStatement(ClassNode original, MethodNode was, ClassNode merged, MethodNode host, MethodInsnNode call) {
		Frame<SourceValue>[] frames;
		try {
			frames = new Analyzer<>(new SourceInterpreter()).analyze(original.name, was);
		} catch (AnalyzerException | RuntimeException unanalysable) {
			Caught.expected(unanalysable);
			return null;
		}
		Frame<SourceValue> atCall = frames[was.instructions.indexOf(call)];
		if (atCall == null) return null;
		int floor = atCall.getStackSize() - operandTypes(call).size();
		AbstractInsnNode start = call;
		for (AbstractInsnNode at = call.getPrevious(); at != null; at = at.getPrevious()) {
			if (at.getOpcode() < 0) {
				if (at instanceof LabelNode label && jumpsTo(was, label)) break;
				continue;
			}
			Frame<SourceValue> frame = frames[was.instructions.indexOf(at)];
			if (frame == null || frame.getStackSize() < floor || at instanceof JumpInsnNode) break;
			start = at;
			if (frame.getStackSize() == floor) break;
		}
		Frame<SourceValue> opening = frames[was.instructions.indexOf(start)];
		if (opening == null || opening.getStackSize() != floor || floor != 0) return null;
		AbstractInsnNode end = nextReal(call);
		if (!(end instanceof VarInsnNode store) || store.getOpcode() < Opcodes.ISTORE || store.getOpcode() > Opcodes.ASTORE) return null;
		Map<AbstractInsnNode, AbstractInsnNode> aligned = CodeAlignment.align(original.name, was, merged.name, host);
		if (aligned == null) return null;
		// The native call and the store of its answer run nowhere in the merged method (a plain read feeding it may).
		if (aligned.containsKey(call) || aligned.containsKey(end)) return null;
		AbstractInsnNode before = previousReal(start);
		if (before == null || !aligned.containsKey(before) || before instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO) return null;
		AbstractInsnNode first = nextReal(aligned.get(before));
		if (first == null || aligned.containsValue(first)) return null;
		// The same ways in: every jump to the native statement is the same step as a jump to the carrier's, and no other.
		Set<AbstractInsnNode> nativeIn = jumpsInto(was, start), mergedIn = jumpsInto(host, first);
		if (nativeIn.size() != mergedIn.size()) return null;
		for (AbstractInsnNode jump : nativeIn) if (!mergedIn.contains(aligned.get(jump))) return null;
		// The carrier's statement stores into the native statement's local, as the native one does.
		AbstractInsnNode point = first;
		while (point != null && !(point instanceof MethodInsnNode || point instanceof FieldInsnNode || point instanceof TypeInsnNode made && made.getOpcode() == Opcodes.NEW)) {
			int opcode = point.getOpcode();
			if (!(point instanceof VarInsnNode load && opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) && !(opcode >= Opcodes.ACONST_NULL && opcode <= Opcodes.SIPUSH)
					&& !(point instanceof LdcInsnNode) && opcode != Opcodes.DUP) return null;
			point = nextReal(point);
		}
		if (point == null || aligned.containsValue(point)) return null;
		AbstractInsnNode carrierEnd = point;
		for (AbstractInsnNode at = point; at != null; at = nextReal(at)) {
			if (at instanceof JumpInsnNode || at instanceof TableSwitchInsnNode || at instanceof LookupSwitchInsnNode
					|| at.getOpcode() >= Opcodes.IRETURN && at.getOpcode() <= Opcodes.RETURN || at.getOpcode() == Opcodes.ATHROW) return null;
			if (at instanceof VarInsnNode kept && kept.getOpcode() >= Opcodes.ISTORE && kept.getOpcode() <= Opcodes.ASTORE) { carrierEnd = at; break; }
		}
		if (!(carrierEnd instanceof VarInsnNode kept) || kept.var != store.var || kept.getOpcode() != store.getOpcode() || aligned.containsValue(kept)) return null;
		String nativeLocal = declaredType(was, store), mergedLocal = declaredType(host, kept);
		if (nativeLocal == null || !nativeLocal.equals(mergedLocal)) return null;
		if (point instanceof TypeInsnNode made)
			return new Decision(host, null, allocations(host, made), false, true, null, null, null, null, null, made);
		if (point instanceof MethodInsnNode invoked && !invoked.name.startsWith("<"))
			return new Decision(host, invoked, occurrence(host, invoked), false, true, null, null, null, null, null, null);
		return null;
	}

	private static boolean jumpsTo(MethodNode method, LabelNode label) {
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof JumpInsnNode jump && jump.label == label) return true;
			if (instruction instanceof TableSwitchInsnNode table && (table.dflt == label || table.labels.contains(label))) return true;
			if (instruction instanceof LookupSwitchInsnNode lookup && (lookup.dflt == label || lookup.labels.contains(label))) return true;
		}
		if (method.tryCatchBlocks != null) for (var block : method.tryCatchBlocks) if (block.handler == label) return true;
		return false;
	}

	/** The jumps whose target is the first real instruction at or after {@code at}'s labels. */
	private static Set<AbstractInsnNode> jumpsInto(MethodNode method, AbstractInsnNode at) {
		Set<LabelNode> labels = Collections.newSetFromMap(new IdentityHashMap<>());
		for (AbstractInsnNode back = at.getPrevious(); back != null && back.getOpcode() < 0; back = back.getPrevious())
			if (back instanceof LabelNode label) labels.add(label);
		Set<AbstractInsnNode> out = Collections.newSetFromMap(new IdentityHashMap<>());
		for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof JumpInsnNode jump && labels.contains(jump.label)) out.add(jump);
		return out;
	}

	/** {@code name:desc} the table declares for {@code store}'s slot right after it; null when none or several. */
	private static String declaredType(MethodNode method, VarInsnNode store) {
		if (method.localVariables == null) return null;
		AbstractInsnNode live = nextReal(store);
		int at = live == null ? -1 : method.instructions.indexOf(live);
		String found = null;
		for (LocalVariableNode local : method.localVariables) {
			if (local.index != store.var || method.instructions.indexOf(local.start) > at || at >= method.instructions.indexOf(local.end)) continue;
			if (found != null && !found.equals(local.desc)) return null;
			found = local.desc;
		}
		return found;
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
		for (AbstractInsnNode at = instruction.getPrevious(); at != null; at = at.getPrevious()) if (at.getOpcode() >= 0) return at;
		return null;
	}

	/**
	 * For each value {@code call} is handed in {@code method}, the instruction that computes it right there (through a
	 * cast), or null where the value is read from a local, a parameter or several places. Null when it does not analyse.
	 */
	private static List<AbstractInsnNode> inlineProducers(ClassNode owner, MethodNode method, MethodInsnNode call) {
		Frame<SourceValue>[] frames;
		try {
			frames = new Analyzer<>(new SourceInterpreter()).analyze(owner.name, method);
		} catch (AnalyzerException | RuntimeException unanalysable) {
			Caught.expected(unanalysable);
			return null;
		}
		Frame<SourceValue> frame = frames[method.instructions.indexOf(call)];
		int count = operandTypes(call).size();
		if (frame == null || frame.getStackSize() < count) return null;
		List<AbstractInsnNode> out = new ArrayList<>();
		for (int k = 0; k < count; k++) {
			SourceValue value = frame.getStack(frame.getStackSize() - count + k);
			AbstractInsnNode producer = value.insns.size() == 1 ? value.insns.iterator().next() : null;
			while (producer != null && producer.getOpcode() == Opcodes.CHECKCAST) {
				Frame<SourceValue> before = frames[method.instructions.indexOf(producer)];
				SourceValue cast = before == null ? null : before.getStack(before.getStackSize() - 1);
				producer = cast != null && cast.insns.size() == 1 ? cast.insns.iterator().next() : null;
			}
			out.add(producer instanceof VarInsnNode ? null : producer);
		}
		return out;
	}

	/** The {@code NEW} whose object {@code constructor} initialises, when it is the only one; null otherwise. */
	private static TypeInsnNode allocationOf(String owner, MethodNode method, MethodInsnNode constructor) {
		Frame<SourceValue>[] frames;
		try {
			frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, method);
		} catch (AnalyzerException | RuntimeException unanalysable) {
			Caught.expected(unanalysable);
			return null;
		}
		Frame<SourceValue> frame = frames[method.instructions.indexOf(constructor)];
		int count = Type.getArgumentTypes(constructor.desc).length;
		if (frame == null || frame.getStackSize() <= count) return null;
		SourceValue receiver = frame.getStack(frame.getStackSize() - count - 1);
		return receiver.insns.size() == 1 && receiver.insns.iterator().next() instanceof TypeInsnNode made && made.getOpcode() == Opcodes.NEW
				&& made.desc.equals(constructor.owner) ? made : null;
	}

	/** Which {@code NEW} of its class, in order, {@code made} is in {@code method}. */
	private static int allocations(MethodNode method, TypeInsnNode made) {
		int n = 0;
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction == made) return n;
			if (instruction instanceof TypeInsnNode other && other.getOpcode() == Opcodes.NEW && other.desc.equals(made.desc)) n++;
		}
		return n;
	}

	/** Whether a value of {@code from} may be handed where {@code to} is wanted, as far as a descriptor tells: equal types. */
	private static boolean assignable(Type from, Type to) {
		return from.equals(to);
	}

	private static List<NativeCallChanges.Expr> operandsOf(ClassNode owner, MethodNode method, MethodInsnNode call) {
		for (NativeCallChanges.Site site : NativeCallChanges.sites(owner, method)) if (site.call() == call) return site.operands();
		return null;
	}

	/** Whether the table declares {@code slot} as a local of {@code type} live at {@code at} (what a {@code @Local(index)} reads). */
	private static boolean declared(MethodNode method, int slot, AbstractInsnNode at, Type type) {
		if (method.localVariables == null) return false;
		int index = method.instructions.indexOf(at);
		for (LocalVariableNode local : method.localVariables)
			if (local.index == slot && local.desc.equals(type.getDescriptor()) && method.instructions.indexOf(local.start) <= index
					&& index < method.instructions.indexOf(local.end)) return true;
		return false;
	}

	/**
	 * The calls {@code host} makes that the native method never makes, of the native call's type — never one named as the
	 * native call is: the carrier widening a call it keeps (more parameters, the same name) is {@link MixinAtWidenedCall}'s.
	 */
	private static List<MethodInsnNode> novel(MethodNode host, Set<String> nativeMembers, Type value, String nativeName) {
		List<MethodInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode instruction : host.instructions)
			if (instruction instanceof MethodInsnNode candidate && !candidate.name.startsWith("<") && !nativeMembers.contains(candidate.name + candidate.desc)
					&& !candidate.name.equals(nativeName) && Type.getReturnType(candidate.desc).equals(value)) out.add(candidate);
		return out;
	}

	/** Where {@code jump} goes on the answer {@code answer} of the boolean it tests. */
	private static AbstractInsnNode outcome(MethodNode method, JumpInsnNode jump, boolean answer) {
		boolean taken = jump.getOpcode() == Opcodes.IFNE ? answer : !answer;
		return taken ? firstReal(jump.label) : nextReal(jump);
	}

	/**
	 * The straight-line code from {@code start}, as tokens: every instruction up to and including the first that ends the
	 * stretch (a branch, a switch, a return or a throw), an unconditional jump followed rather than recorded. A local is
	 * read by its debug name and type where the table names it there. Null where the stretch loops or runs too long.
	 */
	static List<String> run(MethodNode method, AbstractInsnNode start) {
		List<String> tokens = new ArrayList<>();
		Set<AbstractInsnNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (AbstractInsnNode at = start; at != null; ) {
			if (!seen.add(at) || tokens.size() > RUN) return null;
			if (at instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO) {
				at = firstReal(jump.label);
				continue;
			}
			tokens.add(token(method, at));
			int opcode = at.getOpcode();
			if (at instanceof JumpInsnNode || at instanceof TableSwitchInsnNode || at instanceof LookupSwitchInsnNode
					|| opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW) return tokens;
			at = nextReal(at);
		}
		return null;
	}

	private static String token(MethodNode method, AbstractInsnNode instruction) {
		StringBuilder token = new StringBuilder().append(instruction.getOpcode());
		if (instruction instanceof MethodInsnNode call) token.append(' ').append(call.owner).append('.').append(call.name).append(call.desc);
		else if (instruction instanceof FieldInsnNode field) token.append(' ').append(field.owner).append('.').append(field.name).append(':').append(field.desc);
		else if (instruction instanceof VarInsnNode variable) token.append(' ').append(local(method, variable.var, instruction));
		else if (instruction instanceof IincInsnNode increment) token.append(' ').append(local(method, increment.var, instruction)).append(' ').append(increment.incr);
		else if (instruction instanceof LdcInsnNode ldc) token.append(' ').append(ldc.cst);
		else if (instruction instanceof IntInsnNode number) token.append(' ').append(number.operand);
		else if (instruction instanceof TypeInsnNode type) token.append(' ').append(type.desc);
		else if (instruction instanceof InvokeDynamicInsnNode dynamic) token.append(' ').append(dynamic.name).append(dynamic.desc);
		return token.toString();
	}

	/** A local as the table names it at {@code at} (or right after, for a store): name and type; else its slot. */
	private static String local(MethodNode method, int slot, AbstractInsnNode at) {
		if (method.localVariables != null) {
			int index = method.instructions.indexOf(at), after = method.instructions.indexOf(nextReal(at) == null ? at : nextReal(at));
			for (LocalVariableNode local : method.localVariables) {
				if (local.index != slot) continue;
				int from = method.instructions.indexOf(local.start), to = method.instructions.indexOf(local.end);
				if (from <= index && index < to || from <= after && after < to) return local.name + ":" + local.desc;
			}
		}
		return "#" + slot;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// A copy the carrier no longer makes

	static final String CALLBACK_INFO = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;",
			CALLBACK_INFO_RETURNABLE = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

	/**
	 * A redirect of a native call whose answer the method keeps in a local, where the merged method keeps, in the local the
	 * table names alike, the call's own operand instead: the carrier no longer makes the copy, it keeps the value itself
	 * (vanilla's {@code Language.loadDefault} stores {@code Map.copyOf(loaded)} as its storage, NeoForge stores {@code loaded}
	 * so its hook can still add to it). A handler whose every answer is that operand, or the same operation of the same name
	 * made on it (another library's copy), only adds what it does before answering: it runs, where the native call stood,
	 * as an {@code @Inject} handed the operand (a MixinExtras {@code @Local}), and the carrier's value stands. The injector goes
	 * before the merged method's first call, field access or allocation after its store of the operand, which only plain
	 * reads may precede.
	 */
	private static boolean keepsOperand(ClassNode mixin, MethodNode handler, AnnotationNode injector, AnnotationNode at, MethodInsnNode call,
			ClassNode original, MethodNode was, ClassNode merged, MethodNode host) {
		if (call.getOpcode() != Opcodes.INVOKESTATIC || Type.getArgumentTypes(call.desc).length != 1 || hasParameterAnnotations(handler)) return false;
		Type operand = Type.getArgumentTypes(call.desc)[0], answer = Type.getReturnType(call.desc);
		if (operand.getSort() != Type.OBJECT || answer.getSort() != Type.OBJECT) return false;
		if (!(nextReal(call) instanceof VarInsnNode kept) || kept.getOpcode() != Opcodes.ASTORE) return false;
		String name = localName(was, kept, answer);
		List<NativeCallChanges.Expr> values = operandsOf(original, was, call);
		if (name == null || values == null) return false;
		// The merged store into the same local, of the call's own operand.
		VarInsnNode store = null;
		int slot = -1;
		Frame<SourceValue>[] frames;
		try {
			frames = new Analyzer<>(new SourceInterpreter()).analyze(merged.name, host);
		} catch (AnalyzerException | RuntimeException unanalysable) {
			Caught.expected(unanalysable);
			return false;
		}
		for (AbstractInsnNode instruction : host.instructions) {
			if (!(instruction instanceof VarInsnNode candidate) || candidate.getOpcode() != Opcodes.ASTORE || !name.equals(localName(host, candidate, answer))) continue;
			Frame<SourceValue> frame = frames[host.instructions.indexOf(candidate)];
			if (frame == null || frame.getStackSize() < 1) continue;
			SourceValue value = frame.getStack(frame.getStackSize() - 1);
			if (value.insns.size() != 1 || !(value.insns.iterator().next() instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) continue;
			Map<Integer, NativeCallChanges.Expr> held = NativeCallChanges.localsAt(merged.name, host, load);
			if (held == null || !values.getFirst().equals(held.get(load.var)) || !declared(host, load.var, candidate, operand)) continue;
			if (store != null) return false;
			store = candidate;
			slot = load.var;
		}
		if (store == null) return false;
		// The handler's answers: its operand, or the same operation made on its operand.
		boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		if (isStatic != ((host.access & Opcodes.ACC_STATIC) != 0)) return false;
		Type[] parameters = Type.getArgumentTypes(handler.desc);
		Type[] hostArguments = Type.getArgumentTypes(host.desc);
		if (parameters.length < 1 || !parameters[0].equals(operand) || !Type.getReturnType(handler.desc).equals(answer) || parameters.length - 1 > hostArguments.length) return false;
		for (int i = 1; i < parameters.length; i++) if (!parameters[i].equals(hostArguments[i - 1])) return false;
		int parameter = isStatic ? 0 : 1;
		if (stores(handler, parameter) != 0) return false;
		Frame<SourceValue>[] handlerFrames;
		try {
			handlerFrames = new Analyzer<>(new SourceInterpreter()).analyze(mixin.name, handler);
		} catch (AnalyzerException | RuntimeException unanalysable) {
			Caught.expected(unanalysable);
			return false;
		}
		int answers = 0;
		for (AbstractInsnNode instruction : handler.instructions) {
			if (instruction.getOpcode() != Opcodes.ARETURN) continue;
			answers++;
			Frame<SourceValue> frame = handlerFrames[handler.instructions.indexOf(instruction)];
			if (frame == null || frame.getStackSize() < 1) return false;
			SourceValue value = frame.getStack(frame.getStackSize() - 1);
			if (value.insns.size() != 1) return false;
			AbstractInsnNode producer = value.insns.iterator().next();
			if (producer instanceof VarInsnNode load && load.var == parameter && load.getOpcode() == Opcodes.ALOAD) continue;
			if (!(producer instanceof MethodInsnNode copy) || copy.getOpcode() != Opcodes.INVOKESTATIC || !copy.name.equals(call.name)
					|| Type.getArgumentTypes(copy.desc).length != 1) return false;
			Frame<SourceValue> before = handlerFrames[handler.instructions.indexOf(copy)];
			SourceValue argument = before == null ? null : before.getStack(before.getStackSize() - 1);
			if (argument == null || argument.insns.size() != 1 || !(argument.insns.iterator().next() instanceof VarInsnNode load)
					|| load.var != parameter || load.getOpcode() != Opcodes.ALOAD) return false;
		}
		if (answers == 0) return false;
		// Where the native call stood: the merged method's first call, field access or allocation after the store.
		AbstractInsnNode point = nextReal(store);
		while (point != null && (point instanceof VarInsnNode variable && variable.getOpcode() >= Opcodes.ILOAD && variable.getOpcode() <= Opcodes.ALOAD
				|| point.getOpcode() >= Opcodes.ACONST_NULL && point.getOpcode() <= Opcodes.SIPUSH || point instanceof LdcInsnNode)) point = nextReal(point);
		AnnotationNode where = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		where.values = new ArrayList<>();
		if (point instanceof MethodInsnNode next && !next.name.startsWith("<")) {
			where.values.addAll(List.of("value", "INVOKE", "target", member(next), "ordinal", occurrence(host, next)));
		} else if (point instanceof TypeInsnNode made && made.getOpcode() == Opcodes.NEW) {
			where.values.addAll(List.of("value", "NEW", "target", made.desc, "ordinal", allocations(host, made)));
		} else return false;
		for (MethodNode other : mixin.methods) if (other.name.equals(handler.name + SUFFIX)) return false;
		// The new handler: the host's arguments (when the handler captured any), its callback, and the operand as a @Local.
		boolean returns = Type.getReturnType(host.desc).getSort() != Type.VOID;
		List<Type> wrapperParameters = new ArrayList<>();
		int captured = parameters.length - 1;
		if (captured > 0) wrapperParameters.addAll(List.of(hostArguments));
		wrapperParameters.add(Type.getType(returns ? CALLBACK_INFO_RETURNABLE : CALLBACK_INFO));
		wrapperParameters.add(operand);
		MethodNode wrapper = new MethodNode(Opcodes.ACC_PRIVATE | (isStatic ? Opcodes.ACC_STATIC : 0), handler.name,
				Type.getMethodDescriptor(Type.VOID_TYPE, wrapperParameters.toArray(Type[]::new)), null, null);
		int[] slots = new int[wrapperParameters.size()];
		int next = isStatic ? 0 : 1;
		for (int i = 0; i < slots.length; i++) { slots[i] = next; next += wrapperParameters.get(i).getSize(); }
		if (!isStatic) wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, slots[slots.length - 1]));
		for (int i = 0; i < captured; i++) wrapper.instructions.add(new VarInsnNode(hostArguments[i].getOpcode(Opcodes.ILOAD), slots[i]));
		handler.name = handler.name + SUFFIX;
		wrapper.instructions.add(MixinHandlerShim.callOwn(mixin, isStatic, handler.name, handler.desc));
		wrapper.instructions.add(new InsnNode(Opcodes.POP));
		wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
		wrapper.maxLocals = next;
		wrapper.maxStack = next + 1;
		@SuppressWarnings("unchecked") List<AnnotationNode>[] table = new List[wrapperParameters.size()];
		AnnotationNode local = new AnnotationNode(LOCAL);
		local.values = new ArrayList<>(List.of("index", slot));
		table[table.length - 1] = new ArrayList<>(List.of(local));
		wrapper.invisibleParameterAnnotations = table;
		wrapper.invisibleAnnotableParameterCount = table.length;
		AnnotationNode inject = new AnnotationNode(INJECT);
		inject.values = new ArrayList<>();
		for (int i = 0; i + 1 < injector.values.size(); i += 2) {
			Object key = injector.values.get(i);
			if ("method".equals(key) || "remap".equals(key) || "require".equals(key) || "expect".equals(key) || "allow".equals(key)) {
				inject.values.add(key);
				inject.values.add(injector.values.get(i + 1));
			}
		}
		inject.values.add("at");
		inject.values.add(new ArrayList<>(List.of(where)));
		boolean visible = handler.visibleAnnotations != null && handler.visibleAnnotations.remove(injector);
		if (!visible && handler.invisibleAnnotations != null) handler.invisibleAnnotations.remove(injector);
		if (visible) wrapper.visibleAnnotations = new ArrayList<>(List.of(inject));
		else wrapper.invisibleAnnotations = new ArrayList<>(List.of(inject));
		mixin.methods.add(wrapper);
		CurrentBodyOrdinals.mark(wrapper);
		ForbricLog.info("[Forbric/Mixin] %s.%s: %s's copy is no longer made in %s.%s — the carrier keeps the value itself; the handler runs where "
				+ "the copy was made, handed that value, and the carrier's value stands", mixin.name.replace('/', '.'), wrapper.name, readable(member(call)),
				merged.name.replace('/', '.'), host.name);
		return true;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The moves

	private static boolean relocateInject(AnnotationNode injector, AnnotationNode at, Decision decision, MethodNode selected) {
		if (decision.allocation() != null) {
			// Before the allocation, as natively before the call: the point is the NEW, not the constructor call after its operands.
			if (MixinFit.value(at, "shift") != null && !"BEFORE".equals(MixinFit.asString(MixinFit.value(at, "shift")))) return false;
			set(at, "value", "NEW");
			set(at, "target", decision.allocation().desc);
		} else set(at, "target", member(decision.call()));
		set(at, "ordinal", decision.occurrence());
		if (decision.moved(selected)) set(injector, "method", new ArrayList<>(List.of(decision.host().name + decision.host().desc)));
		return true;
	}

	/**
	 * Gives the redirect to a new handler of the carrier's call's shape, which hands the original handler the native
	 * values and maps its answer; see the class comment.
	 */
	private static MethodNode relocateRedirect(ClassNode mixin, MethodNode handler, AnnotationNode injector, AnnotationNode at, MethodInsnNode call,
			Decision decision, MethodNode selected, ClassNode original, MethodNode was, ClassNode merged) {
		if (decision.allocation() != null) return null;
		boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		if (isStatic != ((decision.host().access & Opcodes.ACC_STATIC) != 0)) return null;
		List<Type> nativeOperands = operandTypes(call), mergedOperands = operandTypes(decision.call());
		Type[] all = Type.getArgumentTypes(handler.desc);
		// The handler's MixinExtras sugar, which trails its other parameters: each kept as written on the new handler, where it
		// still names the native value there.
		int plain = sugarStart(handler, all.length);
		if (plain < 0) return null;
		List<AnnotationNode> sugar = new ArrayList<>();
		for (int p = plain; p < all.length; p++) {
			AnnotationNode annotation = sugarOf(handler, p);
			if (!sugarHolds(annotation, all[p], original, was, call, merged, decision)) return null;
			sugar.add(annotation);
		}
		Type[] parameters = Arrays.copyOf(all, plain);
		Type answer = Type.getReturnType(call.desc);
		if (!Type.getReturnType(handler.desc).equals(answer) || !Type.getReturnType(decision.call().desc).equals(answer)) return null;
		if (parameters.length < nativeOperands.size()) return null;
		for (int i = 0; i < nativeOperands.size(); i++) if (!parameters[i].equals(nativeOperands.get(i))) return null;
		List<Type> captured = List.of(parameters).subList(nativeOperands.size(), parameters.length);
		Type[] hostArguments = Type.getArgumentTypes(decision.host().desc);
		if (captured.size() > hostArguments.length) return null;
		for (int i = 0; i < captured.size(); i++) if (!captured.get(i).equals(hostArguments[i])) return null;
		String name = handler.name;
		for (MethodNode other : mixin.methods) if (other.name.equals(name + SUFFIX)) return null;

		List<Type> wrapperParameters = new ArrayList<>(mergedOperands);
		wrapperParameters.addAll(captured);
		List<Integer> localSlots = new ArrayList<>();
		int[] localParameter = new int[nativeOperands.size()];
		Arrays.fill(localParameter, -1);
		for (int i = 0; i < nativeOperands.size(); i++) if (decision.locals()[i] >= 0) {
			localParameter[i] = wrapperParameters.size();
			wrapperParameters.add(nativeOperands.get(i));
			localSlots.add(decision.locals()[i]);
		}
		int sugarFrom = wrapperParameters.size();
		for (int p = plain; p < all.length; p++) wrapperParameters.add(all[p]);
		String desc = Type.getMethodDescriptor(answer, wrapperParameters.toArray(Type[]::new));
		MethodNode wrapper = new MethodNode(Opcodes.ACC_PRIVATE | (isStatic ? Opcodes.ACC_STATIC : 0), name, desc, null, null);
		int[] slot = new int[wrapperParameters.size()];
		int next = isStatic ? 0 : 1;
		for (int i = 0; i < slot.length; i++) {
			slot[i] = next;
			next += wrapperParameters.get(i).getSize();
		}
		InsnList body = wrapper.instructions;
		if (!isStatic) body.add(new VarInsnNode(Opcodes.ALOAD, 0));
		for (int i = 0; i < nativeOperands.size(); i++) {
			if (decision.derivedFrom()[i] >= 0) {
				MethodInsnNode getter = decision.getters()[i];
				body.add(new VarInsnNode(Opcodes.ALOAD, slot[decision.derivedFrom()[i]]));
				body.add(new MethodInsnNode(getter.getOpcode(), getter.owner, getter.name, getter.desc, getter.itf));
				Type got = Type.getReturnType(getter.desc);
				if (!got.equals(nativeOperands.get(i))) body.add(new TypeInsnNode(Opcodes.CHECKCAST, nativeOperands.get(i).getInternalName()));
				continue;
			}
			int from = decision.carried()[i] >= 0 ? decision.carried()[i] : localParameter[i];
			body.add(new VarInsnNode(nativeOperands.get(i).getOpcode(Opcodes.ILOAD), slot[from]));
		}
		for (int i = 0; i < captured.size(); i++) {
			int from = mergedOperands.size() + i;
			body.add(new VarInsnNode(captured.get(i).getOpcode(Opcodes.ILOAD), slot[from]));
		}
		for (int p = plain; p < all.length; p++) body.add(new VarInsnNode(all[p].getOpcode(Opcodes.ILOAD), slot[sugarFrom + p - plain]));
		handler.name = name + SUFFIX;
		handler.visibleParameterAnnotations = null;
		handler.invisibleParameterAnnotations = null;
		handler.visibleAnnotableParameterCount = 0;
		handler.invisibleAnnotableParameterCount = 0;
		forward(mixin, handler, call, decision);
		body.add(MixinHandlerShim.callOwn(mixin, isStatic, handler.name, handler.desc));
		if (decision.inverted()) {
			body.add(new InsnNode(Opcodes.ICONST_1));
			body.add(new InsnNode(Opcodes.IXOR));
		}
		body.add(new InsnNode(answer.getOpcode(Opcodes.IRETURN)));
		wrapper.maxLocals = next;
		wrapper.maxStack = next + 2;
		if (!localSlots.isEmpty() || !sugar.isEmpty()) {
			@SuppressWarnings("unchecked") List<AnnotationNode>[] table = new List[wrapperParameters.size()];
			int k = 0;
			for (int i = 0; i < nativeOperands.size(); i++) if (localParameter[i] >= 0) {
				AnnotationNode local = new AnnotationNode(LOCAL);
				local.values = new ArrayList<>(List.of("index", localSlots.get(k++)));
				table[localParameter[i]] = new ArrayList<>(List.of(local));
			}
			for (int p = 0; p < sugar.size(); p++) table[sugarFrom + p] = new ArrayList<>(List.of(sugar.get(p)));
			wrapper.invisibleParameterAnnotations = table;
			wrapper.invisibleAnnotableParameterCount = table.length;
		}
		boolean visible = handler.visibleAnnotations != null && handler.visibleAnnotations.remove(injector);
		if (!visible && handler.invisibleAnnotations != null) handler.invisibleAnnotations.remove(injector);
		if (visible) wrapper.visibleAnnotations = new ArrayList<>(List.of(injector));
		else wrapper.invisibleAnnotations = new ArrayList<>(List.of(injector));
		set(at, "target", member(decision.call()));
		set(at, "ordinal", decision.occurrence());
		if (decision.moved(selected)) set(injector, "method", new ArrayList<>(List.of(decision.host().name + decision.host().desc)));
		mixin.methods.add(wrapper);
		return wrapper;
	}

	/**
	 * The original handler's own call of the member it redirects, handed the very values it was handed, becomes the
	 * carrier's call (its answer mapped back) — only where every value the carrier's call takes is one of those values.
	 */
	private static void forward(ClassNode mixin, MethodNode handler, MethodInsnNode call, Decision decision) {
		List<Type> nativeOperands = operandTypes(call), mergedOperands = operandTypes(decision.call());
		int[] inverse = new int[mergedOperands.size()];
		Arrays.fill(inverse, -1);
		for (int i = 0; i < nativeOperands.size(); i++) {
			int j = decision.carried()[i];
			if (j >= 0) inverse[j] = i;
		}
		for (int j : inverse) if (j < 0) return;
		Frame<SourceValue>[] frames;
		try {
			frames = new Analyzer<>(new SourceInterpreter()).analyze(mixin.name, handler);
		} catch (AnalyzerException | RuntimeException unanalysable) {
			Caught.expected(unanalysable);
			return;
		}
		boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		List<MethodInsnNode> forwards = new ArrayList<>();
		for (AbstractInsnNode instruction : handler.instructions) {
			if (!(instruction instanceof MethodInsnNode own) || !own.owner.equals(call.owner) || !own.name.equals(call.name) || !own.desc.equals(call.desc)
					|| own.getOpcode() != call.getOpcode()) continue;
			Frame<SourceValue> frame = frames[handler.instructions.indexOf(own)];
			if (frame == null || frame.getStackSize() < nativeOperands.size()) return;
			Set<AbstractInsnNode> producers = Collections.newSetFromMap(new IdentityHashMap<>());
			for (int k = 0; k < nativeOperands.size(); k++) {
				SourceValue value = frame.getStack(frame.getStackSize() - nativeOperands.size() + k);
				if (value.insns.size() != 1) return;
				AbstractInsnNode producer = value.insns.iterator().next();
				if (!handedAgain(handler, isStatic, producer, k, nativeOperands, call, decision, frames, mixin)) return;
				producers.add(producer);
				if (producer instanceof FieldInsnNode field) {
					Frame<SourceValue> before = frames[handler.instructions.indexOf(field)];
					producers.add(before.getStack(before.getStackSize() - 1).insns.iterator().next());
				}
			}
			// Nothing happens before the call but reading the values it is handed: a field read again is the value handed.
			for (AbstractInsnNode before = handler.instructions.getFirst(); before != own; before = before.getNext())
				if (before.getOpcode() >= 0 && !producers.contains(before)) return;
			forwards.add(own);
		}
		for (MethodInsnNode own : forwards) {
			InsnList replacement = new InsnList();
			int base = handler.maxLocals;
			int[] slots = new int[nativeOperands.size()];
			int next = base;
			for (int k = 0; k < nativeOperands.size(); k++) {
				slots[k] = next;
				next += nativeOperands.get(k).getSize();
			}
			for (int k = nativeOperands.size() - 1; k >= 0; k--) replacement.add(new VarInsnNode(nativeOperands.get(k).getOpcode(Opcodes.ISTORE), slots[k]));
			for (int j = 0; j < mergedOperands.size(); j++) replacement.add(new VarInsnNode(mergedOperands.get(j).getOpcode(Opcodes.ILOAD), slots[inverse[j]]));
			MethodInsnNode carrier = decision.call();
			replacement.add(new MethodInsnNode(carrier.getOpcode(), carrier.owner, carrier.name, carrier.desc, carrier.itf));
			if (decision.inverted()) {
				replacement.add(new InsnNode(Opcodes.ICONST_1));
				replacement.add(new InsnNode(Opcodes.IXOR));
			}
			handler.instructions.insert(own, replacement);
			handler.instructions.remove(own);
			handler.maxLocals = next;
			handler.maxStack = Math.max(handler.maxStack, next - base + 2);
		}
	}

	/** Whether {@code producer} hands the original handler's own call its native value {@code k} again. */
	private static boolean handedAgain(MethodNode handler, boolean isStatic, AbstractInsnNode producer, int k, List<Type> nativeOperands,
			MethodInsnNode call, Decision decision, Frame<SourceValue>[] frames, ClassNode mixin) {
		int parameter = (isStatic ? 0 : 1);
		for (int i = 0; i < k; i++) parameter += nativeOperands.get(i).getSize();
		if (producer instanceof VarInsnNode load && load.var == parameter && load.getOpcode() == nativeOperands.get(k).getOpcode(Opcodes.ILOAD))
			return stores(handler, parameter) == 0;
		// The same field of the instance the native call is handed, read again: the native value is that field read on the
		// method's own receiver, and the handler reads it on its own (the target's instance) before anything else happens.
		if (!isStatic && producer instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD && decision.values() != null) {
			NativeCallChanges.Expr wanted = decision.values().get(k);
			Frame<SourceValue> before = frames[handler.instructions.indexOf(field)];
			if (before == null || before.getStackSize() < 1) return false;
			SourceValue receiver = before.getStack(before.getStackSize() - 1);
			if (receiver.insns.size() != 1 || !(receiver.insns.iterator().next() instanceof VarInsnNode self) || self.var != 0
					|| self.getOpcode() != Opcodes.ALOAD || stores(handler, 0) != 0) return false;
			String owner = field.owner.equals(mixin.name) ? call.owner : field.owner;
			return wanted.kind().equals("field") && wanted.inputs().size() == 1 && wanted.inputs().getFirst().equals(new NativeCallChanges.Expr("parameter", "0"))
					&& wanted.symbol().endsWith(";" + field.name + ":" + field.desc) && (field.owner.equals(mixin.name) || wanted.symbol().equals("L" + owner + ";" + field.name + ":" + field.desc));
		}
		return false;
	}

	private static final String SUGAR = "Lcom/llamalad7/mixinextras/sugar/", SHARE = "Lcom/llamalad7/mixinextras/sugar/Share;";

	/** The first parameter of a trailing run of MixinExtras sugar ({@code count} when none); -1 when sugar is not trailing. */
	private static int sugarStart(MethodNode handler, int count) {
		int start = count;
		for (int p = count - 1; p >= 0 && sugarOf(handler, p) != null; p--) start = p;
		for (int p = 0; p < start; p++) if (parameterAnnotated(handler, p)) return -1;
		return start;
	}

	private static AnnotationNode sugarOf(MethodNode handler, int parameter) {
		AnnotationNode found = null;
		for (List<AnnotationNode>[] table : Arrays.asList(handler.visibleParameterAnnotations, handler.invisibleParameterAnnotations)) {
			if (table == null || parameter >= table.length || table[parameter] == null) continue;
			for (AnnotationNode annotation : table[parameter]) {
				if (!annotation.desc.startsWith(SUGAR) || found != null) return null;
				found = annotation;
			}
		}
		return found;
	}

	private static boolean parameterAnnotated(MethodNode handler, int parameter) {
		for (List<AnnotationNode>[] table : Arrays.asList(handler.visibleParameterAnnotations, handler.invisibleParameterAnnotations))
			if (table != null && parameter < table.length && table[parameter] != null && !table[parameter].isEmpty()) return true;
		return false;
	}

	/**
	 * Whether a piece of sugar means the same at the carrier's call as at the native one: a {@code @Share} always (it is
	 * the mixin's own value); a {@code @Local} named by its debug name alone when the merged method has a local of that
	 * name and type at the call holding the very expression the native local of that name holds at the native call.
	 */
	private static boolean sugarHolds(AnnotationNode sugar, Type type, ClassNode original, MethodNode was, MethodInsnNode call, ClassNode merged,
			Decision decision) {
		if (SHARE.equals(sugar.desc)) return true;
		if (!LOCAL.equals(sugar.desc) || sugar.values == null || sugar.values.size() != 2 || !"name".equals(sugar.values.get(0))) return false;
		List<String> names = MixinFit.stringList(sugar.values.get(1));
		if (names.size() != 1) return false;
		int nativeSlot = named(was, names.getFirst(), type, call), mergedSlot = named(decision.host(), names.getFirst(), type, decision.call());
		if (nativeSlot < 0 || mergedSlot < 0) return false;
		Map<Integer, NativeCallChanges.Expr> before = NativeCallChanges.localsAt(original.name, was, call),
				after = NativeCallChanges.localsAt(merged.name, decision.host(), decision.call());
		return before != null && after != null && before.get(nativeSlot) != null && before.get(nativeSlot).equals(after.get(mergedSlot));
	}

	/** The one slot the table names {@code name} with {@code type} live at {@code at}; -1 when none or several. */
	private static int named(MethodNode method, String name, Type type, AbstractInsnNode at) {
		if (method.localVariables == null) return -1;
		int index = method.instructions.indexOf(at), found = -1;
		for (LocalVariableNode local : method.localVariables) {
			if (!local.name.equals(name) || !local.desc.equals(type.getDescriptor())) continue;
			if (method.instructions.indexOf(local.start) > index || index >= method.instructions.indexOf(local.end)) continue;
			if (found >= 0 && found != local.index) return -1;
			found = local.index;
		}
		return found;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Small helpers

	static List<Type> operandTypes(MethodInsnNode call) {
		List<Type> out = new ArrayList<>();
		if (call.getOpcode() != Opcodes.INVOKESTATIC) out.add(Type.getObjectType(call.owner));
		out.addAll(List.of(Type.getArgumentTypes(call.desc)));
		return out;
	}

	private static boolean hasParameterAnnotations(MethodNode method) {
		for (List<AnnotationNode>[] table : Arrays.asList(method.visibleParameterAnnotations, method.invisibleParameterAnnotations))
			if (table != null) for (List<AnnotationNode> annotations : table) if (annotations != null && !annotations.isEmpty()) return true;
		return false;
	}

	private static int stores(MethodNode method, int slot) {
		int n = 0;
		for (AbstractInsnNode instruction : method.instructions)
			if (instruction instanceof VarInsnNode store && store.var == slot && store.getOpcode() >= Opcodes.ISTORE && store.getOpcode() <= Opcodes.ASTORE) n++;
			else if (instruction instanceof IincInsnNode increment && increment.var == slot) n++;
		return n;
	}

	/**
	 * How the local {@code store} writes is read: the instruction right after each read of it within the table's range for
	 * it, as tokens (a value kept for a null check, a branch, a call argument). Two methods keep a value alike when they
	 * read it alike.
	 */
	static Set<String> uses(MethodNode method, VarInsnNode store) {
		Set<String> out = new java.util.TreeSet<>();
		LocalVariableNode range = null;
		AbstractInsnNode live = nextReal(store);
		int at = live == null ? -1 : method.instructions.indexOf(live);
		if (method.localVariables != null) for (LocalVariableNode local : method.localVariables)
			if (local.index == store.var && method.instructions.indexOf(local.start) <= at && at < method.instructions.indexOf(local.end)) range = local;
		if (range == null) return out;
		int from = method.instructions.indexOf(range.start), to = method.instructions.indexOf(range.end);
		int load = store.getOpcode() - (Opcodes.ISTORE - Opcodes.ILOAD);
		for (AbstractInsnNode instruction : method.instructions) {
			int index = method.instructions.indexOf(instruction);
			if (index < from || index >= to || !(instruction instanceof VarInsnNode read) || read.var != store.var || read.getOpcode() != load) continue;
			AbstractInsnNode consumer = nextReal(read);
			out.add(consumer == null ? "end" : consumer instanceof VarInsnNode ? String.valueOf(consumer.getOpcode()) : token(method, consumer));
		}
		return out;
	}

	/**
	 * The debug name and declared type the table gives {@code store}'s local right after the store ({@code name:desc}); null
	 * when none or ambiguous. The declared type may be wider than the value stored ({@code Map} for a {@code HashMap}).
	 */
	private static String localName(MethodNode method, VarInsnNode store, Type type) {
		if (method.localVariables == null) return null;
		AbstractInsnNode live = nextReal(store);
		if (live == null) return null;
		int at = method.instructions.indexOf(live);
		String found = null;
		for (LocalVariableNode local : method.localVariables) {
			if (local.index != store.var || type.getSort() != Type.getType(local.desc).getSort()) continue;
			if (method.instructions.indexOf(local.start) <= at && at < method.instructions.indexOf(local.end)) {
				String named = local.name + ":" + local.desc;
				if (found != null && !found.equals(named)) return null;
				found = named;
			}
		}
		return found;
	}

	private static AbstractInsnNode firstReal(AbstractInsnNode at) {
		for (; at != null; at = at.getNext()) if (at.getOpcode() >= 0) return at;
		return null;
	}

	private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
		return instruction == null ? null : firstReal(instruction.getNext());
	}

	private static int occurrence(MethodNode method, MethodInsnNode call) {
		int n = 0;
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction == call) return n;
			if (instruction instanceof MethodInsnNode other && other.owner.equals(call.owner) && other.name.equals(call.name) && other.desc.equals(call.desc)) n++;
		}
		return n;
	}

	private static String member(MethodInsnNode call) {
		return "L" + call.owner + ";" + call.name + call.desc;
	}

	private static String readable(String target) {
		if (target == null) return "?";
		int semicolon = target.indexOf(';'), paren = target.indexOf('(');
		if (!target.startsWith("L") || semicolon < 0 || paren < semicolon) return target;
		return target.substring(1, semicolon).replace('/', '.') + "." + target.substring(semicolon + 1, paren);
	}

	private static boolean annotated(MethodNode method, String desc) {
		for (List<AnnotationNode> list : Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations))
			if (list != null) for (AnnotationNode annotation : list) if (annotation.desc.equals(desc)) return true;
		return false;
	}

	private static void set(AnnotationNode annotation, String key, Object value) {
		if (annotation.values == null) annotation.values = new ArrayList<>();
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) if (key.equals(annotation.values.get(i))) { annotation.values.set(i + 1, value); return; }
		annotation.values.add(key);
		annotation.values.add(value);
	}
}
