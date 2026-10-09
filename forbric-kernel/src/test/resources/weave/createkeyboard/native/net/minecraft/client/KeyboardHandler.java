package net.minecraft.client;

import net.minecraft.client.input.KeyEvent;

/**
 * A stand-in for vanilla 26.2's keyPress, with its six returns in vanilla's order: another window, a global key, a key
 * a screen consumed on press, one it consumed on release, the key release (ordinal 4), and the final return (ordinal
 * 5), which only a press or a repeat reaches. The test compiles this as the native reference and as the native run's
 * game, never as the merged one.
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
		if (action == 1 && consumed) {
			return;
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
			return;
		}
		pressed(event);
	}

	private void released(KeyEvent event) {
	}

	private void pressed(KeyEvent event) {
	}
}
