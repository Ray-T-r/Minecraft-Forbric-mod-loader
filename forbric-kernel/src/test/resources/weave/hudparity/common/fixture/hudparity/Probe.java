package fixture.hudparity;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;

/** One frame with the HUD shown and one with it hidden (F1): what each frame drew. Same probe on both shapes. */
public class Probe {
	public String probe() {
		return "shown: " + frame(new Hud(false)) + " | hidden: " + frame(new Hud(true));
	}

	private static String frame(Hud hud) {
		GuiGraphicsExtractor graphics = new GuiGraphicsExtractor();
		hud.extractRenderState(graphics, new DeltaTracker());
		return graphics.drawn.isEmpty() ? "nothing" : String.join(", ", graphics.drawn);
	}
}
