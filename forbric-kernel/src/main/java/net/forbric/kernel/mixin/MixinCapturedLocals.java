/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Caught;

/**
 * Turns a guest {@code @Inject(locals = CAPTURE_*)} into MixinExtras {@code @Local}s that read, in the merged body, the
 * locals it captured in the native one.
 *
 * <p>A locals capture takes the target's locals at the injection point, in slot order from the first one after the
 * arguments, and Mixin checks the handler's parameters after the {@code CallbackInfo} against exactly that list. A carrier
 * that declares one more local in front of the captured ones changes the list: NeoForge's {@code Minecraft.startUseItem}
 * keeps an {@code InputEvent.InteractionKeyMappingTriggered} between the hand and the held item, so Architectury's
 * CLIENT_RIGHT_CLICK_AIR (captures {@code InteractionHand[], int, int, InteractionHand, ItemStack}) failed Mixin's check,
 * was softened to a skip (MixinLocalsCapture), and never fired.
 *
 * <p>Each captured parameter is the native local in its position: the locals at the native point with a value
 * (NativeCallChanges.localsAt) from the first slot after the arguments, without the ones the local variable table says
 * are out of scope there, in slot order — and each must be of the parameter's type there (its table entry, or, for a
 * local the compiler left unnamed such as a for-each's array and index, the type of what produced it). In the merged body
 * the same local is the one slot holding the same value (the same producing expression, loop counters and array reads
 * included), of the same type. Only then does every captured parameter become {@code @Local(index = slot)} and the
 * capture go; one unproved parameter leaves the injector as written. A slice, a callback group, more than one point, or a
 * point the merged body does not select exactly once are left alone. {@code -Dforbric.capturedLocals=off} leaves every
 * capture as compiled.
 */
public final class MixinCapturedLocals {
	public static final String PROPERTY = "forbric.capturedLocals";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String LOCAL_CAPTURE = "Lorg/spongepowered/asm/mixin/injection/callback/LocalCapture;";
	private static final String CALLBACK_INFO = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
	private static final String CALLBACK_INFO_RETURNABLE = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";

	private MixinCapturedLocals() {
	}

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		return adapt(mixin, targets, NativeGameReferences::reference);
	}

	static int adapt(ClassNode mixin, Function<String, ClassNode> targets, BiFunction<Ecosystem, String, ClassNode> references) {
		if ("off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY, "on")) || mixin == null || mixin.methods == null
				|| targets == null || references == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		List<String> owners = MixinOverloadPin.targetsOf(mixin);
		if (ecosystem == null || owners.size() != 1) return 0;
		ClassNode current = null, original = null;
		int converted = 0;
		for (MethodNode handler : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(handler);
			if (injector == null || !INJECT.equals(injector.desc) || !captures(injector) || MixinFit.value(injector, "slice") != null
					|| MixinFit.groupOf(handler) != null || CurrentBodyOrdinals.counted(handler)) continue;
			List<AnnotationNode> points = MixinFit.atNodes(injector);
			if (points.size() != 1 || MixinFit.value(points.getFirst(), "slice") != null) continue;
			if (current == null) {
				current = targets.apply(owners.getFirst());
				original = references.apply(ecosystem, owners.getFirst());
				if (current == null || original == null) return converted;
			}
			MethodNode before = MixinTargetSelectors.one(handler, original), after = MixinTargetSelectors.one(handler, current);
			if (before == null || after == null || !before.name.equals(after.name) || !before.desc.equals(after.desc)) continue;
			Map<Integer, Integer> locals = convert(handler, points.getFirst(), original, before, current, after);
			if (locals == null) continue;
			apply(handler, injector, locals);
			converted++;
			ForbricLog.info("[Forbric/Mixin] %s: %s's locals capture in %s.%s%s now reads each captured local where the merged body "
					+ "keeps it (handler parameter → slot %s): the carrier declares other locals there, and the capture's list no longer "
					+ "matched", mixin.name.replace('/', '.'), handler.name, current.name.replace('/', '.'), after.name, after.desc, locals);
		}
		return converted;
	}

	/** Whether the injector captures locals (any {@code CAPTURE_*}). */
	private static boolean captures(AnnotationNode injector) {
		Object value = MixinFit.value(injector, "locals");
		return value instanceof String[] e && e.length == 2 && LOCAL_CAPTURE.equals(e[0]) && e[1].startsWith("CAPTURE_");
	}

	/** Handler parameter → merged slot for every captured parameter; null when any is not proved. */
	static Map<Integer, Integer> convert(MethodNode handler, AnnotationNode at, ClassNode originalOwner, MethodNode before,
			ClassNode currentOwner, MethodNode after) {
		List<AbstractInsnNode> nativePoints = MixinCallbackProofs.points(before, at);
		if (nativePoints == null || nativePoints.size() != 1) return null;
		AbstractInsnNode nativePoint = nativePoints.getFirst();
		AbstractInsnNode mergedPoint = mergedPoint(at, originalOwner, before, nativePoint, currentOwner, after);
		if (mergedPoint == null) return null;
		Type[] params = Type.getArgumentTypes(handler.desc);
		int callback = -1;
		for (int p = 0; p < params.length; p++) {
			if (params[p].getSort() == Type.OBJECT && (params[p].getInternalName().equals(CALLBACK_INFO)
					|| params[p].getInternalName().equals(CALLBACK_INFO_RETURNABLE))) { callback = p; break; }
		}
		if (callback < 0 || callback + 1 >= params.length) return null;
		List<Integer> captured = new ArrayList<>();
		for (int p = callback + 1; p < params.length; p++) {
			if (sugared(handler, p)) break;
			captured.add(p);
		}
		if (captured.isEmpty()) return null;
		Map<Integer, NativeCallChanges.Expr> was = NativeCallChanges.localsAt(originalOwner.name, before, nativePoint);
		Map<Integer, NativeCallChanges.Expr> now = NativeCallChanges.localsAt(currentOwner.name, after, mergedPoint);
		Frame<SourceValue>[] nativeFrames = sources(originalOwner.name, before), mergedFrames = sources(currentOwner.name, after);
		if (was == null || now == null || nativeFrames == null || mergedFrames == null) return null;
		int nativeAt = before.instructions.indexOf(nativePoint), mergedAt = after.instructions.indexOf(mergedPoint);
		// The locals Mixin lists at the native point: every slot past the arguments holding a value there and in scope, in
		// slot order (the second half of a long or double holds none). Each of the ones captured must have an expression.
		Frame<SourceValue> nativeFrame = nativeFrames[nativeAt];
		if (nativeFrame == null) return null;
		List<Integer> slots = new ArrayList<>();
		for (int slot = firstLocal(before); slot < nativeFrame.getLocals(); slot++) {
			SourceValue held = nativeFrame.getLocal(slot);
			if (held != null && !held.insns.isEmpty() && inScope(before, slot, nativeAt)) slots.add(slot);
		}
		if (slots.size() < captured.size()) return null;
		for (int i = 0; i < captured.size(); i++) if (!was.containsKey(slots.get(i))) return null;
		Map<Integer, Integer> result = new LinkedHashMap<>();
		for (int i = 0; i < captured.size(); i++) {
			int parameter = captured.get(i), slot = slots.get(i);
			Type type = params[parameter];
			if (!ofType(before, nativeAt, slot, type, was.get(slot))) return null;
			NativeCallChanges.Expr value = was.get(slot);
			// The slots holding that value on each side. One each: that one. Several (a for-each copies its argument array
			// into a local of its own): as many on both sides, taken in slot order; otherwise which one is a guess.
			List<Integer> holding = new ArrayList<>(), held = new ArrayList<>();
			for (Map.Entry<Integer, NativeCallChanges.Expr> candidate : was.entrySet()) if (candidate.getValue().equals(value)) holding.add(candidate.getKey());
			for (Map.Entry<Integer, NativeCallChanges.Expr> candidate : now.entrySet()) if (candidate.getValue().equals(value)) held.add(candidate.getKey());
			holding.sort(Integer::compare);
			held.sort(Integer::compare);
			Integer found = held.size() == holding.size() && !held.isEmpty() ? held.get(holding.indexOf(slot)) : null;
			if (found == null || !inScope(after, found, mergedAt) || !ofType(after, mergedAt, found, type, now.get(found))) return null;
			if (result.containsValue(found)) return null;
			result.put(parameter, found);
		}
		// Where the merged body lists the same locals in front, the capture still matches as written: nothing to change.
		Frame<SourceValue> mergedFrame = mergedFrames[mergedAt];
		List<Integer> listed = new ArrayList<>();
		for (int slot = firstLocal(after); mergedFrame != null && slot < mergedFrame.getLocals() && listed.size() < captured.size(); slot++) {
			SourceValue held = mergedFrame.getLocal(slot);
			if (held != null && !held.insns.isEmpty() && inScope(after, slot, mergedAt)) listed.add(slot);
		}
		if (listed.equals(new ArrayList<>(result.values()))) return null;
		return result;
	}

	/**
	 * The merged point for a native point: the same instruction position when the merged body makes the call as often,
	 * else the occurrence the alignment proves (CallOccurrenceAlignment), for an {@code INVOKE} point; null otherwise.
	 */
	private static AbstractInsnNode mergedPoint(AnnotationNode at, ClassNode originalOwner, MethodNode before, AbstractInsnNode nativePoint,
			ClassNode currentOwner, MethodNode after) {
		String value = MixinFit.asString(MixinFit.value(at, "value"));
		if (!"INVOKE".equals(value) || !(nativePoint instanceof MethodInsnNode call)) {
			List<AbstractInsnNode> merged = MixinCallbackProofs.points(after, at);
			return merged != null && merged.size() == 1 && ("HEAD".equals(value) || "TAIL".equals(value)) ? merged.getFirst() : null;
		}
		String member = CallOccurrenceAlignment.member(call);
		List<AbstractInsnNode> nativeCalls = new ArrayList<>(), mergedCalls = new ArrayList<>();
		for (AbstractInsnNode i : before.instructions) if (i instanceof MethodInsnNode c && CallOccurrenceAlignment.member(c).equals(member)) nativeCalls.add(i);
		for (AbstractInsnNode i : after.instructions) if (i instanceof MethodInsnNode c && CallOccurrenceAlignment.member(c).equals(member)) mergedCalls.add(i);
		int k = nativeCalls.indexOf(nativePoint);
		if (k < 0 || mergedCalls.isEmpty()) return null;
		int live;
		if (mergedCalls.size() == nativeCalls.size()) live = k;
		else if (mergedCalls.size() > nativeCalls.size()) {
			int[] counterparts = CallOccurrenceAlignment.nativeAmongAdded(originalOwner, before, currentOwner, after, member);
			live = counterparts == null ? -1 : counterparts[k];
		} else live = CallOccurrenceAlignment.retainedOrdinal(originalOwner, before, currentOwner, after, member, k);
		return live < 0 || live >= mergedCalls.size() ? null : mergedCalls.get(live);
	}

	private static boolean sugared(MethodNode handler, int parameter) {
		for (List<AnnotationNode>[] all : List.of(handler.visibleParameterAnnotations == null ? new List[0] : handler.visibleParameterAnnotations,
				handler.invisibleParameterAnnotations == null ? new List[0] : handler.invisibleParameterAnnotations)) {
			if (parameter < all.length && all[parameter] != null && !all[parameter].isEmpty()) return true;
		}
		return false;
	}

	private static int firstLocal(MethodNode method) {
		int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
		for (Type argument : Type.getArgumentTypes(method.desc)) slot += argument.getSize();
		return slot;
	}

	/** A slot the table names somewhere is in scope where an entry for it is live; one it never names is the compiler's own. */
	private static boolean inScope(MethodNode method, int slot, int point) {
		if (method.localVariables == null) return false;
		boolean named = false;
		for (LocalVariableNode variable : method.localVariables) {
			if (variable.index != slot) continue;
			named = true;
			if (method.instructions.indexOf(variable.start) <= point && point < method.instructions.indexOf(variable.end)) return true;
		}
		return !named;
	}

	/** Whether {@code slot} holds a {@code type} at {@code point}: by its live table entry, else by the expression that produced it. */
	private static boolean ofType(MethodNode method, int point, int slot, Type type, NativeCallChanges.Expr value) {
		if (method.localVariables != null) for (LocalVariableNode variable : method.localVariables) {
			if (variable.index == slot && method.instructions.indexOf(variable.start) <= point && point < method.instructions.indexOf(variable.end))
				return variable.desc.equals(type.getDescriptor());
		}
		Type produced = typeOf(method, value);
		return produced != null && produced.equals(type);
	}

	/** The static type of what {@code value} computes, where its kind says it: a parameter, a call, a field, an array read or length. */
	private static Type typeOf(MethodNode method, NativeCallChanges.Expr value) {
		if (value == null) return null;
		switch (value.kind()) {
			case "parameter" -> {
				int slot = Integer.parseInt(value.symbol()), at = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
				if (slot == 0 && at == 1) return null;
				for (Type argument : Type.getArgumentTypes(method.desc)) {
					if (at == slot) return argument;
					at += argument.getSize();
				}
				return null;
			}
			case "call" -> {
				MixinFit.Member member = MixinFit.parseMember(value.symbol());
				return member == null || member.desc() == null || !member.desc().startsWith("(") ? null : Type.getReturnType(member.desc());
			}
			case "field", "static-field" -> {
				MixinFit.Member member = MixinFit.parseMember(value.symbol());
				return member == null || member.desc() == null ? null : Type.getType(member.desc());
			}
			case "array-read" -> {
				Type array = value.inputs().isEmpty() ? null : typeOf(method, value.inputs().getFirst());
				return array == null || array.getSort() != Type.ARRAY ? null : Type.getType(array.getDescriptor().substring(1));
			}
			case "array-length", "iteration" -> {
				return Type.INT_TYPE;
			}
			default -> {
				return null;
			}
		}
	}

	private static Frame<SourceValue>[] sources(String owner, MethodNode method) {
		try {
			return new Analyzer<>(new SourceInterpreter(Opcodes.ASM9) {
				// A parameter is a value too (a for-each over an argument array copies it into a local): give it a producer.
				@Override public SourceValue newParameterValue(boolean isInstanceMethod, int local, Type type) {
					return new SourceValue(type.getSize(), new org.objectweb.asm.tree.VarInsnNode(type.getOpcode(Opcodes.ILOAD), local));
				}
				@Override public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) { return value; }
			}).analyze(owner, method);
		} catch (AnalyzerException | RuntimeException invalid) {
			Caught.expected(invalid);
			return null;
		}
	}

	/** Each captured parameter takes {@code @Local(index = slot)}; the capture goes. */
	@SuppressWarnings("unchecked")
	private static void apply(MethodNode handler, AnnotationNode injector, Map<Integer, Integer> locals) {
		int count = Type.getArgumentTypes(handler.desc).length;
		List<AnnotationNode>[] annotations = handler.invisibleParameterAnnotations;
		if (annotations == null || annotations.length < count) {
			List<AnnotationNode>[] grown = new List[count];
			if (annotations != null) System.arraycopy(annotations, 0, grown, 0, annotations.length);
			annotations = grown;
		}
		for (Map.Entry<Integer, Integer> local : locals.entrySet()) {
			AnnotationNode sugar = new AnnotationNode(MixinHandlerShape.LOCAL);
			sugar.values = new ArrayList<>(List.of("index", local.getValue()));
			annotations[local.getKey()] = new ArrayList<>(List.of(sugar));   // a captured parameter carries no sugar of its own
		}
		handler.invisibleParameterAnnotations = annotations;
		handler.invisibleAnnotableParameterCount = count;
		for (int i = 0; i + 1 < injector.values.size(); i += 2) {
			if ("locals".equals(injector.values.get(i))) {
				injector.values.remove(i + 1);
				injector.values.remove(i);
				break;
			}
		}
	}
}
