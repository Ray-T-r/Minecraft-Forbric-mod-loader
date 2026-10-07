/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.forbric.kernel.boot.DefinedMethodContracts.MethodContract;
import net.forbric.kernel.boot.KernelPredicateSeams.*;
import org.objectweb.asm.Type;

/** Contracts emitted by a closed source/shared-reference and native-query alignment. */
public final class KernelSharedPredicateContracts {
 public record Dispatch(Guard guard,boolean transparent) { }
 public record Site(String owner,String name,String descriptor,String body) {
  public Site { owner=owner.replace('/','.'); }
  public String id(){return owner+"#"+name+descriptor+"#"+body;}
 }
 public record Leaf(MethodContract caller,int bytecodeIndex) { }
 public record Contract(List<Dispatch>roots,List<MethodContract>definitions,Map<String,Integer>declarationFlags,List<Guard>stateGuards,
   String question,String descriptor,String dispatchKey,Leaf first,Leaf second,Site getter,Site guardRecorder,String guardSite,String sourceSchema,
   Value interaction) {
  public Contract {roots=List.copyOf(roots);definitions=List.copyOf(definitions);declarationFlags=Map.copyOf(declarationFlags);stateGuards=List.copyOf(stateGuards);}
 }
 private static final Map<String,Contract>CONTRACTS=new ConcurrentHashMap<>();
 private KernelSharedPredicateContracts(){ }
 public static String register(Contract contract){try{String key=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(contract.toString().getBytes(StandardCharsets.UTF_8)));Contract old=CONTRACTS.putIfAbsent(key,contract);if(old!=null&&!old.equals(contract))throw new IllegalStateException("Ambiguous shared predicate source");return key;}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
 public static Contract contract(String key){return CONTRACTS.get(key);}
 public static boolean roots(Object receiver,String key){Contract contract=CONTRACTS.get(key);if(contract==null||receiver==null)return false;try{
  for(Dispatch dispatch:contract.roots()){Guard guard=dispatch.guard();Object target=value(guard.receiver(),receiver);if(target==null)return decline(key,"root projection unavailable: "+guard.receiver());
   if(guard.direct()){Class<?>owner=Class.forName(guard.method().owner(),false,target.getClass().getClassLoader());if(!owner.isInstance(target)||!safeDeclared(owner,guard.method())||!DefinedMethodContracts.observed(owner.getClassLoader(),guard.method()))return decline(key,"direct root witness/flags changed: "+guard.method());}
   else if(!safeVirtual(target,guard.method())||!(dispatch.transparent()?DefinedMethodContracts.validatesTransparentDispatch(target,guard.method()):DefinedMethodContracts.validates(target,guard.method())))return decline(key,"virtual root dispatch/witness changed: "+guard.method()+" actual="+actual(target,guard.method()));
  }
  for(MethodContract method:contract.definitions()){Class<?>owner=Class.forName(method.owner(),false,receiver.getClass().getClassLoader());if(!safeDeclared(owner,method,contract.declarationFlags().get(method.owner()+"#"+method.name()+method.descriptor()))||!DefinedMethodContracts.observed(owner.getClassLoader(),method))return decline(key,"definition body/flags changed: "+method);}
  Object interaction=value(contract.interaction(),receiver);if(!KernelTagSourceContracts.validates(interaction,contract.sourceSchema()))return decline(key,"source schema witness rejected: "+contract.sourceSchema());var schema=KernelTagSourceContracts.schema(contract.sourceSchema());Class<?>owner=Class.forName(schema.owner().replace('/','.'),false,interaction.getClass().getClassLoader());Field ready=owner.getDeclaredField(schema.readyField());ready.setAccessible(true);return ready.getBoolean(interaction)||decline(key,"source view is not ready: "+contract.sourceSchema());
 }catch(ReflectiveOperationException|RuntimeException|LinkageError unknown){return decline(key,"root reflection proof failed: "+unknown);}}
 public static boolean state(Object receiver,String key){Contract contract=CONTRACTS.get(key);if(contract==null||receiver==null)return false;try{for(Guard guard:contract.stateGuards()){Object target=value(guard.receiver(),receiver);if(target==null)return false;if(guard.direct()){Class<?>owner=Class.forName(guard.method().owner(),false,target.getClass().getClassLoader());if(!owner.isInstance(target)||!safeDeclared(owner,guard.method())||!DefinedMethodContracts.observed(owner.getClassLoader(),guard.method()))return false;}else if(!safeVirtual(target,guard.method())||!DefinedMethodContracts.validates(target,guard.method()))return false;}return true;}catch(ReflectiveOperationException|RuntimeException|LinkageError unknown){return false;}}
 private static final Set<String>DIAGNOSTICS=ConcurrentHashMap.newKeySet();
 private static boolean decline(String key,String reason){if(DIAGNOSTICS.add(key+":"+reason))net.forbric.kernel.util.ForbricLog.warn("[Forbric/Mixin] shared source root proof declined: "+reason+" (source="+key+")");return false;}
 private static String actual(Object receiver,MethodContract expected){try{for(Method method:receiver.getClass().getMethods())if(method.getName().equals(expected.name())&&Type.getMethodDescriptor(method).equals(expected.descriptor()))return method.getDeclaringClass().getName()+" flags="+method.getModifiers()+" loader="+method.getDeclaringClass().getClassLoader();return "missing";}catch(RuntimeException|LinkageError unknown){return unknown.toString();}}
 public static boolean safeVirtual(Object receiver,MethodContract contract){try{for(Method method:receiver.getClass().getMethods())if(method.getName().equals(contract.name())&&Type.getMethodDescriptor(method).equals(contract.descriptor()))return Modifier.isPublic(method.getModifiers())&&(method.getModifiers()&(Modifier.STATIC|Modifier.SYNCHRONIZED|Modifier.NATIVE|Modifier.ABSTRACT))==0;return false;}catch(RuntimeException|LinkageError unknown){return false;}}
 private static boolean safeDeclared(Class<?>owner,MethodContract contract){return safeDeclared(owner,contract,null);}
 private static boolean safeDeclared(Class<?>owner,MethodContract contract,Integer flags){for(Method method:owner.getDeclaredMethods())if(method.getName().equals(contract.name())&&Type.getMethodDescriptor(method).equals(contract.descriptor()))return(method.getModifiers()&(Modifier.SYNCHRONIZED|Modifier.NATIVE|Modifier.ABSTRACT))==0&&(flags==null||(method.getModifiers()&(Modifier.PUBLIC|Modifier.PRIVATE|Modifier.PROTECTED|Modifier.STATIC))==(flags&(Modifier.PUBLIC|Modifier.PRIVATE|Modifier.PROTECTED|Modifier.STATIC)));return false;}
 private static Object value(Value value,Object root)throws ReflectiveOperationException{if(value instanceof Root index)return index.index()==0?root:null;if(!(value instanceof Projection projection))return null;Object target=value(projection.receiver(),root);if(target==null)return null;Class<?>owner=Class.forName(projection.owner().replace('/','.'),false,target.getClass().getClassLoader());Field field=owner.getDeclaredField(projection.name());if(!owner.isInstance(target)||!Modifier.isFinal(field.getModifiers())||Modifier.isStatic(field.getModifiers())||!Type.getDescriptor(field.getType()).equals(projection.descriptor()))return null;field.setAccessible(true);return field.get(target);}
 public static void resetForTests(){CONTRACTS.clear();}
}
