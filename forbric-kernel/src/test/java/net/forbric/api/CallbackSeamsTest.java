/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.api;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
class CallbackSeamsTest {
 static class Host{}static class Helper{}static class Other{}
 private final ClassLoader loader=Host.class.getClassLoader();
 @AfterEach void release(){CallbackSeams.release(loader);}
 @Test void absentOrFailedFinalWitnessCannotArmAScope(){
  assertThrows(IllegalStateException.class,()->CallbackSeams.enter(Host.class,Helper.class,"missing",()->fail("unproved callback")));
  CallbackSeams.register(loader,"false",Host.class.getName(),Helper.class.getName(),ignored->false);
  assertThrows(IllegalStateException.class,()->CallbackSeams.enter(Host.class,Helper.class,"false",()->fail("changed final body")));
 }
 @Test void theImmediateClaimIgnoresDirectAndRecursiveHelpersAndChecksTheActualHostAndHelper(){
  AtomicInteger called=new AtomicInteger();CallbackSeams.register(loader,"scope",Host.class.getName(),Helper.class.getName(),ignored->true);
  CallbackSeams.beginHelper(Helper.class,"scope").fire();assertEquals(0,called.get());
  assertThrows(IllegalStateException.class,()->CallbackSeams.enter(Other.class,Helper.class,"scope",called::incrementAndGet));
  try(var scope=CallbackSeams.enter(Host.class,Helper.class,"scope",called::incrementAndGet)){
   var outer=CallbackSeams.beginHelper(Helper.class,"scope");CallbackSeams.beginHelper(Helper.class,"scope").fire();assertEquals(0,called.get());
   outer.fire();scope.complete();assertEquals(1,called.get());assertThrows(IllegalStateException.class,outer::fire);
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
  try{assertThrows(IllegalStateException.class,()->CallbackSeams.enter(second,second,"identity",()->fail("foreign loader")));assertThrows(IllegalStateException.class,()->CallbackSeams.enter(first,second,"identity",()->fail("foreign helper")));}
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
