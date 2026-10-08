/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.api;
import java.lang.ref.WeakReference;
/** A source-proved lookup outcome controls its caller's cache without changing the lookup or retaining game objects. */
public final class LookupOutcomes {
 private record Outcome(WeakReference<Object> owner,WeakReference<Class<?>> type,boolean foreign){}
 private static final ThreadLocal<Outcome> CURRENT=new ThreadLocal<>();
 private LookupOutcomes(){}
 public static void nativeLookup(Object owner,Class<?> type){CURRENT.set(new Outcome(new WeakReference<>(owner),new WeakReference<>(type),false));}
 public static Object foreign(Object owner,Class<?> type,Object value){CURRENT.set(new Outcome(new WeakReference<>(owner),new WeakReference<>(type),true));return value;}
 /** Consume only the exact lookup's latest outcome; stale direct calls and other actors/types cannot affect a cache. */
 public static boolean takeForeign(Object owner,Class<?> type){Outcome outcome=CURRENT.get();CURRENT.remove();return outcome!=null&&outcome.owner.get()==owner&&outcome.type.get()==type&&outcome.foreign;}
}
