/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Injectors that follow setBlock's notification tail into markAndNotifyBlock.
 *
 * <p>NeoForge cut everything {@code Level.setBlock(BlockPos, BlockState, int, int)} does after the chunk took the new
 * state — the client update and its chunk-status check, the neighbour updates, the shape updates under the flags, the POI
 * update — out into {@code markAndNotifyBlock}, which setBlock calls once (and captured block snapshots call when they
 * are committed). A mod compiled against vanilla anchors in setBlock on a call or a constant that is no longer there.
 * An injector that describes only that one operation — the call it modifies, wraps, redirects or conditions, or the
 * constant it replaces — and nothing of setBlock itself (its arguments, locals, return or cancellation) can follow the
 * operation into the helper unchanged: its selector names markAndNotifyBlock instead. C2ME lowers the chunk-status
 * threshold on {@code isOrAfter} that way; Carpet suppresses the shape updates (the 16) and the neighbour update.
 *
 * <p>Proved for each such injector, never by its mod or spelling: Mixin binds its selectors to that setBlock alone; it is a
 * {@code @ModifyArg}, {@code @ModifyArgs}, {@code @ModifyExpressionValue}, {@code @WrapOperation}, {@code @Redirect},
 * {@code @WrapWithCondition} or {@code @ModifyConstant} with no slice, group, sugar or captured argument, one point and no
 * other occurrence than the first, and a handler that takes exactly what its kind takes from that operation; the operation
 * is gone from setBlock and made exactly once in the helper setBlock calls once (and once in the native setBlock, when the
 * class the mod was compiled against is at hand). Where vanilla gives the operation its meaning through the flags argument,
 * that meaning must hold in the helper too, on the parameter setBlock hands its flags to: the 16 is the
 * {@code UPDATE_KNOWN_SHAPE} bit whose test skips the shape updates, and the neighbour update runs only under
 * {@code UPDATE_NEIGHBORS}; with the native class, every flags test around the operation must be the same in both.
 *
 * <p>Atomic per mixin: every injector of the mixin anchored in the moved tail must follow it, or none does — a mixin's
 * hooks on one method are one behaviour, and half of it would be another. Two injectors on one operation (a second
 * redirect of the same call is Mixin's own conflict) move neither. {@code -Dforbric.chunkStatusRetarget=off} or
 * {@code -Dforbric.playerWorldCallbacks=off} keeps every selector as compiled.
 */
final class MixinChunkStatusRetarget {

	static final String PROPERTY = "forbric.chunkStatusRetarget";
	static final String LEVEL = "net/minecraft/world/level/Level";
	static final String STATUS = "Lnet/minecraft/server/level/FullChunkStatus;";
	static final String POSITION = "Lnet/minecraft/core/BlockPos;";
	static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
	static final String ORIGINAL = "setBlock(" + POSITION + STATE + "II)Z";
	static final String HELPER = "markAndNotifyBlock(" + POSITION
			+ "Lnet/minecraft/world/level/chunk/LevelChunk;" + STATE + STATE + "II)V";
	static final String ANCHOR = STATUS + "isOrAfter(" + STATUS + ")Z";
	static final String NEIGHBOURS = "L" + LEVEL + ";updateNeighborsAt(" + POSITION + "Lnet/minecraft/world/level/block/Block;)V";
	static final String SHAPES = STATE + "updateNeighbourShapes(Lnet/minecraft/world/level/LevelAccessor;" + POSITION + "II)V";
	/** Vanilla's flag bits that give a tail operation its meaning: UPDATE_NEIGHBORS and UPDATE_KNOWN_SHAPE. */
	static final int UPDATE_NEIGHBORS = 1, UPDATE_KNOWN_SHAPE = 16;
	private static final String ARGS = "Lorg/spongepowered/asm/mixin/injection/invoke/arg/Args;";
	private static final String OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";

	private MixinChunkStatusRetarget() { }

	/**
	 * The rule serves both the per-class adapter ({@link MixinPlayerWorldCallbackAdapter}, as Mixin loads a Level mixin) and
	 * MixinRetarget's plan for a mixin the census read as partial; either one's switch turns the whole rule off, so that
	 * neither path moves what the other was told to leave.
	 */
	static boolean enabled() {
		return !"off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY, "on")) && MixinPlayerWorldCallbackAdapter.enabled();
	}

	/** One operation of the tail an injector names: a call ({@code member}) or an int constant. */
	record Anchor(String member, Integer constant) { }

	/** MixinRetarget's plan for one handler: its one selector string moves, when its whole mixin's tail hooks can. */
	static MixinRetarget.Rewrite plan(ClassNode mixin, MethodNode handler, List<String> selectors, ClassNode target,
			ClassNode nativeLevel) {
		if (!LEVEL.equals(target.name) || !enabled() || selectors.size() != 1) return null;
		List<MethodNode> moving = movable(mixin, target, nativeLevel);
		if (moving == null || !moving.contains(handler)) return null;
		return new MixinRetarget.Rewrite(handler.name, MixinRetarget.Element.SELECTOR, selectors.getFirst(), HELPER,
				"the operation it describes moved with setBlock's notification tail into markAndNotifyBlock");
	}

	/**
	 * The handlers of {@code mixin} bound to setBlock whose operation NeoForge moved into markAndNotifyBlock, when every one
	 * of them can follow it; empty when none is anchored there; null when one is and cannot follow (then none moves).
	 */
	static List<MethodNode> movable(ClassNode mixin, ClassNode level, ClassNode nativeLevel) {
		if (!enabled() || mixin == null || mixin.methods == null || level == null || !LEVEL.equals(level.name)) return List.of();
		MethodNode original = method(level, ORIGINAL), helper = method(level, HELPER);
		if (original == null || helper == null || (helper.access & Opcodes.ACC_STATIC) != 0
				|| CarrierHelpers.occurrences(original, "L" + LEVEL + ";" + HELPER) != 1) return List.of();
		MethodNode nativeOriginal = nativeLevel == null ? null : method(nativeLevel, ORIGINAL);
		List<MethodNode> moving = new ArrayList<>();
		Set<Anchor> claimed = new HashSet<>();
		for (MethodNode handler : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(handler);
			if (injector == null) continue;
			List<MethodNode> bound = MixinTargetSelectors.bound(handler, level);
			if (bound == null || bound.size() != 1 || bound.getFirst() != original) continue;
			Anchor anchor = anchor(handler, injector);
			// Anchored on something setBlock still has, or on nothing its tail has: not this rule's.
			if (anchor == null || count(original, anchor) != 0 || count(helper, anchor) == 0) {
				if (anchor == null && inTail(injector, original, helper)) return null;
				continue;
			}
			if (!claimed.add(anchor) || count(helper, anchor) != 1
					|| nativeOriginal != null && count(nativeOriginal, anchor) != 1
					|| !describesOnly(handler, injector, anchor, helper)
					|| !meaningHolds(level.name, original, helper, anchor, nativeLevel == null ? null : nativeLevel.name, nativeOriginal)) return null;
			moving.add(handler);
		}
		return moving;
	}

	/**
	 * The one operation the injector names, when it names one the tail rule can judge: a single {@code INVOKE} point, or a
	 * single int constant (a {@code @ModifyConstant}'s, or a {@code CONSTANT} point's), at no occurrence past the first.
	 */
	static Anchor anchor(MethodNode handler, AnnotationNode injector) {
		String kind = injector.desc.substring(injector.desc.lastIndexOf('/') + 1, injector.desc.length() - 1);
		if (!MixinCallbackShape.kind(handler, kind)) return null;
		if (kind.equals("ModifyConstant")) {
			Object constants = MixinFit.value(injector, "constant");
			List<?> rows = constants instanceof List<?> list ? list : constants instanceof AnnotationNode node ? List.of(node) : List.of();
			if (rows.size() != 1 || !(rows.getFirst() instanceof AnnotationNode constant) || !(MixinFit.value(constant, "intValue") instanceof Integer value)) return null;
			for (int i = 0; i < constant.values.size(); i += 2) {
				Object key = constant.values.get(i);
				if (!"intValue".equals(key) && !("ordinal".equals(key) && firstOccurrence(constant.values.get(i + 1)))) return null;
			}
			return MixinFit.atNodes(injector).isEmpty() ? new Anchor(null, value) : null;
		}
		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.size() != 1) return null;
		AnnotationNode at = points.getFirst();
		if (!firstOccurrence(MixinFit.value(at, "ordinal")) || MixinFit.value(at, "shift") != null || MixinFit.value(at, "by") != null
				|| MixinFit.value(at, "opcode") != null || MixinFit.value(at, "slice") != null) return null;
		Object value = MixinFit.value(at, "value");
		if ("INVOKE".equals(value)) {
			String target = MixinFit.asString(MixinFit.value(at, "target"));
			MixinFit.Member member = target == null ? null : MixinFit.parseMember(target);
			if (member == null || member.owner() == null || member.desc() == null || MixinFit.value(at, "args") != null) return null;
			return new Anchor("L" + member.owner() + ";" + member.name() + member.desc(), null);
		}
		if ("CONSTANT".equals(value) && kind.equals("ModifyExpressionValue")) {
			List<String> args = MixinFit.stringList(MixinFit.value(at, "args"));
			if (args.size() != 1 || !args.getFirst().startsWith("intValue=")) return null;
			try { return new Anchor(null, Integer.valueOf(args.getFirst().substring("intValue=".length()).trim())); }
			catch (NumberFormatException malformed) { return null; }
		}
		return null;
	}

	/** An injector this rule cannot read whose point is a call the tail makes and setBlock no longer does: it cannot follow. */
	private static boolean inTail(AnnotationNode injector, MethodNode original, MethodNode helper) {
		for (AnnotationNode at : MixinFit.atNodes(injector)) {
			String target = MixinFit.asString(MixinFit.value(at, "target"));
			if (target != null && !MixinFit.containsMember(original, target) && MixinFit.containsMember(helper, target)) return true;
		}
		return false;
	}

	private static boolean firstOccurrence(Object ordinal) {
		return ordinal == null || Integer.valueOf(-1).equals(ordinal) || Integer.valueOf(0).equals(ordinal);
	}

	/** Whether the handler takes exactly what its kind takes from the operation, and nothing of setBlock. */
	static boolean describesOnly(MethodNode handler, AnnotationNode injector, Anchor anchor, MethodNode helper) {
		MixinHandlerShape shape = MixinHandlerShape.of(handler);
		if (shape == null || !shape.extras().isEmpty() || (handler.access & Opcodes.ACC_STATIC) != 0) return false;
		List<Type> operands = shape.operands();
		Type returns = shape.returns();
		String kind = shape.kind();
		if (anchor.constant() != null) {
			return (kind.equals("ModifyConstant") || kind.equals("ModifyExpressionValue"))
					&& operands.equals(List.of(Type.INT_TYPE)) && returns.equals(Type.INT_TYPE);
		}
		MethodInsnNode call = first(helper, anchor);
		if (call == null) return false;
		List<Type> arguments = List.of(Type.getArgumentTypes(call.desc));
		Type result = Type.getReturnType(call.desc);
		List<Type> receiverAndArguments = new ArrayList<>();
		if (call.getOpcode() != Opcodes.INVOKESTATIC) receiverAndArguments.add(Type.getObjectType(call.owner));
		receiverAndArguments.addAll(arguments);
		return switch (kind) {
			case "ModifyArg" -> modifiesOneArgument(injector, operands, returns, arguments);
			case "ModifyArgs" -> operands.equals(List.of(Type.getType(ARGS))) && returns.equals(Type.VOID_TYPE);
			case "ModifyExpressionValue" -> !result.equals(Type.VOID_TYPE) && operands.equals(List.of(result)) && returns.equals(result);
			case "Redirect" -> operands.equals(receiverAndArguments) && returns.equals(result);
			case "WrapWithCondition" -> result.equals(Type.VOID_TYPE) && operands.equals(receiverAndArguments) && returns.equals(Type.BOOLEAN_TYPE);
			case "WrapOperation" -> {
				List<Type> wanted = new ArrayList<>(receiverAndArguments);
				wanted.add(Type.getType(OPERATION));
				yield operands.equals(wanted) && returns.equals(result);
			}
			default -> false;
		};
	}

	/** A {@code @ModifyArg} handler: the one argument {@code index} names (or the only one of its type), alone or after all of them. */
	private static boolean modifiesOneArgument(AnnotationNode injector, List<Type> operands, Type returns, List<Type> arguments) {
		Object index = MixinFit.value(injector, "index");
		int position;
		if (index instanceof Integer explicit && explicit >= 0) position = explicit;
		else {
			if (operands.isEmpty()) return false;
			position = arguments.indexOf(operands.getFirst());
			if (position < 0 || arguments.lastIndexOf(operands.getFirst()) != position) return false;
		}
		if (position >= arguments.size() || !returns.equals(arguments.get(position))) return false;
		return operands.equals(List.of(arguments.get(position))) || index instanceof Integer && operands.equals(arguments);
	}

	/**
	 * Whether the operation means in the helper what it meant in setBlock. Where vanilla's flags give it its meaning — the
	 * 16 is the bit whose test skips the shape updates, the neighbour update runs only under bit 1 — that holds on the
	 * helper's parameter setBlock hands its flags to. With the native setBlock, every flags test around the operation (and
	 * the constant's own) is the same in both.
	 */
	private static boolean meaningHolds(String owner, MethodNode original, MethodNode helper, Anchor anchor, String nativeOwner,
			MethodNode nativeOriginal) {
		int flags = forwardedFlags(owner, original, helper);
		if (flags < 0) return false;
		AbstractInsnNode at = anchor.constant() != null ? constant(helper, anchor.constant()) : first(helper, anchor);
		if (anchor.constant() != null) {
			String test = flagTest(at, flags);
			if (test == null) return false;
			if (anchor.constant() == UPDATE_KNOWN_SHAPE && !skips(helper, at, Opcodes.IFNE, SHAPES)) return false;
		} else if (anchor.member().equals(NEIGHBOURS)) {
			AbstractInsnNode guard = at;
			while (guard != null && !(guard instanceof JumpInsnNode)) guard = MixinPlayerWorldCallbackAdapter.previous(guard);
			AbstractInsnNode bit = guard == null ? null : MixinPlayerWorldCallbackAdapter.previous(MixinPlayerWorldCallbackAdapter.previous(guard));
			if (bit == null || bit.getOpcode() != Opcodes.ICONST_1 || !(String.valueOf(UPDATE_NEIGHBORS) + "/" + Opcodes.IFEQ).equals(flagTest(bit, flags))
					|| !skips(helper, bit, Opcodes.IFEQ, NEIGHBOURS)) return false;
		}
		if (nativeOriginal == null) return true;
		int nativeFlags = firstIntParameter(nativeOriginal);
		AbstractInsnNode nativeAt = anchor.constant() != null ? constant(nativeOriginal, anchor.constant()) : first(nativeOriginal, anchor);
		if (nativeAt == null || nativeFlags < 0) return false;
		if (anchor.constant() != null && !java.util.Objects.equals(flagTest(at, flags), flagTest(nativeAt, nativeFlags))) return false;
		return flagTestsAround(helper, at, flags).equals(flagTestsAround(nativeOriginal, nativeAt, nativeFlags));
	}

	/** The helper's parameter slot setBlock passes its own flags parameter (its first int) to, unchanged; -1 when none. */
	static int forwardedFlags(String owner, MethodNode original, MethodNode helper) {
		int flags = firstIntParameter(original);
		MethodInsnNode call = null;
		for (AbstractInsnNode insn : original.instructions) {
			if (insn instanceof MethodInsnNode c && c.owner.equals(owner) && (c.name + c.desc).equals(helper.name + helper.desc)) call = c;
			if (insn instanceof VarInsnNode v && v.var == flags && v.getOpcode() == Opcodes.ISTORE) return -1;
			if (insn instanceof IincInsnNode n && n.var == flags) return -1;
		}
		if (flags < 0 || call == null) return -1;
		Frame<SourceValue> frame;
		try { frame = new Analyzer<>(new SourceInterpreter()).analyze(owner, original)[original.instructions.indexOf(call)]; }
		catch (AnalyzerException | RuntimeException unanalysable) { return -1; }
		if (frame == null) return -1;
		Type[] arguments = Type.getArgumentTypes(call.desc);
		int first = frame.getStackSize() - arguments.length, slot = 1, found = -1;
		for (int a = 0; a < arguments.length; a++) {
			SourceValue value = frame.getStack(first + a);
			if (value.insns.size() == 1 && value.insns.iterator().next() instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD && load.var == flags) {
				if (found >= 0) return -1;
				found = slot;
			}
			slot += arguments[a].getSize();
		}
		for (AbstractInsnNode insn : helper.instructions) {
			if (insn instanceof VarInsnNode v && v.var == found && v.getOpcode() == Opcodes.ISTORE) return -1;
			if (insn instanceof IincInsnNode n && n.var == found) return -1;
		}
		return found;
	}

	private static int firstIntParameter(MethodNode method) {
		int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
		for (Type argument : Type.getArgumentTypes(method.desc)) {
			if (argument.equals(Type.INT_TYPE)) return slot;
			slot += argument.getSize();
		}
		return -1;
	}

	/** {@code bit/jump} when {@code bit} is the mask of {@code iload flags; <bit>; iand; ifeq|ifne}; null otherwise. */
	private static String flagTest(AbstractInsnNode bit, int flags) {
		Integer value = intValue(bit);
		if (value == null || !(MixinPlayerWorldCallbackAdapter.previous(bit) instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ILOAD
				|| load.var != flags) return null;
		AbstractInsnNode and = MixinPlayerWorldCallbackAdapter.next(bit);
		if (and == null || and.getOpcode() != Opcodes.IAND || !(MixinPlayerWorldCallbackAdapter.next(and) instanceof JumpInsnNode jump)
				|| jump.getOpcode() != Opcodes.IFEQ && jump.getOpcode() != Opcodes.IFNE) return null;
		return value + "/" + jump.getOpcode();
	}

	/** Every flags test whose jump passes over {@code at}: the conditions that decide whether the operation runs. */
	private static Set<String> flagTestsAround(MethodNode method, AbstractInsnNode at, int flags) {
		Set<String> tests = new TreeSet<>();
		int index = method.instructions.indexOf(at);
		for (AbstractInsnNode insn : method.instructions) {
			String test = flagTest(insn, flags);
			if (test == null || insn == at) continue;
			JumpInsnNode jump = (JumpInsnNode) MixinPlayerWorldCallbackAdapter.next(MixinPlayerWorldCallbackAdapter.next(insn));
			if (method.instructions.indexOf(jump) < index && index < method.instructions.indexOf(jump.label)) tests.add(test);
		}
		return tests;
	}

	/** Whether the flags test on {@code bit} jumps with {@code opcode} past the first call of {@code member}. */
	private static boolean skips(MethodNode method, AbstractInsnNode bit, int opcode, String member) {
		AbstractInsnNode jump = MixinPlayerWorldCallbackAdapter.next(MixinPlayerWorldCallbackAdapter.next(bit));
		MethodInsnNode guarded = MixinPlayerWorldCallbackAdapter.first(method, member);
		if (!(jump instanceof JumpInsnNode skip) || skip.getOpcode() != opcode || guarded == null) return false;
		int at = method.instructions.indexOf(guarded);
		return method.instructions.indexOf(skip) < at && at < method.instructions.indexOf(skip.label);
	}

	private static Integer intValue(AbstractInsnNode insn) {
		if (insn == null) return null;
		int opcode = insn.getOpcode();
		if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return opcode - Opcodes.ICONST_0;
		if (insn instanceof IntInsnNode push && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) return push.operand;
		if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) return value;
		return null;
	}

	private static AbstractInsnNode constant(MethodNode method, int value) {
		for (AbstractInsnNode insn : method.instructions) if (Integer.valueOf(value).equals(intValue(insn))) return insn;
		return null;
	}

	private static MethodInsnNode first(MethodNode method, Anchor anchor) {
		return anchor.member() == null ? null : MixinPlayerWorldCallbackAdapter.first(method, anchor.member());
	}

	static int count(MethodNode method, Anchor anchor) {
		if (anchor.member() != null) return MixinPlayerWorldCallbackAdapter.count(method, anchor.member());
		int n = 0;
		for (AbstractInsnNode insn : method.instructions) if (anchor.constant().equals(intValue(insn))) n++;
		return n;
	}

	private static MethodNode method(ClassNode target, String selector) {
		return target.methods.stream().filter(m -> selector.equals(m.name + m.desc)).findFirst().orElse(null);
	}
}
