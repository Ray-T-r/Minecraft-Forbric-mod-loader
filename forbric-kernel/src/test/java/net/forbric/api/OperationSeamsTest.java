/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.api;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;

/** Source operations preserve mutable operands while native gates and selection control their exact lifetime. */
class OperationSeamsTest {
 static class Host{}static class Helper{}static class Foreign{}
 record Receiver(String id){}
 private final ClassLoader loader=Host.class.getClassLoader();private final String graph="unknown-proved-graph";
 @AfterEach void release(){OperationSeams.release(loader);}
 private void register(String key){OperationSeams.register(loader,key,Host.class.getName(),Helper.class.getName(),graph,ignored->true);}
 private Object scoped(String key,OperationSeams.Invocation source,OperationSeams.NativeOperation gateway,Object... arguments)throws Throwable{
  return OperationSeams.scoped(Host.class,Helper.class,key,source,gateway,arguments);
 }
 private Object selected(OperationSeams.NativeOperation nativeBody,Object[] values)throws Throwable{return OperationSeams.selected(Helper.class,graph,nativeBody,values);}
 private Object apply(OperationSeams.NativeOperation original,Object[] values)throws Throwable{return OperationSeams.apply(Helper.class,graph,original,values);}
 private static Object original(Object operation,Object[] values)throws Throwable{return ((OperationSeams.NativeOperation)operation).invoke(values);}

 @Test void receiverAndEveryArgumentMayBeReplacedAtTheSourceOperationInsideOneNativePrePostPair()throws Throwable{
  register("replace");List<String> events=new ArrayList<>();Receiver before=new Receiver("old"),after=new Receiver("new");Object context=new Object();
  OperationSeams.Invocation source=(operation,values)->{assertSame(before,values[0]);assertSame(context,values[1]);assertEquals(5,values[2]);assertEquals(9L,values[3]);assertEquals(1.25f,values[4]);events.add("source");return original(operation,new Object[]{after,context,7,12L,2.5f});};
  Object result=scoped("replace",source,values->selected(nativeValues->{events.add("pre:"+((Receiver)nativeValues[0]).id());Object answer=apply(actual->{assertSame(after,actual[0]);assertSame(context,actual[1]);events.add("native:"+actual[2]+":"+actual[3]+":"+actual[4]);return "drawn";},nativeValues);events.add("post:"+((Receiver)nativeValues[0]).id());return answer;},values),before,context,5,9L,1.25f);
  assertEquals("drawn",result);assertEquals(List.of("pre:old","source","native:7:12:2.5","post:old"),events);
 }
 @Test void aGuestMaySkipOrRepeatItsOperationWithoutRepeatingTheNativePrePostGates()throws Throwable{
  register("count");List<String> events=new ArrayList<>();OperationSeams.NativeOperation gateway=values->selected(v->{events.add("pre");Object answer=apply(actual->{events.add("native:"+actual[0]);return actual[0];},v);events.add("post");return answer;},values);
  assertEquals("guest",scoped("count",(operation,values)->{events.add("skip");return "guest";},gateway,"one"));assertEquals(List.of("pre","skip","post"),events);
  events.clear();assertEquals("two",scoped("count",(operation,values)->{events.add("source");original(operation,new Object[]{"one"});return original(operation,new Object[]{"two"});},gateway,"input"));assertEquals(List.of("pre","source","native:one","native:two","post"),events);
 }
 @Test void nativeCancellationRunsBeforeTheSourceAndClearsTheSelectionForTheNextInvocation()throws Throwable{
  register("cancel");List<String> events=new ArrayList<>();AtomicBoolean cancel=new AtomicBoolean(true);
  OperationSeams.NativeOperation gateway=values->selected(v->{events.add("pre");if(cancel.get())return "cancelled";Object answer=apply(actual->{events.add("native");return "done";},v);events.add("post");return answer;},values);
  OperationSeams.Invocation source=(operation,values)->{events.add("source");return original(operation,values);};
  assertEquals("cancelled",scoped("cancel",source,gateway,"value"));assertEquals(List.of("pre"),events);events.clear();cancel.set(false);
  assertEquals("done",scoped("cancel",source,gateway,"value"));assertEquals(List.of("pre","source","native","post"),events);
 }
 @Test void layerForeignAndUnselectedCallsKeepTheirOwnOperationWhileOnlyTheExactTopCallIsWrapped()throws Throwable{
  register("top");List<String> events=new ArrayList<>();OperationSeams.NativeOperation nativeCall=values->{events.add("native:"+values[0]);return null;};
  scoped("top",(operation,values)->{events.add("source:"+values[0]);return original(operation,values);},values->{
   apply(nativeCall,new Object[]{"layer"});OperationSeams.selected(Foreign.class,graph,v->OperationSeams.apply(Foreign.class,graph,nativeCall,v),new Object[]{"foreign"});
   OperationSeams.selected(Helper.class,"another",v->OperationSeams.apply(Helper.class,"another",nativeCall,v),new Object[]{"another"});
   selected(v->{apply(nativeCall,v);apply(nativeCall,new Object[]{"same-selection-second"});return null;},new Object[]{"top"});apply(nativeCall,new Object[]{"after"});return null;
  },"gateway");assertEquals(List.of("native:layer","native:foreign","native:another","source:top","native:top","native:same-selection-second","native:after"),events);
 }
 @Test void nestedSourceScopesKeepTheirDeclaredNestingAndAnIndependentNestedHostDoesNotStealOuterFrames()throws Throwable{
  register("outer");register("inner");register("nested");List<String> events=new ArrayList<>();
  OperationSeams.Invocation outer=(operation,values)->{events.add("outer.before");Object answer=original(operation,values);events.add("outer.after");return answer;};
  OperationSeams.Invocation inner=(operation,values)->{events.add("inner.before");scoped("nested",(nested,v)->{events.add("nested.source");return original(nested,v);},v->selected(selected->apply(actual->{events.add("nested.native");return null;},selected),v),"nested");Object answer=original(operation,values);events.add("inner.after");return answer;};
  scoped("outer",outer,values->scoped("inner",inner,v->selected(selected->apply(actual->{events.add("native");return null;},selected),v),values),"root");
  assertEquals(List.of("outer.before","inner.before","nested.source","nested.native","native","inner.after","outer.after"),events);
 }
 @Test void recursionAndOtherThreadsDoNotReuseAnOuterSelectionAndFailuresDoNotLeakItsSource()throws Throwable{
  register("recursive");List<String> events=new ArrayList<>();OperationSeams.NativeOperation[] recursive=new OperationSeams.NativeOperation[1];recursive[0]=values->{events.add("native:"+values[0]);if(values[0].equals("root"))selected(v->apply(recursive[0],v),new Object[]{"recursive"});return null;};
  scoped("recursive",(operation,values)->{events.add("source");return original(operation,values);},values->selected(v->apply(recursive[0],v),values),"root");assertEquals(List.of("source","native:root","native:recursive"),events);
  assertThrows(IllegalStateException.class,()->scoped("recursive",(operation,values)->{throw new IllegalStateException("guest failed");},values->selected(v->apply(recursive[0],v),values),"failure"));
  events.clear();apply(recursive[0],new Object[]{"direct"});assertEquals(List.of("native:direct"),events);
  AtomicInteger sourceCalls=new AtomicInteger(),nativeCalls=new AtomicInteger();AtomicReference<Throwable> failure=new AtomicReference<>();scoped("recursive",(operation,values)->{sourceCalls.incrementAndGet();return original(operation,values);},values->{Thread thread=new Thread(()->{try{selected(v->apply(a->{nativeCalls.incrementAndGet();return null;},v),new Object[]{"thread"});}catch(Throwable failed){failure.set(failed);}});thread.start();thread.join();return null;},"outer");assertNull(failure.get());assertEquals(0,sourceCalls.get());assertEquals(1,nativeCalls.get());
 }
 /** Declined, never refused: the native gateway runs exactly as written, the source is skipped, the site reports once. */
 @Test void missingChangedWrongHostAndDifferentLoaderWitnessesRunTheNativeGatewayAlone()throws Throwable{
  OperationSeams.Invocation source=(operation,values)->fail("unproved source");List<String> events=new ArrayList<>();
  OperationSeams.NativeOperation gateway=values->selected(v->apply(actual->{events.add("native:"+actual[0]);return "native";},v),values);
  assertEquals("native",scoped("missing",source,gateway,"a"));
  List<String> reports=new ArrayList<>();AtomicBoolean valid=new AtomicBoolean(true);
  OperationSeams.register(loader,"changing",Host.class.getName(),Helper.class.getName(),graph,ignored->valid.get(),new int[0],new int[0],(owner,key,reason)->{assertSame(loader,owner);reports.add(key+": "+reason);});valid.set(false);
  assertEquals("native",scoped("changing",source,gateway,"b"));assertEquals("native",scoped("changing",source,gateway,"c"));
  assertEquals(List.of("changing: the final body witness does not hold"),reports);
  register("wrong");assertEquals("native",OperationSeams.scoped(Foreign.class,Helper.class,"wrong",source,gateway,new Object[]{"d"}));assertEquals("native",OperationSeams.scoped(Host.class,Foreign.class,"wrong",source,gateway,new Object[]{"e"}));
  SameLoader a=new SameLoader(),b=new SameLoader();Class<?> first=a.marker(),second=b.marker();assertEquals(first.getName(),second.getName());OperationSeams.register(a,"identity",first.getName(),first.getName(),graph,ignored->true);
  try{assertEquals("native",OperationSeams.scoped(first,second,"identity",source,gateway,new Object[]{"f"}));assertEquals("native",OperationSeams.scoped(second,second,"identity",source,gateway,new Object[]{"g"}));}finally{OperationSeams.release(a);OperationSeams.release(b);}
  OperationSeams.register(loader,"reporter",Host.class.getName(),Helper.class.getName(),graph,ignored->false,new int[0],new int[0],(owner,key,reason)->{throw new IllegalStateException("broken report");});
  assertEquals("native",scoped("reporter",source,gateway,"h"),"a failing report never reaches the host");
  assertEquals(List.of("native:a","native:b","native:c","native:d","native:e","native:f","native:g","native:h"),events);
  valid.set(true);assertEquals("guest",scoped("changing",(operation,values)->"guest",gateway,"i"),"a proof that holds again scopes the source as before");assertEquals(1,reports.size());
 }
 static class SameLoader extends ClassLoader {
  @Override public boolean equals(Object ignored){return true;}@Override public int hashCode(){return 1;}
  Class<?> marker(){ClassWriter w=new ClassWriter(0);w.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,"arbitrary/Marker",null,"java/lang/Object",null);w.visitEnd();byte[] bytes=w.toByteArray();return defineClass("arbitrary.Marker",bytes,0,bytes.length);}
 }
}
