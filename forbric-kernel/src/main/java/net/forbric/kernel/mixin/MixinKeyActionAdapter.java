/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * NeoForge joins {@code KeyboardHandler.keyPress}'s exits into one {@code ClientHooks.onKeyInput(event, action)} call just
 * before its final return (the merged body keeps MinecraftForge's own {@code ForgeHooksClient.onKeyInput} in the same
 * join, in front of it), so a guest's {@code @Inject} at a native {@code RETURN} ordinal names a return the merged
 * body no longer has, and its {@code TAIL} also runs on the paths whose native returns came earlier.
 *
 * <p>Which native return a handler names is read off the native body the guest was compiled against
 * ({@link JoinedReturnExits}); no ordinal is assumed:
 * <ul>
 *   <li>a native return the carrier joined into the hook, proved by its own run and the action values reaching it,
 *       moves to just before the join's first hook call and runs only for those action values (in 26.2 vanilla: the key-release exit,
 *       native {@code RETURN} ordinal 4, action {@code 0});</li>
 *   <li>the native final return ({@code TAIL}, or a {@code RETURN} ordinal naming the last native return — ordinal 5
 *       in 26.2 vanilla) stays the final return, which the hook now precedes, and runs only for the action values that
 *       reached it natively (press and repeat, never release).</li>
 * </ul>
 * A handler keeps its body; the guard is a wrapper with the handler's name and injector. Every other return, a
 * cancellable callback moved before the hook, captured locals and anything the analysis cannot prove stay as compiled.
 */
public final class MixinKeyActionAdapter {
	public static final String PROPERTY = "forbric.keyActionCallbacks";
	static final String TARGET = "net/minecraft/client/KeyboardHandler";
	static final String EVENT = "Lnet/minecraft/client/input/KeyEvent;";
	static final String HOST = "keyPress(JI" + EVENT + ")V";
	static final String CALLBACK = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	static final String HANDLER = "(JI" + EVENT + CALLBACK + ")V";
	static final String BARE = "(" + CALLBACK + ")V";
	static final String LIVE = "Lnet/neoforged/neoforge/client/ClientHooks;onKeyInput(" + EVENT + "I)V";
	static final String SUFFIX = "$forbrickeyexit";
	static final String MODIFY_EXPRESSION_VALUE = "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;";
	static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
	static final String SHARE = "Lcom/llamalad7/mixinextras/sugar/Share;";
	static final String FLAG = "com/llamalad7/mixinextras/sugar/ref/LocalBooleanRef";
	/** The share a decision exit's observer sets, per native return ordinal: {@code forbric$exit<ordinal>}. */
	static final String EXIT_SHARE = "forbric$exit";

	private MixinKeyActionAdapter() { }

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY, "on"));
	}

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		return adapt(mixin, targets, NativeGameReferences::reference);
	}

	/** The source seam supplies hash-verified native bytes in production and explicit original bytes in tests. */
	static int adapt(ClassNode mixin, Function<String, ClassNode> targets, BiFunction<Ecosystem, String, ClassNode> references) {
		if (!enabled() || targets == null || references == null || !MixinCallbackShape.targets(mixin, TARGET)) return 0;
		List<MethodNode> candidates = new ArrayList<>();
		for (MethodNode handler : mixin.methods) if (candidate(handler)) candidates.add(handler);
		if (candidates.isEmpty()) return 0;
		ClassNode current = targets.apply(TARGET), original = references.apply(MixinStubRebind.ecosystemOf(mixin.name), TARGET);
		if (current == null || original == null) return 0;
		MethodNode host = MixinPlayerWorldCallbackAdapter.selector(current, HOST), source = MixinPlayerWorldCallbackAdapter.selector(original, HOST);
		JoinedReturnExits exits = JoinedReturnExits.of(original, source, current, host, LIVE);
		if (exits == null) return 0;
		int changed = 0;
		for (MethodNode handler : candidates) {
			// However the selector is written, it must bind keyPress alone, natively and in the merged class.
			MethodNode bound = MixinTargetSelectors.one(handler, current), nativeBound = MixinTargetSelectors.one(handler, original);
			if (host == null || source == null || bound != host || nativeBound != source) continue;
			if (relocate(mixin, handler, exits)) changed++;
		}
		return changed;
	}

	/** An unconditional, single-point {@code @Inject} at a RETURN ordinal or TAIL, without captured locals or sugar. */
	private static boolean candidate(MethodNode handler) {
		if (CurrentBodyOrdinals.counted(handler) || !MixinCallbackShape.kind(handler, "Inject")
				|| !(MixinCallbackShape.shape(handler, HANDLER) || MixinCallbackShape.shape(handler, BARE))) return false;
		AnnotationNode injector = MixinFit.injectorOf(handler);
		if (MixinFit.value(injector, "locals") != null) return false;
		if (MixinCallbackShape.point(handler, "TAIL", null)) return true;
		return MixinCallbackShape.point(handler, "RETURN", null)
				&& MixinFit.value(MixinFit.atNodes(injector).getFirst(), "ordinal") instanceof Integer ordinal && ordinal >= 0;
	}

	private static boolean relocate(ClassNode mixin, MethodNode handler, JoinedReturnExits exits) {
		AnnotationNode injector = MixinFit.injectorOf(handler), at = MixinFit.atNodes(injector).getFirst();
		boolean tail = "TAIL".equals(MixinFit.value(at, "value"));
		int ordinal = tail ? exits.nativeReturns() - 1 : (Integer) MixinFit.value(at, "ordinal");
		if (ordinal >= exits.nativeReturns()) return false;
		JoinedReturnExits.Values guard;
		String placed;
		List<JoinedReturnExits.DecisionExit> decisions = List.of();
		if (exits.isFinal(ordinal)) {
			// The native final return is still the final return; a RETURN ordinal counted on the native body is not.
			if (!exits.finalReached()) return false;
			guard = exits.finalGuard();
			// And a native early return the carrier sends here on a decision (a global key handleGlobalKeyPress handled)
			// must not run it either: the decision's answer, seen where it is made, is shared with the handler's guard.
			decisions = exits.decisionExits();
			boolean recount = !tail && exits.currentReturns() != exits.nativeReturns();
			if (guard == null && !recount && decisions.isEmpty()) return false;
			if (recount) {
				MixinPlayerWorldCallbackAdapter.set(at, "value", "TAIL");
				MixinPlayerWorldCallbackAdapter.remove(at, "ordinal");
			}
			placed = "the merged final return";
		} else {
			guard = exits.joined(ordinal);
			// Before the hook, a cancelled callback would also cancel the carrier's event, which the native return never had.
			if (guard == null || Boolean.TRUE.equals(MixinFit.value(injector, "cancellable"))) return false;
			// Just before the join's first call: the hook, or another ecosystem's twin the merge kept in front of it.
			MixinPlayerWorldCallbackAdapter.set(at, "value", "INVOKE");
			MixinPlayerWorldCallbackAdapter.set(at, "target", exits.entry());
			MixinPlayerWorldCallbackAdapter.set(at, "ordinal", exits.entryOrdinal());
			placed = "the merged " + exits.entry() + " call that begins the join";
			if (guard.all()) guard = null;
		}
		List<String> shares = new ArrayList<>();
		for (JoinedReturnExits.DecisionExit decision : decisions) shares.add(observe(mixin, decision));
		MethodNode marked = guard == null && shares.isEmpty() ? handler : guarded(mixin, handler, guard, shares);
		CurrentBodyOrdinals.mark(marked);
		StringBuilder unless = new StringBuilder();
		for (JoinedReturnExits.DecisionExit decision : decisions)
			unless.append(", and not after ").append(readable(decision.member())).append(" answered ").append(decision.nonZero() ? "true" : "false")
					.append(" (native return ").append(decision.ordinal()).append(", which the carrier sends into its hook)");
		ForbricLog.info("[Forbric/Mixin] %s: %s's %s on KeyboardHandler.keyPress is native return %d of %d; it now runs at %s%s%s",
				mixin.name.replace('/', '.'), marked.name, tail ? "TAIL" : "RETURN ordinal " + ordinal, ordinal, exits.nativeReturns(),
				placed, guard == null ? "" : ", for action " + guard + " only, as the native return was reached", unless);
		return true;
	}

	/**
	 * The mixin's observer of a decision exit's call, added once: a MixinExtras {@code @ModifyExpressionValue} on that call in
	 * {@code keyPress} (its occurrence in the current body, which the native body makes as often) that hands the answer back
	 * unchanged and, when it is the answer that natively returned there, sets the per-call share the guards read. Returns the
	 * share's name.
	 */
	private static String observe(ClassNode mixin, JoinedReturnExits.DecisionExit decision) {
		String share = EXIT_SHARE + decision.ordinal();
		String name = MixinHandlerShim.asideName(mixin.name, share, "$taken");
		for (MethodNode existing : mixin.methods) if (existing.name.equals(name)) return share;
		Type answer = Type.getReturnType(decision.member().substring(decision.member().indexOf('(')));
		MethodNode observer = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE, name,
				Type.getMethodDescriptor(answer, answer, Type.getObjectType(FLAG)), null, null);
		LabelNode keep = new LabelNode();
		observer.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
		observer.instructions.add(new JumpInsnNode(decision.nonZero() ? Opcodes.IFEQ : Opcodes.IFNE, keep));
		observer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
		observer.instructions.add(new InsnNode(Opcodes.ICONST_1));
		observer.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, FLAG, "set", "(Z)V", true));
		observer.instructions.add(keep);
		observer.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		observer.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
		observer.instructions.add(new InsnNode(answer.getOpcode(Opcodes.IRETURN)));
		observer.maxLocals = 3;
		observer.maxStack = 2;
		AnnotationNode modify = new AnnotationNode(MODIFY_EXPRESSION_VALUE), at = new AnnotationNode(AT);
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", decision.member(), "ordinal", decision.occurrence()));
		modify.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(HOST)), "at", at));
		observer.visibleAnnotations = new ArrayList<>(List.of(modify));
		@SuppressWarnings("unchecked") List<AnnotationNode>[] table = new List[2];
		table[1] = new ArrayList<>(List.of(shareAnnotation(share)));
		observer.invisibleParameterAnnotations = table;
		observer.invisibleAnnotableParameterCount = 2;
		CurrentBodyOrdinals.mark(observer);
		mixin.methods.add(observer);
		return share;
	}

	private static AnnotationNode shareAnnotation(String name) {
		AnnotationNode share = new AnnotationNode(SHARE);
		share.values = new ArrayList<>(List.of("value", name));
		return share;
	}

	private static String readable(String member) {
		int semicolon = member.indexOf(';'), paren = member.indexOf('(');
		if (!member.startsWith("L") || semicolon < 0 || paren < semicolon) return member;
		return member.substring(1, semicolon).replace('/', '.') + "." + member.substring(semicolon + 1, paren);
	}

	/**
	 * The handler renamed aside, and in its place a method with its name, descriptor (the full one, for a bare handler),
	 * annotations and position that calls it only for the guard's action values.
	 */
	private static MethodNode guarded(ClassNode mixin, MethodNode handler, JoinedReturnExits.Values guard, List<String> shares) {
		boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		// The handler's own shape, then one MixinExtras @Share flag per decision exit it must not run after.
		StringBuilder desc = new StringBuilder(HANDLER.substring(0, HANDLER.length() - 2));
		for (int i = 0; i < shares.size(); i++) desc.append('L').append(FLAG).append(';');
		desc.append(")V");
		MethodNode outer = new MethodNode(Opcodes.ASM9, handler.access, handler.name, desc.toString(), null,
				handler.exceptions == null ? null : handler.exceptions.toArray(new String[0]));
		outer.visibleAnnotations = handler.visibleAnnotations;
		outer.invisibleAnnotations = handler.invisibleAnnotations;
		handler.visibleAnnotations = null;
		handler.invisibleAnnotations = null;
		Type[] parameters = Type.getArgumentTypes(HANDLER);
		Type[] all = Type.getArgumentTypes(outer.desc);
		int[] slots = new int[all.length];
		int slot = isStatic ? 0 : 1;
		for (int i = 0; i < all.length; i++) { slots[i] = slot; slot += all[i].getSize(); }
		if (!shares.isEmpty()) {
			@SuppressWarnings("unchecked") List<AnnotationNode>[] table = new List[all.length];
			for (int i = 0; i < shares.size(); i++) table[parameters.length + i] = new ArrayList<>(List.of(shareAnnotation(shares.get(i))));
			outer.invisibleParameterAnnotations = table;
			outer.invisibleAnnotableParameterCount = all.length;
		}

		LabelNode skip = new LabelNode();
		for (int i = 0; i < shares.size(); i++) {
			outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, slots[parameters.length + i]));
			outer.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, FLAG, "get", "()Z", true));
			outer.instructions.add(new JumpInsnNode(Opcodes.IFNE, skip));
		}
		if (guard == null) {
			// Only the decision exits to rule out: run.
		} else if (guard.other()) {
			int action = slots[guard.parameter()];
			int[] constants = guard.constants();
			for (int i = 0; i < constants.length; i++) if (!guard.allowed().get(i)) jumpIfEqual(outer.instructions, action, constants[i], skip);
		} else {
			int action = slots[guard.parameter()];
			int[] constants = guard.constants();
			LabelNode run = new LabelNode();
			for (int i = 0; i < constants.length; i++) if (guard.allowed().get(i)) jumpIfEqual(outer.instructions, action, constants[i], run);
			outer.instructions.add(new JumpInsnNode(Opcodes.GOTO, skip));
			outer.instructions.add(run);
			outer.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		}
		if (!isStatic) outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		if (HANDLER.equals(handler.desc)) {
			for (int i = 0; i < parameters.length; i++) outer.instructions.add(new VarInsnNode(parameters[i].getOpcode(Opcodes.ILOAD), slots[i]));
		} else {
			outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, slots[parameters.length - 1]));
		}
		String inner = MixinHandlerShim.asideName(mixin.name, handler.name, SUFFIX);
		outer.instructions.add(MixinHandlerShim.callOwn(mixin, isStatic, inner, handler.desc));
		outer.instructions.add(skip);
		outer.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		outer.instructions.add(new InsnNode(Opcodes.RETURN));
		outer.maxLocals = slot;
		outer.maxStack = slot + 1;
		// The mod's own code may call the handler directly; it keeps the unguarded body it called.
		for (MethodNode caller : mixin.methods) for (AbstractInsnNode instruction : caller.instructions) {
			if (instruction instanceof MethodInsnNode call && call.owner.equals(mixin.name) && call.name.equals(handler.name)
					&& call.desc.equals(handler.desc)) call.name = inner;
		}
		handler.name = inner;
		handler.access = handler.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED) | Opcodes.ACC_PRIVATE;
		// The wrapper takes the handler's place, so callbacks sharing a point keep their declaration order.
		mixin.methods.set(mixin.methods.indexOf(handler), outer);
		mixin.methods.add(handler);
		return outer;
	}

	private static void jumpIfEqual(InsnList code, int slot, int constant, LabelNode to) {
		code.add(new VarInsnNode(Opcodes.ILOAD, slot));
		if (constant == 0) {
			code.add(new JumpInsnNode(Opcodes.IFEQ, to));
			return;
		}
		if (constant >= -1 && constant <= 5) code.add(new InsnNode(Opcodes.ICONST_0 + constant));
		else if (constant >= Byte.MIN_VALUE && constant <= Byte.MAX_VALUE) code.add(new IntInsnNode(Opcodes.BIPUSH, constant));
		else if (constant >= Short.MIN_VALUE && constant <= Short.MAX_VALUE) code.add(new IntInsnNode(Opcodes.SIPUSH, constant));
		else code.add(new LdcInsnNode(constant));
		code.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, to));
	}
}
