/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.*;
import java.util.function.*;
import net.forbric.kernel.util.ForbricLog;

/** Lexical ownership of a source predicate evaluation and its closed result consumer. */
public final class KernelSourceDecodeScopes {
 private record Judgement(Object input,Class<?> owner,String name,MethodType type) { }
 private static final class Scope { final List<Judgement> judged=new ArrayList<>(); }
 private static final ThreadLocal<Scope> ACTIVE=new ThreadLocal<>();
 private static final StackWalker CALLER=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
 private static final ClassValue<Set<String>> REPORTED=new ClassValue<>(){
  @Override protected Set<String> computeValue(Class<?> owner){return java.util.concurrent.ConcurrentHashMap.newKeySet();}
 };
 private KernelSourceDecodeScopes() { }
 public static Object call(Supplier<Object> operation){
  Scope previous=ACTIVE.get(),current=new Scope();ACTIVE.set(current);
  try{return operation.get();}finally{if(previous==null)ACTIVE.remove();else ACTIVE.set(previous);}
 }
 /** The predicate has already returned; this preserves its Boolean and records no guessed verdict. */
 public static boolean record(boolean result,Object input,String owner,String name,String descriptor){
  Scope scope=ACTIVE.get();if(scope==null)return result;
  var caller=CALLER.walk(frames->frames.skip(1).findFirst()).orElseThrow();
  if(!net.forbric.kernel.boot.KernelDecodeGuardWitnesses.validates(caller.getDeclaringClass(),caller.getMethodName(),caller.getDescriptor())){
   if(REPORTED.get(caller.getDeclaringClass()).add(caller.getMethodName()+caller.getDescriptor()))
    ForbricLog.warn("[Forbric/Conditions] final source predicate/input witness is unavailable or changed; native condition fallback remains active");return result;
  }
  try { ClassLoader loader=KernelSourceDecodeScopes.class.getClassLoader();scope.judged.add(new Judgement(input,Class.forName(owner.replace('/','.'),false,loader),name,MethodType.fromMethodDescriptorString(descriptor,loader))); }
  catch(ReflectiveOperationException|RuntimeException|LinkageError unproved){ForbricLog.warn("[Forbric/Conditions] source predicate evaluation ownership could not be recorded; native condition fallback remains active",unproved);}
  return result;
 }
 /**
  * Whether a source guard of the active decode scope has already judged this very input by {@code key}: its verdict
  * stands. A judgement counts when the predicate the guard asked comes from a class whose constant pool holds the key it
  * judges — the JSON key a condition format is read under ("fabric:load_conditions"), which is the data both sides agree
  * on, not what the mod calls its evaluator. A guard that asked something else about the same input judged something else.
  */
 static boolean alreadyJudged(Object input,String key){
  Scope scope=ACTIVE.get();if(scope==null||key==null)return false;
  for(Judgement judgement:scope.judged)if(judgement.input==input&&mentions(judgement.owner,key))return true;
  return false;
 }
 private static final ClassValue<Map<String,Boolean>> MENTIONS=new ClassValue<>(){
  @Override protected Map<String,Boolean> computeValue(Class<?> owner){return new java.util.concurrent.ConcurrentHashMap<>();}
 };
 /** Whether {@code owner}'s class file holds {@code key} as a constant (a UTF-8 constant pool entry), read once per key. */
 private static boolean mentions(Class<?> owner,String key){
  return MENTIONS.get(owner).computeIfAbsent(key,wanted->{
   ClassLoader loader=owner.getClassLoader();if(loader==null)return false;
   try(java.io.InputStream in=loader.getResourceAsStream(owner.getName().replace('.','/')+".class")){
    return in!=null&&net.forbric.kernel.util.ByteScan.constantPoolNames(in.readAllBytes(),new byte[][]{net.forbric.kernel.util.ByteScan.poolEntry(wanted)})[0];
   }catch(java.io.IOException|RuntimeException unreadable){return false;}
  });
 }
 /** Only a transported original cancellation callback creates this consumer. Its delegate is called unchanged. */
 public static final class OwnedConsumer implements Consumer<Object> {
  private final Consumer<Object> original;private final Predicate<Object> cancel;
  @SuppressWarnings("unchecked") private OwnedConsumer(Consumer<?> original,Predicate<Object> cancel){this.original=(Consumer<Object>)Objects.requireNonNull(original);this.cancel=Objects.requireNonNull(cancel);}
  @Override public void accept(Object value){if(!cancel.test(value))original.accept(value);}
 }
 public static Consumer<?> consumer(Consumer<?> original,Predicate<Object> cancel){return new OwnedConsumer(original,cancel);}
}
