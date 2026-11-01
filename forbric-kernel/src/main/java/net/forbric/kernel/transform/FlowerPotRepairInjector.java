/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Makes NeoForge's {@code FlowerPotBlock(Block, Properties)} store its plant the way vanilla's does.
 *
 * <p>NeoForge's vanilla-shaped constructor puts the plant into a supplier and leaves vanilla's {@code potted} field null,
 * and registers nothing in vanilla's {@code POTTED_BY_CONTENT}: NeoForge derives its pot table from the registry at bake
 * time and reads the plant through {@code getPotted()}. A Fabric mod reads {@code potted} and {@code POTTED_BY_CONTENT}
 * as vanilla has them and finds nothing. The constructor now stores the plant in both, as vanilla does. This is not a
 * merge repair: NeoForge's own base stores null just the same.
 *
 * <p>The merged base also needed two edits of MinecraftForge's half of the class (its {@code useItemOn} lookup in
 * {@code fullPots} and its {@code addPlant}); NeoForge's base has no such half, and they went in Stage 4 with
 * {@code KernelFlowerPots}' lookup decorators. {@code -Dforbric.flowerPotRepair=off} leaves the class as NeoForge has it.
 */
public final class FlowerPotRepairInjector implements ClassTransformer {
	static final String TARGET = "net.minecraft.world.level.block.FlowerPotBlock";
	static final String OWNER = "net/minecraft/world/level/block/FlowerPotBlock";
	static final String BLOCK = "net/minecraft/world/level/block/Block";
	static final String BLOCK_CTOR = "(L" + BLOCK + ";Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;)V";
	static final String USE_ITEM_ON = "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/block/state/BlockState;"
			+ "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;"
			+ "Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;";
	static final String MAP = "java/util/Map";

	static boolean enabled() {
		return NativeCoremodParity.on(NativeCoremodParity.FLOWER_POT);
	}

	@Override public String name() { return "forbric-flower-pot-repair"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("flower pot repair explicitly disabled with -D" + NativeCoremodParity.FLOWER_POT + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"flower pots throw on pick-block, planting and taking a plant out, and accept no plant"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || !TARGET.equals(className) || bytes == null || bytes.length == 0) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		// The constructor's null is NeoForge's own, so the edit needs NeoForge's half: potted beside the plant's supplier.
		// Vanilla's class has no supplier and is not this repair's.
		if (!hasField(node, "potted", "L" + BLOCK + ";") || !hasField(node, "flowerDelegate", "Ljava/util/function/Supplier;")) return bytes;
		if (storePlant(node) == 0) {
			ForbricLog.warn("[Forbric/FlowerPot] left FlowerPotBlock as it is: its (Block, Properties) constructor is not the "
					+ "reviewed shape");
			return bytes;
		}
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/FlowerPot] FlowerPotBlock repaired: plant stored by the vanilla constructor");
		return writer.toByteArray();
	}

	/** {@code (Block, Properties)}: {@code potted = plant} instead of null, and {@code POTTED_BY_CONTENT.put(plant, this)}. */
	static int storePlant(ClassNode node) {
		MethodNode ctor = method(node, "<init>", BLOCK_CTOR);
		if (ctor == null) return 0;
		FieldInsnNode store = null;
		int stores = 0;
		for (AbstractInsnNode insn : ctor.instructions) {
			if (insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD && put.owner.equals(OWNER) && put.name.equals("potted")) {
				store = put;
				stores++;
			}
		}
		if (stores != 1 || store.getPrevious() == null || store.getPrevious().getOpcode() != Opcodes.ACONST_NULL) return 0;
		ctor.instructions.set(store.getPrevious(), new VarInsnNode(Opcodes.ALOAD, 1));
		if (hasField(node, "POTTED_BY_CONTENT", "L" + MAP + ";") && !references(ctor, "POTTED_BY_CONTENT")) {
			for (AbstractInsnNode insn : ctor.instructions.toArray()) {
				if (insn.getOpcode() != Opcodes.RETURN) continue;
				InsnList put = new InsnList();
				put.add(new FieldInsnNode(Opcodes.GETSTATIC, OWNER, "POTTED_BY_CONTENT", "L" + MAP + ";"));
				put.add(new VarInsnNode(Opcodes.ALOAD, 1));
				put.add(new VarInsnNode(Opcodes.ALOAD, 0));
				put.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
				put.add(new InsnNode(Opcodes.POP));
				ctor.instructions.insertBefore(insn, put);
			}
		}
		return 1;
	}

	private static boolean references(MethodNode method, String field) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode access && access.owner.equals(OWNER) && access.name.equals(field)) return true;
		}
		return false;
	}

	private static boolean hasField(ClassNode node, String name, String desc) {
		for (FieldNode field : node.fields) if (field.name.equals(name) && field.desc.equals(desc)) return true;
		return false;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}
}
