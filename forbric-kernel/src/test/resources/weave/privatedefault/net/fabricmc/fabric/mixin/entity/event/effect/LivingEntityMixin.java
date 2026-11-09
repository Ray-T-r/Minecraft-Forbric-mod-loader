package net.fabricmc.fabric.mixin.entity.event.effect;

import fixture.privatedefault.Trail;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * fabric-entity-events-v1's effect.LivingEntityMixin in shape: a private {@code @Unique LivingEntity self()} of its own
 * (javap, fabric-entity-events-v1 5.0.5 in fabric-api 0.155.2), which its handlers call. Mixin renames a {@code @Unique}
 * method only for a clash with a method the target class itself declares, and {@code LivingEntity} declares no self(),
 * so it is merged under its own name. On vanilla nothing else names self(); on NeoForge's base it then hides the
 * interface default of the same name and descriptor. The handler is the fixture's own.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void onTick(CallbackInfo ci) {
		Trail.log("fabric:" + self().name());
	}

	@Unique
	private LivingEntity self() {
		return (LivingEntity) (Object) this;
	}
}
