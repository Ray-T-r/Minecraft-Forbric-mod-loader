/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;
import java.util.*;import java.util.concurrent.ConcurrentHashMap;
/** Only an actual final source call followed by recording the same input grants evaluator ownership. */
public final class KernelDecodeGuardWitnesses {
 private static final Map<String,Set<DefinedMethodContracts.MethodContract>> CALLERS=new ConcurrentHashMap<>();
 private KernelDecodeGuardWitnesses(){ }
 public static void register(DefinedMethodContracts.MethodContract contract){CALLERS.computeIfAbsent(contract.owner()+"#"+contract.name()+contract.descriptor(),k->ConcurrentHashMap.newKeySet()).add(contract);}
 public static boolean validates(Class<?> owner,String name,String descriptor){return CALLERS.getOrDefault(owner.getName()+"#"+name+descriptor,Set.of()).stream().anyMatch(c->DefinedMethodContracts.observed(owner.getClassLoader(),c));}
}
