/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Points a mod's {@code @Local} at the local its own loader hands it, where the merged method holds more locals of that
 * type at the point and MixinExtras would find several (or another) there.
 *
 * <p>A carrier often reads a value once more into a local of its own — NeoForge's {@code destroyBlock} keeps the state it
 * read first beside the state {@code playerWillDestroy} returned; a toss keeps the selected stack beside the removed one.
 * A {@code @Local BlockState} that names the only state at vanilla's point names two at the merged one, and MixinExtras
 * refuses the injection.
 *
 * <p>Nothing here knows a mod, a class or a method by name. For one injector with one call or field point and no slice,
 * whose method is the same natively (the class its ecosystem's mods were compiled against) and merged, and whose point
 * selects as many instructions in both: each {@code @Local} that resolves to one local at the native point and not to
 * one at the merged point is pinned ({@code @Local(index)}) to the merged local that holds what the native one holds —
 * proved by the instruction that produced it ({@link MixinLocalOriginProof#prove}), or, where that proof is not
 * available, the one merged local with the native local's debug name and type live at the point. Where the point selects
 * several instructions, every one must agree on the slot. {@code -Dforbric.nativeLocalPins=off} pins nothing.
 */
public final class NativeLocalPins {
	public static final String PROPERTY = "forbric.nativeLocalPins";

	private NativeLocalPins() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Pins what {@code mixin}'s injectors' {@code @Local}s should read; returns how many it pinned. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> code, BiFunction<Ecosystem, String, ClassNode> natives) {
		if (!enabled() || mixin == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		if (ecosystem == null) return 0;
		int pinned = 0;
		for (String target : MixinFit.mixinTargets(mixin)) {
			ClassNode original = natives.apply(ecosystem, target), merged = null;
			if (original == null) continue;
			for (MethodNode handler : mixin.methods) {
				AnnotationNode injector = MixinFit.injectorOf(handler);
				if (injector == null || MixinFit.value(injector, "slice") != null || !hasLocal(handler)) continue;
				if (merged == null) merged = code.apply(target);
				if (merged == null) break;
				Pin pin = derive(original, merged, handler, injector);
				if (pin == null) continue;
				int count = MixinCallbackProofs.pinLocals(handler, pin.host(), pin.point(), pin.slots());
				if (count > 0) {
					pinned += count;
					ForbricLog.info("[Forbric/Mixin] %s.%s: the merged %s.%s holds more locals of a type the handler asks for at its point; "
							+ "pinned %s to the locals %s hands it", mixin.name.replace('/', '.'), handler.name, target.replace('/', '.'),
							pin.host().name, describe(handler, pin.slots()), ecosystem);
				}
			}
		}
		return pinned;
	}

	private record Pin(MethodNode host, AbstractInsnNode point, Map<Integer, Integer> slots) {
	}

	private static boolean hasLocal(MethodNode handler) {
		int count = Type.getArgumentTypes(handler.desc).length;
		for (int i = 0; i < count; i++) if (MixinStubRebind.sugar(handler, i, MixinRetarget.LOCAL_SUGAR) != null) return true;
		return false;
	}

	private static String describe(MethodNode handler, Map<Integer, Integer> slots) {
		Type[] parameters = Type.getArgumentTypes(handler.desc);
		StringBuilder out = new StringBuilder();
		for (var entry : slots.entrySet()) {
			if (out.length() > 0) out.append(", ");
			out.append(parameters[entry.getKey()].getClassName()).append(" → slot ").append(entry.getValue());
		}
		return out.toString();
	}

	/** The pins one handler needs at its merged point, or null. */
	static Pin derive(ClassNode original, ClassNode merged, MethodNode handler, AnnotationNode injector) {
		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.size() != 1) return null;
		AnnotationNode at = points.getFirst();
		String kind = MixinFit.asString(MixinFit.value(at, "value"));
		if (kind == null || !(kind.startsWith("INVOKE") || kind.equals("FIELD"))) return null;
		MethodNode was = MixinTargetSelectors.one(handler, original), host = MixinTargetSelectors.one(handler, merged);
		if (was == null || host == null || !was.name.equals(host.name) || !was.desc.equals(host.desc)) return null;
		List<AbstractInsnNode> before = MixinCallbackShape.pointSelected(at, was), after = MixinCallbackShape.pointSelected(at, host);
		if (before.isEmpty() || before.size() != after.size()) return null;
		int ordinal = MixinFit.value(at, "ordinal") instanceof Number number ? number.intValue() : -1;
		int from = ordinal >= 0 ? ordinal : 0, to = ordinal >= 0 ? ordinal : before.size() - 1;
		if (to >= before.size()) return null;
		Type[] parameters = Type.getArgumentTypes(handler.desc);
		Map<Integer, Integer> agreed = null;
		for (int index = from; index <= to; index++) {
			AbstractInsnNode nativePoint = before.get(index), mergedPoint = after.get(index);
			int nativeAt = was.instructions.indexOf(nativePoint), mergedAt = host.instructions.indexOf(mergedPoint);
			Map<Integer, Integer> wanted = new LinkedHashMap<>();
			for (int p = 0; p < parameters.length; p++) {
				AnnotationNode local = MixinStubRebind.sugar(handler, p, MixinRetarget.LOCAL_SUGAR);
				if (local == null) continue;
				if (parameters[p].getSort() == Type.OBJECT && parameters[p].getInternalName().startsWith("com/llamalad7/mixinextras/sugar/ref/"))
					return null;
				int nativeSlot = MixinLocalOriginProof.slot(local, parameters[p], was, nativeAt);
				if (nativeSlot < 0 || MixinLocalOriginProof.slot(local, parameters[p], host, mergedAt) >= 0) continue;
				wanted.put(p, nativeSlot);
			}
			if (wanted.isEmpty()) return null;
			Map<Integer, Integer> proved = MixinLocalOriginProof.prove(handler, original.name, was, nativePoint, host, mergedPoint);
			Map<Integer, Integer> slots = new HashMap<>();
			for (var entry : wanted.entrySet()) {
				Integer slot = proved == null ? null : proved.get(entry.getKey());
				if (slot == null) slot = byName(was, entry.getValue(), nativeAt, host, mergedAt, parameters[entry.getKey()]);
				if (slot == null) return null;
				slots.put(entry.getKey(), slot);
			}
			if (agreed != null && !agreed.equals(slots)) return null;
			agreed = slots;
		}
		// In parameter order: Map.copyOf iterates in an order salted per JVM, which made the log line differ run to run.
		return agreed == null ? null : new Pin(host, after.get(from), java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(agreed)));
	}

	/** The one merged local live at the point with the native local's debug name and type. */
	private static Integer byName(MethodNode was, int nativeSlot, int nativeAt, MethodNode host, int mergedAt, Type type) {
		LocalVariableNode named = live(was, nativeSlot, nativeAt);
		if (named == null || !Type.getType(named.desc).equals(type) || host.localVariables == null) return null;
		Integer found = null;
		for (LocalVariableNode local : host.localVariables) {
			if (!local.name.equals(named.name) || !local.desc.equals(named.desc)) continue;
			int start = host.instructions.indexOf(local.start), end = host.instructions.indexOf(local.end);
			if (start > mergedAt || mergedAt >= end) continue;
			if (found != null && found != local.index) return null;
			found = local.index;
		}
		return found;
	}

	private static LocalVariableNode live(MethodNode method, int slot, int at) {
		if (method.localVariables == null) return null;
		for (LocalVariableNode local : method.localVariables) {
			if (local.index != slot) continue;
			int start = method.instructions.indexOf(local.start), end = method.instructions.indexOf(local.end);
			if (start <= at && at < end) return local;
		}
		return null;
	}
}
