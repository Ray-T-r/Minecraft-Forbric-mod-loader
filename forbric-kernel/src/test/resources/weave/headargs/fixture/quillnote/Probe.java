package fixture.quillnote;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Draws one tooltip through the vanilla signature and one through the deferred path; reports where each landed. */
public class Probe {
	public String probe() {
		GuiGraphicsExtractor graphics = new GuiGraphicsExtractor();
		graphics.tooltip(null, List.of(), 10, 20, null, null);
		String old = graphics.drawnAt;
		graphics.deferred(null, List.of(), 30, 40);
		return "vanilla-call=" + old + " deferred=" + graphics.drawnAt;
	}
}
