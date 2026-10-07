package nl.enjarai.doabarrelroll.mixin.client.roll;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalFloatRef;
import fixture.barrelroll.Flight;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A camera-roll mixin in Do a Barrel Roll's shape: its class and handler names, descriptors and injectors are the ones
 * MixinCameraRollAdapter reviewed (vanilla ordinals 1, 2 and 3 of setRotation(FF), a shared tick delta, and a
 * name-only setRotation selector on the rotationYXZ roll); the bodies are the fixture's own.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Unique private float doABarrelRoll$lastTickDelta;
	@Unique private Float doABarrelRoll$pending;

	@Inject(method = "alignWithEntity", at = @At("HEAD"))
	private void doABarrelRoll$captureTickDeltaAndUpdate(float tickDelta, CallbackInfo ci,
			@Share("tickDelta") LocalFloatRef tickDeltaRef) {
		tickDeltaRef.set(tickDelta);
		doABarrelRoll$lastTickDelta = tickDelta;
	}

	@WrapWithCondition(method = "alignWithEntity", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 1))
	private boolean doABarrelRoll$addRoll1(Camera thiz, float yaw, float pitch, @Share("tickDelta") LocalFloatRef tickDelta) {
		Flight.ran("ordinary");
		doABarrelRoll$pending = Flight.ROLL_PER_TICK * tickDelta.get();
		return true;
	}

	@WrapWithCondition(method = "alignWithEntity", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 2))
	private boolean doABarrelRoll$addRoll2(Camera thiz, float yaw, float pitch) {
		Flight.ran("mirrored");
		doABarrelRoll$pending = -Flight.ROLL_PER_TICK * doABarrelRoll$lastTickDelta;
		return true;
	}

	@WrapWithCondition(method = "alignWithEntity", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 3))
	private boolean doABarrelRoll$addRoll3(Camera thiz, float yaw, float pitch) {
		Flight.ran("bed");
		doABarrelRoll$pending = 0.0F;
		return true;
	}

	@ModifyArg(method = "setRotation", at = @At(value = "INVOKE",
			target = "Lorg/joml/Quaternionf;rotationYXZ(FFF)Lorg/joml/Quaternionf;", remap = false), index = 2)
	private float doABarrelRoll$setRoll(float original) {
		Float pending = doABarrelRoll$pending;
		return pending == null ? original : original + pending;
	}
}
