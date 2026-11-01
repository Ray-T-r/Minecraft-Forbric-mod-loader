package net.neoforged.fixture.defaultconflict;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStackTemplate;

/** NeoForge's route for a stack's remainder: the item's ItemInstance overload, the one its items override. */
public interface ItemInstanceExtension {
	Item item();

	default ItemStackTemplate getCraftingRemainder() {
		return item().getCraftingRemainder((ItemInstance) this);
	}
}
