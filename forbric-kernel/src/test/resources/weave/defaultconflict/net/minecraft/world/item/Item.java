package net.minecraft.world.item;

import net.neoforged.fixture.defaultconflict.IItemExtension;

/**
 * NeoForge's Item: its extension defaults only the ItemInstance remainder overload, so nothing conflicts when the mod's
 * mixin adds a default for the ItemStack one — but that default asks past the overload a NeoForge item overrides.
 */
public class Item implements IItemExtension {
	private final ItemStackTemplate craftingRemainder;

	public Item(ItemStackTemplate craftingRemainder) {
		this.craftingRemainder = craftingRemainder;
	}

	public ItemStackTemplate getCraftingRemainder() {
		return craftingRemainder;
	}

	/** Branches, so this class carries a stack map that the post-Mixin rewrite must hand back intact. */
	public boolean hasCraftingRemainder() {
		return craftingRemainder != null && !craftingRemainder.id().isEmpty();
	}
}
