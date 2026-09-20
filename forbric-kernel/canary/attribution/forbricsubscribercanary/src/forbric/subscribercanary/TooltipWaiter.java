package forbric.subscribercanary;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/** Registers fine and waits for NeoForge's ItemTooltipEvent, which the merged ItemStack never posts. */
@EventBusSubscriber(modid = "forbricsubscribercanary")
public final class TooltipWaiter {
	@SubscribeEvent
	public static void onTooltip(ItemTooltipEvent event) {
	}
}
