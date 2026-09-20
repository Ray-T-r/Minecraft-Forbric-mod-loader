package forbric.subscribercanary;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod("forbricsubscribercanary")
public final class ForbricSubscriberCanary {
	public ForbricSubscriberCanary(IEventBus modBus) {
		System.out.println("[ForbricSubscriberCanary] constructed");
	}
}
