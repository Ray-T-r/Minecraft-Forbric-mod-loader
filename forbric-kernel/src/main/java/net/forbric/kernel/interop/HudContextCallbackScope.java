/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;
import java.util.List;import java.util.function.Function;import java.util.function.Supplier;

/**
 * The guest callbacks open around the native contextual-bar update. A frame's callback takes {@code [hud, original]};
 * {@code original} is a {@link CallbackFrames.Next}: applied to the HUD the handler passes on, it is the next frame inward,
 * or the native selection.
 */
public final class HudContextCallbackScope {
	private static final CallbackFrames<Function<Object[],Object>> FRAMES=new CallbackFrames<>();
	private HudContextCallbackScope() { }
	public static Object enter(Function<Object[],Object> callback){return FRAMES.enter(callback);}
	public static void leave(Object previous){FRAMES.leave(previous);}
	/** {@link #compose} with a native selection that does not depend on which HUD is passed on. */
	public static Object query(Object hud,Supplier<?> nativeQuery){return compose(hud,passed->nativeQuery.get());}
	/** Every open frame, outermost first, each handed the next one inward as its original; the innermost, {@code nativeQuery}. */
	public static Object compose(Object hud,Function<Object,Object> nativeQuery){
		List<Function<Object[],Object>> frames=FRAMES.outermostFirst();if(frames.isEmpty())return nativeQuery.apply(hud);
		Object open=FRAMES.suspend();
		try{return call(frames,0,hud,nativeQuery);}finally{FRAMES.leave(open);}
	}
	private static Object call(List<Function<Object[],Object>> frames,int index,Object hud,Function<Object,Object> nativeQuery){
		if(index==frames.size())return nativeQuery.apply(hud);
		return frames.get(index).apply(new Object[]{hud,new CallbackFrames.Next(hud,passed->call(frames,index+1,passed,nativeQuery))});
	}
}
