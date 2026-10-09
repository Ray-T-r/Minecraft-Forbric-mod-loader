package fixture.keyexits;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;

/** Presses, repeats and releases one key, and reports who heard each action, in order. */
public class Probe {
	public String probe() {
		KeyboardHandler keyboard = new KeyboardHandler(7L);
		KeyEvent g = new KeyEvent("G");
		StringBuilder out = new StringBuilder();
		for (int action : new int[] {1, 2, 0}) {
			keyboard.keyCallback(7L, action, g);
			out.append(out.isEmpty() ? "" : " | ").append(action).append("=[").append(Trail.drain()).append(']');
		}
		return out.toString();
	}
}
