/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;
import java.util.*;import java.util.concurrent.ConcurrentHashMap;
/** Only an actual final source call followed by recording the same input grants evaluator ownership. */
public final class KernelDecodeGuardWitnesses {
 private record Witness(DefinedMethodContracts.MethodContract caller,List<DefinedMethodContracts.MethodContract> dependencies){}
 private static final Map<String,Set<Witness>> CALLERS=new ConcurrentHashMap<>();
 private KernelDecodeGuardWitnesses(){ }
 public static void register(DefinedMethodContracts.MethodContract contract){register(contract,List.of());}
 public static void register(DefinedMethodContracts.MethodContract contract,List<DefinedMethodContracts.MethodContract> dependencies){CALLERS.computeIfAbsent(contract.owner()+"#"+contract.name()+contract.descriptor(),k->ConcurrentHashMap.newKeySet()).add(new Witness(contract,List.copyOf(dependencies)));}
 public static boolean validates(Class<?> owner,String name,String descriptor){return CALLERS.getOrDefault(owner.getName()+"#"+name+descriptor,Set.of()).stream().anyMatch(w->DefinedMethodContracts.observed(owner.getClassLoader(),w.caller)&&w.dependencies.stream().allMatch(c->DefinedMethodContracts.observed(owner.getClassLoader(),c)));}
}
