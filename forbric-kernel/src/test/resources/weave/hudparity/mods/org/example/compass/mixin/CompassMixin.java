package org.example.compass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Draws a compass with the frame's graphics, then lets the contextual bar through. */
@Mixin(Hud.class)
public abstract class CompassMixin {
	@WrapOperation(method = "extractHotbarAndDecorations",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Hud;nextContextualInfoState()Lnet/minecraft/client/gui/Hud$ContextualInfo;"))
	private Hud.ContextualInfo compass$bar(Hud hud, Operation<Hud.ContextualInfo> original,
			@Local(argsOnly = true) GuiGraphicsExtractor graphics, @Local(argsOnly = true) DeltaTracker deltaTracker) {
		graphics.drawn.add("compass");
		return original.call(hud);
	}
}
