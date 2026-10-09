/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;
import java.util.List;import java.util.function.Function;

/**
 * Carries the guest callbacks through the synchronous native breathing calculation and event. Either callback may be
 * absent (null): a mod that wraps only one of the two vanilla calls leaves the other decision to NeoForge.
 *
 * <p>Each frame holds one mod's pair. {@link #lava} runs every open frame's lava callback, outermost first. {@link #water}
 * composes the frames that wrap the water call as vanilla composes two wraps of one call: the outermost callback is
 * called, and its original is the next frame's answer, computed only if it asks ({@link CallbackFrames.Deferred}); the
 * innermost's original is the native result.
 */
public final class BreathingCallbackScope {
	private record Callbacks(Function<Object[],Object> lava,Function<Object[],Object> water) { }
	private static final CallbackFrames<Callbacks> FRAMES=new CallbackFrames<>();
	private BreathingCallbackScope() { }
	public static Object enter(Function<Object[],Object> lava,Function<Object[],Object> water){return FRAMES.enter(new Callbacks(lava,water));}
	public static void leave(Object previous){FRAMES.leave(previous);}
	public static void lava(Object entity,Object level){
		List<Callbacks> frames=FRAMES.outermostFirst().stream().filter(frame->frame.lava()!=null).toList();if(frames.isEmpty())return;
		Object open=FRAMES.suspend();
		try{for(Callbacks frame:frames)frame.lava().apply(new Object[]{entity,level});}finally{FRAMES.leave(open);}
	}
	public static boolean water(Object entity,boolean nativeResult,Object level){
		List<Callbacks> frames=FRAMES.outermostFirst().stream().filter(frame->frame.water()!=null).toList();if(frames.isEmpty())return nativeResult;
		Object open=FRAMES.suspend();
		try{return (Boolean)CallbackFrames.Deferred.resolve(water(frames,0,entity,nativeResult,level));}finally{FRAMES.leave(open);}
	}
	private static Object water(List<Callbacks> frames,int index,Object entity,boolean nativeResult,Object level){
		Object original=index+1==frames.size()?(Object)nativeResult:new CallbackFrames.Deferred(()->CallbackFrames.Deferred.resolve(water(frames,index+1,entity,nativeResult,level)));
		return frames.get(index).water().apply(new Object[]{entity,original,level});
	}
}
