/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.*;

/**
 * Names what broke a transported seam's proof, from the final defined class, and makes the one report a declined
 * site makes.
 *
 * <p>A seam that carries a guest's callback or Operation into a carrier helper keeps the guest's code only while the
 * helper, and the host's call into it, are the bodies the proof was made on. Another mod's mixin into either one is
 * legitimate and coexists natively, so the seam declines (the carrier's code runs as written) instead of failing the
 * host method, and names the mixins that changed the method: the ones whose merged handler the final method calls,
 * or whose {@code @MixinMerged} it carries itself (an overwrite), including through an Operation or lambda body that
 * the method creates.
 */
final class SeamDeclines {
    private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    private SeamDeclines() { }

    /** Mixins other than {@code excluded} (dotted names) merged into {@code method} or a lambda body it creates. */
    static List<String> contributors(ClassNode owner, MethodNode method, Set<String> excluded) {
        Set<String> found = new TreeSet<>();
        Set<MethodNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<MethodNode> pending = new ArrayDeque<>(List.of(method));
        while (!pending.isEmpty()) {
            MethodNode at = pending.poll();
            if (!seen.add(at)) continue;
            add(found, mergedFrom(at), excluded);
            for (AbstractInsnNode instruction : at.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(owner.name)) {
                    MethodNode target = NativeCallChanges.method(owner, call.name + call.desc);
                    if (target != null) add(found, mergedFrom(target), excluded);
                } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                    for (Object argument : dynamic.bsmArgs) {
                        if (!(argument instanceof Handle handle) || !handle.getOwner().equals(owner.name)) continue;
                        MethodNode body = NativeCallChanges.method(owner, handle.getName() + handle.getDesc());
                        if (body != null) pending.add(body);
                    }
                }
            }
        }
        return List.copyOf(found);
    }

    /** The clause a reason ends with: who changed the method, or that no mixin handler explains it. */
    static String changedBy(List<String> mixins) {
        return mixins.isEmpty() ? " (no other mixin's handler is in it, so a transformer changed it)"
                : " (changed by mixin " + String.join(", ", mixins) + ")";
    }

    /** The site's finding and its one warning, attributed to the mod whose mixin's code is skipped there. */
    static void report(String sourceMixin, String handlerName, String handlerDesc, String site, String detail,
            List<String> evidence) {
        MixinCompatibility.recordDeclinedSeam(MixinStubRebind.configOf(sourceMixin), sourceMixin.replace('/', '.'),
                handlerName, handlerDesc, site, detail, evidence);
    }

    private static void add(Set<String> found, String mixin, Set<String> excluded) {
        if (mixin != null && !excluded.contains(mixin)) found.add(mixin);
    }

    private static String mergedFrom(MethodNode method) {
        if (method.visibleAnnotations == null) return null;
        for (AnnotationNode annotation : method.visibleAnnotations) {
            if (annotation.desc.equals(MERGED) && MixinFit.value(annotation, "mixin") instanceof String mixin) return mixin;
        }
        return null;
    }
}
