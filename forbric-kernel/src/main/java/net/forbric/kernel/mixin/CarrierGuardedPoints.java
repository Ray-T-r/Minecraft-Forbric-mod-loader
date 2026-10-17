/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
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
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
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
 * Moves a mod's {@code @Inject} that the surviving carrier put behind a branch of its own back to where it runs natively:
 * right after the native code it follows, ahead of the carrier's branch, so it runs on every way it runs natively.
 *
 * <p>Vanilla's {@code LivingEntity.updateFallFlying} picks the glider slot to damage with {@code Util.getRandom(slots,
 * random)} every tenth tick of flight; NeoForge skips that choice when no slot holds a glider ({@code if (slots.isEmpty())}).
 * A mod's injector before {@code getRandom} that runs custom flight (a glider-less elytra, which has no slot) never runs:
 * the carrier's guard goes around it. Vanilla's {@code canGlide()} reads the equipment ({@code EquipmentSlot.VALUES}) right
 * after its movement checks; NeoForge's {@code canGlide()} forwards to {@code canGlide(true)}, which reads a gliding
 * attribute there instead and returns, reaching the equipment only for {@code canGlide(false)}: a mod's injector at
 * {@code VALUES} (an elytra veto) binds and never runs.
 *
 * <p>Nothing here knows a mod, a class or a method by name. An injector is moved when:
 * <ul>
 *   <li>it is an {@code @Inject} before one point (no {@code by}, no captured locals, no slice, no {@code @Group}), which
 *       selects one instruction in the method as the mod's own loader has it (the native reference of its ecosystem) and
 *       one in the merged method — or, where the merged method only forwards to an overload of its own (handing over its
 *       own arguments and constants), in that overload, for a handler that captures no argument;</li>
 *   <li>the branches that decide whether the merged point's statement is reached ({@link NativeCallInlining.Conditions})
 *       are those of the native one and more: the carrier's guard;</li>
 *   <li>right before the merged statement runs carrier code alone ({@link CodeAlignment}: no instruction of it is a step
 *       of the native method), and its earliest instruction reached under exactly the native conditions, on every way to
 *       the statement, is where the injector goes: before that instruction's first call, field access, allocation or
 *       branch, which only plain reads may precede;</li>
 *   <li>what goes around the statement is not a veto of the operation: a branch there tests an argument the method the mod
 *       names hands the overload as a constant (the carrier's own entry), or — for a handler handed nothing but its
 *       callback — every branch there tests only a value the point is handed, or a method of it taking nothing else (the
 *       native call's precondition); and the injector is cancellable, so it decides whether the method goes on (one that
 *       only observes the point must not run where the carrier goes around it).</li>
 * </ul>
 * {@code -Dforbric.carrierGuardedPoints=off} leaves every injector as compiled.
 */
public final class CarrierGuardedPoints {
	public static final String PROPERTY = "forbric.carrierGuardedPoints";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String GROUP = "Lorg/spongepowered/asm/mixin/injection/Group;";

	private static final boolean DEBUG = Boolean.getBoolean("forbric.carrierGuardedPoints.debug");

	private static void debug(String format, Object... args) {
		if (DEBUG) ForbricLog.info("[Forbric/Mixin] carrier guarded points: " + format, args);
	}

	private CarrierGuardedPoints() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Where an injector goes: the method, the instruction its point selects there, and that instruction's occurrence. */
	record Move(MethodNode host, AbstractInsnNode point, int occurrence) {
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
			for (MethodNode handler : mixin.methods) {
				AnnotationNode injector = MixinFit.injectorOf(handler);
				if (injector == null || !INJECT.equals(injector.desc) || annotated(handler, GROUP)) continue;
				// Only an injector that decides whether the method goes on: one that only observes the point must not run where
				// the carrier goes around it (an effect a carrier's event kept is not "about to be removed").
				if (!Boolean.TRUE.equals(MixinFit.value(injector, "cancellable"))) continue;
				if (MixinFit.value(injector, "slice") != null || MixinFit.value(injector, "locals") != null) continue;
				List<AnnotationNode> points = MixinFit.atNodes(injector);
				if (points.size() != 1) continue;
				AnnotationNode at = points.getFirst();
				Object shift = MixinFit.value(at, "shift");
				if (MixinFit.value(at, "by") != null || shift != null && !"BEFORE".equals(MixinFit.asString(shift))) continue;
				String kind = MixinFit.asString(MixinFit.value(at, "value"));
				if (!"INVOKE".equals(kind) && !"FIELD".equals(kind) && !"NEW".equals(kind)) continue;
				if (merged == null) merged = code.apply(target);
				if (merged == null) break;
				MethodNode was = MixinTargetSelectors.one(handler, original), selected = MixinTargetSelectors.one(handler, merged);
				debug("%s: native %s, merged %s", handler.name, was == null ? null : was.name + was.desc, selected == null ? null : selected.name + selected.desc);
				if (was == null || selected == null || !was.name.equals(selected.name) || !was.desc.equals(selected.desc)) continue;
				MethodNode host = selected, body = overloadBody(merged, selected);
				debug("%s: forwards to %s", handler.name, body == null ? null : body.name + body.desc);
				if (body != null) {
					if (Type.getArgumentTypes(handler.desc).length != 1) continue;
					host = body;
				}
				// A handler handed nothing but its callback cannot see a value the carrier's guard keeps from the native call.
				Move move = derive(original, was, merged, host, at, host != selected ? selected : null, Type.getArgumentTypes(handler.desc).length == 1);
				if (move == null) continue;
				String before = String.valueOf(MixinFit.value(at, "target"));
				retarget(at, move);
				if (host != selected) set(injector, "method", new ArrayList<>(List.of(host.name + host.desc)));
				CurrentBodyOrdinals.mark(handler);
				moved++;
				ForbricLog.info("[Forbric/Mixin] %s.%s: the carrier put %s in %s.%s behind a branch of its own; the injector moves ahead of it, "
						+ "to %s, so it runs on every way it runs natively", mixin.name.replace('/', '.'), handler.name, before, target.replace('/', '.'),
						host.name + (host != selected ? " (" + selected.name + selected.desc + " forwards there)" : ""), describe(move.point()));
			}
		}
		return moved;
	}

	/** Where the injector at {@code at} runs natively, in {@code host}; null when that is not proved. */
	static Move derive(ClassNode original, MethodNode was, ClassNode merged, MethodNode host, AnnotationNode at, MethodNode stub, boolean handsNothing) {
		AbstractInsnNode nativePoint = only(at, was), mergedPoint = only(at, host);
		debug("points %s / %s", nativePoint, mergedPoint);
		if (nativePoint == null || mergedPoint == null) return null;
		AbstractInsnNode nativeStart = statementStart(original.name, was, nativePoint), mergedStart = statementStart(merged.name, host, mergedPoint);
		debug("starts %s / %s", nativeStart, mergedStart);
		if (nativeStart == null || mergedStart == null) return null;
		NativeCallInlining.Conditions nativeConditions = NativeCallInlining.Conditions.of(original.name, was, Integer.MAX_VALUE),
				mergedConditions = NativeCallInlining.Conditions.of(merged.name, host, Integer.MAX_VALUE);
		if (nativeConditions == null || mergedConditions == null) return null;
		Set<String> wanted = nativeConditions.of(nativeStart), found = mergedConditions.of(mergedStart);
		debug("conditions %s / %s", wanted, found);
		if (!found.containsAll(wanted)) return null;
		Map<AbstractInsnNode, AbstractInsnNode> aligned = CodeAlignment.align(original.name, was, merged.name, host);
		if (aligned == null) return null;
		Set<AbstractInsnNode> steps = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		steps.addAll(aligned.values());
		// The carrier code right before the merged statement, back to the last step the native method has too.
		AbstractInsnNode last = previousReal(mergedStart);
		while (last != null && !(steps.contains(last) && effect(last))) last = previousReal(last);
		AbstractInsnNode from = last == null ? firstReal(host.instructions.getFirst()) : nextReal(last);
		debug("last step %s", last == null ? null : host.instructions.indexOf(last));
		while (from != null && from != mergedStart && !mergedConditions.of(from).equals(wanted)) from = nextReal(from);
		debug("from %s", from == null ? null : host.instructions.indexOf(from));
		if (from == null || from == mergedStart) return null;
		// Nothing the injector would now run ahead of is a step of the native method: no call, field access, allocation or
		// store there is one (a constant or a return alone may line up with one by chance).
		for (AbstractInsnNode at0 = from; at0 != mergedStart; at0 = nextReal(at0))
			if (at0 == null || steps.contains(at0) && effect(at0)) return null;
		if (!dominates(host, from, mergedStart)) return null;
		// The carrier's code there can go around the statement: a way from it leaves the method without reaching it, where
		// natively, reached under the same conditions, the point is never gone around.
		if (!avoidable(host, from, mergedStart)) return null;
		// …and what goes around it is not a veto of the operation (a carrier's event deciding the native step does not
		// happen): either a branch there tests an argument the method the mod names hands the overload as a constant —
		// the carrier's own entry for its own answer, which callers of the native signature always take — or every branch
		// there only tests a value the point itself is handed (the native call's precondition: a slot list to pick from,
		// empty), for a handler handed nothing but its callback — it cannot see the value the guard keeps from the call.
		if (!constantEntry(stub, host, from, mergedStart) && !(handsNothing && testsOnlyItsOperands(merged, host, from, mergedStart, mergedPoint))) {
			debug("the way around is neither the carrier's own entry nor a test of the point's own values");
			return null;
		}
		AbstractInsnNode point = from;
		while (point != mergedStart && pureRead(point)) point = nextReal(point);
		if (point == mergedStart) return null;
		if (point instanceof MethodInsnNode call && !call.name.startsWith("<")) return new Move(host, point, occurrence(host, point));
		if (point instanceof FieldInsnNode) return new Move(host, point, occurrence(host, point));
		if (point instanceof TypeInsnNode made && made.getOpcode() == Opcodes.NEW) return new Move(host, point, occurrence(host, point));
		// The carrier's own branch opens its code: the injector goes right before that test.
		if (point instanceof JumpInsnNode jump && jump.getOpcode() != Opcodes.GOTO && jump.getOpcode() != Opcodes.JSR) return new Move(host, point, occurrence(host, point));
		return null;
	}

	/**
	 * The overload of its own class that {@code method} only forwards to — its receiver, each of its own arguments in order,
	 * and constants — returning what it returns; null when it does anything else.
	 */
	static MethodNode overloadBody(ClassNode owner, MethodNode method) {
		List<AbstractInsnNode> code = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) code.add(instruction);
		if (code.size() < 2 || !(code.get(code.size() - 2) instanceof MethodInsnNode call) || !call.owner.equals(owner.name)
				|| !call.name.equals(method.name) || call.desc.equals(method.desc)) return null;
		if (code.getLast().getOpcode() != Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN) || !Type.getReturnType(call.desc).equals(Type.getReturnType(method.desc)))
			return null;
		boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
		int slot = isStatic ? 0 : 1, parameter = 0, i = 0;
		Type[] parameters = Type.getArgumentTypes(method.desc);
		if (!isStatic && !(code.get(i++) instanceof VarInsnNode self && self.var == 0 && self.getOpcode() == Opcodes.ALOAD)) return null;
		for (; i < code.size() - 2; i++) {
			AbstractInsnNode instruction = code.get(i);
			if (instruction instanceof VarInsnNode load && parameter < parameters.length && load.var == slot
					&& load.getOpcode() == parameters[parameter].getOpcode(Opcodes.ILOAD)) {
				slot += parameters[parameter++].getSize();
				continue;
			}
			int opcode = instruction.getOpcode();
			if (!(opcode >= Opcodes.ACONST_NULL && opcode <= Opcodes.SIPUSH) && !(instruction instanceof LdcInsnNode)) return null;
		}
		if (parameter != parameters.length) return null;
		MethodNode body = NativeCallChanges.method(owner, call.name + call.desc);
		return body == null || body.instructions.size() == 0 || ((body.access ^ method.access) & Opcodes.ACC_STATIC) != 0 ? null : body;
	}

	/** The one instruction {@code at} selects in {@code method} (by its ordinal, when it has one); null otherwise. */
	private static AbstractInsnNode only(AnnotationNode at, MethodNode method) {
		String kind = MixinFit.asString(MixinFit.value(at, "value"));
		List<AbstractInsnNode> selected;
		if ("NEW".equals(kind)) {
			String target = MixinFit.asString(MixinFit.value(at, "target"));
			if (target == null) return null;
			String type = target.startsWith("L") && target.contains(";") ? target.substring(1, target.indexOf(';')) : target;
			selected = new ArrayList<>();
			for (AbstractInsnNode instruction : method.instructions)
				if (instruction instanceof TypeInsnNode made && made.getOpcode() == Opcodes.NEW && made.desc.equals(type)) selected.add(made);
		} else selected = MixinCallbackShape.pointSelected(at, method);
		int ordinal = MixinFit.value(at, "ordinal") instanceof Number number ? number.intValue() : -1;
		if (ordinal >= 0) return ordinal < selected.size() ? selected.get(ordinal) : null;
		return selected.size() == 1 ? selected.getFirst() : null;
	}

	/** The first instruction computing what {@code point} consumes, in one straight stretch; {@code point} when it consumes nothing. */
	static AbstractInsnNode statementStart(String owner, MethodNode method, AbstractInsnNode point) {
		int consumed = point instanceof MethodInsnNode call ? CarrierDecisions.operandTypes(call).size()
				: point instanceof FieldInsnNode field ? (field.getOpcode() == Opcodes.GETFIELD ? 1 : field.getOpcode() == Opcodes.PUTFIELD ? 2
						: field.getOpcode() == Opcodes.PUTSTATIC ? 1 : 0) : 0;
		if (consumed == 0) return point;
		Frame<SourceValue>[] frames;
		try {
			frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, method);
		} catch (AnalyzerException | RuntimeException unanalysable) {
			Caught.expected(unanalysable);
			return null;
		}
		Frame<SourceValue> at = frames[method.instructions.indexOf(point)];
		if (at == null) return null;
		int floor = at.getStackSize() - consumed;
		AbstractInsnNode start = point;
		for (AbstractInsnNode back = point.getPrevious(); back != null; back = back.getPrevious()) {
			if (back.getOpcode() < 0) {
				if (back instanceof LabelNode label && targeted(method, label)) return null;
				continue;
			}
			Frame<SourceValue> frame = frames[method.instructions.indexOf(back)];
			if (frame == null || back instanceof JumpInsnNode || frame.getStackSize() < floor) return null;
			start = back;
			if (frame.getStackSize() == floor) return start;
		}
		return null;
	}

	private static boolean targeted(MethodNode method, LabelNode label) {
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof JumpInsnNode jump && jump.label == label) return true;
			if (instruction instanceof TableSwitchInsnNode table && (table.dflt == label || table.labels.contains(label))) return true;
			if (instruction instanceof LookupSwitchInsnNode lookup && (lookup.dflt == label || lookup.labels.contains(label))) return true;
		}
		if (method.tryCatchBlocks != null) for (TryCatchBlockNode block : method.tryCatchBlocks) if (block.handler == label) return true;
		return false;
	}

	/** Whether every way from the method's entry to {@code to} passes {@code from} (exception edges included). */
	static boolean dominates(MethodNode method, AbstractInsnNode from, AbstractInsnNode to) {
		List<AbstractInsnNode> real = new ArrayList<>();
		Map<AbstractInsnNode, Integer> index = new IdentityHashMap<>();
		for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) { index.put(instruction, real.size()); real.add(instruction); }
		Integer avoid = index.get(from), goal = index.get(to);
		if (avoid == null || goal == null || real.isEmpty()) return false;
		List<List<Integer>> successors = new ArrayList<>();
		for (AbstractInsnNode instruction : real) {
			List<Integer> out = new ArrayList<>();
			int opcode = instruction.getOpcode();
			if (instruction instanceof JumpInsnNode jump) {
				out.add(index.getOrDefault(firstReal(jump.label), -1));
				if (opcode != Opcodes.GOTO) out.add(index.getOrDefault(nextReal(instruction), -1));
			} else if (instruction instanceof TableSwitchInsnNode table) {
				out.add(index.getOrDefault(firstReal(table.dflt), -1));
				for (LabelNode label : table.labels) out.add(index.getOrDefault(firstReal(label), -1));
			} else if (instruction instanceof LookupSwitchInsnNode lookup) {
				out.add(index.getOrDefault(firstReal(lookup.dflt), -1));
				for (LabelNode label : lookup.labels) out.add(index.getOrDefault(firstReal(label), -1));
			} else if (!(opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW)) {
				out.add(index.getOrDefault(nextReal(instruction), -1));
			}
			out.removeIf(i -> i < 0);
			successors.add(out);
		}
		if (method.tryCatchBlocks != null) for (TryCatchBlockNode block : method.tryCatchBlocks) {
			int handler = index.getOrDefault(firstReal(block.handler), -1), start = index.getOrDefault(firstReal(block.start), -1),
					end = block.end == null ? real.size() : index.getOrDefault(firstReal(block.end), real.size());
			if (handler < 0 || start < 0) continue;
			for (int i = start; i < end; i++) if (!successors.get(i).contains(handler)) successors.get(i).add(handler);
		}
		BitSet seen = new BitSet();
		ArrayDeque<Integer> work = new ArrayDeque<>(List.of(0));
		while (!work.isEmpty()) {
			int at = work.poll();
			if (at == avoid) continue;
			if (at == goal) return false;
			if (seen.get(at)) continue;
			seen.set(at);
			work.addAll(successors.get(at));
		}
		return true;
	}

	/**
	 * Whether a conditional branch between {@code from} and {@code to} tests only an argument of {@code host} that
	 * {@code stub} (the method the mod names, which only forwards to {@code host}) hands it as a constant.
	 */
	private static boolean constantEntry(MethodNode stub, MethodNode host, AbstractInsnNode from, AbstractInsnNode to) {
		if (stub == null) return false;
		// The arguments the stub hands the overload as constants, by the overload's parameter slot.
		Set<Integer> constants = new java.util.HashSet<>();
		List<AbstractInsnNode> code = new ArrayList<>();
		for (AbstractInsnNode instruction : stub.instructions) if (instruction.getOpcode() >= 0) code.add(instruction);
		int slot = (host.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1, position = (stub.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
		Type[] parameters = Type.getArgumentTypes(host.desc);
		for (Type parameter : parameters) {
			if (position >= code.size() - 2) return false;
			AbstractInsnNode handed = code.get(position++);
			if (handed.getOpcode() >= Opcodes.ACONST_NULL && handed.getOpcode() <= Opcodes.SIPUSH || handed instanceof LdcInsnNode) constants.add(slot);
			slot += parameter.getSize();
		}
		if (constants.isEmpty()) return false;
		for (AbstractInsnNode at = from; at != null && at != to; at = nextReal(at)) {
			if (!(at instanceof JumpInsnNode jump) || jump.getOpcode() == Opcodes.GOTO) continue;
			AbstractInsnNode tested = previousReal(jump);
			if (jump.getOpcode() >= Opcodes.IFEQ && jump.getOpcode() <= Opcodes.IFLE && tested instanceof VarInsnNode load
					&& load.getOpcode() == Opcodes.ILOAD && constants.contains(load.var)) return true;
		}
		return false;
	}

	/**
	 * Whether every conditional branch between {@code from} and {@code to} tests only a value {@code point} is handed (that
	 * value itself, or a no-argument method of it), read as the expressions that compute them.
	 */
	private static boolean testsOnlyItsOperands(ClassNode owner, MethodNode method, AbstractInsnNode from, AbstractInsnNode to, AbstractInsnNode point) {
		if (!(point instanceof MethodInsnNode call)) return false;
		List<NativeCallChanges.Expr> operands = null;
		for (NativeCallChanges.Site site : NativeCallChanges.sites(owner, method)) if (site.call() == call) operands = site.operands();
		if (operands == null) return false;
		int branches = 0;
		for (AbstractInsnNode at = from; at != null && at != to; at = nextReal(at)) {
			if (!(at instanceof JumpInsnNode jump) || jump.getOpcode() == Opcodes.GOTO) {
				if (at instanceof TableSwitchInsnNode || at instanceof LookupSwitchInsnNode) return false;
				continue;
			}
			branches++;
			List<NativeCallChanges.Expr> tested = NativeCallChanges.argumentValuesOfBranch(owner.name, method, jump);
			if (tested == null) return false;
			for (NativeCallChanges.Expr value : tested) {
				if (value.kind().equals("constant") || value.kind().equals("integer") || value.kind().equals("literal")) continue;
				// The value itself, or a method of it taking nothing else (a list asked whether it is empty) — never another
				// method handed the value (a carrier's hook asked about the receiver is a veto, not a precondition).
				MixinFit.Member asked = value.kind().equals("call") ? MixinFit.parseMember(value.symbol()) : null;
				boolean ofOperand = operands.contains(value) || asked != null && asked.desc() != null && asked.desc().startsWith("()")
						&& value.inputs().size() == 1 && operands.contains(value.inputs().getFirst());
				if (!ofOperand) return false;
			}
		}
		return branches > 0;
	}

	/** The instructions reachable from {@code from} without passing {@code avoid}. */
	private static List<AbstractInsnNode> reachable(MethodNode method, AbstractInsnNode from, AbstractInsnNode avoid) {
		java.util.Set<AbstractInsnNode> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		List<AbstractInsnNode> out = new ArrayList<>();
		ArrayDeque<AbstractInsnNode> work = new ArrayDeque<>(List.of(from));
		while (!work.isEmpty()) {
			AbstractInsnNode at = work.poll();
			if (at == null || at == avoid || !seen.add(at)) continue;
			out.add(at);
			int opcode = at.getOpcode();
			if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW) continue;
			if (at instanceof JumpInsnNode jump) {
				work.add(firstReal(jump.label));
				if (opcode != Opcodes.GOTO) work.add(nextReal(at));
			} else if (at instanceof TableSwitchInsnNode table) {
				work.add(firstReal(table.dflt));
				for (LabelNode label : table.labels) work.add(firstReal(label));
			} else if (at instanceof LookupSwitchInsnNode lookup) {
				work.add(firstReal(lookup.dflt));
				for (LabelNode label : lookup.labels) work.add(firstReal(label));
			} else work.add(nextReal(at));
		}
		return out;
	}

	/** Whether a way from {@code from} leaves the method (a return or a throw) without passing {@code avoid}. */
	static boolean avoidable(MethodNode method, AbstractInsnNode from, AbstractInsnNode avoid) {
		java.util.Set<AbstractInsnNode> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		ArrayDeque<AbstractInsnNode> work = new ArrayDeque<>(List.of(from));
		while (!work.isEmpty()) {
			AbstractInsnNode at = work.poll();
			if (at == null || at == avoid || !seen.add(at)) continue;
			int opcode = at.getOpcode();
			if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW) return true;
			if (at instanceof JumpInsnNode jump) {
				work.add(firstReal(jump.label));
				if (opcode != Opcodes.GOTO) work.add(nextReal(at));
			} else if (at instanceof TableSwitchInsnNode table) {
				work.add(firstReal(table.dflt));
				for (LabelNode label : table.labels) work.add(firstReal(label));
			} else if (at instanceof LookupSwitchInsnNode lookup) {
				work.add(firstReal(lookup.dflt));
				for (LabelNode label : lookup.labels) work.add(firstReal(label));
			} else work.add(nextReal(at));
		}
		return false;
	}

	private static boolean effect(AbstractInsnNode instruction) {
		int opcode = instruction.getOpcode();
		return instruction instanceof MethodInsnNode || instruction instanceof FieldInsnNode || instruction instanceof TypeInsnNode
				|| instruction instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode || instruction instanceof VarInsnNode && opcode >= Opcodes.ISTORE
				|| instruction instanceof org.objectweb.asm.tree.IincInsnNode || opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE;
	}

	private static boolean pureRead(AbstractInsnNode instruction) {
		int opcode = instruction.getOpcode();
		return instruction instanceof VarInsnNode && opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD || opcode >= Opcodes.ACONST_NULL && opcode <= Opcodes.SIPUSH
				|| instruction instanceof LdcInsnNode || opcode == Opcodes.DUP;
	}

	private static void retarget(AnnotationNode at, Move move) {
		AbstractInsnNode point = move.point();
		if (point instanceof MethodInsnNode call) {
			set(at, "value", "INVOKE");
			set(at, "target", "L" + call.owner + ";" + call.name + call.desc);
			remove(at, "opcode");
		} else if (point instanceof FieldInsnNode field) {
			set(at, "value", "FIELD");
			set(at, "target", "L" + field.owner + ";" + field.name + ":" + field.desc);
			set(at, "opcode", field.getOpcode());
		} else if (point instanceof TypeInsnNode made) {
			set(at, "value", "NEW");
			set(at, "target", made.desc);
			remove(at, "opcode");
		} else if (point instanceof JumpInsnNode jump) {
			set(at, "value", "JUMP");
			remove(at, "target");
			set(at, "opcode", jump.getOpcode());
		}
		set(at, "ordinal", move.occurrence());
	}

	/** Which occurrence of its member (or, for an allocation, its class) {@code point} is in {@code method}. */
	private static int occurrence(MethodNode method, AbstractInsnNode point) {
		int n = 0;
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction == point) return n;
			if (same(instruction, point)) n++;
		}
		return n;
	}

	private static boolean same(AbstractInsnNode a, AbstractInsnNode b) {
		if (a instanceof MethodInsnNode x && b instanceof MethodInsnNode y) return x.owner.equals(y.owner) && x.name.equals(y.name) && x.desc.equals(y.desc);
		if (a instanceof FieldInsnNode x && b instanceof FieldInsnNode y)
			return x.owner.equals(y.owner) && x.name.equals(y.name) && x.desc.equals(y.desc) && x.getOpcode() == y.getOpcode();
		if (a instanceof TypeInsnNode x && b instanceof TypeInsnNode y) return x.getOpcode() == Opcodes.NEW && y.getOpcode() == Opcodes.NEW && x.desc.equals(y.desc);
		if (a instanceof JumpInsnNode && b instanceof JumpInsnNode) return a.getOpcode() == b.getOpcode();
		return false;
	}

	private static String describe(AbstractInsnNode point) {
		if (point instanceof MethodInsnNode call) return call.owner.replace('/', '.') + "." + call.name;
		if (point instanceof FieldInsnNode field) return field.owner.replace('/', '.') + "." + field.name;
		if (point instanceof TypeInsnNode made) return "new " + made.desc.replace('/', '.');
		if (point instanceof JumpInsnNode) return "its first branch (opcode " + point.getOpcode() + ")";
		return "?";
	}

	private static AbstractInsnNode firstReal(AbstractInsnNode at) {
		for (; at != null; at = at.getNext()) if (at.getOpcode() >= 0) return at;
		return null;
	}

	private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
		return instruction == null ? null : firstReal(instruction.getNext());
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
		for (AbstractInsnNode at = instruction.getPrevious(); at != null; at = at.getPrevious()) if (at.getOpcode() >= 0) return at;
		return null;
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

	private static void remove(AnnotationNode annotation, String key) {
		if (annotation.values == null) return;
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) if (key.equals(annotation.values.get(i))) {
			annotation.values.remove(i + 1);
			annotation.values.remove(i);
			return;
		}
	}
}
