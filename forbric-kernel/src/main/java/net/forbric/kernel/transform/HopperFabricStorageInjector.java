/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * NeoForge's hopper asks Fabric's item storage lookup where its own found nothing.
 *
 * <p>fabric-transfer-api-v1's {@code HopperBlockEntityMixin} anchors after vanilla's {@code getAttachedContainer} and
 * {@code getSourceContainer}; the merged hopper is NeoForge's, which looks up a {@code ContainerOrHandler} instead and
 * calls neither, so a Fabric storage NeoForge cannot see (unslotted, on a block without a block entity, or any with
 * the transfer bridge off) never saw a hopper. Two edits, both on NeoForge's "found nothing" branches:
 * <ul>
 * <li>{@code ejectItems}: the {@code iconst_0} it returns when the {@code ContainerOrHandler} is empty becomes
 * {@code KernelFabricHopperStorage.insert(level, pos, hopper)} — false unless a Fabric storage took an item;</li>
 * <li>{@code suckInItems}: where it falls through from an empty {@code ContainerOrHandler} to picking up item
 * entities, {@code KernelFabricHopperStorage.extract(level, hopper)} is asked first; a Fabric storage's answer is
 * returned, {@code NOT_FOUND} continues as before.</li>
 * </ul>
 * No local, field read, or call to a hopper method is added, so every other injector's anchors and ordinals are
 * unchanged. Both edits or neither; a vanilla or MinecraftForge body, where Fabric's own mixin applies, is left alone.
 * {@code -Dforbric.hopperFabricStorage=off}.
 */
public final class HopperFabricStorageInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.hopperFabricStorage";
	static final String HOPPER = "net.minecraft.world.level.block.entity.HopperBlockEntity";
	static final String HOPPER_INTERNAL = "net/minecraft/world/level/block/entity/HopperBlockEntity";
	static final String RUNTIME = "net/forbric/kernel/runtime/transfer/KernelFabricHopperStorage";
	static final String CONTAINER_OR_HANDLER = "net/neoforged/neoforge/transfer/item/ContainerOrHandler";
	static final String HOOKS = "net/neoforged/neoforge/transfer/item/VanillaInventoryCodeHooks";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric:hopper-fabric-storage"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("hoppers left as merged with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(HOPPER, AnchorSet.Severity.REQUIRED,
				"hoppers ignore Fabric item storages NeoForge cannot see — unslotted, block-only, or all of them with the "
						+ "transfer bridge off"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !HOPPER.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		String declined = repair(node);
		if (declined != null) {
			if (!declined.isEmpty()) {
				ForbricLog.warn("[Forbric/Hopper] left HopperBlockEntity as merged: %s — hoppers ignore Fabric storages "
						+ "NeoForge cannot see", declined);
			}
			return bytes;
		}
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Hopper] HopperBlockEntity.ejectItems and suckInItems ask Fabric's item storage lookup "
				+ "where NeoForge's found nothing (fabric-transfer-api-v1's HopperBlockEntityMixin cannot attach to "
				+ "NeoForge's body)");
		return writer.toByteArray();
	}

	/** Null when both edits were made; "" when there is nothing to do (a carrier's own body, or already done); else why not. */
	static String repair(ClassNode hopper) {
		MethodNode eject = method(hopper, "ejectItems", "(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;L" + HOPPER_INTERNAL + ";)Z");
		MethodNode suck = method(hopper, "suckInItems", "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/entity/Hopper;)Z");
		if (eject == null || suck == null) return "no ejectItems/suckInItems";
		if (calls(eject, RUNTIME, null) + calls(suck, RUNTIME, null) > 0) return "";
		if (calls(eject, HOPPER_INTERNAL, "getAttachedContainer") > 0 || calls(suck, HOPPER_INTERNAL, "getSourceContainer") > 0) return "";
		if (calls(eject, HOPPER_INTERNAL, "getContainerOrHandlerAt") != 1 || calls(eject, HOOKS, "insertHook") != 1) {
			return "ejectItems is not NeoForge's one lookup and one insert hook";
		}
		if (calls(suck, HOPPER_INTERNAL, "getSourceContainerOrHandler") != 1 || calls(suck, HOOKS, "extractHook") != 1) {
			return "suckInItems is not NeoForge's one lookup and one extract hook";
		}
		// ejectItems: isEmpty(); ifeq L; iconst_0; ireturn — the one found-nothing exit.
		AbstractInsnNode nothing = null;
		for (AbstractInsnNode insn : eject.instructions) {
			if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(CONTAINER_OR_HANDLER) || !call.name.equals("isEmpty")) continue;
			AbstractInsnNode branch = next(call), zero = next(branch), exit = next(zero);
			if (branch == null || branch.getOpcode() != Opcodes.IFEQ || zero == null || zero.getOpcode() != Opcodes.ICONST_0
					|| exit == null || exit.getOpcode() != Opcodes.IRETURN) continue;
			if (nothing != null) return "ejectItems has two found-nothing exits";
			nothing = zero;
		}
		if (nothing == null) return "ejectItems has no found-nothing exit";
		// suckInItems: itemHandler(); ifnull L … L: frame; aload_1; invokeinterface Hopper.isGridAligned — the fall
		// through to picking up item entities.
		JumpInsnNode noHandler = null;
		for (AbstractInsnNode insn : suck.instructions) {
			if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(CONTAINER_OR_HANDLER) || !call.name.equals("itemHandler")) continue;
			if (!(next(call) instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFNULL) continue;
			if (noHandler != null) return "suckInItems has two handler checks";
			noHandler = jump;
		}
		if (noHandler == null) return "suckInItems has no handler check";
		LabelNode target = noHandler.label;
		FrameNode frame = null;
		AbstractInsnNode scan = target.getNext();
		while (scan != null && scan.getOpcode() < 0) {
			if (scan instanceof FrameNode f) frame = f;
			scan = scan.getNext();
		}
		if (frame == null || !(scan instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 1
				|| !(next(scan) instanceof MethodInsnNode aligned) || !aligned.name.equals("isGridAligned")) {
			return "suckInItems does not fall through to picking up items after its handler check";
		}

		InsnList insert = new InsnList();
		insert.add(new VarInsnNode(Opcodes.ALOAD, 0));
		insert.add(new VarInsnNode(Opcodes.ALOAD, 1));
		insert.add(new VarInsnNode(Opcodes.ALOAD, 2));
		insert.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "insert", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z", false));
		eject.instructions.insertBefore(nothing, insert);
		eject.instructions.remove(nothing);

		LabelNode carryOn = new LabelNode();
		InsnList extract = new InsnList();
		extract.add(new VarInsnNode(Opcodes.ALOAD, 0));
		extract.add(new VarInsnNode(Opcodes.ALOAD, 1));
		extract.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "extract", "(Ljava/lang/Object;Ljava/lang/Object;)I", false));
		extract.add(new InsnNode(Opcodes.DUP));
		extract.add(new JumpInsnNode(Opcodes.IFLT, carryOn));
		extract.add(new InsnNode(Opcodes.IRETURN));
		extract.add(carryOn);
		extract.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[] {Opcodes.INTEGER}));
		extract.add(new InsnNode(Opcodes.POP));
		suck.instructions.insert(frame, extract);
		return null;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}

	private static int calls(MethodNode method, String owner, String name) {
		int n = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && (name == null || call.name.equals(name))) n++;
		}
		return n;
	}

	private static AbstractInsnNode next(AbstractInsnNode insn) {
		AbstractInsnNode next = insn == null ? null : insn.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		return next;
	}
}
