package net.neoforged.fml;

import fixture.registerevents.Trace;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Stand-in: a mod's container. Its "listener" registers at NORMAL and records which container NeoForge's context had
 * active; a container named "thrower" throws on the second registry, as a mod's failing listener does.
 */
public class ModContainer {
	private final String id;

	public ModContainer(String id) {
		this.id = id;
	}

	public String getModId() {
		return id;
	}

	public void acceptEvent(Event event) {
		for (EventPriority phase : EventPriority.values()) acceptEvent(phase, event);
	}

	public void acceptEvent(EventPriority phase, Event event) {
		if (phase != EventPriority.NORMAL) return;
		String what = event instanceof RegisterEvent register ? register.getRegistryKey() : event.getClass().getSimpleName();
		ModContainer active = ModLoadingContext.get().getActiveContainer();
		if (id.equals("thrower") && "b".equals(what)) throw new IllegalStateException("thrower's listener failed on b");
		Trace.add(id + ":" + what + (active == this ? "" : "(not active)"));
	}
}
