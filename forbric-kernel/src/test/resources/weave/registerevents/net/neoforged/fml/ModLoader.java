package net.neoforged.fml;

import fixture.registerevents.Trace;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;

/** Stand-in: NeoForge's own fan-out, which walks ModList and lets the first failure end the dispatch. */
public class ModLoader {
	public static void postEventWrapContainerInModOrder(Event event) {
		Trace.add("native-dispatch");
		for (EventPriority phase : EventPriority.values()) {
			for (ModContainer container : ModList.get().getSortedMods()) {
				ModLoadingContext.get().setActiveContainer(container);
				container.acceptEvent(phase, event);
				ModLoadingContext.get().setActiveContainer(null);
			}
		}
	}

	public static void postEvent(Event event) {
		Trace.add("native-post");
		for (EventPriority phase : EventPriority.values()) {
			for (ModContainer container : ModList.get().getSortedMods()) container.acceptEvent(phase, event);
		}
	}
}
