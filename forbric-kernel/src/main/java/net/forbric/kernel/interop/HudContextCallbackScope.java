/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;
import java.util.function.Function;import java.util.function.Supplier;

public final class HudContextCallbackScope {
	private static final ThreadLocal<Function<Object[],Object>> CURRENT=new ThreadLocal<>();
	private HudContextCallbackScope() { }
	public static Object enter(Function<Object[],Object> callback){var previous=CURRENT.get();CURRENT.set(callback);return previous;}
	@SuppressWarnings("unchecked") public static void leave(Object previous){if(previous==null)CURRENT.remove();else CURRENT.set((Function<Object[],Object>)previous);}
	public static Object query(Object hud,Supplier<?> nativeQuery){var callback=CURRENT.get();return callback==null?nativeQuery.get():callback.apply(new Object[]{hud,nativeQuery});}
}
