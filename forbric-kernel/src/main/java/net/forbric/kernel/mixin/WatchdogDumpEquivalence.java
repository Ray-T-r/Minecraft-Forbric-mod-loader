/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.Map;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** A deferred proof: the actual native renderer must be defined before equivalence is confirmed. */
final class WatchdogDumpEquivalence {
    static final String MIXIN = "net.fabricmc.fabric.mixin.crash.report.info.ServerWatchdogMixin";
    static final String TARGET = "net.minecraft.server.dedicated.ServerWatchdog";
    static final String HELPER = "net.neoforged.neoforge.logging.ThreadInfoUtil";
    static final String HANDLER = "printEntireThreadDump", DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String HANDLER_HASH = "6d0d3d986cc70788588709d5e2fc4cf663c70891f277794224934953a04a8739";
    private static final String HELPER_HASH = "d8d798b31b1186ce55df926f235165e79dec603ce684b44cdaaffb313ee7c142";
    private static final Map<String, String> REPORTS = Map.of(
            "(Ljava/lang/String;J)Lnet/minecraft/CrashReport;", "56a88fc8b1a6049ec4ba307098717ef3c64aabdce86152d27c1fcaf4f28c21bb",
            "(Ljava/lang/String;JLjava/lang/String;)Lnet/minecraft/CrashReport;", "12ecf01a85ceae6b9492e13a8f602829e84ae37d496fb79b4228d3c051a094e3");
    private static volatile Boolean helperMatches;
    private WatchdogDumpEquivalence() { }
    static void reset() { helperMatches = null; }
    static boolean names(String mixin, String name, String desc) {
        return MIXIN.equals(mixin) && HANDLER.equals(name) && DESC.equals(desc);
    }
    static boolean candidate(String mixin, String name, String desc, String fingerprint, ClassNode target) {
        if (!names(mixin, name, desc) || !HANDLER_HASH.equals(fingerprint) || !TARGET.equals(target.name.replace('/', '.'))) return false;
        for (var expected : REPORTS.entrySet()) {
            MethodNode method = target.methods.stream().filter(m -> m.name.equals("createWatchdogCrashReport") && m.desc.equals(expected.getKey())).findFirst().orElse(null);
            if (method == null || !expected.getValue().equals(MixinInstructionFingerprint.hash(method))) return false;
        }
        return true;
    }
    static boolean helperUnknown() { return helperMatches == null; }
    static boolean helperProved() { return Boolean.TRUE.equals(helperMatches); }
    static void observeHelper(ClassNode node) {
        MethodNode method = node.methods.stream().filter(m -> m.name.equals("getEntireStacktrace")
                && m.desc.equals("(Ljava/lang/management/ThreadInfo;)Ljava/lang/String;")).findFirst().orElse(null);
        helperMatches = method != null && HELPER_HASH.equals(MixinInstructionFingerprint.hash(method));
    }
}
