package org.example.steadycam.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import fixture.camerawrap.Views;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A rotation wrapper written another way than Do a Barrel Roll's: another mod, one handler, no ordinal (so on vanilla it
 * wraps all four of alignWithEntity's two-float rotation calls), no shared state; it records the yaw it saw and lets
 * every call through.
 */
@Mixin(Camera.class)
public abstract class SteadyCameraMixin {
	@WrapWithCondition(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
	private boolean steadycam$everyView(Camera camera, float yaw, float pitch) {
		Views.SEEN.add(yaw);
		return true;
	}
}
