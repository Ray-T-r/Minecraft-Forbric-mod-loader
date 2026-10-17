package org.quarry.ledger.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fixture.fabricfreezehook.Trace;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;

/**
 * Synthetic guest mixin of a mod that shares no name with fabric-api and defers the registry freeze as its registry
 * sync does: the bootstrap's {@code BuiltInRegistries.bootStrap()} becomes {@code createContents()} alone, and its own
 * callback marks the bootstrap's step after the freeze. Such a mod in a Fabric game means the game freezes after the
 * mains — which is all the freeze-hook adapter asks about it.
 */
@Mixin(Bootstrap.class)
public class StartupMixin {
	@Inject(method = "bootStrap", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/Bootstrap;wrapStreams()V"))
	private static void openLedger(CallbackInfo info) {
		Trace.add("ledger");
	}

	@Redirect(method = "bootStrap", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/registries/BuiltInRegistries;bootStrap()V"))
	private static void sealLater() {
		BuiltInRegistries.createContents();
	}
}
