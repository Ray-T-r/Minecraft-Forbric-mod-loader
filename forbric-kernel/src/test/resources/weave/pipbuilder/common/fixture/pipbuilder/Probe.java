package fixture.pipbuilder;

import java.util.ArrayList;

import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

/** The plain picture-in-picture map one GuiRenderer ends up with, given no renderers of its own. */
public class Probe {
	public String probe() {
		return new GuiRenderer(new GuiRenderState(), new FeatureRenderDispatcher(), new ArrayList<>()).renderers();
	}
}
