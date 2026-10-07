/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.*;
import net.forbric.kernel.boot.KernelTagSourceContracts;
import net.neoforged.neoforge.fluids.FluidType;

/** Provenance gates only; the source map, selection and metric instructions remain copied native bytecode. */
public final class KernelFabricTagViews {
	private KernelFabricTagViews(){ }
	public static boolean construct(Object interaction,Set<?>tags,Map<?,?>existing,String key){
		if(!KernelFabricFluidBehaviors.enabled()||!KernelTagSourceContracts.validates(interaction,key))return false;
		var schema=KernelTagSourceContracts.schema(key);try{Class<?>tracker=Class.forName(schema.tracker().replace('/','.'),false,interaction.getClass().getClassLoader());
			for(Object tag:tags){Object value=existing.get(tag);if(value==null||value.getClass()!=tracker)return KernelTagSourceContracts.decline(interaction,key,"native concrete or missing constructor tag tracker takes precedence",true);}
			return true;
		}catch(ClassNotFoundException unknown){return KernelTagSourceContracts.decline(interaction,key,"constructor tracker definition is unavailable",false);}
	}
	public static boolean begin(Object interaction,String key){
		if(!KernelFabricFluidBehaviors.enabled()||!KernelTagSourceContracts.validates(interaction,key))return false;
		try{var schema=KernelTagSourceContracts.schema(key);Class<?>owner=Class.forName(schema.owner().replace('/','.'),false,interaction.getClass().getClassLoader());var field=owner.getDeclaredField(schema.mapField());field.setAccessible(true);return field.get(interaction)!=null;}catch(ReflectiveOperationException|RuntimeException unavailable){return false;}
	}
	public static boolean sample(Object interaction,Object fluid,FluidType type,String key){
		if(!KernelFabricFluidBehaviors.ownsNeoType(type))return false;
		if(KernelTagSourceContracts.validatesFluid(interaction,fluid,key))return true;
		disable(interaction,key);
		return false;
	}
	public static boolean owns(FluidType type){return KernelFabricFluidBehaviors.enabled()&&KernelFabricFluidBehaviors.ownsNeoType(type);}
	public static boolean vector(Object interaction,Object vector,String key){boolean valid=KernelTagSourceContracts.validatesVector(interaction,vector,key);if(!valid)disable(interaction,key);return valid;}
	private static void disable(Object interaction,String key){try{var schema=KernelTagSourceContracts.schema(key);Class<?>owner=Class.forName(schema.owner().replace('/','.'),false,interaction.getClass().getClassLoader());var field=owner.getDeclaredField(schema.readyField());field.setAccessible(true);field.setBoolean(interaction,false);}catch(ReflectiveOperationException|RuntimeException unavailable){/* the original proof decline already reports the unavailable view */}}
}
