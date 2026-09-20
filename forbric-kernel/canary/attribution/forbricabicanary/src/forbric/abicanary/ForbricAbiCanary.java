package forbric.abicanary;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.ForbricVanishedEvent;

/**
 * The bucket_of_frog shape: built against a NeoForge that had a class this one does not, and the first touch is
 * inside a deferred setup task. The class is a compile-only stub that is NOT in this jar.
 */
@Mod("forbricabicanary")
public final class ForbricAbiCanary {
	public ForbricAbiCanary(IEventBus modBus) {
		System.out.println("[ForbricAbiCanary] constructed");
		// A lambda, not a method reference: a method reference is bootstrapped when the LISTENER runs and would
		// fail there; the call inside a lambda body resolves when the deferred task itself runs.
		modBus.addListener(FMLCommonSetupEvent.class, event -> event.enqueueWork(() -> ForbricVanishedEvent.touch()));
	}
}
