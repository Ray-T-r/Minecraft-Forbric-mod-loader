/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import net.forbric.api.Ecosystem;

/**
 * Keeps a mod's callbacks around vanilla's {@code Item.useOn} call in {@code ItemStack.useOn} once the merged game makes
 * that call inside the platform's placement transaction instead ({@code ForgeHooks.onPlaceItemIntoWorld} on the server,
 * a lambda on the client): an {@code @Inject} at the call, or at a later point of vanilla's method, has nothing to bind
 * to in the merged {@code useOn}.
 *
 * <p>Each such handler is kept on its own, and runs exactly when vanilla would have run it — which is what moving it to
 * the head or the return of {@code useOn} alone could not promise, since the placement can be refused before the call:
 * <ul>
 * <li>a handler at the call runs at the head of {@code useOn}, inside a replay of vanilla's own instructions up to the
 * call ({@link MixinNativeReplay}): it is called only where vanilla reaches the call, and its {@code @Local}s are the
 * replayed locals MixinExtras would have read there. Cancelling it at the head is cancelling before the call, as it was;
 * </li>
 * <li>a handler at a later point runs at the return of {@code useOn}, once the transaction is over, inside a replay of
 * vanilla's instructions from the call to that point, with the call's result taken from the transaction's return value
 * and the locals vanilla had at the call carried from the head in shared slots — only when the call was reached. It must
 * not read its callback (which now holds the returned value), nor its receiver (which the transaction may have
 * replaced), nor cancel.</li>
 * </ul>
 * Shares of a handler move with it. The move is proved, not assumed: the merged {@code useOn} no longer makes the call
 * but reaches it through what it calls; vanilla's instructions up to the call store nothing and make only calls the
 * merged {@code useOn} makes anyway on its way to the call, so replaying them is not seen; the instructions from the call
 * to a later point store and call nothing, and are the only way to it. A handler alone at its point, written as Mixin
 * reads it, with the class the mod was compiled against at hand.
 */
final class MixinPlacementTransactionAdapter {
	private static final String STACK = "net/minecraft/world/item/ItemStack", CONTEXT = "net/minecraft/world/item/context/UseOnContext";
	private static final String USE_ON = "useOn(L" + CONTEXT + ";)Lnet/minecraft/world/InteractionResult;";
	private static final String CALL = "Lnet/minecraft/world/item/Item;" + USE_ON;
	private static final String CIR = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
	private static final String REF = "com/llamalad7/mixinextras/sugar/ref/LocalRef", FLAG = "com/llamalad7/mixinextras/sugar/ref/LocalBooleanRef";
	private static final String SHARE = "Lcom/llamalad7/mixinextras/sugar/Share;";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;", AT = "Lorg/spongepowered/asm/mixin/injection/At;";
	/** How many calls deep the merged {@code useOn} is searched for the call it moved. */
	private static final int DEPTH = 3;

	private MixinPlacementTransactionAdapter() { }

	static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		return adapt(mixin, targets, NativeGameReferences::reference);
	}

	static int adapt(ClassNode mixin, Function<String, ClassNode> targets, BiFunction<Ecosystem, String, ClassNode> references) {
		if (!MixinCallbackShape.targets(mixin, STACK)) return 0;
		ClassNode target = targets.apply(STACK), source = references == null ? null : references.apply(MixinStubRebind.ecosystemOf(mixin.name), STACK);
		MethodNode live = target == null ? null : MixinPlayerWorldCallbackAdapter.selector(target, USE_ON);
		MethodNode vanilla = source == null ? null : MixinPlayerWorldCallbackAdapter.selector(source, USE_ON);
		if (live == null || vanilla == null || MixinPlayerWorldCallbackAdapter.count(vanilla, CALL) != 1 || MixinPlayerWorldCallbackAdapter.count(live, CALL) != 0) return 0;
		Set<String> repeated = new HashSet<>();
		Function<String, ClassNode> classes = name -> { try { return targets.apply(name); } catch (RuntimeException unreadable) { return null; } };
		if (!MixinNativeReplay.reaches(target, live, CALL, classes, DEPTH, repeated)) return 0;
		MethodInsnNode call = MixinPlayerWorldCallbackAdapter.first(vanilla, CALL);
		int callAt = vanilla.instructions.indexOf(call);
		Proofs proofs = new Proofs(source.name, vanilla, call, callAt, repeated);
		if (!proofs.prefix()) return 0;

		// Every handler here is written for vanilla's useOn: its points are read, and told apart, in that body.
		List<MethodNode> entries = MixinCallbackProofs.alone(mixin, m -> vanilla, m -> MixinCallbackShape.kind(m, "Inject")
				&& MixinCallbackShape.binds(m, target, USE_ON) && atTheCall(m, vanilla));
		List<MethodNode> exits = MixinCallbackProofs.alone(mixin, m -> vanilla, m -> MixinCallbackShape.kind(m, "Inject")
				&& MixinCallbackShape.binds(m, target, USE_ON) && !atTheCall(m, vanilla) && proofs.after(m, live) >= 0);
		List<Runnable> moves = new ArrayList<>();
		for (MethodNode entry : entries) {
			Map<Integer, Integer> locals = proofs.locals(entry, callAt);
			if (locals == null || !operands(entry)) continue;
			moves.add(() -> entry(mixin, entry, proofs, locals, classes));
		}
		for (MethodNode exit : exits) {
			int at = proofs.after(exit, live);
			Map<Integer, Integer> locals = proofs.locals(exit, at);
			if (locals == null || !operands(exit) || Boolean.TRUE.equals(MixinFit.value(MixinFit.injectorOf(exit), "cancellable"))) continue;
			int[] slots = MixinCallbackProofs.slots(exit);
			Type[] parameters = Type.getArgumentTypes(exit.desc);
			int callback = parameters[0].getInternalName().equals(CIR) ? 0 : 1;
			if (!MixinCallbackProofs.unread(exit, slots[callback]) || MixinCallbackShape.instance(exit) && !MixinCallbackShape.noReceiver(exit)) continue;
			Map<Integer, Type> stash = proofs.stash(at, locals);
			if (stash == null) continue;
			moves.add(() -> exit(mixin, exit, proofs, at, locals, stash, classes));
		}
		moves.forEach(Runnable::run);
		return moves.size();
	}

	/** The handler takes {@code useOn}'s context and its callback, or the callback alone. */
	private static boolean operands(MethodNode handler) {
		MixinHandlerShape shape = MixinHandlerShape.of(handler);
		return shape.operands("(L" + CONTEXT + ";L" + CIR + ";)V") || shape.operands("(L" + CIR + ";)V");
	}

	/**
	 * One {@code @At(INVOKE)} before vanilla's {@code Item.useOn} call, its target read as Mixin reads it in vanilla's
	 * {@code useOn} ({@link MixinCallbackShape#names}): no later ordinal, no {@code by}, {@code opcode} or {@code args}.
	 */
	private static boolean atTheCall(MethodNode handler, MethodNode vanilla) {
		List<AnnotationNode> ats = MixinFit.atNodes(MixinFit.injectorOf(handler));
		if (ats.size() != 1) return false;
		AnnotationNode at = ats.getFirst();
		Object ordinal = MixinFit.value(at, "ordinal");
		return "INVOKE".equals(MixinFit.asString(MixinFit.value(at, "value"))) && MixinCallbackShape.names(at, CALL, vanilla) && before(at)
				&& MixinFit.value(at, "opcode") == null && MixinFit.value(at, "args") == null && (ordinal == null || ordinal instanceof Number n && n.intValue() <= 0);
	}

	/** No shift, or a shift BEFORE the instruction; no {@code by}. */
	private static boolean before(AnnotationNode at) {
		Object shift = MixinFit.value(at, "shift");
		return MixinFit.value(at, "by") == null && (shift == null || shift instanceof String[] value && value.length == 2
				&& (value[1].equals("BEFORE") || value[1].equals("NONE")));
	}

	/** What is proved about vanilla's {@code useOn} around its one {@code Item.useOn} call. */
	private static final class Proofs {
		final String owner; final MethodNode vanilla; final MethodInsnNode call; final int callAt; final Set<String> repeated;
		Proofs(String owner, MethodNode vanilla, MethodInsnNode call, int callAt, Set<String> repeated) {
			this.owner = owner; this.vanilla = vanilla; this.call = call; this.callAt = callAt; this.repeated = repeated;
		}

		/** Vanilla's instructions up to the call replay unseen, and nothing but the call's operands is on the stack there. */
		boolean prefix() {
			MixinNativeReplay replay = new MixinNativeReplay(owner, vanilla, 0);
			if (!replay.analysed() || !replay.replayable(0, callAt, repeated)) return false;
			Frame<BasicValue> frame = replay.frame(callAt);
			return frame != null && frame.getStackSize() == Type.getArgumentTypes(call.desc).length + 1;
		}

		/**
		 * The one instruction of vanilla's {@code useOn} past the call that the handler's {@code @At} selects, reached only
		 * from the call through instructions that store and call nothing, and absent from the merged {@code useOn}; -1 otherwise.
		 */
		int after(MethodNode handler, MethodNode live) {
			List<AnnotationNode> ats = MixinFit.atNodes(MixinFit.injectorOf(handler));
			if (ats.size() != 1 || !before(ats.getFirst())) return -1;
			String value = MixinFit.asString(MixinFit.value(ats.getFirst(), "value"));
			if (!"INVOKE".equals(value) && !"FIELD".equals(value) && !"NEW".equals(value)) return -1;
			List<AbstractInsnNode> points = MixinCallbackProofs.points(vanilla, ats.getFirst()), now = MixinCallbackProofs.points(live, ats.getFirst());
			if (points == null || points.size() != 1 || now == null || !now.isEmpty()) return -1;
			int at = vanilla.instructions.indexOf(points.getFirst());
			MixinNativeReplay replay = new MixinNativeReplay(owner, vanilla, 0);
			if (at <= callAt || !replay.replayable(callAt + 1, at, null) || !replay.enteredOnlyFrom(callAt, at) || !reachable(at)) return -1;
			// The stretch is replayed after the transaction: it may not look at the stack the transaction may have replaced.
			for (int i = callAt + 1; i < at; i++) if (vanilla.instructions.get(i) instanceof VarInsnNode load && load.var == 0
					&& (vanilla.access & Opcodes.ACC_STATIC) == 0) return -1;
			return at;
		}

		/** Whether instruction {@code at} is reached from the call at all (its frame was computed on a path from it). */
		private boolean reachable(int at) {
			MixinNativeReplay replay = new MixinNativeReplay(owner, vanilla, 0);
			return replay.frame(at) != null;
		}

		/**
		 * Each {@code @Local} of the handler, by handler parameter, to the vanilla slot MixinExtras reads at instruction
		 * {@code at}; the handler's other extras must be {@code @Share}s. Null when a {@code @Local} names no single slot.
		 */
		Map<Integer, Integer> locals(MethodNode handler, int at) {
			MixinHandlerShape shape = MixinHandlerShape.of(handler);
			Map<Integer, Integer> slots = new LinkedHashMap<>();
			for (MixinHandlerShape.Extra extra : shape.extras()) {
				if (extra.role() == MixinHandlerShape.Role.SHARE) continue;
				if (extra.role() != MixinHandlerShape.Role.LOCAL) return null;
				int slot = MixinLocalOriginProof.slot(extra.sugar(), extra.type(), vanilla, at);
				if (slot < 0) return null;
				slots.put(extra.parameter(), slot);
			}
			return slots;
		}

		/**
		 * The vanilla locals a later handler needs carried from the call — those its replayed stretch reads and those it asks
		 * for, that are defined at the call — with their types; null when one is not an object reference.
		 */
		Map<Integer, Type> stash(int at, Map<Integer, Integer> locals) {
			MixinNativeReplay replay = new MixinNativeReplay(owner, vanilla, 0);
			Set<Integer> needed = new TreeSet<>(locals.values());
			for (int i = callAt + 1; i < at; i++) {
				AbstractInsnNode instruction = vanilla.instructions.get(i);
				if (instruction instanceof VarInsnNode variable && variable.getOpcode() >= Opcodes.ILOAD && variable.getOpcode() <= Opcodes.ALOAD) needed.add(variable.var);
				if (instruction instanceof IincInsnNode increment) needed.add(increment.var);
			}
			Frame<BasicValue> frame = replay.frame(callAt);
			Map<Integer, Type> stash = new LinkedHashMap<>();
			for (int slot : needed) {
				if (slot < replay.firstLocal() || slot >= frame.getLocals() || frame.getLocal(slot) == BasicValue.UNINITIALIZED_VALUE) continue;
				Type type = replay.slotType(callAt, slot);
				if (type == null) return null;
				stash.put(slot, type);
			}
			return stash;
		}
	}

	// ---- the handler at the call ---------------------------------------------------------------------------------------

	/** Replaces {@code entry} by a HEAD handler of its name that replays vanilla up to the call and calls it there. */
	private static void entry(ClassNode mixin, MethodNode entry, Proofs proofs, Map<Integer, Integer> locals, Function<String, ClassNode> classes) {
		AnnotationNode injector = MixinFit.injectorOf(entry);
		List<Integer> shares = shares(entry);
		Type[] parameters = Type.getArgumentTypes(entry.desc);
		List<Type> outer = new ArrayList<>(List.of(Type.getObjectType(CONTEXT), Type.getObjectType(CIR)));
		for (int share : shares) outer.add(parameters[share]);
		MethodNode wrapper = new MethodNode(Opcodes.ACC_PRIVATE, entry.name, Type.getMethodDescriptor(Type.VOID_TYPE, outer.toArray(Type[]::new)), null, null);
		wrapper.visibleAnnotations = new ArrayList<>(List.of(moved(injector, "HEAD")));
		wrapper.invisibleParameterAnnotations = parameterAnnotations(entry, shares, outer.size(), 2);
		MixinNativeReplay replay = new MixinNativeReplay(proofs.owner, proofs.vanilla, slotsOf(outer));
		InsnList code = wrapper.instructions;
		replay.copy(code, 0, proofs.callAt);
		MixinNativeReplay.pop(code, replay.frame(proofs.callAt));
		invoke(code, mixin, entry, locals, replay, shares, 3);
		code.add(new JumpInsnNode(Opcodes.GOTO, replay.skip));
		replay.trampolines(code);
		code.add(replay.skip);
		code.add(new InsnNode(Opcodes.RETURN));
		retire(entry, injector);
		mixin.methods.add(wrapper);
		MixinNativeReplay.frames(mixin, wrapper, classes);
	}

	// ---- a handler past the call ---------------------------------------------------------------------------------------

	/**
	 * Replaces {@code exit} by a RETURN handler of its name that, when the call was reached, replays vanilla from the call to
	 * the handler's point and calls it there; and adds the HEAD handler that records whether the call was reached and the
	 * vanilla locals the replay needs.
	 */
	private static void exit(ClassNode mixin, MethodNode exit, Proofs proofs, int at, Map<Integer, Integer> locals, Map<Integer, Type> stash,
			Function<String, ClassNode> classes) {
		AnnotationNode injector = MixinFit.injectorOf(exit);
		String key = "forbric$" + Integer.toHexString((mixin.name + "." + exit.name + exit.desc).hashCode());
		List<Integer> shares = shares(exit);
		Type[] parameters = Type.getArgumentTypes(exit.desc);

		// At the head: replay vanilla to the call, and there record that it was reached and the locals it had.
		List<Type> head = new ArrayList<>(List.of(Type.getObjectType(CONTEXT), Type.getObjectType(CIR), Type.getObjectType(FLAG)));
		for (int i = 0; i < stash.size(); i++) head.add(Type.getObjectType(REF));
		MethodNode reach = new MethodNode(Opcodes.ACC_PRIVATE, exit.name + "$forbricReach", Type.getMethodDescriptor(Type.VOID_TYPE, head.toArray(Type[]::new)), null, null);
		reach.visibleAnnotations = new ArrayList<>(List.of(moved(injector, "HEAD", "cancellable")));
		reach.invisibleParameterAnnotations = stashAnnotations(key, stash, head.size(), 2);
		MixinNativeReplay before = new MixinNativeReplay(proofs.owner, proofs.vanilla, slotsOf(head));
		InsnList code = reach.instructions;
		before.copy(code, 0, proofs.callAt);
		MixinNativeReplay.pop(code, before.frame(proofs.callAt));
		code.add(new VarInsnNode(Opcodes.ALOAD, 3));
		code.add(new InsnNode(Opcodes.ICONST_1));
		code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, FLAG, "set", "(Z)V", true));
		int ref = 4;
		for (int slot : stash.keySet()) {
			code.add(new VarInsnNode(Opcodes.ALOAD, ref++));
			code.add(new VarInsnNode(Opcodes.ALOAD, before.slot(slot)));
			code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, REF, "set", "(Ljava/lang/Object;)V", true));
		}
		code.add(new JumpInsnNode(Opcodes.GOTO, before.skip));
		before.trampolines(code);
		code.add(before.skip);
		code.add(new InsnNode(Opcodes.RETURN));

		// At the return: if the call was reached, replay vanilla from it to the point, with its result the returned value.
		List<Type> tail = new ArrayList<>(List.of(Type.getObjectType(CONTEXT), Type.getObjectType(CIR)));
		for (int share : shares) tail.add(parameters[share]);
		int flag = tail.size();
		tail.add(Type.getObjectType(FLAG));
		for (int i = 0; i < stash.size(); i++) tail.add(Type.getObjectType(REF));
		MethodNode wrapper = new MethodNode(Opcodes.ACC_PRIVATE, exit.name, Type.getMethodDescriptor(Type.VOID_TYPE, tail.toArray(Type[]::new)), null, null);
		wrapper.visibleAnnotations = new ArrayList<>(List.of(moved(injector, "RETURN")));
		List<AnnotationNode>[] annotations = parameterAnnotations(exit, shares, tail.size(), 2);
		List<AnnotationNode>[] carried = stashAnnotations(key, stash, tail.size(), flag);
		for (int i = flag; i < tail.size(); i++) annotations[i] = carried[i];
		wrapper.invisibleParameterAnnotations = annotations;
		MixinNativeReplay after = new MixinNativeReplay(proofs.owner, proofs.vanilla, slotsOf(tail));
		code = wrapper.instructions;
		code.add(new VarInsnNode(Opcodes.ALOAD, 1 + flag));
		code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, FLAG, "get", "()Z", true));
		code.add(new JumpInsnNode(Opcodes.IFEQ, after.skip));
		ref = 2 + flag;
		for (var entry : stash.entrySet()) {
			code.add(new VarInsnNode(Opcodes.ALOAD, ref++));
			code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, REF, "get", "()Ljava/lang/Object;", true));
			code.add(new TypeInsnNode(Opcodes.CHECKCAST, entry.getValue().getInternalName()));
			code.add(new VarInsnNode(Opcodes.ASTORE, after.slot(entry.getKey())));
		}
		code.add(new VarInsnNode(Opcodes.ALOAD, 2));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CIR, "getReturnValue", "()Ljava/lang/Object;", false));
		code.add(new TypeInsnNode(Opcodes.CHECKCAST, Type.getReturnType(proofs.call.desc).getInternalName()));
		after.copy(code, proofs.callAt + 1, at);
		MixinNativeReplay.pop(code, after.frame(at));
		invoke(code, mixin, exit, locals, after, shares, 3);
		code.add(new JumpInsnNode(Opcodes.GOTO, after.skip));
		after.trampolines(code);
		code.add(after.skip);
		code.add(new InsnNode(Opcodes.RETURN));
		retire(exit, injector);
		mixin.methods.add(reach);
		mixin.methods.add(wrapper);
		MixinNativeReplay.frames(mixin, reach, classes);
		MixinNativeReplay.frames(mixin, wrapper, classes);
	}

	// ---- shared --------------------------------------------------------------------------------------------------------

	/** Calls the retired {@code handler} with the wrapper's context and callback, its {@code @Local}s from the replayed slots, its shares from the wrapper's. */
	private static void invoke(InsnList code, ClassNode mixin, MethodNode handler, Map<Integer, Integer> locals, MixinNativeReplay replay,
			List<Integer> shares, int firstShare) {
		boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		Type[] parameters = Type.getArgumentTypes(handler.desc);
		if (!isStatic) code.add(new VarInsnNode(Opcodes.ALOAD, 0));
		int share = firstShare;
		for (int i = 0; i < parameters.length; i++) {
			String descriptor = parameters[i].getDescriptor();
			if (i == 0 && descriptor.equals("L" + CONTEXT + ";")) code.add(new VarInsnNode(Opcodes.ALOAD, 1));
			else if (i <= 1 && descriptor.equals("L" + CIR + ";")) code.add(new VarInsnNode(Opcodes.ALOAD, 2));
			else if (locals.containsKey(i)) {
				code.add(new VarInsnNode(parameters[i].getOpcode(Opcodes.ILOAD), replay.slot(locals.get(i))));
				if (parameters[i].getSort() == Type.OBJECT || parameters[i].getSort() == Type.ARRAY)
					code.add(new TypeInsnNode(Opcodes.CHECKCAST, parameters[i].getInternalName()));
			} else if (shares.contains(i)) code.add(new VarInsnNode(Opcodes.ALOAD, share++));
		}
		code.add(MixinHandlerShim.callOwn(mixin, isStatic, handler.name + "$forbricOriginal", handler.desc));
	}

	/** The handler parameters that are {@code @Share}s, in order. */
	private static List<Integer> shares(MethodNode handler) {
		List<Integer> out = new ArrayList<>();
		for (MixinHandlerShape.Extra extra : MixinHandlerShape.of(handler).extras()) if (extra.role() == MixinHandlerShape.Role.SHARE) out.add(extra.parameter());
		return out;
	}

	/** The wrapper's parameter annotations: each share's own {@code @Share}, from position {@code first} on. */
	@SuppressWarnings("unchecked")
	private static List<AnnotationNode>[] parameterAnnotations(MethodNode handler, List<Integer> shares, int size, int first) {
		List<AnnotationNode>[] out = new List[size];
		for (int i = 0; i < shares.size(); i++) {
			AnnotationNode share = MixinStubRebind.sugar(handler, shares.get(i), SHARE);
			out[first + i] = new ArrayList<>(List.of(share));
		}
		return out;
	}

	/** {@code @Share}s for the reached flag at {@code first} and each carried local after it, named after {@code key}. */
	@SuppressWarnings("unchecked")
	private static List<AnnotationNode>[] stashAnnotations(String key, Map<Integer, Type> stash, int size, int first) {
		List<AnnotationNode>[] out = new List[size];
		out[first] = new ArrayList<>(List.of(share(key + "$reached")));
		int i = first + 1;
		for (int slot : stash.keySet()) out[i++] = new ArrayList<>(List.of(share(key + "$local" + slot)));
		return out;
	}

	private static AnnotationNode share(String name) {
		AnnotationNode share = new AnnotationNode(SHARE);
		share.values = new ArrayList<>(List.of("value", name));
		return share;
	}

	/** A copy of {@code injector} with {@code @At(point)} for its one point, less the {@code dropped} keys. */
	private static AnnotationNode moved(AnnotationNode injector, String point, String... dropped) {
		AnnotationNode copy = new AnnotationNode(INJECT);
		copy.values = new ArrayList<>();
		AnnotationNode at = new AnnotationNode(AT);
		at.values = new ArrayList<>(List.of("value", point));
		for (int i = 0; i + 1 < injector.values.size(); i += 2) {
			String name = (String) injector.values.get(i);
			if (List.of(dropped).contains(name)) continue;
			copy.values.add(name);
			copy.values.add(name.equals("at") ? new ArrayList<>(List.of(at)) : injector.values.get(i + 1));
		}
		return copy;
	}

	/** The first free slot of an instance method taking {@code parameters}. */
	private static int slotsOf(List<Type> parameters) {
		int slot = 1;
		for (Type parameter : parameters) slot += parameter.getSize();
		return slot;
	}

	/** Takes the injector off the original handler and renames it, so the wrapper of its name calls it. */
	private static void retire(MethodNode handler, AnnotationNode injector) {
		handler.name += "$forbricOriginal";
		MixinCarrierCallbackAdapters.removeInjector(handler, injector);
		handler.invisibleParameterAnnotations = null;
		handler.visibleParameterAnnotations = null;
	}
}
