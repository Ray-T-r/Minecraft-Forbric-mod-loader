/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.api;

import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.util.*;

/** Access a mutable property only when its final public getter is a proved direct field read. */
public final class VirtualProperties {
    public record Field(String owner, String name, String descriptor) { }
    private static final java.lang.ref.ReferenceQueue<ClassLoader> COLLECTED = new java.lang.ref.ReferenceQueue<>();
    private static final Map<LoaderIdentity, Map<String, Map<String, Field>>> PROOFS = new HashMap<>();
    private static final Map<String, Map<String, Field>> BOOTSTRAP = new HashMap<>();
    private static final class LoaderIdentity extends java.lang.ref.WeakReference<ClassLoader> {
        private final int hash;
        LoaderIdentity(ClassLoader loader, boolean retained) { super(loader, retained ? COLLECTED : null); hash = System.identityHashCode(loader); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) { return this == other || other instanceof LoaderIdentity key && get() != null && get() == key.get(); }
    }
    private static Map<String, Map<String, Field>> ledger(ClassLoader loader, boolean create) {
        for (java.lang.ref.Reference<? extends ClassLoader> stale; (stale = COLLECTED.poll()) != null;) PROOFS.remove(stale);
        if (loader == null) return BOOTSTRAP;
        LoaderIdentity key = new LoaderIdentity(loader, false);
        Map<String, Map<String, Field>> ledger = PROOFS.get(key);
        if (ledger == null && create) { ledger = new HashMap<>(); PROOFS.put(new LoaderIdentity(loader, true), ledger); }
        return ledger == null ? Map.of() : ledger;
    }
    private VirtualProperties() { }
    /** Definition observers publish field reads from final bytes, never from untransformed resources. */
    public static synchronized void observe(ClassLoader loader, String owner, Map<String, Field> getters) {
        ledger(loader, true).put(owner.replace('.', '/'), Map.copyOf(getters));
    }
    public static synchronized void release(ClassLoader loader) { if (loader == null) BOOTSTRAP.clear(); else PROOFS.remove(new LoaderIdentity(loader, false)); }
    public static <T> void set(Class<?> declaration, String getter, Class<T> type, Object receiver, T value) {
        writer(declaration, getter, type, receiver).accept(value);
    }
    /** Resolve before running a native mutation, so an unsupported getter cannot leave partial callback effects. */
    public static <T> java.util.function.Consumer<T> writer(Class<?> declaration, String getter, Class<T> type, Object receiver) {
        return writer(declaration, getter, type, receiver, true);
    }
    /** The declared implementation's storage, for an adapter that delegates to that same native superclass body. */
    public static <T> java.util.function.Consumer<T> declaredWriter(Class<?> declaration, String getter, Class<T> type, Object receiver) {
        return writer(declaration, getter, type, receiver, false);
    }
    private static <T> java.util.function.Consumer<T> writer(Class<?> declaration, String getter, Class<T> type, Object receiver, boolean virtual) {
        Objects.requireNonNull(receiver);
        if (!declaration.isInstance(receiver)) throw new ClassCastException(receiver.getClass().getName());
        List<Method> methods = Arrays.stream(virtual ? receiver.getClass().getMethods() : declaration.getDeclaredMethods()).filter(m -> m.getName().equals(getter)
            && m.getParameterCount() == 0 && m.getReturnType() == type).toList();
        if (methods.size() != 1) throw new IllegalStateException("Ambiguous public property " + getter + " on " + receiver.getClass().getName());
        Method method = methods.getFirst(); Class<?> owner = method.getDeclaringClass(); Field field;
        synchronized (VirtualProperties.class) {
            field = ledger(owner.getClassLoader(), false).getOrDefault(owner.getName().replace('.', '/'), Map.of())
                .get(getter + MethodType.methodType(type).descriptorString());
        }
        if (field == null) throw new IllegalStateException("Final getter does not prove a mutable backing field: " + owner.getName() + "." + getter);
        try {
            Class<?> storage = Class.forName(field.owner().replace('/', '.'), false, owner.getClassLoader());
            MethodHandle write = MethodHandles.privateLookupIn(storage, MethodHandles.lookup()).findSetter(storage, field.name(), type);
            return value -> {
                try { write.invoke(receiver, value); }
                catch (Throwable failure) { VirtualProperties.<RuntimeException>raise(failure); }
            };
        } catch (Throwable failure) { VirtualProperties.<RuntimeException>raise(failure); }
        throw new AssertionError("unreachable");
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> void raise(Throwable failure) throws E { throw (E) failure; }
}
