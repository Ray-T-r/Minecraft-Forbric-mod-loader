/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * A MinecraftForge brewing recipe goes into the merged builder's list as a recipe NeoForge's registry can read.
 *
 * <p>{@code PotionBrewing.Builder.add(net.minecraftforge…IBrewingRecipe)} appends its argument to {@code recipes}, a list
 * NeoForge's {@code BrewingRecipeRegistry} reads as NeoForge's {@code IBrewingRecipe}: the first brewing-stand check
 * threw ClassCastException on a MinecraftForge mod's recipe. The argument is wrapped on its way into the list
 * ({@code KernelBrewing.neoForge}). Applied only when {@code add} is exactly that append.
 * {@code -Dforbric.forgeBrewingRecipes=off} leaves the builder as merged.
 */
public final class ForgeBrewingRecipesInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.forgeBrewingRecipes";
	static final String BUILDER = "net.minecraft.world.item.alchemy.PotionBrewing$Builder";
	static final String FORGE_RECIPE = "Lnet/minecraftforge/common/brewing/IBrewingRecipe;";
	static final String NEO_RECIPE = "Lnet/neoforged/neoforge/common/brewing/IBrewingRecipe;";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-forge-brewing-recipes"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("MinecraftForge brewing recipes left unconverted with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(BUILDER, AnchorSet.Severity.REQUIRED,
				"a MinecraftForge mod's brewing recipe throws ClassCastException at the first brewing-stand check"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !BUILDER.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (!repair(node)) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Brewing] PotionBrewing.Builder.add wraps a MinecraftForge brewing recipe as NeoForge's — "
				+ "it went into NeoForge's recipe list unconverted and threw at the first brewing-stand check");
		return writer.toByteArray();
	}

	static boolean repair(ClassNode builder) {
		for (MethodNode method : builder.methods) {
			if (!method.name.equals("add") || !method.desc.equals("(" + FORGE_RECIPE + ")L" + builder.name + ";")) continue;
			// aload0; getfield recipes; aload1; invokeinterface List.add; pop; aload0; areturn
			AbstractInsnNode argument = null;
			int adds = 0;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call) {
					if (call.name.equals("neoForge")) return false;
					if (!(call.owner.equals("java/util/List") && call.name.equals("add") && call.desc.equals("(Ljava/lang/Object;)Z"))) return false;
					adds++;
					if (call.getPrevious() instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 1
							&& load.getPrevious() instanceof FieldInsnNode field && field.name.equals("recipes")) argument = load;
				}
			}
			if (adds != 1 || argument == null) return false;
			method.instructions.insert(argument, new MethodInsnNode(Opcodes.INVOKESTATIC, "net/forbric/kernel/runtime/KernelBrewing",
					"neoForge", "(" + FORGE_RECIPE + ")" + NEO_RECIPE, false));
			return true;
		}
		return false;
	}
}
