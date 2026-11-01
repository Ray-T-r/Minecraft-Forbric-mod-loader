package forbric.regressprobe;

import net.fabricmc.fabric.api.item.v1.FabricItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;

/** A Fabric item whose crafting remainder depends on the stack it is asked for: a stick for one of itself, else a bottle. */
public class RemainderItem extends Item implements FabricItem {
	public RemainderItem(Item.Properties properties) { super(properties); }
	@Override public ItemStackTemplate getCraftingRemainder(ItemStack stack) {
		RegressProbe.REMAINDER_ASKED.incrementAndGet();
		return stack.getCount() == 1 && stack.is(this) ? new ItemStackTemplate(Items.STICK) : new ItemStackTemplate(Items.GLASS_BOTTLE);
	}
}
