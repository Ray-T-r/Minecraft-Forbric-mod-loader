package org.quarry.ledger.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Main;
import org.quarry.ledger.track.Seals;

/** Synthetic guest mixin of the same mod's server hook: the deferred freeze, then its post-freeze trackers. */
@Mixin(Main.class)
public class LaunchMixin {
	@Inject(method = "main", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;startTimerHackThread()V"))
	private static void afterStart(CallbackInfo info) {
		BuiltInRegistries.bootStrap();
		Seals.afterSeal();
	}
}
