/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.api;

import java.util.*;
import java.lang.ref.*;
import java.util.function.Predicate;
import net.forbric.kernel.util.ForbricLog;

/**
 * One invocation transports a callback to its proved source program point in an extracted helper.
 *
 * <p>Fail-open: an invocation whose proof does not hold -- another transformer changed the helper's final body, or
 * wraps the host's call to it -- is declined, never failed. The carrier's helper then runs exactly as written and
 * the source callback does not run for that invocation. A site reports its first decline once, with what broke it;
 * the host method never throws for a seam.
 */
public final class CallbackSeams {
    /** The one report a site makes, the first time one of its invocations is declined. */
    @FunctionalInterface public interface Declined { void report(ClassLoader loader, String key, String reason); }
    private record Proof(String host, String helper, Predicate<ClassLoader> witness, Declined declined) { }
    private static final ReferenceQueue<ClassLoader> COLLECTED=new ReferenceQueue<>();
    private static final class LoaderKey extends WeakReference<ClassLoader> {
        private final int hash;LoaderKey(ClassLoader loader){super(loader,COLLECTED);hash=System.identityHashCode(loader);}
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object other){return this==other||other instanceof LoaderKey key&&get()!=null&&get()==key.get();}
    }
    private static final Map<LoaderKey, Map<String, Proof>> PROOFS = new HashMap<>();
    /** Sites, per host loader, that already reported their decline. Guarded by {@link #PROOFS}. */
    private static final Map<LoaderKey, Set<String>> DECLINED = new HashMap<>();
    private static final Set<String> BOOTSTRAP_DECLINED = new HashSet<>();
    private static final ThreadLocal<ArrayDeque<Scope>> ACTIVE = new ThreadLocal<>();
    private static final Token INACTIVE = new Token(null);
    /** A declined invocation's scope: never on the stack, so no helper claims it; it completes and closes as a no-op. */
    private static final Scope SKIPPED = new Scope(null, null, null, null);
    private CallbackSeams() { }

    /** A provider must prove both bodies after their successful definition. Registration itself is no witness. */
    public static void register(ClassLoader loader, String key, String host, String helper, Predicate<ClassLoader> witness) {
        register(loader, key, host, helper, witness, null);
    }
    /** As above; {@code declined} names what broke the proof, once per site (null logs a generic warning instead). */
    public static void register(ClassLoader loader, String key, String host, String helper, Predicate<ClassLoader> witness, Declined declined) {
        synchronized (PROOFS) {
            expunge();Proof offered=new Proof(host.replace('/', '.'),helper.replace('/', '.'),Objects.requireNonNull(witness),declined);
            Proof before=PROOFS.computeIfAbsent(new LoaderKey(Objects.requireNonNull(loader)), ignored -> new HashMap<>()).putIfAbsent(key,offered);
            if(before!=null&&(!before.host.equals(offered.host)||!before.helper.equals(offered.helper)))throw new IllegalStateException("Conflicting extracted callback source: "+key);
        }
    }
    private static void expunge(){for(Reference<? extends ClassLoader> key;(key=COLLECTED.poll())!=null;){PROOFS.remove(key);DECLINED.remove(key);}}
    public static void release(ClassLoader loader) { synchronized(PROOFS){expunge();LoaderKey key=new LoaderKey(loader);PROOFS.remove(key);DECLINED.remove(key);} }
    private static Proof proof(Class<?> host, String key) {
        synchronized (PROOFS) {
            expunge();Map<String, Proof> registry = PROOFS.get(new LoaderKey(host.getClassLoader()));
            return registry == null ? null : registry.get(key);
        }
    }
    /** Arms the source callback for this host invocation while its proof holds; otherwise the returned scope is inert. */
    public static Scope enter(Class<?> host, Class<?> helper, String key, Runnable callback) {
        Proof proof = proof(host, key);
        if (proof == null || !proof.host.equals(host.getName()) || !proof.helper.equals(helper.getName())
                || helper.getClassLoader() != host.getClassLoader() || !proof.witness.test(host.getClassLoader()))
            return declined(host, helper, key, proof);
        Scope scope = new Scope(host.getClassLoader(), key, proof, Objects.requireNonNull(callback));
        ArrayDeque<Scope> active=ACTIVE.get();if(active==null){active=new ArrayDeque<>();ACTIVE.set(active);}
        active.addFirst(scope); return scope;
    }
    private static Scope declined(Class<?> host, Class<?> helper, String key, Proof proof) {
        String reason = proof == null ? "no extracted callback proof is registered for this site in its host's loader"
            : !proof.host.equals(host.getName()) ? "invoked from " + host.getName() + ", proved for " + proof.host
            : !proof.helper.equals(helper.getName()) ? "invoked with helper " + helper.getName() + ", proved for " + proof.helper
            : helper.getClassLoader() != host.getClassLoader() ? "helper " + helper.getName() + " is defined by another loader than its host"
            : "the final body witness does not hold";
        decline(host.getClassLoader(), key, proof, reason);
        return SKIPPED;
    }
    /** Reports a site's first decline, once per host loader. A report that itself fails is only logged. */
    private static void decline(ClassLoader loader, String key, Proof proof, String reason) {
        boolean first;
        synchronized (PROOFS) { expunge(); first = (loader == null ? BOOTSTRAP_DECLINED : DECLINED.computeIfAbsent(new LoaderKey(loader), ignored -> new HashSet<>())).add(key); }
        if (!first) return;
        if (proof != null && proof.declined != null) {
            try { proof.declined.report(loader, key, reason); return; }
            catch (RuntimeException | LinkageError failed) { reason = reason + "; its report failed: " + failed; }
        }
        ForbricLog.warn("[Forbric/Mixin] extracted callback skipped at its source site, the carrier helper runs as written: %s (source=%s)", reason, key);
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
            if (scope.fired || scope.closed) {
                decline(scope.loader, scope.key, scope.proof, "the helper reached its source call again outside the one proved occurrence; the repetition runs without the callback");
                return;
            }
            scope.fired = true; scope.callback.run();
        }
    }
    public static final class Scope implements AutoCloseable {
        private final ClassLoader loader; private final String key; private final Proof proof; private final Runnable callback;
        private boolean claimed, fired, closed;
        private Scope(ClassLoader loader, String key, Proof proof, Runnable callback) {
            this.loader = loader; this.key = key; this.proof = proof; this.callback = callback;
        }
        public void complete() {
            if (fired || proof == null) return;
            decline(loader, key, proof, "the helper returned without reaching its proved source call, so the callback did not run for that invocation");
        }
        @Override public void close() {
            if (closed || proof == null) return;
            ArrayDeque<Scope> active = ACTIVE.get();
            if (active==null || active.peekFirst() != this) throw new IllegalStateException("Extracted callback scopes closed out of order");
            active.removeFirst(); closed = true; if (active.isEmpty()) ACTIVE.remove();
        }
    }
}
