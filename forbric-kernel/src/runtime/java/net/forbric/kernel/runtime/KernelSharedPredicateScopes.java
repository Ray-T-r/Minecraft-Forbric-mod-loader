/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.StackWalker.StackFrame;
import java.util.*;
import java.util.function.*;
import net.forbric.kernel.boot.DefinedMethodContracts;
import net.forbric.kernel.boot.KernelSharedPredicateContracts;
import net.forbric.kernel.util.ForbricLog;

/** Shared-reference source leaves execute at actual owned native leaves, inside one host activation. */
public final class KernelSharedPredicateScopes {
 private static final ThreadLocal<Activation>HOST=new ThreadLocal<>();
 private static final StackWalker CALLER=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
 private static final Set<String>DIAGNOSTICS=java.util.concurrent.ConcurrentHashMap.newKeySet();
 private static final class Activation {
  final Object receiver;final String key;Query query;Boolean guard;
  Activation(Object receiver,String key){this.receiver=receiver;this.key=key;}
 }
 private static final class Query {
  final Activation host;final BooleanSupplier under;final Predicate<Object>ground;Boolean first,last;Object state,type;boolean invokingUnder,invokingGround;
  Query(Activation host,BooleanSupplier under,Predicate<Object>ground){this.host=host;this.under=under;this.ground=ground;}
 }
 private KernelSharedPredicateScopes(){ }
 public static Object host(Object receiver,String key,Supplier<Object>nativeHost){Activation previous=HOST.get();HOST.set(new Activation(receiver,key));try{return nativeHost.get();}finally{if(previous==null)HOST.remove();else HOST.set(previous);}}
 public static boolean query(Object receiver,String key,Supplier<Boolean>nativeQuery,BooleanSupplier under,Predicate<Object>ground){Activation host=HOST.get();if(!KernelFabricFluidBehaviors.enabled()||host==null||host.receiver!=receiver||!host.key.equals(key))return nativeQuery.get();if(!net.forbric.kernel.boot.SharedFinalSourceContracts.permits(receiver,key)||!KernelSharedPredicateContracts.roots(receiver,key)){decline(key,"native concrete/changed root or source-view witness retained");return nativeQuery.get();}Query previous=host.query;host.guard=null;host.query=new Query(host,under,ground);try{return nativeQuery.get();}finally{host.query=previous;}}
 static boolean active(){Activation host=HOST.get();return host!=null&&host.query!=null;}
 static Boolean answer(Object adapter,Object subject,StackFrame caller){Activation activation=HOST.get();if(activation==null||activation.query==null)return null;Query query=activation.query;if(subject!=activation.receiver){decline(activation.key,"actual native subject differs from the source activation; native leaf retained");return null;}var contract=KernelSharedPredicateContracts.contract(activation.key);if(contract==null||!caller.getMethodName().equals(contract.question())||!caller.getDescriptor().equals(contract.descriptor())||!(adapter instanceof net.neoforged.neoforge.fluids.FluidType type)||!KernelFabricFluidBehaviors.ownsNeoType(type))return null;
  var owned=KernelFluidPredicateSeams.ownedDefault(caller);if(owned==null||!KernelSharedPredicateContracts.safeVirtual(adapter,owned)||!DefinedMethodContracts.validates(adapter,owned)){decline(activation.key,"owned terminal final dispatch witness changed; native leaf retained");return null;}
  if(contract.dispatchKey()!=null&&!net.forbric.kernel.boot.NativeDefaultDispatchPaths.permits(subject,adapter,contract.dispatchKey(),CALLER.walk(frames->frames.toList()))){decline(activation.key,"actual native conditional/default dispatch path is unproved; native leaf retained");return null;}
  int leaf=leaf(contract);if(query.invokingUnder||query.invokingGround)return null;if(leaf==0){decline(activation.key,"owned leaf caller/CFG site witness is unavailable; native leaf retained");return null;}
  if(leaf==1){if(query.first==null){query.invokingUnder=true;try{query.first=query.under.getAsBoolean();}finally{query.invokingUnder=false;}}return query.first;}
  if(query.first==null){query.state=null;query.type=null;return null;}
  if(query.type!=adapter||query.first!=Boolean.TRUE||!KernelSharedPredicateContracts.state(query.state,activation.key)){decline(activation.key,"recorded receiver/default proof unavailable; native leaf retained");query.state=null;query.type=null;return null;}
  if(query.last==null){query.invokingGround=true;try{query.last=query.ground.test(query.state);}finally{query.invokingGround=false;}}query.state=null;query.type=null;return query.last;
 }
 /** Transparent record: it neither invokes a getter nor changes its returned value. */
 public static void capture(Object result,Object receiver,String site){Activation host=HOST.get();if(host==null||host.query==null)return;Query query=host.query;var contract=KernelSharedPredicateContracts.contract(host.key);if(contract==null||query.invokingUnder||query.invokingGround||!contract.getter().id().equals(site))return;StackFrame caller=CALLER.walk(frames->frames.skip(1).findFirst()).orElseThrow();if(!siteCaller(caller,contract.getter())){decline(host.key,"getter record came from an unproved site; native leaf retained");return;}query.state=receiver;query.type=result;}
 /** Original source continuation guard, after its modifier and before its following getter. */
 public static void recordGuard(boolean result,String site){Activation host=HOST.get();if(host==null||host.query==null||!host.query.invokingUnder)return;var contract=KernelSharedPredicateContracts.contract(host.key);if(contract==null||!contract.guardSite().equals(site))return;StackFrame caller=CALLER.walk(frames->frames.skip(1).findFirst()).orElseThrow();if(siteCaller(caller,contract.guardRecorder()))host.guard=result;else decline(host.key,"source continuation guard recorder changed; native guard retained");}
 public static boolean guard(Object receiver,String key,String site,Supplier<Boolean>nativeGuard){Activation host=HOST.get();var contract=KernelSharedPredicateContracts.contract(key);if(host!=null&&host.receiver==receiver&&host.key.equals(key)&&contract!=null&&contract.guardSite().equals(site)&&host.guard!=null){boolean result=host.guard;host.guard=null;return result;}return nativeGuard.get();}
 private static int leaf(KernelSharedPredicateContracts.Contract contract){return CALLER.walk(frames->{for(var iterator=frames.iterator();iterator.hasNext();){StackFrame frame=iterator.next();for(int which=1;which<=2;which++){var site=which==1?contract.first():contract.second();var method=site.caller();if(frame.getDeclaringClass().getName().equals(method.owner())&&frame.getMethodName().equals(method.name())&&frame.getDescriptor().equals(method.descriptor())&&frame.getByteCodeIndex()==site.bytecodeIndex()&&DefinedMethodContracts.observed(frame.getDeclaringClass().getClassLoader(),method))return which;}}return 0;});}
 private static boolean siteCaller(StackFrame caller,KernelSharedPredicateContracts.Site site){return caller.getDeclaringClass().getName().equals(site.owner())&&caller.getMethodName().equals(site.name())&&caller.getDescriptor().equals(site.descriptor());}
 private static void decline(String key,String reason){if(DIAGNOSTICS.add(key+":"+reason))ForbricLog.warn("[Forbric/Mixin] shared source predicate leaf was not executed; native leaf retained: "+reason+" (source="+key+")");}
}
