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
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Makes the merged {@code FlowerPotBlock} accept, give back and name its plant for every family.
 *
 * <p>The merged class is three halves. Its constructors are NeoForge's: the plant goes into a supplier, the
 * {@code potted} field is left null, and nothing registers the pot anywhere, because NeoForge derives its pot table
 * from the registry at bake time. Its {@code useItemOn} is MinecraftForge's: it looks the plant up in the empty pot's
 * {@code fullPots} map, which only MinecraftForge's constructor and {@code addPlant} filled. And its {@code addPlant}
 * is NeoForge's, which only checks its argument. Together with NeoForge's field-to-getter coremod never running
 * (NativeCoremodParity), every pot threw on pick-block, planting and taking a plant out.
 *
 * <p>Three edits, each stood down on its own when the method is not the reviewed shape:
 * <ul>
 *   <li>the vanilla-shaped constructor {@code (Block, Properties)} stores its plant in {@code potted} and in
 *       {@code POTTED_BY_CONTENT}, as vanilla does — Fabric mods read both;</li>
 *   <li>{@code useItemOn}'s MinecraftForge lookup asks KernelFlowerPots instead, which answers from the explicit
 *       {@code addPlant} entries first and then NeoForge's table;</li>
 *   <li>{@code addPlant} records its entry in {@code fullPots}, as MinecraftForge's does.</li>
 * </ul>
 * The table itself is filled when the registration window closes (KernelFlowerPots.rebuildTable).
 * {@code -Dforbric.flowerPotRepair=off} leaves the class as merged.
 */
public final class FlowerPotRepairInjector implements ClassTransformer {
	static final String TARGET = "net.minecraft.world.level.block.FlowerPotBlock";
	static final String OWNER = "net/minecraft/world/level/block/FlowerPotBlock";
	static final String BLOCK = "net/minecraft/world/level/block/Block";
	static final String BLOCK_CTOR = "(L" + BLOCK + ";Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;)V";
	static final String USE_ITEM_ON = "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/block/state/BlockState;"
			+ "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;"
			+ "Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;";
	static final String ADD_PLANT = "(Lnet/minecraft/resources/Identifier;Ljava/util/function/Supplier;)V";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelFlowerPots";
	static final String FULL_POT_FOR = "(Ljava/util/Map;L" + OWNER + ";L" + BLOCK + ";)L" + BLOCK + ";";
	static final String FORGE_BLOCKS = "net/minecraftforge/registries/ForgeRegistries";
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
		if (!hasField(node, "potted", "L" + BLOCK + ";") || !hasField(node, "fullPots", "L" + MAP + ";")) {
			return bytes;   // not the merged class this is about (vanilla's has neither the supplier nor fullPots)
		}
		int constructor = storePlant(node), lookup = lookUpAllFamilies(node), addPlant = recordAddedPlant(node);
		if (constructor + lookup + addPlant == 0) {
			ForbricLog.warn("[Forbric/FlowerPot] left FlowerPotBlock as merged: none of its constructor, useItemOn or addPlant "
					+ "is the reviewed shape");
			return bytes;
		}
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/FlowerPot] FlowerPotBlock repaired: plant stored by the vanilla constructor %s, lookup over "
				+ "every family's pots %s, addPlant recorded %s", yes(constructor), yes(lookup), yes(addPlant));
		return writer.toByteArray();
	}

	private static String yes(int edits) {
		return edits > 0 ? "yes" : "NO";
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

	/**
	 * In {@code useItemOn}, the MinecraftForge expression after {@code getEmptyPot().fullPots} — registry key, air
	 * delegate, {@code getOrDefault}, supplier, cast — becomes {@code KernelFlowerPots.fullPotFor(fullPots, this, block)}.
	 */
	static int lookUpAllFamilies(ClassNode node) {
		MethodNode use = method(node, "useItemOn", USE_ITEM_ON);
		if (use == null) return 0;
		// Real instructions only: a line-number label may sit inside the expression.
		AbstractInsnNode[] code = java.util.Arrays.stream(use.instructions.toArray()).filter(insn -> insn.getOpcode() >= 0)
				.toArray(AbstractInsnNode[]::new);
		for (int i = 0; i < code.length; i++) {
			if (!(code[i] instanceof FieldInsnNode full) || full.getOpcode() != Opcodes.GETFIELD || !full.name.equals("fullPots")) continue;
			// fullPots, then: GETSTATIC BLOCKS, ALOAD item, INVOKEVIRTUAL getBlock, getKey, GETSTATIC BLOCKS, GETSTATIC AIR,
			// getDelegateOrThrow, Map.getOrDefault, CHECKCAST Supplier, Supplier.get, CHECKCAST Block.
			int at = i + 1;
			if (at + 10 >= code.length) return 0;
			if (!(code[at] instanceof FieldInsnNode registry) || !registry.owner.equals(FORGE_BLOCKS)) return 0;
			if (!(code[at + 1] instanceof VarInsnNode item) || item.getOpcode() != Opcodes.ALOAD) return 0;
			if (!(code[at + 2] instanceof MethodInsnNode getBlock) || !getBlock.name.equals("getBlock")) return 0;
			if (!call(code[at + 3], "getKey") || !(code[at + 4] instanceof FieldInsnNode) || !(code[at + 5] instanceof FieldInsnNode)
					|| !call(code[at + 6], "getDelegateOrThrow") || !call(code[at + 7], "getOrDefault")
					|| !(code[at + 8] instanceof TypeInsnNode supplier) || !supplier.desc.equals("java/util/function/Supplier")
					|| !call(code[at + 9], "get") || !(code[at + 10] instanceof TypeInsnNode block) || !block.desc.equals(BLOCK)) return 0;
			InsnList lookup = new InsnList();
			lookup.add(new VarInsnNode(Opcodes.ALOAD, 0));
			lookup.add(new VarInsnNode(Opcodes.ALOAD, item.var));
			lookup.add(new MethodInsnNode(getBlock.getOpcode(), getBlock.owner, getBlock.name, getBlock.desc, getBlock.itf));
			lookup.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "fullPotFor", FULL_POT_FOR, false));
			use.instructions.insertBefore(code[at], lookup);
			for (int k = at; k <= at + 10; k++) use.instructions.remove(code[k]);
			return 1;
		}
		return 0;
	}

	/** {@code addPlant}: after NeoForge's check, {@code fullPots.put(id, supplier)}, as MinecraftForge's body does. */
	static int recordAddedPlant(ClassNode node) {
		MethodNode add = method(node, "addPlant", ADD_PLANT);
		if (add == null || references(add, "fullPots")) return 0;
		AbstractInsnNode last = add.instructions.getLast();
		while (last != null && last.getOpcode() < 0) last = last.getPrevious();
		if (last == null || last.getOpcode() != Opcodes.RETURN) return 0;
		InsnList put = new InsnList();
		put.add(new VarInsnNode(Opcodes.ALOAD, 0));
		put.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, "fullPots", "L" + MAP + ";"));
		put.add(new VarInsnNode(Opcodes.ALOAD, 1));
		put.add(new VarInsnNode(Opcodes.ALOAD, 2));
		put.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
		put.add(new InsnNode(Opcodes.POP));
		add.instructions.insertBefore(last, put);
		return 1;
	}

	private static boolean call(AbstractInsnNode insn, String name) {
		return insn instanceof MethodInsnNode method && method.name.equals(name);
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
