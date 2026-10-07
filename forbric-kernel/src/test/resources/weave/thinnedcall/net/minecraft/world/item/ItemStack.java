package net.minecraft.world.item;

/** Hand-written stand-in, not game code: a named stack, empty or not, that a sneaking player may bypass. */
public final class ItemStack {
	public final String name;
	private final boolean empty;

	public ItemStack(String name, boolean empty) {
		this.name = name;
		this.empty = empty;
	}

	public boolean isEmpty() {
		return empty;
	}

	public boolean doesSneakBypassUse() {
		return empty;
	}
}
