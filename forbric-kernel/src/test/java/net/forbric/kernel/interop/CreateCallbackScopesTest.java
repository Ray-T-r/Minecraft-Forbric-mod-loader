/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;import org.junit.jupiter.api.Test;

class CreateCallbackScopesTest {
	@Test void soundOverrideIsLazyAndScopesRestoreAfterNestedCalls(){
		AtomicInteger nativeCalls=new AtomicInteger();Object state=new Object(),pos=new Object(),nativeValue=new Object(),custom=new Object();
		assertSame(nativeValue,BlockSoundCallbackScope.query(state,pos,()->{nativeCalls.incrementAndGet();return nativeValue;}));
		Object previous=BlockSoundCallbackScope.enter(args->{assertSame(state,args[0]);assertSame(pos,args[1]);return custom;});
		try{assertSame(custom,BlockSoundCallbackScope.query(state,pos,()->{nativeCalls.incrementAndGet();return nativeValue;}));assertEquals(1,nativeCalls.get());Object outer=BlockSoundCallbackScope.enter(args->((java.util.function.Supplier<?>)args[2]).get());try{assertSame(nativeValue,BlockSoundCallbackScope.query(state,pos,()->nativeValue));}finally{BlockSoundCallbackScope.leave(outer);}assertSame(custom,BlockSoundCallbackScope.query(state,pos,()->nativeValue));}finally{BlockSoundCallbackScope.leave(previous);}
		assertSame(nativeValue,BlockSoundCallbackScope.query(state,pos,()->nativeValue));
	}
	@Test void breathingKeepsNativeDecisionsWithoutScopeAndHandsTheExactResultToTheOriginalCallback(){
		Object entity=new Object(),level=new Object();AtomicInteger lava=new AtomicInteger();assertFalse(BreathingCallbackScope.water(entity,false,level));assertTrue(BreathingCallbackScope.water(entity,true,level));
		Object old=BreathingCallbackScope.enter(args->{assertSame(entity,args[0]);assertSame(level,args[1]);lava.incrementAndGet();return null;},args->{assertSame(entity,args[0]);assertSame(level,args[2]);return args[1];});try{BreathingCallbackScope.lava(entity,level);assertFalse(BreathingCallbackScope.water(entity,false,level));assertTrue(BreathingCallbackScope.water(entity,true,level));assertEquals(1,lava.get());}finally{BreathingCallbackScope.leave(old);}assertFalse(BreathingCallbackScope.water(entity,false,level));
	}
	@Test void hudOverrideCanSkipNativeSelectionAndRestoresThePreviousContext(){
		Object hud=new Object(),nativeValue=new Object(),empty=new Object();Object old=HudContextCallbackScope.enter(args->{assertSame(hud,args[0]);return empty;});try{assertSame(empty,HudContextCallbackScope.query(hud,()->{throw new AssertionError("selection must be skipped");}));}finally{HudContextCallbackScope.leave(old);}assertSame(nativeValue,HudContextCallbackScope.query(hud,()->nativeValue));
	}
}
