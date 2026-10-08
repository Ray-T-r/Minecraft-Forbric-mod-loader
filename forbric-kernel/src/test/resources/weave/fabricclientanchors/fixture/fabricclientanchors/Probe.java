package fixture.fabricclientanchors;

import com.mojang.blaze3d.vertex.PoseStack;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;

/**
 * Draws one frame of an open screen over a layer, with a Fabric listener on each side of the screen's draw, and one
 * block destroy animation; reports the draw order and what the animation submitted.
 */
public class Probe {
	public String run() {
		Gui gui = new Gui();
		Screen inventory = new Screen("inventory");
		gui.screen = inventory;
		gui.layers.push(new Screen("toast-layer"));
		ScreenEvents.beforeExtract(inventory).register((screen, graphics, x, y, tick) ->
				graphics.draws.add("before:" + screen.name() + "@" + x + "," + y + "," + tick));
		ScreenEvents.afterExtract(inventory).register((screen, graphics, x, y, tick) -> graphics.draws.add("after:" + screen.name()));
		GuiGraphicsExtractor graphics = new GuiGraphicsExtractor();
		gui.extractRenderState(graphics, 3, 4, 0.5f);
		graphics = gui.lastGraphics;

		SubmitNodeCollector collector = new SubmitNodeCollector();
		new LevelRenderer().submitBlockDestroyAnimation(new PoseStack(), collector, new LevelRenderState());
		return "frame=" + graphics.draws + " destroy=" + collector.submitted;
	}
}
