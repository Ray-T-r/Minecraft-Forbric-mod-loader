package fixture.shearsrelaymobs;

import net.minecraft.world.item.ItemStack;

/** The guest's own test for a shears item: the common shears tag, whatever the item is. */
public final class ShearTags {
	private ShearTags() {
	}

	public static boolean isShear(ItemStack stack) {
		return stack.getItem().tagged("c:tools/shear");
	}
}
