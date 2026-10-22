package net.minecraft.client.resources.language;

/** Fixture stand-in. */
public final class I18n {
	private I18n() {
	}

	public static String get(String key, Object... args) {
		return key.equals("menu.modded") ? " (Modded)" : key;
	}
}
