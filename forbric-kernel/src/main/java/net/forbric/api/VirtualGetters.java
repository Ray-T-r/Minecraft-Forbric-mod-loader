/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.api;

import java.lang.invoke.*;
import java.util.concurrent.ConcurrentHashMap;

/** Select a public virtual getter by its complete JVM descriptor, including its return type. */
public final class VirtualGetters {
    private VirtualGetters() { }
    private record Key(String name, Class<?> result) { }
    private static final ClassValue<ConcurrentHashMap<Key, MethodHandle>> CACHE = new ClassValue<>() {
        @Override protected ConcurrentHashMap<Key, MethodHandle> computeValue(Class<?> type) { return new ConcurrentHashMap<>(); }
    };
    /** Java source cannot choose between two carrier methods differing only in return type; the JVM can. */
    @SuppressWarnings("unchecked")
    public static <T> T get(Class<?> declaration, String name, Class<T> result, Object receiver) {
        Key key = new Key(name, result);
        MethodHandle call = CACHE.get(declaration).computeIfAbsent(key, unused -> resolve(declaration, key));
        try { return (T) call.invokeExact(receiver); }
        catch (Throwable failure) { return VirtualGetters.<T, RuntimeException>raise(failure); }
    }
    private static MethodHandle resolve(Class<?> declaration, Key key) {
        try {
            return MethodHandles.publicLookup().findVirtual(declaration, key.name, MethodType.methodType(key.result))
                .asType(MethodType.methodType(Object.class, Object.class));
        } catch (NoSuchMethodException missing) {
            NoSuchMethodError error = new NoSuchMethodError(declaration.getName() + "." + key.name + MethodType.methodType(key.result));
            error.initCause(missing); throw error;
        } catch (IllegalAccessException inaccessible) {
            IllegalAccessError error = new IllegalAccessError(declaration.getName() + "." + key.name);
            error.initCause(inaccessible); throw error;
        }
    }
    @SuppressWarnings("unchecked") private static <T, E extends Throwable> T raise(Throwable failure) throws E { throw (E) failure; }
}
