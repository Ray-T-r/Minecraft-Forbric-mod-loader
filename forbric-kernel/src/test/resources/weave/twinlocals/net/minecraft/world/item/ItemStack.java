package net.minecraft.world.item;

/** Stand-in: the tool in hand. */
public record ItemStack(String name) {
	public ItemStack copy() {
		return new ItemStack(name);
	}
}
