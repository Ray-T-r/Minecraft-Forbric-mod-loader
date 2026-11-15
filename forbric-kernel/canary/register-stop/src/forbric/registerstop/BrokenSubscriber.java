/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package forbric.registerstop;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;

/**
 * The register-stop canary's {@code @EventBusSubscriber} class. With {@code -Dforbric.registerstop=ebs} it fails to
 * initialise when NeoForge loads it to register it: natively {@code FMLModContainer.constructMod} turns that into
 * {@code fml.modloadingissue.failedtoloadmod} naming the mod, and "Mod Construction" stops the boot.
 */
@EventBusSubscriber(modid = ForbricRegisterStop.ID)
public final class BrokenSubscriber {
	static {
		ForbricRegisterStop.fail("ebs", "its @EventBusSubscriber class's initialiser");
	}

	private BrokenSubscriber() {
	}

	@SubscribeEvent
	static void onLoadComplete(FMLLoadCompleteEvent event) {
		System.out.println("[ForbricRegisterStop] subscriber saw load complete, mode=" + ForbricRegisterStop.MODE);
	}
}
