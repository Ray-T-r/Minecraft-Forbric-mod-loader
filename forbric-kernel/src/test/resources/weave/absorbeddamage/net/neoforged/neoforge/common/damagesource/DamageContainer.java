package net.neoforged.neoforge.common.damagesource;
public class DamageContainer{public enum Reduction{ARMOR,ABSORPTION}
 private float damage;private final java.util.EnumMap<Reduction,Float>reductions=new java.util.EnumMap<>(Reduction.class);
 public DamageContainer(net.minecraft.world.damagesource.DamageSource source,float damage){this.damage=damage;}
 public float getNewDamage(){return damage;}public void setNewDamage(float damage){this.damage=damage;}
 public void setReduction(Reduction type,float reduction){reductions.put(type,reduction);damage=Math.max(0.0F,damage-reduction);}
 public float getReduction(Reduction type){return reductions.getOrDefault(type,0.0F);}}
