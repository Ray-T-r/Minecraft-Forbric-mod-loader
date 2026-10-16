/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Lands a guest's {@code @At(shift = BY, by = n)} on the instruction it lands on in the body the guest was compiled
 * against, when a carrier's code now stands between the point and that instruction.
 *
 * <p>Mixin resolves {@code by} by counting nodes in the instruction list: {@code n} places from what the point selects.
 * The count was right in the native body. A merge that keeps another family's code inside that stretch moves the count
 * onto that code instead. Shoulder Surfing (NeoForge) modifies {@code Camera.calculateFov}'s {@code fov} at
 * {@code TAIL} {@code by = -2}: natively that is the load of {@code fov} that feeds
 * {@code modifyFovBasedOnDeathOrFluid}, whose result the method returns. The merged body goes on after that call into
 * MinecraftForge's {@code fireComputeFov(...).getFOV()} before it returns, so two places back from the return is the
 * Forge event call — the handler's value was stored into {@code fov} after the last read of it, and the FOV override
 * did nothing, with no error anywhere.
 *
 * <p>The native target is read off the native body (META-INF/forbric/native-reference, {@link NativeGameReferences}) with
 * Mixin's own counting. Its counterpart in the merged body is the one instruction that the same run of real
 * instructions ending at it — or starting at it — singles out in both bodies (the shortest run that is unique in the
 * native body, grown until it is unique in the merged one); when both runs find one, they must agree. The point is then
 * re-spelled on the nearest call (or return) at or after that instruction, in the merged body's own count, shifted back
 * by the nodes in between — never forward, so Mixin's {@code maxShiftBy} limit is never met. Only an injector with one
 * {@code @At} that shifts {@code BY}, no slice, no group, on a method its selector binds alone in both bodies with the
 * same descriptor, whose points were not already translated to the merged body; nothing moves where the native target
 * is not an instruction or its counterpart cannot be proved, and that is reported where the stretch differs.
 * {@code -Dforbric.shiftedPoints=off} leaves every point as compiled.
 */
public final class MixinShiftedPoints {
	public static final String PROPERTY = "forbric.shiftedPoints";
	private static final String SHIFT = "Lorg/spongepowered/asm/mixin/injection/At$Shift;";
	/** Longest run of instructions a counterpart is looked for by; a longer one proves nothing a shorter one did not. */
	private static final int CONTEXT = 16;

	private MixinShiftedPoints() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY, "on"));
	}

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		return adapt(mixin, targets, NativeGameReferences::reference);
	}

	/** The source seam supplies hash-verified native bytes in production and explicit original bytes in tests. */
	static int adapt(ClassNode mixin, Function<String, ClassNode> targets, BiFunction<Ecosystem, String, ClassNode> references) {
		if (!enabled() || mixin == null || mixin.methods == null || targets == null || references == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		List<String> owners = MixinOverloadPin.targetsOf(mixin);
		if (ecosystem == null || owners.size() != 1) return 0;
		ClassNode current = null, original = null;
		int moved = 0;
		for (MethodNode handler : mixin.methods) {
			if (CurrentBodyOrdinals.counted(handler)) continue;
			AnnotationNode injector = MixinFit.injectorOf(handler);
			if (injector == null || MixinFit.value(injector, "slice") != null || MixinFit.groupOf(handler) != null) continue;
			List<AnnotationNode> ats = MixinFit.atNodes(injector);
			if (ats.size() != 1 || !shiftsBy(ats.getFirst())) continue;
			AnnotationNode at = ats.getFirst();
			if (current == null) {
				current = targets.apply(owners.getFirst());
				original = references.apply(ecosystem, owners.getFirst());
				if (current == null || original == null) return moved;
			}
			MethodNode before = MixinTargetSelectors.one(handler, original), after = MixinTargetSelectors.one(handler, current);
			if (before == null || after == null || !(before.name + before.desc).equals(after.name + after.desc)) continue;
			int by = ((Number) MixinFit.value(at, "by")).intValue();
			AbstractInsnNode nativeTarget = shifted(before, at, by), liveTarget = shifted(after, at, by);
			if (nativeTarget == null || nativeTarget.getOpcode() < 0) continue;
			AbstractInsnNode counterpart = counterpart(before, nativeTarget, after);
			if (counterpart == null) {
				if (liveTarget == null || !token(liveTarget).equals(token(nativeTarget))) {
					ForbricLog.warn("[Forbric/Mixin] %s: %s's point %s shifted by %d lands on %s in the native %s.%s and on %s in the merged one; "
							+ "the native instruction's counterpart in the merged body is not proved, so the point stays as compiled",
							mixin.name.replace('/', '.'), handler.name, MixinFit.asString(MixinFit.value(at, "value")), by, token(nativeTarget),
							current.name.replace('/', '.'), after.name, liveTarget == null ? "nothing" : token(liveTarget));
				}
				continue;
			}
			if (counterpart == liveTarget) continue;
			Respelled respelled = respell(current.name, after, counterpart);
			if (respelled == null) {
				ForbricLog.warn("[Forbric/Mixin] %s: %s's point %s shifted by %d lands on %s natively; the merged %s.%s has it, but no call or "
						+ "return after it to name it by, so the point stays as compiled", mixin.name.replace('/', '.'), handler.name,
						MixinFit.asString(MixinFit.value(at, "value")), by, token(nativeTarget), current.name.replace('/', '.'), after.name);
				continue;
			}
			String was = describe(at);
			rewrite(at, respelled);
			// Counted over the merged body now: the ordinal rules after this one must not translate it again.
			CurrentBodyOrdinals.mark(handler);
			moved++;
			ForbricLog.info("[Forbric/Mixin] %s: %s's point %s lands natively on %s in %s.%s%s; the merged body keeps that instruction behind "
					+ "a carrier's code, so the point now names it as %s", mixin.name.replace('/', '.'), handler.name, was, token(nativeTarget),
					current.name.replace('/', '.'), after.name, after.desc, describe(at));
		}
		return moved;
	}

	/** A point that shifts {@code BY} a non-zero count, with nothing on it this does not model. */
	static boolean shiftsBy(AnnotationNode at) {
		Object shift = MixinFit.value(at, "shift");
		if (!(shift instanceof String[] e) || e.length != 2 || !"BY".equals(e[1])) return false;
		if (!(MixinFit.value(at, "by") instanceof Number by) || by.intValue() == 0) return false;
		if (MixinFit.value(at, "slice") != null || MixinFit.value(at, "args") != null) return false;
		String value = MixinFit.asString(MixinFit.value(at, "value"));
		return value != null && List.of("HEAD", "RETURN", "TAIL", "INVOKE", "FIELD", "NEW").contains(value);
	}

	/** Where Mixin lands {@code at} shifted by {@code by} in {@code method}: one selected node, moved by node count; else null. */
	static AbstractInsnNode shifted(MethodNode method, AnnotationNode at, int by) {
		List<AbstractInsnNode> selected = MixinCallbackProofs.points(method, at);
		if (selected == null || selected.size() != 1) return null;
		int index = method.instructions.indexOf(selected.getFirst()) + by;
		return index < 0 || index >= method.instructions.size() ? null : method.instructions.get(index);
	}

	/**
	 * The instruction of {@code current} that is {@code nativeTarget} of {@code original}: the shortest run of real
	 * instructions ending at it that occurs once in the native body, grown until it also occurs once in the merged one,
	 * and the same for the run starting at it. Both found: they must agree. Neither: null.
	 */
	static AbstractInsnNode counterpart(MethodNode original, AbstractInsnNode nativeTarget, MethodNode current) {
		List<AbstractInsnNode> was = real(original), now = real(current);
		List<String> wasTokens = tokens(was), nowTokens = tokens(now);
		int at = was.indexOf(nativeTarget);
		if (at < 0) return null;
		AbstractInsnNode backward = null, forward = null;
		for (int k = 0; k < CONTEXT && at - k >= 0; k++) {
			List<String> run = wasTokens.subList(at - k, at + 1);
			if (occurrences(wasTokens, run).size() != 1) continue;
			List<Integer> found = occurrences(nowTokens, run);
			if (found.isEmpty()) break;
			if (found.size() == 1) { backward = now.get(found.getFirst() + k); break; }
		}
		for (int k = 0; k < CONTEXT && at + k < wasTokens.size(); k++) {
			List<String> run = wasTokens.subList(at, at + k + 1);
			if (occurrences(wasTokens, run).size() != 1) continue;
			List<Integer> found = occurrences(nowTokens, run);
			if (found.isEmpty()) break;
			if (found.size() == 1) { forward = now.get(found.getFirst()); break; }
		}
		if (backward != null && forward != null && backward != forward) return null;
		return backward != null ? backward : forward;
	}

	record Respelled(String value, String target, Integer opcode, int ordinal, int by) { }

	/**
	 * {@code target} named in {@code method}'s own count: by itself when it is a call or a return, else by the nearest call or
	 * return after it, shifted back by the nodes between them (labels and line numbers included, as Mixin counts them).
	 */
	static Respelled respell(String owner, MethodNode method, AbstractInsnNode target) {
		for (AbstractInsnNode anchor = target; anchor != null; anchor = anchor.getNext()) {
			int by = method.instructions.indexOf(target) - method.instructions.indexOf(anchor);
			if (anchor instanceof MethodInsnNode call) {
				int ordinal = 0;
				for (AbstractInsnNode i = method.instructions.getFirst(); i != anchor; i = i.getNext()) {
					if (i instanceof MethodInsnNode c && c.owner.equals(call.owner) && c.name.equals(call.name) && c.desc.equals(call.desc)) ordinal++;
				}
				return new Respelled("INVOKE", "L" + call.owner + ";" + call.name + call.desc, null, ordinal, by);
			}
			int opcode = anchor.getOpcode();
			if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
				int ordinal = 0;
				for (AbstractInsnNode i = method.instructions.getFirst(); i != anchor; i = i.getNext()) {
					if (i.getOpcode() >= Opcodes.IRETURN && i.getOpcode() <= Opcodes.RETURN) ordinal++;
				}
				return new Respelled("RETURN", null, null, ordinal, by);
			}
			if (opcode == Opcodes.ATHROW || opcode == Opcodes.GOTO || anchor instanceof TableSwitchInsnNode
					|| anchor instanceof LookupSwitchInsnNode) return null;   // control leaves: what follows is not "after" it
		}
		return null;
	}

	private static void rewrite(AnnotationNode at, Respelled respelled) {
		List<Object> values = new ArrayList<>(List.of("value", respelled.value()));
		if (respelled.target() != null) { values.add("target"); values.add(respelled.target()); }
		values.add("ordinal");
		values.add(respelled.ordinal());
		if (respelled.by() != 0) {
			values.add("shift");
			values.add(new String[] {SHIFT, "BY"});
			values.add("by");
			values.add(respelled.by());
		}
		for (String kept : List.of("id", "remap", "unsafe")) {
			Object value = MixinFit.value(at, kept);
			if (value != null) { values.add(kept); values.add(value); }
		}
		at.values = values;
	}

	private static String describe(AnnotationNode at) {
		StringBuilder out = new StringBuilder("@At(").append(MixinFit.asString(MixinFit.value(at, "value")));
		Object target = MixinFit.value(at, "target"), ordinal = MixinFit.value(at, "ordinal"), by = MixinFit.value(at, "by");
		if (target != null) out.append(", target=").append(MixinFit.asString(target) != null ? MixinFit.asString(target) : target);
		if (ordinal != null) out.append(", ordinal=").append(ordinal);
		if (by != null) out.append(", by=").append(by);
		return out.append(')').toString();
	}

	private static List<AbstractInsnNode> real(MethodNode method) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) out.add(instruction);
		return out;
	}

	private static List<String> tokens(List<AbstractInsnNode> instructions) {
		List<String> out = new ArrayList<>(instructions.size());
		for (AbstractInsnNode instruction : instructions) out.add(token(instruction));
		return out;
	}

	private static List<Integer> occurrences(List<String> tokens, List<String> run) {
		List<Integer> found = new ArrayList<>();
		for (int start = 0; start + run.size() <= tokens.size(); start++) {
			if (tokens.subList(start, start + run.size()).equals(run)) found.add(start);
		}
		return found;
	}

	/** An instruction by what it does: its opcode and operands; a jump or switch by its opcode and keys, never its labels. */
	static String token(AbstractInsnNode instruction) {
		StringBuilder token = new StringBuilder(String.valueOf(instruction.getOpcode()));
		if (instruction instanceof VarInsnNode variable) token.append(':').append(variable.var);
		else if (instruction instanceof IincInsnNode increment) token.append(':').append(increment.var).append(':').append(increment.incr);
		else if (instruction instanceof FieldInsnNode field) token.append(':').append(field.owner).append('.').append(field.name).append(field.desc);
		else if (instruction instanceof MethodInsnNode call) token.append(':').append(call.owner).append('.').append(call.name).append(call.desc);
		else if (instruction instanceof InvokeDynamicInsnNode dynamic) token.append(':').append(dynamic.name).append(dynamic.desc).append(':').append(dynamic.bsm);
		else if (instruction instanceof TypeInsnNode type) token.append(':').append(type.desc);
		else if (instruction instanceof LdcInsnNode literal) token.append(':').append(literal.cst.getClass().getSimpleName()).append(':').append(literal.cst);
		else if (instruction instanceof IntInsnNode integer) token.append(':').append(integer.operand);
		else if (instruction instanceof MultiANewArrayInsnNode array) token.append(':').append(array.desc).append(':').append(array.dims);
		else if (instruction instanceof LookupSwitchInsnNode lookup) token.append(':').append(lookup.keys);
		else if (instruction instanceof TableSwitchInsnNode table) token.append(':').append(table.min).append('-').append(table.max);
		return token.toString();
	}
}
