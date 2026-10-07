/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;
import net.forbric.kernel.boot.KernelTagFacadeContracts;
/** Tagged source containers are used only for the owned type's unchanged default facade. */
public final class KernelTagFacadeSources{
 private KernelTagFacadeSources(){}
 public static boolean permits(Object actor,Object container,Object type,String key){return KernelFabricFluidBehaviors.enabled()&&type instanceof net.neoforged.neoforge.fluids.FluidType fluid&&KernelFabricFluidBehaviors.ownsNeoType(fluid)&&KernelTagFacadeContracts.permits(actor,container,key);}
}
