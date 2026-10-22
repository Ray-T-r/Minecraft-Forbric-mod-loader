package fixture.tallyline.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fixture.tallyline.Probe;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.TitleScreen;

/** A guest on the same method that does not touch the version string: nothing about the string may change. */
@Mixin(TitleScreen.class)
public abstract class HeadMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void tally$frame(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		Probe.frames++;
	}
}
