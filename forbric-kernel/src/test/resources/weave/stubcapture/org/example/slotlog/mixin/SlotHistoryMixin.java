package org.example.slotlog.mixin;

import fixture.stubcapture.History;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/**
 * A locals capture on another carrier stub than the atlas listing, by another mod: vanilla's setItem(int, ItemStack),
 * named by its bare name, a void target (a CallbackInfo, not a returnable one), two captured locals, the second an int.
 */
@Mixin(SimpleContainer.class)
public abstract class SlotHistoryMixin {
	@Inject(method = "setItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/SimpleContainer;setChanged()V"),
			locals = LocalCapture.CAPTURE_FAILSOFT)
	private void slotlog$beforeChanged(int slot, ItemStack stack, CallbackInfo ci, ItemStack previous, int limit) {
		History.SEEN.add(slot + ":" + previous + "->" + stack + "/" + limit);
	}
}
