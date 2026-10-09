package net.minecraft.client;

import net.minecraft.client.input.KeyEvent;
import net.neoforged.neoforge.client.ClientHooks;

/**
 * A stand-in for the merged base's keyPress: the exits vanilla ends with returns of their own once the screen has had
 * its turn — a global key, the key release, the press — all reach one ClientHooks.onKeyInput call before the final
 * return, so the method keeps four returns where vanilla has six.
 */
public class KeyboardHandler {
	private final long window;
	private Object screen;
	private boolean consumed;

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
		joined: {
			if (action == 1 && consumed) {
				break joined;
			}
			if (screen != null) {
				if (action == 1 || action == 2) {
					if (consumed) {
						return;
					}
				} else if (consumed) {
					return;
				}
			}
			if (action == 0) {
				released(event);
				break joined;
			}
			pressed(event);
		}
		ClientHooks.onKeyInput(event, action);
	}

	private void released(KeyEvent event) {
	}

	private void pressed(KeyEvent event) {
	}
}
