/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.api;

import java.util.*;
import java.lang.ref.*;
import java.util.function.Predicate;

/** One invocation transports a callback to its proved source program point in an extracted helper. */
public final class CallbackSeams {
    private record Proof(String host, String helper, Predicate<ClassLoader> witness) { }
    private static final ReferenceQueue<ClassLoader> COLLECTED=new ReferenceQueue<>();
    private static final class LoaderKey extends WeakReference<ClassLoader> {
        private final int hash;LoaderKey(ClassLoader loader){super(loader,COLLECTED);hash=System.identityHashCode(loader);}
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object other){return this==other||other instanceof LoaderKey key&&get()!=null&&get()==key.get();}
    }
    private static final Map<LoaderKey, Map<String, Proof>> PROOFS = new HashMap<>();
    private static final ThreadLocal<ArrayDeque<Scope>> ACTIVE = new ThreadLocal<>();
    private static final Token INACTIVE = new Token(null);
    private CallbackSeams() { }

    /** A provider must prove both bodies after their successful definition. Registration itself is no witness. */
    public static void register(ClassLoader loader, String key, String host, String helper, Predicate<ClassLoader> witness) {
        synchronized (PROOFS) {
            expunge();Proof offered=new Proof(host.replace('/', '.'),helper.replace('/', '.'),Objects.requireNonNull(witness));
            Proof before=PROOFS.computeIfAbsent(new LoaderKey(Objects.requireNonNull(loader)), ignored -> new HashMap<>()).putIfAbsent(key,offered);
            if(before!=null&&(!before.host.equals(offered.host)||!before.helper.equals(offered.helper)))throw new IllegalStateException("Conflicting extracted callback source: "+key);
        }
    }
    private static void expunge(){for(Reference<? extends ClassLoader> key;(key=COLLECTED.poll())!=null;)PROOFS.remove(key);}
    public static void release(ClassLoader loader) { synchronized(PROOFS){expunge();PROOFS.remove(new LoaderKey(loader));} }
    private static Proof proof(Class<?> host, String key) {
        synchronized (PROOFS) {
            expunge();Map<String, Proof> registry = PROOFS.get(new LoaderKey(host.getClassLoader()));
            return registry == null ? null : registry.get(key);
        }
    }
    public static Scope enter(Class<?> host, Class<?> helper, String key, Runnable callback) {
        Proof proof = proof(host, key);
        if (proof == null || !proof.host.equals(host.getName()) || !proof.helper.equals(helper.getName())
                || helper.getClassLoader() != host.getClassLoader() || !proof.witness.test(host.getClassLoader()))
            throw new IllegalStateException("Extracted callback has no final body witness: " + key);
        Scope scope = new Scope(host.getClassLoader(), key, proof, Objects.requireNonNull(callback));
        ArrayDeque<Scope> active=ACTIVE.get();if(active==null){active=new ArrayDeque<>();ACTIVE.set(active);}
        active.addFirst(scope); return scope;
    }
    /** Claim at helper entry, before guest code can recurse; only the immediate scoped invocation can fire. */
    public static Token beginHelper(Class<?> helper, String key) {
        ArrayDeque<Scope> active=ACTIVE.get();if(active==null)return INACTIVE;
        for (Scope scope : active) {
            if (!scope.key.equals(key) || scope.loader != helper.getClassLoader()) continue;
            if (!scope.proof.helper.equals(helper.getName())) throw new IllegalStateException("Different callback helper: " + helper);
            if (scope.claimed) return INACTIVE;
            scope.claimed = true; return new Token(scope);
        }
        return INACTIVE;
    }
    public static final class Token {
        private final Scope scope;
        private Token(Scope scope) { this.scope = scope; }
        public void fire() {
            if (scope == null) return;
            if (scope.fired || scope.closed) throw new IllegalStateException("Extracted callback fired outside its source occurrence");
            scope.fired = true; scope.callback.run();
        }
    }
    public static final class Scope implements AutoCloseable {
        private final ClassLoader loader; private final String key; private final Proof proof; private final Runnable callback;
        private boolean claimed, fired, closed;
        private Scope(ClassLoader loader, String key, Proof proof, Runnable callback) {
            this.loader = loader; this.key = key; this.proof = proof; this.callback = callback;
        }
        public void complete() { if (!fired) throw new IllegalStateException("Extracted helper did not execute its source callback: " + key); }
        @Override public void close() {
            if (closed) return;
            ArrayDeque<Scope> active = ACTIVE.get();
            if (active==null || active.peekFirst() != this) throw new IllegalStateException("Extracted callback scopes closed out of order");
            active.removeFirst(); closed = true; if (active.isEmpty()) ACTIVE.remove();
        }
    }
}
