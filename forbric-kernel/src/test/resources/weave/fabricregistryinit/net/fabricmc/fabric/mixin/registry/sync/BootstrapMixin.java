package net.fabricmc.fabric.mixin.registry.sync;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fixture.fabricregistryinit.Trace;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;

/**
 * Synthetic guest mixin in the shape of fabric-registry-sync's: trackers installed at the stream wrap, and the
 * registry freeze redirected to {@code createContents} so Fabric can freeze later itself.
 */
@Mixin(Bootstrap.class)
public class BootstrapMixin {
	@Inject(method = "bootStrap", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/Bootstrap;wrapStreams()V"))
	private static void afterInitialize(CallbackInfo info) {
		net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager.bootstrapRegistries();
	}

	@Redirect(method = "bootStrap", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/registries/BuiltInRegistries;bootStrap()V"))
	private static void delayRegistryFreeze() {
		BuiltInRegistries.createContents();
	}
}
