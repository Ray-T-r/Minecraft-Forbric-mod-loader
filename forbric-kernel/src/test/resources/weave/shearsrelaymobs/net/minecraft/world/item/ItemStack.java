package net.minecraft.world.item;

import net.minecraft.core.TypedInstance;

/** Fixture stand-in: vanilla's typed is(item), answered for the stack's item. */
public final class ItemStack implements TypedInstance<Item> {
	private final Item item;

	public ItemStack(Item item) {
		this.item = item;
	}

	@Override
	public Item typeInstance() {
		return item;
	}

	public Item getItem() {
		return item;
	}
}
