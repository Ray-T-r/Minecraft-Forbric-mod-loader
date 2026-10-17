package net.minecraft.world.entity;
import net.minecraft.server.level.ServerLevel;import net.minecraft.world.damagesource.DamageSource;import net.neoforged.neoforge.common.damagesource.DamageContainer;
public class LivingEntity{
 public float health=20;public float absorption;public boolean dead;
 protected final java.util.Stack<DamageContainer>damageContainers=new java.util.Stack<>();
 public boolean isInvulnerableTo(ServerLevel level,DamageSource source){return false;}
 protected float getDamageAfterArmorAbsorb(DamageSource source,float damage){return damage;}
 public float getAbsorptionAmount(){return absorption;}public void setAbsorptionAmount(float value){absorption=Math.max(0.0F,value);fixture.absorbeddamage.Trace.LINES.add("absorption="+absorption);}
 public float getHealth(){return health;}public void setHealth(float value){health=value;}
 public void hit(ServerLevel level,DamageSource source,float amount){actuallyHurt(level,source,amount);}
 protected void actuallyHurt(ServerLevel level,DamageSource source,float damage){
  if(!this.isInvulnerableTo(level,source)){
   damage=this.getDamageAfterArmorAbsorb(source,damage);
   float original=damage;
   damage=Math.max(damage-this.getAbsorptionAmount(),0.0F);
   this.setAbsorptionAmount(this.getAbsorptionAmount()-(original-damage));
   if(damage!=0.0F){this.setHealth(this.getHealth()-damage);this.setAbsorptionAmount(this.getAbsorptionAmount()-damage);fixture.absorbeddamage.Trace.LINES.add("health="+health);}
  }
 }}
