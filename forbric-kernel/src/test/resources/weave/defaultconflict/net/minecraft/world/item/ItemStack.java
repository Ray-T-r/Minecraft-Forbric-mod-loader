package net.minecraft.world.item;

public final class ItemStack implements ItemInstance {
	private final String id;
	private final Item item;

	public ItemStack(String id) {
		this(id, new Item(new ItemStackTemplate("")));
	}

	public ItemStack(String id, Item item) {
		this.id = id;
		this.item = item;
	}

	public Item getItem() {
		return item;
	}

	@Override
	public Item item() {
		return item;
	}

	@Override
	public String toString() {
		return id;
	}
}
