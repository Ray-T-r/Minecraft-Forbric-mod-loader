package net.minecraft.world.entity;
import net.minecraft.server.level.ServerLevel;import net.minecraft.world.damagesource.DamageSource;import net.neoforged.neoforge.common.damagesource.DamageContainer;
public class LivingEntity{
 public float health=20;public float absorption;public boolean dead;
 protected final java.util.Stack<DamageContainer>damageContainers=new java.util.Stack<>();
 public boolean isInvulnerableTo(ServerLevel level,DamageSource source){return false;}
 protected float getDamageAfterArmorAbsorb(DamageSource source,float damage){return damage;}
 public float getAbsorptionAmount(){return absorption;}public void setAbsorptionAmount(float value){absorption=Math.max(0.0F,value);fixture.absorbeddamage.Trace.LINES.add("absorption="+absorption);}
 public float getHealth(){return health;}public void setHealth(float value){health=value;}
 public void hit(ServerLevel level,DamageSource source,float amount){damageContainers.push(new DamageContainer(source,amount));try{actuallyHurt(level,source,amount);}finally{damageContainers.pop();}}
 protected void actuallyHurt(ServerLevel level,DamageSource source,float amount){
  if(!this.isInvulnerableTo(level,source)){
   this.damageContainers.peek().setNewDamage(this.getDamageAfterArmorAbsorb(source,this.damageContainers.peek().getNewDamage()));
   float pre=net.neoforged.neoforge.common.CommonHooks.onLivingDamagePre(this,this.damageContainers.peek());
   this.damageContainers.peek().setReduction(DamageContainer.Reduction.ABSORPTION,Math.min(this.getAbsorptionAmount(),pre));
   float absorbed=Math.min(pre,this.damageContainers.peek().getReduction(DamageContainer.Reduction.ABSORPTION));
   this.setAbsorptionAmount(Math.max(0.0F,this.getAbsorptionAmount()-absorbed));
   amount=this.damageContainers.peek().getNewDamage();
   if(amount!=0.0F){this.setHealth(this.getHealth()-amount);fixture.absorbeddamage.Trace.LINES.add("health="+health);}
  }
 }}
