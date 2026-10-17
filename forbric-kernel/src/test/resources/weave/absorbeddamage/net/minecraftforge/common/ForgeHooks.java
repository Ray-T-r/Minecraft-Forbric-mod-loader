package net.minecraftforge.common;
public class ForgeHooks{public static float onLivingHurt(net.minecraft.world.entity.LivingEntity entity,net.minecraft.world.damagesource.DamageSource source,float amount){return amount;}
 public static float onLivingDamage(net.minecraft.world.entity.LivingEntity entity,net.minecraft.world.damagesource.DamageSource source,float amount){return amount;}
 public static boolean onPlayerAttack(net.minecraft.world.entity.LivingEntity player,net.minecraft.world.damagesource.DamageSource source,float amount){return true;}}
