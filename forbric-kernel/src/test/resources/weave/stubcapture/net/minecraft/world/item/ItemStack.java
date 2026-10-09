package net.minecraft.world.item;

/** Fixture stand-in: a named stack with a count. */
public class ItemStack {
	public static final ItemStack EMPTY = new ItemStack("air", 0);

	private final String item;
	private int count;

	public ItemStack(String item, int count) {
		this.item = item;
		this.count = count;
	}

	public void limitSize(int max) {
		if (count > max) count = max;
	}

	@Override
	public String toString() {
		return item + "x" + count;
	}
}
