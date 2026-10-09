package org.example.locator.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Marks the frame, asks for the bar, and swaps the experience bar for the locator bar. */
@Mixin(Hud.class)
public abstract class LocatorMixin {
	@WrapOperation(method = "extractHotbarAndDecorations(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Hud;nextContextualInfoState()Lnet/minecraft/client/gui/Hud$ContextualInfo;"))
	private Hud.ContextualInfo locator$bar(Hud hud, Operation<Hud.ContextualInfo> original,
			@Local(argsOnly = true) GuiGraphicsExtractor graphics, @Local(argsOnly = true) DeltaTracker deltaTracker) {
		graphics.drawn.add("locator");
		Hud.ContextualInfo bar = original.call(hud);
		return bar == Hud.ContextualInfo.EXPERIENCE ? Hud.ContextualInfo.LOCATOR : bar;
	}
}
