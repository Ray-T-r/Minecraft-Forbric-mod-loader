package net.minecraft.world.entity;
/** As merged: the air is the carrier's hook's to decide. */
public class LivingEntity extends Entity implements net.neoforged.neoforge.common.extensions.ILivingEntityExtension{
 public final net.minecraft.world.level.Level world=new net.minecraft.server.level.ServerLevel();public final EntityFluidInteraction interaction=new EntityFluidInteraction();public int air=100;public boolean breather;
 public net.minecraft.world.level.Level level(){return world;}public EntityFluidInteraction getFluidInteraction(){return interaction;}public double getX(){return 0;}public double getEyeY(){return 1;}public double getZ(){return 0;}
 public boolean canBreatheUnderwater(){fixture.crosshostreader.Trace.add("breathe");return breather;}
 public LivingEntity self(){return this;}public int getAirSupply(){return air;}public int decreaseAirSupply(int supply){return supply-1;}public int increaseAirSupply(int supply){return supply+4;}public void setAirSupply(int supply){air=supply;}
 public void baseTick(){
  net.minecraft.world.level.Level level=this.level();
  if(level instanceof net.minecraft.server.level.ServerLevel serverLevel){
   boolean player=this instanceof net.minecraft.world.entity.player.Player;
   int supply=this.getAirSupply();
   net.neoforged.neoforge.common.CommonHooks.onLivingBreathe(this,serverLevel,supply-this.decreaseAirSupply(supply),this.increaseAirSupply(supply)-supply);
   fixture.crosshostreader.Trace.add("after");
  }
 }}
