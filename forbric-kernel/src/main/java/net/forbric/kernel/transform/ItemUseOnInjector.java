/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * {@code ItemStack.useOn} again does what both NeoForge's and Fabric's code expect it to do.
 *
 * <p>The merge kept MinecraftForge's body: on a server it returns {@code onPlaceItemIntoWorld(context)} (NeoForge's
 * {@code CommonHooks} one since the kernel's {@code routePlaceItemHookToNeoForge}, whose block snapshots it drains), on
 * a client {@code onItemUse(context, c -> getItem().useOn(context))}. Vanilla's body calls {@code Item.useOn}
 * itself, and NeoForge's first posts {@code UseItemOnBlockEvent} in its {@code ITEM_AFTER_BLOCK} phase and returns its
 * result when a listener cancels. Two things were lost with that:
 *
 * <ul>
 *   <li>NeoForge's {@code ITEM_AFTER_BLOCK} phase was never posted (its {@code ITEM_BEFORE_BLOCK} phase, in
 *       {@code onItemUseFirst}, and the {@code BLOCK} phase survived). NeoForge's exact post sequence is put back at
 *       the head of the body, where NeoForge has it.</li>
 *   <li>fabric-api fires {@code ItemEvents.USE_ON} from a {@code @WrapOperation} on the {@code Item.useOn} call in
 *       {@code useOn}, and MinecraftForge's body makes that call elsewhere: in {@code onPlaceItemIntoWorld} and in the
 *       client lambda. Both calls now go through one ItemStack method,
 *       {@code forbric$useOnItem(Item, UseOnContext)}, whose whole body is that call, and
 *       {@code MixinRelocatedCall} points an injector written for {@code useOn}'s {@code Item.useOn} there. The
 *       server's call reaches it through a static bridge on the stack the context holds, which is the stack
 *       {@code onPlaceItemIntoWorld} itself read the item from.</li>
 * </ul>
 *
 * <p>NeoForge's own body — the genuine base's, which Stage 4 switches to — already posts {@code ITEM_AFTER_BLOCK} at its
 * head and otherwise is MinecraftForge's body with NeoForge's hook: one {@code onPlaceItemIntoWorld} and the same client
 * lambda. Fabric's wrap is lost there just the same, so it is routed the same way; only the post is not put back, because
 * it is already there and a second one would fire every listener twice.
 *
 * <p>The bridge is added to every {@code ItemStack}, whatever its body; the relay only with the {@code useOn} edit.
 * {@link #relayServerCall} points each family's {@code onPlaceItemIntoWorld} at the bridge without seeing
 * {@code ItemStack} — the two classes are transformed whenever each happens to load — so the only way the two halves
 * cannot come apart is for the bridge's presence never to depend on {@code useOn}'s shape. Its body does: on a body this
 * routes it goes through the relay, and on any other (vanilla's, or one an edit below stood down on) it makes the
 * {@code Item.useOn} call itself and the class gets no relay at all. MixinRelocatedCall moves an injector only onto a
 * relay the class declares, so nothing moves Fabric's wrap onto a body that was not reviewed: on a declined body the
 * server's call would reach a relay the client's never does, half a repair the decline's warning says is not there.
 * Before this the bridge came only with the {@code useOn} edit, and on NeoForge's body, where that edit stood down,
 * {@code CommonHooks} was still pointed at it: a {@code NoSuchMethodError} on every item used on a block.
 *
 * <p>Each {@code useOn} edit checks the reviewed shape and declines on anything else: {@code useOn} must be
 * MinecraftForge's or NeoForge's (no {@code Item.useOn}, one {@code onPlaceItemIntoWorld}, one lambda whose body is
 * exactly {@code getItem().useOn(context)}); {@code onPlaceItemIntoWorld} must make exactly one {@code Item.useOn}
 * call. Both families' {@code onPlaceItemIntoWorld} are relayed, NeoForge's because useOn calls it and MinecraftForge's
 * because a mod may call it itself — MinecraftForge's is a HEDGE, there only while its carrier is. On vanilla's body
 * {@code useOn} is not changed. {@code -Dforbric.itemUseOn=off} leaves all three classes alone.
 */
public final class ItemUseOnInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.itemUseOn";
	static final String STACK = "net.minecraft.world.item.ItemStack";
	static final String FORGE_HOOKS = "net.minecraftforge.common.ForgeHooks";
	static final String NEO_HOOKS = "net.neoforged.neoforge.common.CommonHooks";
	static final String STACK_INTERNAL = "net/minecraft/world/item/ItemStack";
	static final String ITEM = "net/minecraft/world/item/Item";
	static final String CONTEXT = "net/minecraft/world/item/context/UseOnContext";
	static final String RESULT = "net/minecraft/world/InteractionResult";
	static final String USE_ON_DESC = "(L" + CONTEXT + ";)L" + RESULT + ";";
	/** The one method both relocated calls go through; a guest injector written for useOn's call lands here. */
	public static final String RELAY = "forbric$useOnItem";
	public static final String RELAY_DESC = "(L" + ITEM + ";L" + CONTEXT + ";)L" + RESULT + ";";
	static final String BRIDGE = "forbric$useOnItemFor";
	static final String EVENT = "net/neoforged/neoforge/event/entity/player/UseItemOnBlockEvent";
	static final String PHASE = EVENT + "$UsePhase";
	static final String BUS = "net/neoforged/bus/api/IEventBus";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-item-use-on"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("ItemStack.useOn explicitly left as merged with -D" + PROPERTY + "=off");
		return AnchorSet.of(
				new AnchorSet.Anchor(STACK, AnchorSet.Severity.REQUIRED, "NeoForge's UseItemOnBlockEvent ITEM_AFTER_BLOCK phase "
						+ "and fabric-api's ItemEvents.USE_ON never fire"),
				new AnchorSet.Anchor(NEO_HOOKS, AnchorSet.Severity.REQUIRED, "fabric-api's ItemEvents.USE_ON never fires on a server"),
				// MinecraftForge's hooks exist only while its carrier does; NeoForge's genuine base has none to relay.
				new AnchorSet.Anchor(FORGE_HOOKS, AnchorSet.Severity.HEDGE, "fabric-api's ItemEvents.USE_ON never fires when a mod "
						+ "calls MinecraftForge's onPlaceItemIntoWorld itself"));
	}

	/** The {@code useOn} edit on its own line: the class anchor alone is hit by the bridge, which always goes in. */
	static final String ROUTED_CLAIM = "forbric-item-use-on#useOnCallRouted";

	@Override public List<Claim> claims() {
		if (!enabled()) return List.of();
		return List.of(new Claim(ROUTED_CLAIM, AnchorSet.of(new AnchorSet.Anchor(STACK, AnchorSet.Severity.REQUIRED,
				"fabric-api's ItemEvents.USE_ON never fires, and on MinecraftForge's body NeoForge's ITEM_AFTER_BLOCK phase neither"))));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context, ClaimReporter reporter) {
		byte[] result = transform(className, bytes, context);
		if (enabled() && STACK.equals(className) && result != null && result.length > 0 && routed(result)) reporter.hit(ROUTED_CLAIM);
		return result;
	}

	/**
	 * Whether {@code useOn} makes no {@code Item.useOn} call itself and its client lambda reaches the item through the
	 * relay: the end state, whichever pass made it.
	 */
	static boolean routed(byte[] stackBytes) {
		ClassNode stack = new ClassNode();
		new ClassReader(stackBytes).accept(stack, 0);
		MethodNode useOn = method(stack, "useOn", USE_ON_DESC);
		if (useOn == null || method(stack, RELAY, RELAY_DESC) == null) return false;
		boolean throughRelay = false;
		for (AbstractInsnNode insn : useOn.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(ITEM) && call.name.equals("useOn")) return false;
			MethodNode lambda = insn instanceof InvokeDynamicInsnNode indy ? implementation(stack, indy) : null;
			if (lambda == null) continue;
			for (AbstractInsnNode inner : lambda.instructions) {
				throughRelay |= inner instanceof MethodInsnNode call && call.owner.equals(STACK_INTERNAL) && call.name.equals(RELAY)
						&& call.desc.equals(RELAY_DESC);
			}
		}
		return throughRelay;
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0
				|| !(STACK.equals(className) || FORGE_HOOKS.equals(className) || NEO_HOOKS.equals(className))) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		boolean stack = STACK.equals(className);
		int changed = stack ? adaptStack(node) : relayServerCall(node);
		if (changed <= 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		if (!stack) {
			ForbricLog.info("[Forbric/Item] " + className.substring(className.lastIndexOf('.') + 1) + ".onPlaceItemIntoWorld calls Item.useOn "
					+ "through ItemStack, where Fabric's ItemEvents.USE_ON wraps it");
		} else if (changed == POSTED_AND_ROUTED) {
			ForbricLog.info("[Forbric/Item] ItemStack.useOn posts NeoForge's UseItemOnBlockEvent (ITEM_AFTER_BLOCK) again, and its "
					+ "Item.useOn call is one Fabric's ItemEvents.USE_ON can wrap");
		} else if (changed == ROUTED) {
			ForbricLog.info("[Forbric/Item] ItemStack.useOn is NeoForge's, which posts UseItemOnBlockEvent (ITEM_AFTER_BLOCK) itself; "
					+ "its Item.useOn call is now one Fabric's ItemEvents.USE_ON can wrap");
		}
		return writer.toByteArray();
	}

	/** {@link #adaptStack}: only the bridge went in, calling {@code Item.useOn} itself; {@code useOn} is as it was. */
	static final int BRIDGE_ONLY = 1;
	/** {@link #adaptStack}: NeoForge's body, which posts its event itself; its lambda now calls the relay. */
	static final int ROUTED = 2;
	/** {@link #adaptStack}: MinecraftForge's body; NeoForge's post went in at its head and its lambda calls the relay. */
	static final int POSTED_AND_ROUTED = 3;

	/**
	 * ItemStack: the static bridge always. On MinecraftForge's or NeoForge's body the relay with it, the bridge and the
	 * lambda going through the relay, and on MinecraftForge's the NeoForge post at the head of useOn; on any other body
	 * a bridge that calls {@code Item.useOn} itself, and no relay. Returns 0 when already done, else
	 * {@link #BRIDGE_ONLY}, {@link #ROUTED} or {@link #POSTED_AND_ROUTED}.
	 */
	static int adaptStack(ClassNode stack) {
		if (method(stack, BRIDGE, RELAY_DESC) != null) return 0;   // already done
		int routed = route(stack);
		if (routed == BRIDGE_ONLY) {
			stack.methods.add(directBridge());
			return BRIDGE_ONLY;
		}
		stack.methods.add(relay());
		stack.methods.add(bridge());
		return routed;
	}

	/**
	 * The {@code useOn} edit alone: on the reviewed bodies the lambda through the relay {@link #adaptStack} then adds
	 * and, on MinecraftForge's, the post; on any other body nothing at all, and {@link #BRIDGE_ONLY}.
	 */
	private static int route(ClassNode stack) {
		MethodNode useOn = method(stack, "useOn", USE_ON_DESC);
		if (useOn == null) return declined("ItemStack.useOn(UseOnContext) is missing");
		MethodNode lambda = null;
		int placeCalls = 0;
		boolean posted = false;
		for (AbstractInsnNode insn : useOn.instructions) {
			if (insn instanceof MethodInsnNode call) {
				if (call.owner.equals(ITEM) && call.name.equals("useOn")) return BRIDGE_ONLY;   // vanilla's or a vanilla-shaped body
				if (call.name.equals("onPlaceItemIntoWorld")) placeCalls++;
				if (call.owner.equals(EVENT)) posted = true;                                       // NeoForge's body
			}
			if (insn instanceof TypeInsnNode type && type.desc.equals(EVENT)) posted = true;
			if (insn instanceof InvokeDynamicInsnNode indy) {
				if (lambda != null) return declined("ItemStack.useOn makes more than one lambda");
				lambda = implementation(stack, indy);
				if (lambda == null) return declined("ItemStack.useOn's lambda is not one of its own methods");
			}
		}
		if (placeCalls != 1 || lambda == null) {
			return declined("ItemStack.useOn is not MinecraftForge's or NeoForge's reviewed body");
		}
		if (!posted) {
			for (AbstractInsnNode insn : useOn.instructions) {
				if (insn.getOpcode() >= 0) break;
				if (insn instanceof FrameNode) return declined("ItemStack.useOn's first instruction is a branch target");
			}
		}
		MethodInsnNode relayed = onlyUseOnCall(lambda);
		if (relayed == null || !lambdaShape(lambda, relayed)) return declined("ItemStack.useOn's lambda is not getItem().useOn(context)");

		lambda.instructions.insert(new VarInsnNode(Opcodes.ALOAD, 0));
		lambda.instructions.set(relayed, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, STACK_INTERNAL, RELAY, RELAY_DESC, false));
		if (posted) return ROUTED;
		useOn.instructions.insert(neoForgePost());
		return POSTED_AND_ROUTED;
	}

	/**
	 * Either family's hooks: onPlaceItemIntoWorld's one Item.useOn call goes through ItemStack's static bridge — which
	 * {@link #adaptStack} adds to every ItemStack, through the relay or not, so this edit never points at a member the
	 * game lacks.
	 */
	static int relayServerCall(ClassNode hooks) {
		String owner = hooks.name.substring(hooks.name.lastIndexOf('/') + 1);
		MethodNode place = method(hooks, "onPlaceItemIntoWorld", USE_ON_DESC);
		if (place == null) return hooksDeclined(owner, "there is no onPlaceItemIntoWorld(UseOnContext)");
		List<MethodInsnNode> calls = new ArrayList<>();
		for (AbstractInsnNode insn : place.instructions) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			if (call.owner.equals(STACK_INTERNAL) && call.name.equals(BRIDGE)) return 0;   // already done
			if (call.owner.equals(ITEM) && call.name.equals("useOn") && call.desc.equals(USE_ON_DESC)) calls.add(call);
		}
		if (calls.size() != 1 || calls.getFirst().getOpcode() != Opcodes.INVOKEVIRTUAL) {
			return hooksDeclined(owner, "it does not make exactly one Item.useOn call");
		}
		place.instructions.set(calls.getFirst(), new MethodInsnNode(Opcodes.INVOKESTATIC, STACK_INTERNAL, BRIDGE, RELAY_DESC, false));
		return 1;
	}

	/** {@code public InteractionResult forbric$useOnItem(Item item, UseOnContext context) { return item.useOn(context); }} */
	static MethodNode relay() {
		MethodNode relay = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC, RELAY, RELAY_DESC, null, null);
		relay.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		relay.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
		relay.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM, "useOn", USE_ON_DESC, false));
		relay.instructions.add(new InsnNode(Opcodes.ARETURN));
		relay.maxStack = 2;
		relay.maxLocals = 3;
		return relay;
	}

	/** {@code public static InteractionResult forbric$useOnItemFor(Item item, UseOnContext c) { return c.getItemInHand().forbric$useOnItem(item, c); }} */
	static MethodNode bridge() {
		MethodNode bridge = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, BRIDGE,
				RELAY_DESC, null, null);
		bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CONTEXT, "getItemInHand", "()L" + STACK_INTERNAL + ";", false));
		bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, STACK_INTERNAL, RELAY, RELAY_DESC, false));
		bridge.instructions.add(new InsnNode(Opcodes.ARETURN));
		bridge.maxStack = 3;
		bridge.maxLocals = 2;
		return bridge;
	}

	/**
	 * {@code public static InteractionResult forbric$useOnItemFor(Item item, UseOnContext c) { return item.useOn(c); }}: the
	 * bridge of a body that is not routed. It makes the call {@code onPlaceItemIntoWorld} made, on the same receiver with
	 * the same argument, and leaves no relay for an injector to be moved onto.
	 */
	static MethodNode directBridge() {
		MethodNode bridge = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, BRIDGE,
				RELAY_DESC, null, null);
		bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM, "useOn", USE_ON_DESC, false));
		bridge.instructions.add(new InsnNode(Opcodes.ARETURN));
		bridge.maxStack = 2;
		bridge.maxLocals = 2;
		return bridge;
	}

	/**
	 * NeoForge's head of useOn, instruction for instruction: post {@code UseItemOnBlockEvent(context, ITEM_AFTER_BLOCK)}
	 * and return its cancellation result when a listener cancelled it. The event is kept on the stack, not in a local,
	 * so no slot of MinecraftForge's body is disturbed.
	 */
	static InsnList neoForgePost() {
		InsnList post = new InsnList();
		LabelNode proceed = new LabelNode();
		post.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/neoforged/neoforge/common/NeoForge", "EVENT_BUS", "L" + BUS + ";"));
		post.add(new TypeInsnNode(Opcodes.NEW, EVENT));
		post.add(new InsnNode(Opcodes.DUP));
		post.add(new VarInsnNode(Opcodes.ALOAD, 1));
		post.add(new FieldInsnNode(Opcodes.GETSTATIC, PHASE, "ITEM_AFTER_BLOCK", "L" + PHASE + ";"));
		post.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, EVENT, "<init>", "(L" + CONTEXT + ";L" + PHASE + ";)V", false));
		post.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, BUS, "post", "(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;", true));
		post.add(new TypeInsnNode(Opcodes.CHECKCAST, EVENT));
		post.add(new InsnNode(Opcodes.DUP));
		post.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EVENT, "isCanceled", "()Z", false));
		LabelNode cancelled = new LabelNode();
		post.add(new JumpInsnNode(Opcodes.IFNE, cancelled));
		post.add(new InsnNode(Opcodes.POP));
		post.add(new JumpInsnNode(Opcodes.GOTO, proceed));
		post.add(cancelled);
		post.add(new FrameNode(Opcodes.F_FULL, 2, new Object[] { STACK_INTERNAL, CONTEXT }, 1, new Object[] { EVENT }));
		post.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, EVENT, "getCancellationResult", "()L" + RESULT + ";", false));
		post.add(new InsnNode(Opcodes.ARETURN));
		post.add(proceed);
		post.add(new FrameNode(Opcodes.F_FULL, 2, new Object[] { STACK_INTERNAL, CONTEXT }, 0, new Object[0]));
		return post;
	}

	/** The ItemStack method an invokedynamic's LambdaMetafactory handle names, or null. */
	private static MethodNode implementation(ClassNode stack, InvokeDynamicInsnNode indy) {
		if (indy.bsmArgs == null || indy.bsmArgs.length < 2 || !(indy.bsmArgs[1] instanceof Handle handle)) return null;
		if (!handle.getOwner().equals(stack.name)) return null;
		return method(stack, handle.getName(), handle.getDesc());
	}

	private static MethodInsnNode onlyUseOnCall(MethodNode lambda) {
		MethodInsnNode only = null;
		for (AbstractInsnNode insn : lambda.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(ITEM) && call.name.equals("useOn") && call.desc.equals(USE_ON_DESC)) {
				if (only != null) return null;
				only = call;
			}
		}
		return only;
	}

	/** Exactly {@code aload_0; getItem(); aload_n; Item.useOn; areturn} in an instance lambda taking contexts. */
	private static boolean lambdaShape(MethodNode lambda, MethodInsnNode useOn) {
		if ((lambda.access & Opcodes.ACC_STATIC) != 0) return false;
		List<AbstractInsnNode> real = new ArrayList<>();
		for (AbstractInsnNode insn : lambda.instructions) if (insn.getOpcode() >= 0) real.add(insn);
		return real.size() == 5
				&& real.get(0) instanceof VarInsnNode self && self.getOpcode() == Opcodes.ALOAD && self.var == 0
				&& real.get(1) instanceof MethodInsnNode getItem && getItem.owner.equals(STACK_INTERNAL) && getItem.name.equals("getItem")
				&& real.get(2) instanceof VarInsnNode context && context.getOpcode() == Opcodes.ALOAD && context.var >= 1
				&& real.get(3) == useOn && useOn.getOpcode() == Opcodes.INVOKEVIRTUAL
				&& real.get(4).getOpcode() == Opcodes.ARETURN;
	}

	/** A {@code useOn} edit stood down before it changed anything: only the bridge that calls Item.useOn itself goes in. */
	private static int declined(String reason) {
		ForbricLog.warn("[Forbric/Item] left ItemStack.useOn as merged: %s — NeoForge's ITEM_AFTER_BLOCK phase and Fabric's "
				+ "ItemEvents.USE_ON stay silent", reason);
		return BRIDGE_ONLY;
	}

	/** {@link #relayServerCall} stood down: nothing in the hooks class changes. */
	private static int hooksDeclined(String owner, String reason) {
		ForbricLog.warn("[Forbric/Item] left %s.onPlaceItemIntoWorld as it is: %s — Fabric's ItemEvents.USE_ON stays silent "
				+ "when it runs", owner, reason);
		return -1;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}
}
