/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.api.EnvType;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * {@code letBothEcosystemsSetBurnTime} asks NeoForge too where FuelValues asks only MinecraftForge, and asks nobody twice:
 * a method that already calls NeoForge's {@code EventHooks.getItemBurnTime} itself — a merge that keeps both sides' edits
 * can write both families' calls into one body — is left alone, and that end state satisfies the claim.
 */
class BurnTimeRedirectTest {
	private static final String FUEL_VALUES = "net/minecraft/world/level/block/entity/FuelValues";
	private static final String FORGE = "net/minecraftforge/event/ForgeEventFactory";
	private static final String NEO = "net/neoforged/neoforge/event/EventHooks";
	private static final String KERNEL = "net/forbric/kernel/runtime/KernelFuelValues";
	private static final String FORGE_DESC = "(Lnet/minecraft/world/item/ItemStack;ILnet/minecraft/world/item/crafting/RecipeType;)I";
	private static final String NEO_DESC = "(Lnet/minecraft/world/item/ItemStack;ILnet/minecraft/world/item/crafting/RecipeType;L" + FUEL_VALUES + ";)I";
	private static final String CLAIM = "forbric-merged-base-compat#letBothEcosystemsSetBurnTime";
	private static final TransformContext CTX = new TransformContext(EnvType.SERVER, false, "intermediary");

	@Test
	void minecraftForgesAloneIsRoutedThroughTheKernel() {
		byte[] before = fuelValues(false);
		Set<String> hits = new HashSet<>();
		byte[] after = new ForbricMergedBaseCompatTransformer(name -> null).transform(FUEL_VALUES.replace('/', '.'), before, CTX, hits::add);
		assertEquals(List.of(KERNEL + ".burnDuration"), calls(after));
		assertEquals(true, hits.contains(CLAIM));
	}

	@Test
	void aBodyThatAlreadyAsksBothIsLeftAloneAndSatisfiesTheClaim() {
		byte[] before = fuelValues(true);
		Set<String> hits = new HashSet<>();
		byte[] after = new ForbricMergedBaseCompatTransformer(name -> null).transform(FUEL_VALUES.replace('/', '.'), before, CTX, hits::add);
		assertSame(before, after, "routing it through the kernel would ask NeoForge twice");
		assertEquals(List.of(FORGE + ".getItemBurnTime", NEO + ".getItemBurnTime"), calls(after));
		assertEquals(true, hits.contains(CLAIM), "both ecosystems already set the burn time");
	}

	/** {@code int burnDuration(ItemStack, RecipeType)} asking MinecraftForge, and NeoForge after it when {@code both}. */
	private static byte[] fuelValues(boolean both) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, FUEL_VALUES, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "burnDuration",
				"(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/RecipeType;)I", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitInsn(Opcodes.ICONST_0);
		mv.visitVarInsn(Opcodes.ALOAD, 2);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, FORGE, "getItemBurnTime", FORGE_DESC, false);
		if (both) {
			mv.visitVarInsn(Opcodes.ISTORE, 3);
			mv.visitVarInsn(Opcodes.ALOAD, 1);
			mv.visitVarInsn(Opcodes.ILOAD, 3);
			mv.visitVarInsn(Opcodes.ALOAD, 2);
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, NEO, "getItemBurnTime", NEO_DESC, false);
		}
		mv.visitInsn(Opcodes.IRETURN);
		mv.visitMaxs(5, 4);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static List<String> calls(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		List<String> calls = new ArrayList<>();
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode call && (call.name.equals("getItemBurnTime") || call.name.equals("burnDuration"))) {
					calls.add(call.owner + "." + call.name);
				}
			}
		}
		return calls;
	}
}
