/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;
import java.util.List;import java.util.function.Function;import java.util.function.Supplier;

/**
 * The native block still plays its sound; the guest callbacks open around that playback supply the original sound-group
 * override only when queried.
 *
 * <p>A frame's callback takes {@code [state, pos, original]}; {@code original} is a {@link CallbackFrames.Next}: applied
 * to the state the handler passes on, it is the next frame inward, or the native query for that state.
 */
public final class BlockSoundCallbackScope {
	private static final CallbackFrames<Function<Object[],Object>> FRAMES=new CallbackFrames<>();
	private BlockSoundCallbackScope() { }
	public static Object enter(Function<Object[],Object> callback){return FRAMES.enter(callback);}
	public static void leave(Object previous){FRAMES.leave(previous);}
	/**
	 * The innermost frame alone, handed {@code nativeQuery} itself: the single-frame contract this scope first had. The
	 * native sound methods do not read it; they ask {@link #compose}, which runs every open frame.
	 */
	public static Object query(Object state,Object pos,Supplier<?> nativeQuery){var callback=FRAMES.innermost();return callback==null?nativeQuery.get():callback.apply(new Object[]{state,pos,nativeQuery});}
	/**
	 * Every open frame, outermost first, each handed the next one inward as its original; the innermost is handed
	 * {@code nativeQuery}, which answers for whatever state the handlers pass on.
	 */
	public static Object compose(Object state,Object pos,Function<Object,Object> nativeQuery){
		List<Function<Object[],Object>> frames=FRAMES.outermostFirst();if(frames.isEmpty())return nativeQuery.apply(state);
		Object open=FRAMES.suspend();
		try{return call(frames,0,state,pos,nativeQuery);}finally{FRAMES.leave(open);}
	}
	private static Object call(List<Function<Object[],Object>> frames,int index,Object state,Object pos,Function<Object,Object> nativeQuery){
		if(index==frames.size())return nativeQuery.apply(state);
		return frames.get(index).apply(new Object[]{state,pos,new CallbackFrames.Next(state,passed->call(frames,index+1,passed,pos,nativeQuery))});
	}
}
