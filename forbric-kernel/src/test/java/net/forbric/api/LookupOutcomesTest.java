/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.api;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
class LookupOutcomesTest {
 @Test void onlyTheActualForeignOutcomeAffectsItsActorAndTypeAndItIsConsumed(){Object owner=new Object(),value=new Object();LookupOutcomes.nativeLookup(owner,String.class);assertFalse(LookupOutcomes.takeForeign(owner,String.class));assertSame(value,LookupOutcomes.foreign(owner,String.class,value));assertTrue(LookupOutcomes.takeForeign(owner,String.class));assertFalse(LookupOutcomes.takeForeign(owner,String.class));LookupOutcomes.foreign(owner,String.class,value);assertFalse(LookupOutcomes.takeForeign(new Object(),String.class));LookupOutcomes.foreign(owner,String.class,value);assertFalse(LookupOutcomes.takeForeign(owner,Integer.class));}
 @Test void aFreshNativeLookupReplacesAnOldDirectOrFailedLookupOutcome(){Object owner=new Object();LookupOutcomes.foreign(owner,String.class,"old");LookupOutcomes.nativeLookup(owner,String.class);assertFalse(LookupOutcomes.takeForeign(owner,String.class));LookupOutcomes.foreign(owner,String.class,"old");LookupOutcomes.nativeLookup(owner,String.class);try{throw new IllegalStateException("provider");}catch(IllegalStateException expected){}LookupOutcomes.nativeLookup(owner,String.class);assertFalse(LookupOutcomes.takeForeign(owner,String.class));}
 @Test void anotherThreadDoesNotConsumeOrClassifyThisThreadsLookup()throws Exception{Object owner=new Object();LookupOutcomes.foreign(owner,String.class,"value");AtomicBoolean foreign=new AtomicBoolean(true);Thread thread=new Thread(()->foreign.set(LookupOutcomes.takeForeign(owner,String.class)));thread.start();thread.join();assertFalse(foreign.get());assertTrue(LookupOutcomes.takeForeign(owner,String.class));}
}
