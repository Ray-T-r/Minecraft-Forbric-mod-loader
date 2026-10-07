package net.minecraft.world.item;

/** Hand-written stand-in, not game code: a named stack of an item; isSameItem is vanilla's test, by item. */
public final class ItemStack {
	public final String item;
	public final String name;

	public ItemStack(String item, String name) {
		this.item = item;
		this.name = name;
	}

	public static boolean isSameItem(ItemStack a, ItemStack b) {
		return a.item.equals(b.item);
	}
}
