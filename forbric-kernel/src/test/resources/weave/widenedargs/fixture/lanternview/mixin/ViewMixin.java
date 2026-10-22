package fixture.lanternview.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import fixture.lanternview.Angles;
import net.minecraft.client.Camera;

/**
 * Written against the native camera: the first setRotation(FF) after the minecart branch's setPosition(Vec3) is the
 * ordinary view's, and the hook turns its yaw through lambdas over the argument list, as an angles event would.
 */
@Mixin(Camera.class)
public abstract class ViewMixin {
	@ModifyArgs(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V", ordinal = 0),
			slice = @Slice(from = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(Lnet/minecraft/world/phys/Vec3;)V")))
	public void lantern$turn(Args args, float partialTicks) {
		Angles.calls++;
		Angles yaw = Angles.of(value -> args.set(0, value), () -> args.get(0));
		Angles pitch = Angles.of(value -> args.set(1, value), () -> args.get(1));
		yaw.add(Angles.turn * partialTicks * 2.0F);
		pitch.add(0.0F);
	}
}
