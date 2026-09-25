/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.minecraft.world.item.ItemStack;

/**
 * A MinecraftForge brewing recipe as NeoForge's brewing registry reads it.
 *
 * <p>The merged {@code PotionBrewing.Builder} keeps one recipe list, typed NeoForge's {@code IBrewingRecipe}, and its
 * MinecraftForge-shaped {@code add} appended MinecraftForge's recipe objects to it unconverted: the first brewing-stand
 * check cast one to NeoForge's interface and threw. The two interfaces are the same three methods, so the recipe is
 * wrapped as it is added (ForgeBrewingRecipesInjector) and every call goes straight through.
 */
public final class KernelBrewing {
	private KernelBrewing() {
	}

	/** The recipe NeoForge's list can hold: itself when it already is one, otherwise a pass-through. */
	public static net.neoforged.neoforge.common.brewing.IBrewingRecipe neoForge(net.minecraftforge.common.brewing.IBrewingRecipe recipe) {
		if (recipe instanceof net.neoforged.neoforge.common.brewing.IBrewingRecipe already) return already;
		return new ForgeRecipe(recipe);
	}

	/** MinecraftForge's recipe behind NeoForge's interface. */
	public record ForgeRecipe(net.minecraftforge.common.brewing.IBrewingRecipe recipe)
			implements net.neoforged.neoforge.common.brewing.IBrewingRecipe {
		@Override public boolean isInput(ItemStack input) { return recipe.isInput(input); }
		@Override public boolean isIngredient(ItemStack ingredient) { return recipe.isIngredient(ingredient); }
		@Override public ItemStack getOutput(ItemStack input, ItemStack ingredient) { return recipe.getOutput(input, ingredient); }
	}
}
