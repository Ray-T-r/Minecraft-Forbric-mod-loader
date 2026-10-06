/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.mixin.MixinInstructionFingerprint;

/**
 * Final-defined method contracts, scoped to the actual defining class loader. Raw jar bytes are not a runtime
 * witness: a guest can change a default before it is defined, and a concrete receiver can override that default.
 * This ledger stores only code fingerprints and symbols, never class ASTs. Unknown definitions fail closed.
 */
public final class DefinedMethodContracts {
    /** A body contract derived from bytecode, rather than a table of known owners or methods. */
    public record MethodContract(String owner, String name, String descriptor, String fingerprint) {
        public MethodContract {
            owner = Objects.requireNonNull(owner).replace('/', '.');
            Objects.requireNonNull(name);
            Objects.requireNonNull(descriptor);
            Objects.requireNonNull(fingerprint);
        }
    }

    private static final ReferenceQueue<ClassLoader> COLLECTED = new ReferenceQueue<>();
    private static final ConcurrentHashMap<LoaderIdentity, ConcurrentHashMap<String, Set<MethodContract>>> LOADERS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Set<MethodContract>> BOOTSTRAP = new ConcurrentHashMap<>();
    private static final ClassValue<ConcurrentHashMap<MethodContract, Optional<Class<?>>>> RESOLUTIONS = new ClassValue<>() {
        @Override protected ConcurrentHashMap<MethodContract, Optional<Class<?>>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private DefinedMethodContracts() { }

    /** The same instruction/control-flow hash used by the final mixin application evidence. */
    public static String fingerprint(MethodNode method) { return MixinInstructionFingerprint.hash(method); }

    /** Call only after a successful definition, with its exact final bytes and actual defining loader. */
    public static void observe(ClassLoader definingLoader, String binary, byte[] bytes) {
        String owner = Objects.requireNonNull(binary).replace('/', '.');
        ConcurrentHashMap<String, Set<MethodContract>> ledger = ledger(definingLoader, true);
        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (!node.name.replace('/', '.').equals(owner)) { ledger.remove(owner); return; }
            Set<MethodContract> contracts = new HashSet<>();
            for (MethodNode method : node.methods) {
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                contracts.add(new MethodContract(owner, method.name, method.desc, fingerprint(method)));
            }
            ledger.put(owner, Set.copyOf(contracts));
        } catch (RuntimeException malformed) {
            ledger.remove(owner);
        }
    }

    /** Checks a helper/projection body witness without making a claim about virtual dispatch. */
    public static boolean observed(ClassLoader loader, MethodContract contract) {
        if (contract == null) return false;
        ConcurrentHashMap<String, Set<MethodContract>> ledger = ledger(loader, false);
        if (ledger == null) return false;
        Set<MethodContract> contracts = ledger.get(contract.owner());
        return contracts != null && contracts.contains(contract);
    }

    /**
     * The receiver must dispatch this exact public instance signature to the contract's declaring owner, and that
     * owner's final-defined body must still match. A same-named subclass override or a transformed default fails.
     * Reflection resolution is cached per receiver class; the final body witness is checked fresh on every call.
     */
    public static boolean validates(Object receiver, MethodContract contract) {
        if (receiver == null || contract == null) return false;
        Optional<Class<?>> declaring = RESOLUTIONS.get(receiver.getClass())
                .computeIfAbsent(contract, expected -> resolve(receiver.getClass(), expected));
        return declaring.isPresent() && observed(declaring.get().getClassLoader(), contract);
    }

    private static Optional<Class<?>> resolve(Class<?> receiver, MethodContract contract) {
        try {
            Method found = null;
            for (Method method : receiver.getMethods()) {
                if (!method.getName().equals(contract.name()) || Modifier.isStatic(method.getModifiers())
                        || Modifier.isAbstract(method.getModifiers())
                        || !Type.getMethodDescriptor(method).equals(contract.descriptor())) continue;
                if (found != null) return Optional.empty();
                found = method;
            }
            return found != null && found.getDeclaringClass().getName().equals(contract.owner())
                    ? Optional.of(found.getDeclaringClass()) : Optional.empty();
        } catch (RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    /** Tests may replace an observation to verify that stale witnesses do not survive a mutation. */
    public static void resetForTests() {
        LOADERS.clear(); BOOTSTRAP.clear();
        while (COLLECTED.poll() != null) { /* discard collected loader keys */ }
    }

    private static ConcurrentHashMap<String, Set<MethodContract>> ledger(ClassLoader loader, boolean create) {
        LoaderIdentity expired;
        while ((expired = (LoaderIdentity) COLLECTED.poll()) != null) LOADERS.remove(expired);
        if (loader == null) return BOOTSTRAP;
        LoaderIdentity lookup = new LoaderIdentity(loader, null);
        ConcurrentHashMap<String, Set<MethodContract>> found = LOADERS.get(lookup);
        if (found != null || !create) return found;
        ConcurrentHashMap<String, Set<MethodContract>> made = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, Set<MethodContract>> existing = LOADERS.putIfAbsent(new LoaderIdentity(loader, COLLECTED), made);
        return existing == null ? made : existing;
    }

    /** A class loader's equals/hashCode override cannot make another loader's definition look like this one's. */
    private static final class LoaderIdentity extends WeakReference<ClassLoader> {
        private final int hash;
        LoaderIdentity(ClassLoader loader, ReferenceQueue<ClassLoader> queue) {
            super(loader, queue); hash = System.identityHashCode(loader);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            return other instanceof LoaderIdentity identity && get() != null && get() == identity.get();
        }
    }
}
