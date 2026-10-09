package net.neoforged.neoforge.client;

import fixture.keyexits.Trail;
import net.minecraft.client.input.KeyEvent;

/** A stand-in for NeoForge's one input event, posted for a press, a repeat and a release alike. */
public final class ClientHooks {
	private ClientHooks() {
	}

	public static void onKeyInput(KeyEvent event, int action) {
		Trail.add("neoforge " + event.key() + action);
	}
}
