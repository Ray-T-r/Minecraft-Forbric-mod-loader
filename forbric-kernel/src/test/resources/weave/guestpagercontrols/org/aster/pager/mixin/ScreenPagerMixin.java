package org.aster.pager.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Synthetic guest pager with names of its own, in the shape a creative pager has: a page of its own (a static the two
 * API calls read and write), an injector that writes that page and adds the pager's own page buttons when the screen
 * opens, and a key handler that only calls the API.
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class ScreenPagerMixin implements net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen {
	@Unique
	private static int asterPage;

	@Shadow
	protected abstract Object addRenderableWidget(Object widget);

	public int getCurrentPage() {
		return asterPage;
	}

	public boolean switchToPage(int page) {
		if (page < 0) return false;
		asterPage = page;
		return true;
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void asterButtons(CallbackInfo info) {
		asterPage = 0;
		net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen api =
				(net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen) (Object) this;
		addRenderableWidget(new Button("aster-previous", button -> api.switchToPreviousPage()));
		addRenderableWidget(new Button("aster-next", button -> api.switchToNextPage()));
	}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void asterKeys(int key, CallbackInfoReturnable<Boolean> info) {
		if (key == 267 && switchToNextPage()) info.setReturnValue(true);
	}
}
