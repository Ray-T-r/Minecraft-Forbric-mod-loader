/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;
import java.util.function.Function;

/** Carries the guest callbacks through the synchronous native breathing calculation and event. */
public final class BreathingCallbackScope {
	private record Callbacks(Function<Object[],Object> lava,Function<Object[],Object> water) { }
	private static final ThreadLocal<Callbacks> CURRENT=new ThreadLocal<>();
	private BreathingCallbackScope() { }
	public static Object enter(Function<Object[],Object> lava,Function<Object[],Object> water){Callbacks previous=CURRENT.get();CURRENT.set(new Callbacks(lava,water));return previous;}
	public static void leave(Object previous){if(previous==null)CURRENT.remove();else CURRENT.set((Callbacks)previous);}
	public static void lava(Object entity,Object level){Callbacks active=CURRENT.get();if(active!=null)active.lava().apply(new Object[]{entity,level});}
	public static boolean water(Object entity,boolean nativeResult,Object level){Callbacks active=CURRENT.get();return active==null?nativeResult:(Boolean)active.water().apply(new Object[]{entity,nativeResult,level});}
}
