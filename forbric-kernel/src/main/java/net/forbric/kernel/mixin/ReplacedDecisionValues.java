/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Moves a mod's {@code @ModifyExpressionValue} of a vanilla decision onto the call the surviving carrier makes in its
 * place, where that call decides the very same branch or is kept in the very same local.
 *
 * <p>Vanilla's {@code Player.tick} asks {@code isEyeInFluid(FluidTags.WATER)} and, when the eye is not in water and a
 * turtle helmet is worn, ticks it; NeoForge asks {@code getFluidInteraction().isEyeInFluidMatching(this, predicate)}
 * there instead. A mod that changes the vanilla answer (a water-breathing origin that counts itself as submerged on land)
 * has nothing to change: the call it names is gone.
 *
 * <p>Nothing here knows a mod, a class or a method by name. One injector is moved when its only point is a call, with no
 * slice and no {@code @Group}; its method is the same natively (the class its ecosystem's mods were compiled against) and
 * merged; the native method makes that call exactly where the point picks it (once, or the occurrence its ordinal names)
 * and the merged method never; the native call is followed by a conditional jump; and the merged method makes exactly
 * one call the native method never makes, of the same return type, followed by the same conditional jump over a block
 * identical, instruction for instruction, to the native one — or the native call is stored straight into a local the
 * method stores only there, and the merged method stores exactly one such call, straight, into its one store of the local
 * the table names alike (same debug name and type): every later read of that local reads it, as natively ({@code Hud}'s
 * {@code isUnderWater} for the air bubbles). The handler only changes that value (its first parameter
 * and its return are the call's type), so on the carrier's call it changes the value the same branch reads. The point is
 * re-spelled to name the carrier's call. {@code -Dforbric.replacedDecisionValues=off} leaves every point as compiled.
 */
public final class ReplacedDecisionValues {
	public static final String PROPERTY = "forbric.replacedDecisionValues";
	private static final String MODIFY_EXPRESSION_VALUE = "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;";

	private ReplacedDecisionValues() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Moves every eligible value modifier of {@code mixin}; returns how many. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> code, BiFunction<Ecosystem, String, ClassNode> natives) {
		if (!enabled() || mixin == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		if (ecosystem == null) return 0;
		int moved = 0;
		for (String target : MixinFit.mixinTargets(mixin)) {
			ClassNode original = natives.apply(ecosystem, target), merged = null;
			if (original == null) continue;
			for (MethodNode handler : mixin.methods) {
				AnnotationNode injector = MixinFit.injectorOf(handler);
				if (injector == null || !MODIFY_EXPRESSION_VALUE.equals(injector.desc) || MixinFit.value(injector, "slice") != null) continue;
				if (annotated(handler, "Lorg/spongepowered/asm/mixin/injection/Group;")) continue;
				if (merged == null) merged = code.apply(target);
				if (merged == null) break;
				MethodInsnNode replacement = derive(original, merged, handler, injector);
				if (replacement == null) continue;
				AnnotationNode at = MixinFit.atNodes(injector).getFirst();
				String was = MixinFit.asString(MixinFit.value(at, "target"));
				MethodNode host = MixinTargetSelectors.one(handler, merged);
				set(at, "target", "L" + replacement.owner + ";" + replacement.name + replacement.desc);
				set(at, "ordinal", occurrence(host, replacement));
				moved++;
				ForbricLog.info("[Forbric/Mixin] %s.%s: %s is no longer called in %s; the call the merged method makes in its place, deciding "
						+ "the same branch, is %s.%s — the value it changes is that call's", mixin.name.replace('/', '.'), handler.name, readable(was),
						target.replace('/', '.') + "." + host.name, replacement.owner.replace('/', '.'), replacement.name);
			}
		}
		return moved;
	}

	/** The carrier's call that decides the branch the native call decided, or null. */
	static MethodInsnNode derive(ClassNode original, ClassNode merged, MethodNode handler, AnnotationNode injector) {
		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.size() != 1 || !"INVOKE".equals(MixinFit.asString(MixinFit.value(points.getFirst(), "value")))) return null;
		AnnotationNode at = points.getFirst();
		for (int i = 0; at.values != null && i + 1 < at.values.size(); i += 2)
			if (!Set.of("value", "target", "ordinal", "remap").contains(at.values.get(i))) return null;
		MethodNode was = MixinTargetSelectors.one(handler, original), host = MixinTargetSelectors.one(handler, merged);
		if (was == null || host == null || !was.name.equals(host.name) || !was.desc.equals(host.desc)) return null;
		if (!MixinCallbackShape.calls(at, host).isEmpty()) return null;
		List<MethodInsnNode> nativeCalls = MixinCallbackShape.calls(at, was);
		int ordinal = MixinFit.value(at, "ordinal") instanceof Number number ? number.intValue() : -1;
		MethodInsnNode call = ordinal >= 0 ? (ordinal < nativeCalls.size() ? nativeCalls.get(ordinal) : null) : nativeCalls.size() == 1 ? nativeCalls.getFirst() : null;
		if (call == null) return null;
		Type value = Type.getReturnType(call.desc);
		Type[] parameters = Type.getArgumentTypes(handler.desc);
		if (value.getSort() == Type.VOID || parameters.length == 0 || !parameters[0].equals(value) || !Type.getReturnType(handler.desc).equals(value)) return null;
		Set<String> nativeMembers = new TreeSet<>();
		for (AbstractInsnNode instruction : was.instructions) if (instruction instanceof MethodInsnNode other) nativeMembers.add(other.name + other.desc);
		AbstractInsnNode after = nextReal(call);
		if (after instanceof JumpInsnNode decided) {
			if (decided.getOpcode() == Opcodes.GOTO || decided.getOpcode() == Opcodes.JSR) return null;
			List<String> block = block(was, decided);
			if (block == null) return null;
			MethodInsnNode found = null;
			for (MethodInsnNode candidate : novel(host, nativeMembers, value)) {
				if (!(nextReal(candidate) instanceof JumpInsnNode jump) || jump.getOpcode() != decided.getOpcode() || !block.equals(block(host, jump))) continue;
				if (found != null) return null;
				found = candidate;
			}
			return found;
		}
		// The value kept in a local the method stores once: the carrier's call that the merged method keeps in that same local
		// (its debug name and type), stored there once too, is the value every later read of the local reads.
		if (!(after instanceof VarInsnNode kept) || kept.getOpcode() != value.getOpcode(Opcodes.ISTORE) || stores(was, kept.var) != 1) return null;
		String name = localName(was, kept, value);
		if (name == null) return null;
		MethodInsnNode found = null;
		for (MethodInsnNode candidate : novel(host, nativeMembers, value)) {
			if (!(nextReal(candidate) instanceof VarInsnNode store) || store.getOpcode() != kept.getOpcode() || stores(host, store.var) != 1
					|| !name.equals(localName(host, store, value))) continue;
			if (found != null) return null;
			found = candidate;
		}
		return found;
	}

	/** The calls {@code host} makes that the native method never makes, returning {@code value}. */
	private static List<MethodInsnNode> novel(MethodNode host, Set<String> nativeMembers, Type value) {
		List<MethodInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode instruction : host.instructions)
			if (instruction instanceof MethodInsnNode candidate && !candidate.name.startsWith("<") && !nativeMembers.contains(candidate.name + candidate.desc)
					&& Type.getReturnType(candidate.desc).equals(value)) out.add(candidate);
		return out;
	}

	private static int stores(MethodNode method, int slot) {
		int n = 0;
		for (AbstractInsnNode instruction : method.instructions)
			if (instruction instanceof VarInsnNode store && store.var == slot && store.getOpcode() >= Opcodes.ISTORE && store.getOpcode() <= Opcodes.ASTORE) n++;
			else if (instruction instanceof IincInsnNode increment && increment.var == slot) n++;
		return n;
	}

	/** The debug name the table gives {@code store}'s local of {@code type} right after the store; null when none or unnamed. */
	private static String localName(MethodNode method, VarInsnNode store, Type type) {
		if (method.localVariables == null) return null;
		AbstractInsnNode live = nextReal(store);
		if (live == null) return null;
		int at = method.instructions.indexOf(live);
		String found = null;
		for (var local : method.localVariables) {
			if (local.index != store.var || !local.desc.equals(type.getDescriptor())) continue;
			if (method.instructions.indexOf(local.start) <= at && at < method.instructions.indexOf(local.end)) {
				if (found != null && !found.equals(local.name)) return null;
				found = local.name;
			}
		}
		return found;
	}

	/** The real instructions a conditional jump skips over, as tokens; null when they are not a forward block. */
	static List<String> block(MethodNode method, JumpInsnNode jump) {
		List<AbstractInsnNode> real = new ArrayList<>();
		Map<AbstractInsnNode, Integer> index = new IdentityHashMap<>();
		for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) { index.put(instruction, real.size()); real.add(instruction); }
		int from = index.get(jump) + 1, to = target(index, jump.label);
		if (to < 0 || to <= from) return null;
		List<String> tokens = new ArrayList<>();
		for (int i = from; i < to; i++) {
			AbstractInsnNode instruction = real.get(i);
			StringBuilder token = new StringBuilder().append(instruction.getOpcode());
			if (instruction instanceof MethodInsnNode call) token.append(' ').append(call.owner).append('.').append(call.name).append(call.desc);
			else if (instruction instanceof FieldInsnNode field) token.append(' ').append(field.owner).append('.').append(field.name).append(':').append(field.desc);
			else if (instruction instanceof VarInsnNode variable) token.append(' ').append(variable.var);
			else if (instruction instanceof IincInsnNode increment) token.append(' ').append(increment.var).append(' ').append(increment.incr);
			else if (instruction instanceof LdcInsnNode ldc) token.append(' ').append(ldc.cst);
			else if (instruction instanceof IntInsnNode number) token.append(' ').append(number.operand);
			else if (instruction instanceof TypeInsnNode type) token.append(' ').append(type.desc);
			else if (instruction instanceof JumpInsnNode inner) token.append(' ').append(target(index, inner.label) - from);
			else if (instruction instanceof InvokeDynamicInsnNode dynamic) token.append(' ').append(dynamic.name).append(dynamic.desc);
			else if (instruction instanceof TableSwitchInsnNode || instruction instanceof LookupSwitchInsnNode) return null;
			tokens.add(token.toString());
		}
		return tokens;
	}

	private static int target(Map<AbstractInsnNode, Integer> index, LabelNode label) {
		for (AbstractInsnNode at = label; at != null; at = at.getNext()) if (at.getOpcode() >= 0) return index.getOrDefault(at, -1);
		return -1;
	}

	private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
		for (AbstractInsnNode at = instruction.getNext(); at != null; at = at.getNext()) if (at.getOpcode() >= 0) return at;
		return null;
	}

	/** Which call of its member, in order, {@code call} is in {@code method}. */
	private static int occurrence(MethodNode method, MethodInsnNode call) {
		int n = 0;
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction == call) return n;
			if (instruction instanceof MethodInsnNode other && other.owner.equals(call.owner) && other.name.equals(call.name) && other.desc.equals(call.desc)) n++;
		}
		return n;
	}

	/** {@code Lowner;name(desc)} as {@code owner.name}; anything else as written. */
	private static String readable(String target) {
		if (target == null) return "?";
		int semicolon = target.indexOf(';'), paren = target.indexOf('(');
		if (!target.startsWith("L") || semicolon < 0 || paren < semicolon) return target;
		return target.substring(1, semicolon).replace('/', '.') + "." + target.substring(semicolon + 1, paren);
	}

	private static boolean annotated(MethodNode method, String desc) {
		for (List<AnnotationNode> list : java.util.Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations))
			if (list != null) for (AnnotationNode annotation : list) if (annotation.desc.equals(desc)) return true;
		return false;
	}

	private static void set(AnnotationNode annotation, String key, Object value) {
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) if (key.equals(annotation.values.get(i))) { annotation.values.set(i + 1, value); return; }
		annotation.values.add(key);
		annotation.values.add(value);
	}

}
