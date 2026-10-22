package fixture.tallyline;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.TitleScreen;

/** Renders the title screen twice and reports what each frame drew. */
public class Probe {
	public static int frames;

	public String probe() {
		StringBuilder out = new StringBuilder();
		TitleScreen screen = new TitleScreen();
		for (int frame = 0; frame < 2; frame++) {
			GuiGraphicsExtractor graphics = new GuiGraphicsExtractor();
			screen.extractRenderState(graphics);
			out.append(frame == 0 ? "" : " | ").append(String.join("; ", graphics.drawn));
		}
		return out.toString();
	}
}
