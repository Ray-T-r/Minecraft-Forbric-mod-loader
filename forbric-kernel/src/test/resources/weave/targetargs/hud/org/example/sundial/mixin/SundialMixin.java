package org.example.sundial.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Draws a sundial's shadow with the frame's graphics, then lets the contextual bar through. Unlike the compass and the
 * locator it asks for the graphics as the target argument Mixin appends after the Operation — no {@code @Local}, and not
 * the delta tracker — and writes its point with a dotted owner and whitespace.
 */
@Mixin(Hud.class)
public abstract class SundialMixin {
	@WrapOperation(method = "extractHotbarAndDecorations",
			at = @At(value = "INVOKE", target = " net.minecraft.client.gui.Hud.nextContextualInfoState ()Lnet/minecraft/client/gui/Hud$ContextualInfo;"))
	private Hud.ContextualInfo sundial$shade(Hud hud, Operation<Hud.ContextualInfo> original, GuiGraphicsExtractor graphics) {
		graphics.drawn.add("sundial");
		return original.call(hud);
	}
}
