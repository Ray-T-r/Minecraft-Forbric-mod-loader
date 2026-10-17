package net.minecraft.world.entity.player;
import net.minecraft.server.level.ServerLevel;import net.minecraft.world.damagesource.DamageSource;
/** As merged: NeoForge's player override, the same container body. */
public class Player extends net.minecraft.world.entity.LivingEntity{
 protected void actuallyHurt(ServerLevel level,DamageSource source,float amount){
  if(!this.isInvulnerableTo(level,source)){
   this.damageContainers.peek().setNewDamage(this.getDamageAfterArmorAbsorb(source,this.damageContainers.peek().getNewDamage()));
   float pre=net.neoforged.neoforge.common.CommonHooks.onLivingDamagePre(this,this.damageContainers.peek());
   this.damageContainers.peek().setReduction(net.neoforged.neoforge.common.damagesource.DamageContainer.Reduction.ABSORPTION,Math.min(this.getAbsorptionAmount(),pre));
   float absorbed=Math.min(pre,this.damageContainers.peek().getReduction(net.neoforged.neoforge.common.damagesource.DamageContainer.Reduction.ABSORPTION));
   this.setAbsorptionAmount(Math.max(0.0F,this.getAbsorptionAmount()-absorbed));
   amount=this.damageContainers.peek().getNewDamage();
   if(amount!=0.0F){this.setHealth(this.getHealth()-amount);fixture.absorbeddamage.Trace.LINES.add("health="+health);}
  }
 }}
