package org.marsh.reed.mixin;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.At;
@Mixin(net.minecraft.world.entity.LivingEntity.class)
abstract class SoakMixin extends net.minecraft.world.entity.Entity{
 @ModifyExpressionValue(method="baseTick",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/LivingEntity;isEyeInFluid(Lnet/minecraft/tags/TagKey;)Z"))
 private boolean reed$soak(boolean original){fixture.crosshostreader.Trace.add("reed:soak");if(original)return true;return this.isEyeInFluid(org.marsh.reed.Pools.SOAK)&&org.marsh.reed.Pools.drowns((net.minecraft.world.entity.LivingEntity)(Object)this);}
}
