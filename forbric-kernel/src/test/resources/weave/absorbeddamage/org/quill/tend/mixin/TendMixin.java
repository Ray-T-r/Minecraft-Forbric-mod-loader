package org.quill.tend.mixin;
import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.At;import org.spongepowered.asm.mixin.injection.ModifyVariable;
/** A NeoForge mod's modifier at the same call: compiled against NeoForge's body, which makes the call once. */
@Mixin(net.minecraft.world.entity.LivingEntity.class)
abstract class TendMixin{
 @ModifyVariable(method="actuallyHurt",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/LivingEntity;setAbsorptionAmount(F)V"),argsOnly=true)
 private float tend$damage(float damage,net.minecraft.server.level.ServerLevel level,net.minecraft.world.damagesource.DamageSource source){
  fixture.absorbeddamage.Trace.LINES.add("tend:"+damage);return damage;}
}
