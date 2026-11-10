package net.neoforged.neoforge.registries;

import net.neoforged.bus.api.Event;

/** Stand-in: one registry's RegisterEvent; the key is the registry's id. */
public class RegisterEvent extends Event {
	private final String key;

	RegisterEvent(String key) {
		this.key = key;
	}

	public String getRegistryKey() {
		return key;
	}
}
