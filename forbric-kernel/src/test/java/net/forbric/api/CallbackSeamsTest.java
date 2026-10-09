/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.api;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
class CallbackSeamsTest {
 static class Host{}static class Helper{}static class Other{}
 private final ClassLoader loader=Host.class.getClassLoader();
 @AfterEach void release(){CallbackSeams.release(loader);}
 /** A declined invocation runs the helper as written: no scope is armed, nothing throws, and the site reports once. */
 private static void invoke(Class<?> host,Class<?> helper,String key,Runnable callback){
  try(var scope=CallbackSeams.enter(host,helper,key,callback)){CallbackSeams.Token token=CallbackSeams.beginHelper(helper,key);token.fire();scope.complete();}
 }
 @Test void absentOrFailedFinalWitnessDeclinesEveryInvocationAndReportsTheSiteOnce(){
  invoke(Host.class,Helper.class,"missing",()->fail("unproved callback"));
  List<String> reports=new ArrayList<>();AtomicBoolean valid=new AtomicBoolean(false);
  CallbackSeams.register(loader,"false",Host.class.getName(),Helper.class.getName(),ignored->valid.get(),(owner,key,reason)->{assertSame(loader,owner);reports.add(key+": "+reason);});
  for(int i=0;i<3;i++)invoke(Host.class,Helper.class,"false",()->fail("changed final body"));
  assertEquals(List.of("false: the final body witness does not hold"),reports);
  CallbackSeams.beginHelper(Helper.class,"false").fire();
  AtomicInteger called=new AtomicInteger();valid.set(true);invoke(Host.class,Helper.class,"false",called::incrementAndGet);
  assertEquals(1,called.get(),"a proof that holds again arms the scope as before");assertEquals(1,reports.size());
  CallbackSeams.register(loader,"reporter",Host.class.getName(),Helper.class.getName(),ignored->false,(owner,key,reason)->{throw new IllegalStateException("broken report");});
  assertDoesNotThrow(()->invoke(Host.class,Helper.class,"reporter",()->fail("changed final body")),"a failing report never reaches the host");
 }
 @Test void aProvedScopeWhoseHelperSkipsOrRepeatsItsSourceCallReportsInsteadOfThrowing(){
  List<String> reports=new ArrayList<>();AtomicInteger called=new AtomicInteger();
  CallbackSeams.register(loader,"unreached",Host.class.getName(),Helper.class.getName(),ignored->true,(owner,key,reason)->reports.add(key));
  for(int i=0;i<2;i++)try(var scope=CallbackSeams.enter(Host.class,Helper.class,"unreached",called::incrementAndGet)){CallbackSeams.beginHelper(Helper.class,"unreached");scope.complete();}
  assertEquals(List.of("unreached"),reports);assertEquals(0,called.get());
  CallbackSeams.register(loader,"repeated",Host.class.getName(),Helper.class.getName(),ignored->true,(owner,key,reason)->reports.add(key));
  try(var scope=CallbackSeams.enter(Host.class,Helper.class,"repeated",called::incrementAndGet)){var token=CallbackSeams.beginHelper(Helper.class,"repeated");token.fire();token.fire();scope.complete();}
  assertEquals(List.of("unreached","repeated"),reports);assertEquals(1,called.get());
 }
 @Test void theImmediateClaimIgnoresDirectAndRecursiveHelpersAndChecksTheActualHostAndHelper(){
  AtomicInteger called=new AtomicInteger();CallbackSeams.register(loader,"scope",Host.class.getName(),Helper.class.getName(),ignored->true);
  CallbackSeams.beginHelper(Helper.class,"scope").fire();assertEquals(0,called.get());
  invoke(Other.class,Helper.class,"scope",called::incrementAndGet);assertEquals(0,called.get(),"another host is declined, not armed");
  try(var scope=CallbackSeams.enter(Host.class,Helper.class,"scope",called::incrementAndGet)){
   var outer=CallbackSeams.beginHelper(Helper.class,"scope");CallbackSeams.beginHelper(Helper.class,"scope").fire();assertEquals(0,called.get());
   outer.fire();scope.complete();assertEquals(1,called.get());outer.fire();assertEquals(1,called.get(),"a second firing is skipped");
  }
  CallbackSeams.beginHelper(Helper.class,"scope").fire();assertEquals(1,called.get());
 }
 @Test void nestedHostInvocationsGetIndependentCallbacksAndAnExceptionDoesNotLeakAScope()throws Exception{
  List<String> calls=new ArrayList<>();CallbackSeams.register(loader,"nested",Host.class.getName(),Helper.class.getName(),ignored->true);
  try(var outer=CallbackSeams.enter(Host.class,Helper.class,"nested",()->calls.add("outer"))){
   var outerToken=CallbackSeams.beginHelper(Helper.class,"nested");
   try(var inner=CallbackSeams.enter(Host.class,Helper.class,"nested",()->calls.add("inner"))){CallbackSeams.beginHelper(Helper.class,"nested").fire();inner.complete();}
   Thread thread=new Thread(()->CallbackSeams.beginHelper(Helper.class,"nested").fire());thread.start();thread.join();outerToken.fire();outer.complete();
  }assertEquals(List.of("inner","outer"),calls);
  assertThrows(IllegalStateException.class,()->{try(var failing=CallbackSeams.enter(Host.class,Helper.class,"nested",()->{throw new IllegalStateException("callback");})){CallbackSeams.beginHelper(Helper.class,"nested").fire();}});
  CallbackSeams.beginHelper(Helper.class,"nested").fire();assertEquals(List.of("inner","outer"),calls);
 }
 @Test void sameNamedClassesAndLoadersWithCustomEqualityCannotReuseAnotherLoadersWitness(){
  var a=new OperationSeamsTest.SameLoader();var b=new OperationSeamsTest.SameLoader();Class<?> first=a.marker(),second=b.marker();
  CallbackSeams.register(a,"identity",first.getName(),first.getName(),ignored->true);
  try{invoke(second,second,"identity",()->fail("foreign loader"));invoke(first,second,"identity",()->fail("foreign helper"));}
  finally{CallbackSeams.release(a);CallbackSeams.release(b);}
 }
 @Test void anotherThreadCannotClaimAPendingHostScope()throws Exception{
  AtomicInteger calls=new AtomicInteger();AtomicReference<Throwable> failed=new AtomicReference<>();CallbackSeams.register(loader,"thread",Host.class.getName(),Helper.class.getName(),ignored->true);
  try(var scope=CallbackSeams.enter(Host.class,Helper.class,"thread",calls::incrementAndGet)){
   Thread thread=new Thread(()->{try{CallbackSeams.beginHelper(Helper.class,"thread").fire();}catch(Throwable fault){failed.set(fault);}});thread.start();thread.join();assertNull(failed.get());assertEquals(0,calls.get());
   CallbackSeams.beginHelper(Helper.class,"thread").fire();scope.complete();assertEquals(1,calls.get());
  }
 }
}
