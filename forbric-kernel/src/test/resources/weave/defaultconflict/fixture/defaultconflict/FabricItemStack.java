package fixture.defaultconflict;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

/**
 * The mod's stack extension: asks the item's stack-typed remainder (real fabric-api is compiled against an Item that
 * already carries FabricItem; this fixture's Item does not, hence the cast).
 */
public interface FabricItemStack {
	default ItemStackTemplate getCraftingRemainder() {
		ItemStack stack = (ItemStack) (Object) this;
		return ((FabricItem) (Object) stack.getItem()).getCraftingRemainder(stack);
	}
}
