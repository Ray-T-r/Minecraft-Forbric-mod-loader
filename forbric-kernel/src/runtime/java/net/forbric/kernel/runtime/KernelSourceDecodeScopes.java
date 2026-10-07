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
 static boolean alreadyJudged(Object input,MethodHandle evaluator){
  Scope scope=ACTIVE.get();if(scope==null||evaluator==null)return false;
  try { var info=MethodHandles.lookup().revealDirect(evaluator);return scope.judged.stream().anyMatch(j->j.input==input&&j.owner==info.getDeclaringClass()&&j.name.equals(info.getName())&&j.type.equals(info.getMethodType())); }
  catch(RuntimeException unproved){return false;}
 }
 /** Only a transported original cancellation callback creates this consumer. Its delegate is called unchanged. */
 public static final class OwnedConsumer implements Consumer<Object> {
  private final Consumer<Object> original;private final Predicate<Object> cancel;
  @SuppressWarnings("unchecked") private OwnedConsumer(Consumer<?> original,Predicate<Object> cancel){this.original=(Consumer<Object>)Objects.requireNonNull(original);this.cancel=Objects.requireNonNull(cancel);}
  @Override public void accept(Object value){if(!cancel.test(value))original.accept(value);}
 }
 public static Consumer<?> consumer(Consumer<?> original,Predicate<Object> cancel){return new OwnedConsumer(original,cancel);}
}
