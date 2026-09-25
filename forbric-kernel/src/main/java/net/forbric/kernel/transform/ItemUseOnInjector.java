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
 * <p>Each edit checks the reviewed shape and declines on anything else: {@code useOn} must be MinecraftForge's (no
 * {@code Item.useOn}, no {@code UseItemOnBlockEvent}, one {@code onPlaceItemIntoWorld}, one lambda whose body is
 * exactly {@code getItem().useOn(context)}); {@code onPlaceItemIntoWorld} must make exactly one {@code Item.useOn}
 * call. Both families' {@code onPlaceItemIntoWorld} are relayed, NeoForge's because useOn calls it and MinecraftForge's
 * because a mod may call it itself. On NeoForge's or vanilla's body nothing is changed. {@code -Dforbric.itemUseOn=off}
 * leaves all three classes alone.
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
				new AnchorSet.Anchor(FORGE_HOOKS, AnchorSet.Severity.REQUIRED, "fabric-api's ItemEvents.USE_ON never fires when a mod "
						+ "calls MinecraftForge's onPlaceItemIntoWorld itself"));
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
		ForbricLog.info(stack
				? "[Forbric/Item] ItemStack.useOn posts NeoForge's UseItemOnBlockEvent (ITEM_AFTER_BLOCK) again, and its "
						+ "Item.useOn call is one Fabric's ItemEvents.USE_ON can wrap"
				: "[Forbric/Item] " + className.substring(className.lastIndexOf('.') + 1) + ".onPlaceItemIntoWorld calls Item.useOn "
						+ "through ItemStack, where Fabric's ItemEvents.USE_ON wraps it");
		return writer.toByteArray();
	}

	/** ItemStack: the NeoForge post at the head of useOn, the relay, its static bridge, and the lambda through the relay. */
	static int adaptStack(ClassNode stack) {
		MethodNode useOn = method(stack, "useOn", USE_ON_DESC);
		if (useOn == null) return declined("ItemStack.useOn(UseOnContext) is missing");
		if (method(stack, RELAY, RELAY_DESC) != null) return 0;   // already done
		MethodNode lambda = null;
		int placeCalls = 0;
		for (AbstractInsnNode insn : useOn.instructions) {
			if (insn instanceof MethodInsnNode call) {
				if (call.owner.equals(ITEM) && call.name.equals("useOn")) return 0;   // vanilla's or a vanilla-shaped body
				if (call.name.equals("onPlaceItemIntoWorld")) placeCalls++;
				if (call.owner.equals(EVENT)) return 0;                            // NeoForge's body
			}
			if (insn instanceof TypeInsnNode type && type.desc.equals(EVENT)) return 0;
			if (insn instanceof InvokeDynamicInsnNode indy) {
				if (lambda != null) return declined("ItemStack.useOn makes more than one lambda");
				lambda = implementation(stack, indy);
				if (lambda == null) return declined("ItemStack.useOn's lambda is not one of its own methods");
			}
		}
		if (placeCalls != 1 || lambda == null) return declined("ItemStack.useOn is not MinecraftForge's reviewed body");
		for (AbstractInsnNode insn : useOn.instructions) {
			if (insn.getOpcode() >= 0) break;
			if (insn instanceof FrameNode) return declined("ItemStack.useOn's first instruction is a branch target");
		}
		MethodInsnNode relayed = onlyUseOnCall(lambda);
		if (relayed == null || !lambdaShape(lambda, relayed)) return declined("ItemStack.useOn's lambda is not getItem().useOn(context)");

		stack.methods.add(relay());
		stack.methods.add(bridge());
		lambda.instructions.insert(new VarInsnNode(Opcodes.ALOAD, 0));
		lambda.instructions.set(relayed, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, STACK_INTERNAL, RELAY, RELAY_DESC, false));
		useOn.instructions.insert(neoForgePost());
		return 1;
	}

	/** Either family's hooks: onPlaceItemIntoWorld's one Item.useOn call goes through ItemStack's static bridge. */
	static int relayServerCall(ClassNode hooks) {
		String owner = hooks.name.substring(hooks.name.lastIndexOf('/') + 1);
		MethodNode place = method(hooks, "onPlaceItemIntoWorld", USE_ON_DESC);
		if (place == null) return declined(owner + ".onPlaceItemIntoWorld(UseOnContext) is missing");
		List<MethodInsnNode> calls = new ArrayList<>();
		for (AbstractInsnNode insn : place.instructions) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			if (call.owner.equals(STACK_INTERNAL) && call.name.equals(BRIDGE)) return 0;   // already done
			if (call.owner.equals(ITEM) && call.name.equals("useOn") && call.desc.equals(USE_ON_DESC)) calls.add(call);
		}
		if (calls.size() != 1 || calls.getFirst().getOpcode() != Opcodes.INVOKEVIRTUAL) {
			return declined(owner + ".onPlaceItemIntoWorld does not make exactly one Item.useOn call");
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

	private static int declined(String reason) {
		ForbricLog.warn("[Forbric/Item] left ItemStack.useOn as merged: %s — NeoForge's ITEM_AFTER_BLOCK phase and Fabric's "
				+ "ItemEvents.USE_ON stay silent", reason);
		return -1;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}
}
