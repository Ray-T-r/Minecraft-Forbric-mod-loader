package net.minecraft.client;

import net.minecraft.client.input.KeyEvent;
import net.neoforged.neoforge.client.ClientHooks;

/**
 * A stand-in for the merged base's KeyboardHandler: NeoForge joins vanilla's separate release and press exits into one
 * ClientHooks.onKeyInput call at the end of keyPress, so the method has two returns where vanilla has six.
 */
public class KeyboardHandler {
	private final long window;

	public KeyboardHandler(long window) {
		this.window = window;
	}

	/** GLFW's key callback, reduced to the call it makes. */
	public void keyCallback(long handle, int action, KeyEvent event) {
		keyPress(handle, action, event);
	}

	private void keyPress(long handle, int action, KeyEvent event) {
		if (handle != window) {
			return;
		}
		ClientHooks.onKeyInput(event, action);
	}
}
