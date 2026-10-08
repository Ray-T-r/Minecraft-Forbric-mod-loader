package net.fabricmc.fabric.mixin.registry.sync;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fixture.fabricregistryinit.Trace;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Main;

/** Synthetic guest mixin in the shape of fabric-registry-sync's server hook: freeze, then the post-freeze trackers. */
@Mixin(Main.class)
public class MainMixin {
	@Inject(method = "main", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;startTimerHackThread()V"))
	private static void afterModInit(CallbackInfo info) {
		BuiltInRegistries.bootStrap();
		net.fabricmc.fabric.impl.registry.sync.trackers.vanilla.BlockInitTracker.postFreeze();
	}
}
