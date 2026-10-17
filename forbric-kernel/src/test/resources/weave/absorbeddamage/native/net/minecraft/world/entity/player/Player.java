package net.minecraft.world.entity.player;
import net.minecraft.server.level.ServerLevel;import net.minecraft.world.damagesource.DamageSource;
/** Vanilla's player override: one absorption call, none after the health. */
public class Player extends net.minecraft.world.entity.LivingEntity{
 protected void actuallyHurt(ServerLevel level,DamageSource source,float damage){
  if(!this.isInvulnerableTo(level,source)){
   damage=this.getDamageAfterArmorAbsorb(source,damage);
   float original=damage;
   damage=Math.max(damage-this.getAbsorptionAmount(),0.0F);
   this.setAbsorptionAmount(this.getAbsorptionAmount()-(original-damage));
   if(damage!=0.0F){this.setHealth(this.getHealth()-damage);fixture.absorbeddamage.Trace.LINES.add("health="+health);}
  }
 }}
