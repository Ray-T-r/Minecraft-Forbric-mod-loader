package net.fabricmc.fabric.mixin.entity.event.effect;

import fixture.privatedefault.Trail;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * fabric-entity-events-v1's effect.LivingEntityMixin in shape: a private {@code LivingEntity self()} of its own (not
 * {@code @Unique}), which its handlers call. On vanilla nothing else names self(); on NeoForge's base it hides the
 * interface default of the same name and descriptor. The handler is the fixture's own.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void onTick(CallbackInfo ci) {
		Trail.log("fabric:" + self().name());
	}

	private LivingEntity self() {
		return (LivingEntity) (Object) this;
	}
}
