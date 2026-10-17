package com.tern.gill.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.At;
@Mixin(net.minecraft.world.entity.LivingEntity.class)
abstract class GillMixin{
 @WrapOperation(method="baseTick",at=@At(value="INVOKE",target="Lnet/minecraft/world/effect/MobEffectUtil;hasWaterBreathing(Lnet/minecraft/world/entity/LivingEntity;)Z"))
 private boolean gill$tank(net.minecraft.world.entity.LivingEntity body,Operation<Boolean>original){fixture.crosshostreader.Trace.add("gill:tank");return original.call(body)||com.tern.gill.Tank.full;}
}
