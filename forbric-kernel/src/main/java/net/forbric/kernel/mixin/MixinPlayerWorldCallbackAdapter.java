/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.util.ForbricLog;

/** Restores Authored update and cancellable player callbacks at their merged-game equivalents. */
public final class MixinPlayerWorldCallbackAdapter {
	public static final String PROPERTY = "forbric.playerWorldCallbacks";
	static final String LEVEL = "net/minecraft/world/level/Level";
	static final String POS = "Lnet/minecraft/core/BlockPos;";
	static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
	static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
	static final String GAME_MODE = "net/minecraft/server/level/ServerPlayerGameMode";
	static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
	static final String PLAYER = "net/minecraft/server/level/ServerPlayer";
	static final String BLOCK = "net/minecraft/world/level/block/Block";
	static final String ENTITY = "Lnet/minecraft/world/level/block/entity/BlockEntity;";
	static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
	static final String OLD_FILL = "setBlock(" + POS + STATE + "II)Z";
	static final String LIVE_FILL = "markAndNotifyBlock(" + POS + "Lnet/minecraft/world/level/chunk/LevelChunk;" + STATE + STATE + "II)V";
	static final String SHAPES = STATE + "updateNeighbourShapes(Lnet/minecraft/world/level/LevelAccessor;" + POS + "II)V";
	static final String HANDS = "Lnet/neoforged/neoforge/event/entity/living/LivingSwapItemsEvent$Hands;";
	static final String HAND_READ = "L" + PLAYER + ";getItemInHand(Lnet/minecraft/world/InteractionHand;)" + STACK;
	static final String HAND_WRITE = "L" + PLAYER + ";setItemInHand(Lnet/minecraft/world/InteractionHand;" + STACK + ")V";
	static final String SWAP_EVENT = "Lnet/neoforged/neoforge/common/CommonHooks;onLivingSwapHandItems(Lnet/minecraft/world/entity/LivingEntity;)" + HANDS;
	static final String SWAP_VETO = HANDS + "isCanceled()Z";
	static final String TO_OFF_HAND = HANDS + "getItemSwappedToOffHand()" + STACK;
	static final String TO_MAIN_HAND = HANDS + "getItemSwappedToMainHand()" + STACK;
	static final String OLD_REMOVE = "L" + SERVER_LEVEL + ";removeBlock(" + POS + "Z)Z";
	static final String REMOVE = "L" + GAME_MODE + ";removeBlock(" + POS + STATE + "Z" + STACK + ")Z";
	static final String BREAK_EVENT = "Lnet/neoforged/neoforge/common/CommonHooks;fireBlockBreak(L" + LEVEL
			+ ";Lnet/minecraft/world/level/GameType;Lnet/minecraft/world/entity/player/Player;" + POS + STATE
			+ ")Lnet/neoforged/neoforge/event/level/block/BreakBlockEvent;";
	static final String WILL_DESTROY = "L" + BLOCK + ";playerWillDestroy(L" + LEVEL + ";" + POS + STATE + "Lnet/minecraft/world/entity/player/Player;)" + STATE;
	static final String DROPS = "L" + PLAYER + ";preventsBlockDrops()Z";
	static final String MINE = STACK + "mineBlock(L" + LEVEL + ";" + STATE + POS + "Lnet/minecraft/world/entity/player/Player;)V";
	static final String BLOCK_ENTITY = "L" + SERVER_LEVEL + ";getBlockEntity(" + POS + ")" + ENTITY;
	static final String GET_BLOCK = STATE + "getBlock()L" + BLOCK + ";";
	static final String SWAP_HOST = "handlePlayerAction(Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket;)V";
	static final String BREAK_HOST = "destroyBlock(" + POS + ")Z";
	private MixinPlayerWorldCallbackAdapter() { }

	public static boolean enabled() { return !"off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY, "on")); }

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		int changed = repair(mixin, targets);
		if (changed > 0) ForbricLog.info("[Forbric/Mixin] restored %d callback(s) in %s", changed, mixin.name);
		return changed;
	}

	static int repair(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!enabled()) return 0;
        if (MixinCallbackShape.targets(mixin, LEVEL)) return fill(mixin, targets.apply(LEVEL));
        if (MixinCallbackShape.targets(mixin, "net/minecraft/server/network/ServerGamePacketListenerImpl")) return swap(mixin, targets.apply("net/minecraft/server/network/ServerGamePacketListenerImpl"));
        if (MixinCallbackShape.targets(mixin, GAME_MODE)) return blockBreak(mixin, targets.apply(GAME_MODE));
        return 0;
	}

	/**
	 * What the preflight census judges: {@code bytes} as Mixin will receive it once this adapter and
	 * {@link MixinFluidReactionAdapter} have run, or {@code bytes} itself when neither changes it. Both run when Mixin loads
	 * the class, after the census read the original, so every anchor they repair read as missing there: two "applies only
	 * partially" lines and a SUSPECTED row for mixins that apply in full (the final class could at most discharge it
	 * later, once defined). Writes nothing to the log; the adapters say what they did when Mixin loads the class.
	 */
	public static byte[] asLoaded(byte[] bytes, Function<String, byte[]> resource) {
		if (!enabled()) return bytes;
		try {
			ClassReader reader = new ClassReader(bytes);
			ClassNode mixin = new ClassNode(); reader.accept(mixin, 0);
			Function<String, ClassNode> targets = name -> { byte[] b = resource.apply(name + ".class"); if (b == null) return null;
				ClassNode t = new ClassNode(); new ClassReader(b).accept(t, 0); return t; };
			if (repair(mixin, targets) + MixinFluidReactionAdapter.repair(mixin, targets) == 0) return bytes;
			ClassWriter out = new ClassWriter(0); mixin.accept(out); return out.toByteArray();
		} catch (RuntimeException unreadable) {
			return bytes;
		}
	}

	/**
	 * Every injector of a Level mixin that describes one operation of setBlock's notification tail follows it into
	 * markAndNotifyBlock, decided by {@link MixinChunkStatusRetarget#movable}: Carpet's fill hooks (the 16 and the neighbour
	 * update), one of them alone, C2ME's status threshold, a wrap or a redirect of any other call of the tail alike. The
	 * class the mod was compiled against, when at hand, proves the operation was setBlock's and its flags tests unchanged.
	 */
	private static int fill(ClassNode mixin, ClassNode target) {
		if (target == null) return 0;
		List<MethodNode> moving = MixinChunkStatusRetarget.movable(mixin, target,
				NativeGameReferences.reference(MixinStubRebind.ecosystemOf(mixin.name), LEVEL));
		if (moving == null || moving.isEmpty()) return 0;
		for (MethodNode handler : moving) set(MixinFit.injectorOf(handler), "method", List.of(LIVE_FILL));
		return moving.size();
	}

	private static int swap(ClassNode mixin, ClassNode target) {
		MethodNode handler = MixinCallbackShape.unique(mixin, m -> MixinCallbackShape.shape(m, "(Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V")
                && MixinCallbackShape.instance(m) && MixinCallbackShape.kind(m, "Inject") && MixinCallbackShape.binds(m, target, SWAP_HOST)
                && MixinCallbackShape.beforePoint(m, "INVOKE", HAND_READ));
		MethodNode host = target == null ? null : selector(target, SWAP_HOST);
		if (handler == null || host == null) return 0;
		AnnotationNode inject = MixinFit.injectorOf(handler);
		if (!MixinCallbackShape.binds(handler, target, SWAP_HOST)) return 0;
		List<AnnotationNode> ats = MixinFit.atNodes(inject);
		if (ats.size() != 1 || !HAND_READ.equals(MixinFit.value(ats.getFirst(), "target"))
				|| !Integer.valueOf(1).equals(MixinFit.value(ats.getFirst(), "ordinal")) || count(host, HAND_READ) != 1
				|| count(host, SWAP_EVENT) != 1 || count(host, SWAP_VETO) != 1 || count(host, TO_OFF_HAND) != 1
				|| count(host, TO_MAIN_HAND) != 1 || count(host, HAND_WRITE) != 2) return 0;
		// Vanilla's anchor is the swap branch's first read of a hand, right after its spectator gate. Here NeoForge's
		// event is that read: LivingSwapItemsEvent.Hands keeps both stacks, and after its veto the hands are written
		// from those. So the callback goes before the event, as on Fabric before any hand is read: a script that
		// changes a hand without cancelling is honoured instead of overwritten by the event's stale stacks, and the
		// event sees the hands the script left. The callback runs before the native swap veto, preserving its source ordering.
		MethodInsnNode fire = first(host, SWAP_EVENT);
		if (!(previous(fire) instanceof FieldInsnNode player) || player.getOpcode() != Opcodes.GETFIELD || !player.name.equals("player")
				|| !(previous(player) instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0
				|| !(previous(self) instanceof JumpInsnNode gate) || gate.getOpcode() != Opcodes.IFNE
				|| !(previous(gate) instanceof MethodInsnNode spectator) || !spectator.name.equals("isSpectator")) return 0;
		int at = index(host, fire), veto = index(host, first(host, SWAP_VETO));
		if (veto < at || index(host, first(host, TO_OFF_HAND)) < veto || index(host, first(host, TO_MAIN_HAND)) < veto
				|| index(host, first(host, HAND_WRITE)) < veto) return 0;
		set(ats.getFirst(), "target", SWAP_EVENT); set(ats.getFirst(), "ordinal", 0);
		return 1;
	}

	/**
	 * A callback right before vanilla's {@code removeBlock} in {@code destroyBlock}, however it reaches the locals there:
	 * Mixin's locals capture of a leading run of them (block entity, block, adjusted state — vanilla's slots after the
	 * argument, in order; a handler may stop after any of them, or capture none), or MixinExtras' {@code @Local} of the block
	 * entity or the block by type (the only local of its type there). Cancellable or not: the new point runs it on the path
	 * that removes the block either way. Each value it asks for is handed over by the merged slot its producer stored.
	 */
	private static int blockBreak(ClassNode mixin, ClassNode target) {
		MethodNode handler = MixinCallbackShape.unique(mixin, m -> MixinCallbackShape.instance(m) && MixinCallbackShape.kind(m, "Inject")
                && MixinCallbackShape.binds(m, target, BREAK_HOST) && MixinCallbackShape.beforePoint(m, "INVOKE", OLD_REMOVE)
                && breakExtras(m) != null);
		MethodNode host = target == null ? null : selector(target, BREAK_HOST);
		if (handler == null || host == null) return 0;
		AnnotationNode inject = MixinFit.injectorOf(handler);
		if (!MixinCallbackShape.binds(handler, target, BREAK_HOST)
				|| count(host, OLD_REMOVE) != 0 || count(host, BREAK_EVENT) != 1 || count(host, WILL_DESTROY) != 1
				|| count(host, DROPS) != 1 || count(host, MINE) != 1 || count(host, REMOVE) != 2) return 0;
		List<AnnotationNode> ats = MixinFit.atNodes(inject);
		Object ordinal = ats.size() == 1 ? MixinFit.value(ats.getFirst(), "ordinal") : null;
		if (ats.size() != 1 || !OLD_REMOVE.equals(MixinFit.value(ats.getFirst(), "target"))
				|| ordinal != null && !Integer.valueOf(0).equals(ordinal) && !Integer.valueOf(-1).equals(ordinal)) return 0;
		List<String> wanted = breakExtras(handler);
		// Vanilla's anchor is right before removeBlock, after playerWillDestroy, and the handler captures (blockEntity,
		// block, adjustedState). Here NeoForge removes the block in two branches (creative, and survival after
		// mineBlock), so the callback goes where they split: right after playerWillDestroy stored adjustedState, before
		// durability and removal. A cancelled break then keeps playerWillDestroy's effects as on Fabric (a bed's other
		// half, unstable TNT), and NeoForge's own break event, earlier, can still veto before the callback runs.
		VarInsnNode entity = storedResult(host, BLOCK_ENTITY), block = storedResult(host, GET_BLOCK), adjusted = storedResult(host, WILL_DESTROY);
		MethodInsnNode anchor = first(host, DROPS);
		if (entity == null || block == null || adjusted == null || !(next(adjusted) instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD
				|| self.var != 0 || !(next(self) instanceof FieldInsnNode player) || player.getOpcode() != Opcodes.GETFIELD
				|| !player.name.equals("player") || next(player) != anchor) return 0;
		int at = index(host, anchor);
		if (index(host, first(host, BREAK_EVENT)) > at || index(host, entity) > at || index(host, block) > at) return 0;
		for (var i : host.instructions) if (i instanceof MethodInsnNode c && (MINE.equals(member(c)) || REMOVE.equals(member(c))) && index(host, c) < at) return 0;
		remove(inject, "locals"); set(ats.getFirst(), "target", DROPS);
		java.util.Map<String, Integer> slots = java.util.Map.of(ENTITY, entity.var, "L" + BLOCK + ";", block.var, STATE, adjusted.var);
		int operands = org.objectweb.asm.Type.getArgumentTypes(handler.desc).length - wanted.size();
		@SuppressWarnings("unchecked") List<AnnotationNode>[] annotations = new List[operands + wanted.size()];
		for (int n = 0; n < wanted.size(); n++) {
			AnnotationNode local = new AnnotationNode("Lcom/llamalad7/mixinextras/sugar/Local;");
			local.values = new ArrayList<>(List.of("index", slots.get(wanted.get(n))));
			annotations[operands + n] = new ArrayList<>(List.of(local));
		}
		handler.visibleParameterAnnotations = null; handler.visibleAnnotableParameterCount = 0;
		handler.invisibleParameterAnnotations = wanted.isEmpty() ? null : annotations;
		handler.invisibleAnnotableParameterCount = wanted.isEmpty() ? 0 : annotations.length;
		return 1;
	}

	/**
	 * The locals a break callback asks for, by type, when it asks the way vanilla's {@code destroyBlock} can serve before
	 * {@code removeBlock}: no extras; a locals capture of a leading run of (block entity, block, adjusted state); or
	 * {@code @Local}s by type alone of the block entity and the block, each at most once. Null for anything else.
	 */
	private static List<String> breakExtras(MethodNode handler) {
		MixinHandlerShape shape = MixinHandlerShape.of(handler);
		if (shape == null || !shape.operands(BREAK_HOST.substring(BREAK_HOST.indexOf('(')).replace(")Z", CIR + ")V"))) return null;
		List<MixinHandlerShape.Extra> extras = shape.extras();
		// The rewrite gives the extras their @Local and nothing else: a parameter annotated otherwise (a @Coerce) stays as compiled.
		for (List<AnnotationNode>[] table : java.util.Arrays.asList(handler.visibleParameterAnnotations, handler.invisibleParameterAnnotations)) {
			if (table != null) for (int p = 0; p < table.length; p++) {
				if (table[p] == null || table[p].isEmpty()) continue;
				int at = p;
				if (table[p].size() != 1 || extras.stream().noneMatch(e -> e.parameter() == at && e.role() == MixinHandlerShape.Role.LOCAL)) return null;
			}
		}
		List<String> captured = List.of(ENTITY, "L" + BLOCK + ";", STATE), wanted = new ArrayList<>();
		boolean capturing = MixinFit.value(MixinFit.injectorOf(handler), "locals") != null;
		for (int n = 0; n < extras.size(); n++) {
			MixinHandlerShape.Extra extra = extras.get(n);
			String type = extra.type().getDescriptor();
			if (extra.role() == MixinHandlerShape.Role.CAPTURED) {
				if (!capturing || n >= captured.size() || !captured.get(n).equals(type) || wanted.size() != n) return null;
			} else if (extra.role() == MixinHandlerShape.Role.LOCAL) {
				AnnotationNode local = extra.sugar();
				if (STATE.equals(type) || !captured.contains(type) || wanted.contains(type) || local.values != null && !local.values.isEmpty()) return null;
			} else return null;
			wanted.add(type);
		}
		return wanted;
	}

    /** Locals come from their unique producer calls; debug names and numeric slot layouts are irrelevant. */
    private static VarInsnNode storedResult(MethodNode method,String member) {
        if(count(method,member)!=1)return null;
        MethodInsnNode call=first(method,member);
        if(!(next(call) instanceof VarInsnNode store)||store.getOpcode()!=Opcodes.ASTORE)return null;
        return storedFrom(method,store.var,member);
    }

	/** The one store into {@code slot}, when it directly takes the result of the one call of {@code member}. */
	static VarInsnNode storedFrom(MethodNode m, int slot, String member) {
		VarInsnNode store = null;
		for (var i : m.instructions) if (i instanceof VarInsnNode v && v.getOpcode() == Opcodes.ASTORE && v.var == slot) { if (store != null) return null; store = v; }
		return store != null && count(m, member) == 1 && previous(store) instanceof MethodInsnNode c && member.equals(member(c)) ? store : null;
	}
	@SuppressWarnings("unchecked")
	static List<AnnotationNode>[] local(int params, int param, int... indexes) {
		List<AnnotationNode>[] result = new List[params];
		for (int n = 0; n < indexes.length; n++) {
			AnnotationNode local = new AnnotationNode("Lcom/llamalad7/mixinextras/sugar/Local;");
			local.values = new ArrayList<>(List.of("index", indexes[n])); result[param + n] = new ArrayList<>(List.of(local));
		}
		return result;
	}
	static MethodNode named(ClassNode c, String name) { return c.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElse(null); }
	static MethodNode selector(ClassNode c, String s) { return c.methods.stream().filter(m -> (m.name+m.desc).equals(s)).findFirst().orElse(null); }
	static String member(MethodInsnNode c) { return "L"+c.owner+";"+c.name+c.desc; }
	static int count(MethodNode m, String member) { int n=0;for(var i:m.instructions)if(i instanceof MethodInsnNode c && member.equals(member(c)))n++;return n; }
	static MethodInsnNode first(MethodNode m, String member) { for(var i:m.instructions)if(i instanceof MethodInsnNode c && member.equals(member(c)))return c;return null; }
	static int index(MethodNode m, AbstractInsnNode i) { return i == null ? -1 : m.instructions.indexOf(i); }
	static int constants(MethodNode m,int value) { int n=0;for(var i:m.instructions)if(i instanceof IntInsnNode c && c.operand==value)n++;return n; }
	static AbstractInsnNode next(AbstractInsnNode n) { do {n=n.getNext();}while(n!=null&&n.getOpcode()<0);return n; }
	static AbstractInsnNode previous(AbstractInsnNode n) { do {n=n.getPrevious();}while(n!=null&&n.getOpcode()<0);return n; }
	static void set(AnnotationNode a,String key,Object value) { if(a.values==null)a.values=new ArrayList<>();for(int i=0;i<a.values.size();i+=2)if(key.equals(a.values.get(i))){a.values.set(i+1,value);return;}a.values.add(key);a.values.add(value); }
	static void remove(AnnotationNode a,String key) { if(a.values==null)return;for(int i=0;i<a.values.size();i+=2)if(key.equals(a.values.get(i))){a.values.remove(i+1);a.values.remove(i);return;} }
}
