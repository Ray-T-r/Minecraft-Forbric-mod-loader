/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Predicate;
import net.forbric.api.DiscoveredMod;
import net.forbric.api.ModPresence;
import net.forbric.api.UnifiedDependency;
import org.objectweb.asm.tree.*;

/** Recognises a pure integration only when its owner declares the dependency optional.
 * Namespaced selectors alone are not evidence that losing an injector is harmless. */
final class OptionalMixinDependencies {
    private OptionalMixinDependencies() { }

    static String absent(String configName, ClassNode mixin, Predicate<String> installed) {
        DiscoveredMod owner = ModPresence.metadata(MixinConfigOwners.modIdOf(configName));
        return owner == null ? null : absent(mixin, owner.getDependencies(), installed);
    }


    static String absent(ClassNode mixin, List<UnifiedDependency> dependencies, Predicate<String> installed) {
        if (mixin == null || !mixin.fields.isEmpty() || !mixin.interfaces.isEmpty()) return null;
        List<MethodNode> handlers = mixin.methods.stream().filter(m -> !m.name.startsWith("<")).toList();
        if (handlers.isEmpty()) return null;
        String namespace = null;
        for (MethodNode handler : handlers) {
            AnnotationNode injector = MixinFit.injectorOf(handler);
            if (injector == null || MixinFit.groupOf(handler) != null) return null;
            List<String> selectors = MixinFit.stringList(MixinFit.value(injector, "method"));
            if (selectors.isEmpty()) return null;
            for (String selector : selectors) {
                // Only the explicit prefix of another mod's added method; not a wildcard or dynamic selector.
                int dollar = selector.indexOf('$');
                if (dollar < 1 || selector.indexOf('$', dollar + 1) >= 0 || selector.contains("*")
                        || selector.startsWith("@") || selector.contains(";") || selector.contains("/")) return null;
                String candidate = key(selector.substring(0, dollar));
                if (candidate.isEmpty() || (namespace != null && !namespace.equals(candidate))) return null;
                namespace = candidate;
            }
        }
        String dependency = null;
        for (UnifiedDependency declared : dependencies) {
            if (!key(declared.getModId()).equals(namespace)) continue;
            if (declared.isMandatory() || installed.test(declared.getModId())) return null;
            if (dependency != null && !dependency.equals(declared.getModId())) return null;
            dependency = declared.getModId();
        }
        return dependency;
    }
    private static String key(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }
}
