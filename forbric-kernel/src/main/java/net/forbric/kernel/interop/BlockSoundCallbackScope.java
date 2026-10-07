/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;
import java.util.function.Function;import java.util.function.Supplier;

/** The native block still plays its sound; the scoped callback supplies the original sound-group override only when queried. */
public final class BlockSoundCallbackScope {
	private static final ThreadLocal<Function<Object[],Object>> CURRENT=new ThreadLocal<>();
	private BlockSoundCallbackScope() { }
	public static Object enter(Function<Object[],Object> callback){var previous=CURRENT.get();CURRENT.set(callback);return previous;}
	@SuppressWarnings("unchecked") public static void leave(Object previous){if(previous==null)CURRENT.remove();else CURRENT.set((Function<Object[],Object>)previous);}
	public static Object query(Object state,Object pos,Supplier<?> nativeQuery){var callback=CURRENT.get();return callback==null?nativeQuery.get():callback.apply(new Object[]{state,pos,nativeQuery});}
}
