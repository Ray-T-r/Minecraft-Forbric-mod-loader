package org.quill.mend.mixin;
import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.At;import org.spongepowered.asm.mixin.injection.ModifyVariable;
@Mixin(net.minecraft.world.entity.player.Player.class)
abstract class MendPlayerMixin{
 @ModifyVariable(method="actuallyHurt",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/player/Player;setAbsorptionAmount(F)V"),argsOnly=true)
 private float mend$player(float damage,net.minecraft.server.level.ServerLevel level,net.minecraft.world.damagesource.DamageSource source){
  fixture.absorbeddamage.Trace.LINES.add("mend-player:"+damage);return damage*org.quill.mend.Mend.factor;}
}
