/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;
import java.util.*;import java.util.concurrent.ConcurrentHashMap;import java.lang.reflect.Modifier;
import net.forbric.kernel.util.ForbricLog;
/** A source fallback can use a composite's absent result only while the final record contract is unchanged. */
public final class KernelCompositeCallbacks {
 public record FieldContract(String owner,String name,String descriptor,int modifiers){public FieldContract{owner=owner.replace('/','.');}}
 private record Plan(String owner,List<DefinedMethodContracts.MethodContract> methods,List<DefinedMethodContracts.MethodContract> helpers,List<FieldContract> fields){ }
 private static final Map<String,Plan> PLANS=new ConcurrentHashMap<>();
 private record Caller(DefinedMethodContracts.MethodContract outer,DefinedMethodContracts.MethodContract host,List<DefinedMethodContracts.MethodContract> guest){}
 private static final Map<String,Set<Caller>> CALLERS=new ConcurrentHashMap<>();
 private static final StackWalker WALKER=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
 private static final ClassValue<Set<String>> REPORTED=new ClassValue<>(){@Override protected Set<String>computeValue(Class<?> type){return ConcurrentHashMap.newKeySet();}};
 private KernelCompositeCallbacks(){ }
 public static String register(String owner,List<DefinedMethodContracts.MethodContract>methods){
  return register(owner,methods,List.of());
 }
 public static String register(String owner,List<DefinedMethodContracts.MethodContract>methods,List<DefinedMethodContracts.MethodContract>helpers){
  return register(owner,methods,helpers,List.of());
 }
 public static String register(String owner,List<DefinedMethodContracts.MethodContract>methods,List<DefinedMethodContracts.MethodContract>helpers,List<FieldContract>fields){
  Plan plan=new Plan(owner.replace('/','.'),List.copyOf(methods),List.copyOf(helpers),List.copyOf(fields));
  try{String key=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(plan.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));PLANS.putIfAbsent(key,plan);return key;}
  catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
 }
 public static boolean provesShape(String key,String owner,List<DefinedMethodContracts.MethodContract>methods){Plan plan=PLANS.get(key);return plan!=null&&plan.owner.equals(owner.replace('/','.'))&&plan.methods.equals(methods)&&!plan.helpers.isEmpty();}
 public static void certify(String key,DefinedMethodContracts.MethodContract outer,DefinedMethodContracts.MethodContract host,List<DefinedMethodContracts.MethodContract>guest){if(PLANS.containsKey(key))CALLERS.computeIfAbsent(key,ignored->ConcurrentHashMap.newKeySet()).add(new Caller(outer,host,List.copyOf(guest)));}
 public static boolean permits(Object record,String key){
  Plan plan=PLANS.get(key);boolean valid=record!=null&&plan!=null&&record.getClass().getName().equals(plan.owner)&&record.getClass().isRecord()&&Modifier.isFinal(record.getClass().getModifiers());
  if(valid&&!plan.helpers.isEmpty()){var frame=WALKER.walk(frames->frames.skip(1).findFirst()).orElseThrow();valid=CALLERS.getOrDefault(key,Set.of()).stream().anyMatch(c->c.outer.owner().equals(frame.getDeclaringClass().getName())&&c.outer.name().equals(frame.getMethodName())&&c.outer.descriptor().equals(frame.getDescriptor())&&DefinedMethodContracts.observed(frame.getDeclaringClass().getClassLoader(),c.outer)&&DefinedMethodContracts.observed(frame.getDeclaringClass().getClassLoader(),c.host)&&c.guest.stream().allMatch(g->DefinedMethodContracts.observed(frame.getDeclaringClass().getClassLoader(),g)));}
  if(valid)for(var method:plan.methods)if(!DefinedMethodContracts.observed(record.getClass().getClassLoader(),method)){valid=false;break;}
  if(valid)for(var helper:plan.helpers){try{Class<?> owner=Class.forName(helper.owner(),false,record.getClass().getClassLoader());if(!DefinedMethodContracts.observed(owner.getClassLoader(),helper)){valid=false;break;}}catch(ReflectiveOperationException|RuntimeException|LinkageError unproved){valid=false;break;}}
  if(valid)for(String binary:plan.fields.stream().map(FieldContract::owner).distinct().toList()){try{Class<?> owner=Class.forName(binary,false,record.getClass().getClassLoader());List<FieldContract> actual=Arrays.stream(owner.getDeclaredFields()).map(f->new FieldContract(binary,f.getName(),org.objectweb.asm.Type.getDescriptor(f.getType()),f.getModifiers())).sorted(Comparator.comparing(FieldContract::name).thenComparing(FieldContract::descriptor)).toList();List<FieldContract> expected=plan.fields.stream().filter(f->f.owner.equals(binary)).sorted(Comparator.comparing(FieldContract::name).thenComparing(FieldContract::descriptor)).toList();if(!actual.equals(expected)){valid=false;break;}}catch(ReflectiveOperationException|RuntimeException|LinkageError unproved){valid=false;break;}}
  if(!valid&&record!=null&&REPORTED.get(record.getClass()).add(key))ForbricLog.warn("[Forbric/Mixin] composite lookup final contract is unavailable or changed; the source fallback callback was not executed and the native operation is retained (%s)",record.getClass().getName());
  return valid;
 }
}
