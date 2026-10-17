package org.quarry.ledger.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import org.quarry.ledger.track.Seals;

/**
 * Synthetic guest mixin of a mod sharing no name with fabric-api, deferring the freeze the way its registry sync does:
 * its trackers installed at the stream wrap, and the registry freeze redirected to {@code createContents} so the mod
 * can freeze later itself.
 */
@Mixin(Bootstrap.class)
public class StartupMixin {
	@Inject(method = "bootStrap", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/Bootstrap;wrapStreams()V"))
	private static void openLedger(CallbackInfo info) {
		Seals.open();
	}

	@Redirect(method = "bootStrap", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/registries/BuiltInRegistries;bootStrap()V"))
	private static void sealLater() {
		BuiltInRegistries.createContents();
	}
}
