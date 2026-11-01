package org.example.neoscreen.mixin;

import java.util.Stack;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/**
 * Synthetic NeoForge-mod mixin in the shape of spruceui's NeoForge GuiMixin: its own events around NeoForge's whole
 * layered screen draw, the call {@code Gui.extractRenderState} makes on NeoForge's base.
 */
@Mixin(Gui.class)
abstract class GuiMixin {
	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/neoforged/neoforge/client/ClientHooks;extractScreen(Lnet/minecraft/client/gui/screens/Screen;Ljava/util/Stack;Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
	private void neoscreen$onRenderScreen(Screen screen, Stack<Screen> layers, GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick, Operation<Void> original) {
		graphics.draws.add("neo-before:" + screen.name());
		original.call(screen, layers, graphics, mouseX, mouseY, partialTick);
		graphics.draws.add("neo-after:" + screen.name());
	}
}
