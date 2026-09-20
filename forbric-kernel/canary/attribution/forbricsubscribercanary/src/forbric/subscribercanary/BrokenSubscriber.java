package forbric.subscribercanary;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Its static initializer throws, so it can never be registered — and the mod must be told, not just the log. */
@EventBusSubscriber(modid = "forbricsubscribercanary")
public final class BrokenSubscriber {
	static {
		if (System.currentTimeMillis() > 0) {
			throw new IllegalStateException("[ForbricSubscriberCanary] BrokenSubscriber's <clinit> throws on purpose");
		}
	}

	@SubscribeEvent
	public static void onTick(ServerTickEvent.Post event) {
	}
}
