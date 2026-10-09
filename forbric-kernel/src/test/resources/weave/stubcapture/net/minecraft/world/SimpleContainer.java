package net.minecraft.world;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.ItemStack;

/**
 * Fixture stand-in for the merged container: vanilla's setItem(int, ItemStack) is the carrier's stub, declared first, that
 * forwards to setItem(int, ItemStack, boolean), which carries the body — the previous stack and the size limit in the two
 * locals after its arguments, then setChanged under the carrier's flag.
 */
public class SimpleContainer {
	public final List<String> trace = new ArrayList<>();
	private final List<ItemStack> items = new ArrayList<>(List.of(ItemStack.EMPTY, ItemStack.EMPTY));

	public void setItem(int slot, ItemStack stack) {
		setItem(slot, stack, true);
	}

	public void setItem(int slot, ItemStack stack, boolean markDirty) {
		ItemStack previous = items.set(slot, stack);
		int limit = getMaxStackSize();
		stack.limitSize(limit);
		if (markDirty) {
			setChanged();
		}
	}

	public int getMaxStackSize() {
		return 64;
	}

	public void setChanged() {
		trace.add("changed");
	}
}
