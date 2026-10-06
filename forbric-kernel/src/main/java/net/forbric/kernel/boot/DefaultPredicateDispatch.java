/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.forbric.kernel.util.ForbricLog;

/**
 * A transported guest predicate can replace only the proved native default. A concrete native predicate override
 * remains authoritative and executes through the original operation once, even when the receiver also overrides
 * a guest predicate/flags overload. This global precedence is reported rather than silently dropping the guest path.
 */
public final class DefaultPredicateDispatch {
    private record Plan(DefinedMethodContracts.MethodContract nativeDefault,
                        List<DefinedMethodContracts.MethodContract> sourceBodies) { }
    private static final ConcurrentHashMap<String, Plan> PLANS = new ConcurrentHashMap<>();
    private static final ClassValue<ConcurrentHashMap<String, Optional<List<Class<?>>>>> SOURCES = new ClassValue<>() {
        @Override protected ConcurrentHashMap<String, Optional<List<Class<?>>>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };
    private static final ClassValue<Set<String>> REPORTED = new ClassValue<>() {
        @Override protected Set<String> computeValue(Class<?> type) { return ConcurrentHashMap.newKeySet(); }
    };

    private DefaultPredicateDispatch() { }

    /** Contracts are derived by the adapter from method-family bytecode, never from a named family table. */
    public static void register(String token, DefinedMethodContracts.MethodContract nativeDefault,
                                List<DefinedMethodContracts.MethodContract> sourceBodies) {
        Plan plan = new Plan(nativeDefault, List.copyOf(sourceBodies));
        PLANS.compute(token, (key, previous) -> previous == null || previous.equals(plan) ? plan : null);
    }

    public static boolean allows(Object receiver, String token) {
        Plan plan = PLANS.get(token);
        boolean allowed = receiver != null && plan != null
                && DefinedMethodContracts.validates(receiver, plan.nativeDefault());
        if (allowed) {
            Optional<List<Class<?>>> sources = SOURCES.get(receiver.getClass()).computeIfAbsent(token,
                    key -> resolve(receiver.getClass().getClassLoader(), plan.sourceBodies()));
            allowed = sources.isPresent();
            for (int index = 0; allowed && index < plan.sourceBodies().size(); index++)
                allowed = DefinedMethodContracts.observed(sources.get().get(index).getClassLoader(), plan.sourceBodies().get(index));
        }
        if (!allowed && receiver != null && REPORTED.get(receiver.getClass()).add(token))
            ForbricLog.warn("[Forbric/DefaultDispatch] keeping the live native predicate for %s (%s): its concrete "
                    + "override or final-defined method bodies do not match the default-only transport proof. "
                    + "The guest predicate handler is not called on this receiver; native predicate overrides take "
                    + "precedence when both APIs override the family", receiver.getClass().getName(), token);
        return allowed;
    }

    private static Optional<List<Class<?>>> resolve(ClassLoader loader, List<DefinedMethodContracts.MethodContract> contracts) {
        try {
            java.util.ArrayList<Class<?>> classes = new java.util.ArrayList<>();
            for (var contract : contracts) classes.add(Class.forName(contract.owner(), false, loader));
            return Optional.of(List.copyOf(classes));
        } catch (ClassNotFoundException | RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    public static void resetForTests() { PLANS.clear(); }
}
