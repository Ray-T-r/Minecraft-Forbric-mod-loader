package org.example.neoscreen.hooks;

import java.util.Stack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.ClientHooks;

/**
 * Synthetic NeoForge-mod mixin in the shape of JourneyMap's NeoForge ClientHooksMixin: a HEAD callback into NeoForge's
 * layered screen draw itself, the gateway the operation seam instruments for the Fabric screen events.
 */
@Mixin(ClientHooks.class)
abstract class ClientHooksMixin {
	@Inject(method = "extractScreen", at = @At("HEAD"))
	private static void neohooks$preLayer(Screen top, Stack<Screen> layers, GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick, CallbackInfo ci) {
		graphics.draws.add("jm-head:" + top.name());
	}
}
