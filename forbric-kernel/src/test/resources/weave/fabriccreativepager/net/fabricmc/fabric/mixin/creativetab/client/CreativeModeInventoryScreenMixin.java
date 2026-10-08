package net.fabricmc.fabric.mixin.creativetab.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;

/**
 * Synthetic guest mixin in the shape of fabric-creative-tab-api's: a pager of its own (a static page and the two
 * calls that turn it) and a key handler that calls those two and touches nothing else of the mixin.
 */
@Mixin(CreativeModeInventoryScreen.class)
public abstract class CreativeModeInventoryScreenMixin implements net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen {
	private static final int PAGE_UP = 266;
	private static final int PAGE_DOWN = 267;

	@Unique
	private static int fabricPage;

	public boolean switchToPreviousPage() {
		if (fabricPage == 0) return false;
		fabricPage--;
		return true;
	}

	public boolean switchToNextPage() {
		fabricPage++;
		return true;
	}

    public int getCurrentPage(){return fabricPage;}
    public boolean switchToPage(int page){if(page<0)return false;fabricPage=page;return true;}

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
	private void keyPressed(int key, CallbackInfoReturnable<Boolean> info) {
		if (key == PAGE_UP) {
			if (switchToPreviousPage()) info.setReturnValue(true);
		} else if (key == PAGE_DOWN) {
			if (switchToNextPage()) info.setReturnValue(true);
		}
	}
}
