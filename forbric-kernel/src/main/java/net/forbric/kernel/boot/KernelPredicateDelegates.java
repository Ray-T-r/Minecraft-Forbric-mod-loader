/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.forbric.kernel.boot.DefinedMethodContracts.MethodContract;
import net.forbric.kernel.util.ForbricLog;

/** Final-definition guards for an exact, closed static delegate graph lifted into its caller. */
public final class KernelPredicateDelegates {
    private record Plan(String initializedOwner, List<MethodContract> methods) { }
    private static final Map<String, Plan> PLANS = new ConcurrentHashMap<>();
    private static final ClassValue<Set<String>> REPORTED = new ClassValue<>() {
        @Override protected Set<String> computeValue(Class<?> type) { return ConcurrentHashMap.newKeySet(); }
    };
    private KernelPredicateDelegates() { }
    public static String register(String initializedOwner, List<MethodContract> methods) {
        Plan plan = new Plan(initializedOwner.replace('/', '.'), List.copyOf(methods));
        try {
            String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plan.toString().getBytes(StandardCharsets.UTF_8)));
            PLANS.putIfAbsent(key, plan); return key;
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    /** No provider, predicate or guest callback executes on failure. The original operation remains authoritative. */
    public static boolean permits(Object caller, String key) {
        Plan plan = PLANS.get(key); boolean proved = caller != null && plan != null;
        if (proved) try {
            ClassLoader loader = caller.getClass().getClassLoader();
            for (MethodContract contract : plan.methods()) {
                Class<?> owner = Class.forName(contract.owner(), false, loader);
                if (!DefinedMethodContracts.observed(owner.getClassLoader(), contract)) { proved = false; break; }
            }
        } catch (ClassNotFoundException | LinkageError unavailable) { proved = false; }
        // Initialization errors are the native call's own errors. Do not swallow one and retry an erroneous class.
        if (proved) try { Class.forName(plan.initializedOwner(), true, caller.getClass().getClassLoader()); }
        catch (ClassNotFoundException unavailable) { proved = false; }
        if (!proved && caller != null && REPORTED.get(caller.getClass()).add(key))
            ForbricLog.warn("[Forbric/PredicateDelegate] keeping the original native operation: its delegate/provider final bodies do not match the closed graph proof; the guest predicate decorator is not called (%s)", key);
        return proved;
    }
    public static void resetForTests() { PLANS.clear(); }
}
