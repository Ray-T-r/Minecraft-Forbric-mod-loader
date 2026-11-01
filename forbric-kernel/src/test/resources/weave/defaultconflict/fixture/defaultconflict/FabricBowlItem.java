package fixture.defaultconflict;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;

/** A Fabric mod's item: it overrides FabricItem's stack-typed remainder, which the mixin puts on Item at weave time. */
public class FabricBowlItem extends Item {
	public FabricBowlItem() {
		super(new ItemStackTemplate("bowl"));
	}

	public ItemStackTemplate getCraftingRemainder(ItemStack stack) {
		return new ItemStackTemplate("fabric-override:" + stack);
	}
}
