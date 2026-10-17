package net.minecraft.world.entity;public class EntityFluidInteraction{
 public static class Tracker{public boolean eyes=true;} public final java.util.Map<net.neoforged.neoforge.fluids.FluidType,Tracker>nativeMap=new java.util.LinkedHashMap<>();public final java.util.Map<Object,Object>sourceMap=new java.util.LinkedHashMap<>();public boolean ready=true;
 public void update(){}public void collect(){net.forbric.kernel.runtime.KernelFabricTagViews.begin(this,"reader-physical");}
 public boolean query(LivingEntity actor,net.neoforged.neoforge.fluids.InFluidPredicate predicate){for(var entry:nativeMap.entrySet())if(entry.getValue().eyes&&predicate.test(actor,entry.getKey(),0))return true;return false;}}
