package fixture.replacedcallredirect;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** One entity using a bow while the stack in hand is another stack of bows; one update, and what ran. */
public class Probe {
	public String probe() {
		LivingEntity entity = new LivingEntity();
		entity.useItem = new ItemStack("bow", "used");
		entity.inHand = new ItemStack("bow", "held");
		entity.updatingUsingItem();
		return String.join(",", Trace.LINES);
	}
}
