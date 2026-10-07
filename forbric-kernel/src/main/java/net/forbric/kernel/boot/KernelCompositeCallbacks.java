/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;
import java.util.*;import java.util.concurrent.ConcurrentHashMap;import java.lang.reflect.Modifier;
import net.forbric.kernel.util.ForbricLog;
/** A source fallback can use a composite's absent result only while the final record contract is unchanged. */
public final class KernelCompositeCallbacks {
 private record Plan(String owner,List<DefinedMethodContracts.MethodContract> methods){ }
 private static final Map<String,Plan> PLANS=new ConcurrentHashMap<>();
 private static final ClassValue<Set<String>> REPORTED=new ClassValue<>(){@Override protected Set<String>computeValue(Class<?> type){return ConcurrentHashMap.newKeySet();}};
 private KernelCompositeCallbacks(){ }
 public static String register(String owner,List<DefinedMethodContracts.MethodContract>methods){
  Plan plan=new Plan(owner.replace('/','.'),List.copyOf(methods));
  try{String key=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(plan.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));PLANS.putIfAbsent(key,plan);return key;}
  catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
 }
 public static boolean permits(Object record,String key){
  Plan plan=PLANS.get(key);boolean valid=record!=null&&plan!=null&&record.getClass().getName().equals(plan.owner)&&record.getClass().isRecord()&&Modifier.isFinal(record.getClass().getModifiers());
  if(valid)for(var method:plan.methods)if(!DefinedMethodContracts.observed(record.getClass().getClassLoader(),method)){valid=false;break;}
  if(!valid&&record!=null&&REPORTED.get(record.getClass()).add(key))ForbricLog.warn("[Forbric/Mixin] composite lookup final contract is unavailable or changed; the source fallback callback was not executed and the native operation is retained (%s)",record.getClass().getName());
  return valid;
 }
}
