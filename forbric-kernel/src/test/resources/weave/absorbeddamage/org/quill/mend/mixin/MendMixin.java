package org.quill.mend.mixin;
import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.At;import org.spongepowered.asm.mixin.injection.ModifyVariable;
@Mixin(net.minecraft.world.entity.LivingEntity.class)
abstract class MendMixin{
 @ModifyVariable(method="actuallyHurt",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/LivingEntity;setAbsorptionAmount(F)V"),argsOnly=true)
 private float mend$damage(float damage,net.minecraft.server.level.ServerLevel level,net.minecraft.world.damagesource.DamageSource source){
  fixture.absorbeddamage.Trace.LINES.add("mend:"+damage);return damage*org.quill.mend.Mend.factor;}
}
