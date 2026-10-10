/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.forbric.api.Ecosystem;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * Keeps plain constructor injections around the client hooks that the kernel now dispatches to both families.
 *
 * <p>The injector is recognised as Mixin reads it, never by spelling: its selectors by the constructor they bind in the
 * merged class, its point by the member it names ({@link MixinCallbackShape#names}) — whitespace and a dotted owner are
 * the same target, and one without its owner or descriptor names the hook where the constructor it was written for, in
 * the class the mod was compiled against, decides it.
 */
public final class KernelClientHookMixinAnchors {
    public static final String PROPERTY = "forbric.clientHookMixinAnchors";
    private static final String CLIENT = "net/minecraft/client/Minecraft";
    private static final String NATIVE = "net/neoforged/neoforge/client/ClientHooks";
    private static final String RELAY = "net/forbric/kernel/runtime/KernelForgeClientInit";
    private static final Map<String, String> CALLS = Map.of(
            "initClientHooks", "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/server/packs/resources/ReloadableResourceManager;)V",
            "onRegisterParticleProviders", "(Lnet/minecraft/client/particle/ParticleResources;)V");
    private KernelClientHookMixinAnchors() { }

    public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
        return adapt(mixin, targets, NativeGameReferences::reference);
    }

    /** {@code references} gives the class the mod was compiled against, where a point without its owner or descriptor is read. */
    static int adapt(ClassNode mixin, Function<String, ClassNode> targets, BiFunction<Ecosystem, String, ClassNode> references) {
        if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on")) || !MixinOverloadPin.targetsOf(mixin).equals(List.of(CLIENT))) return 0;
        ClassNode target = targets.apply(CLIENT); if (target == null) return 0;
        ClassNode source = references == null ? null : references.apply(MixinStubRebind.ecosystemOf(mixin.name), CLIENT);
        int changed = 0;
        for (MethodNode handler : mixin.methods) {
            AnnotationNode injector = MixinFit.injectorOf(handler);
            if (injector == null || !injector.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")
                    || MixinFit.value(injector, "slice") != null) continue;
            // The one constructor Mixin binds the selectors to in the merged class, however they are written.
            MethodNode constructor = MixinTargetSelectors.one(handler, target);
            if (constructor == null || !constructor.name.equals("<init>")) continue;
            Type[] arguments = Type.getArgumentTypes(handler.desc), nativeArguments = Type.getArgumentTypes(constructor.desc);
            if (arguments.length != nativeArguments.length + 1 || !arguments[arguments.length - 1].getDescriptor().equals(MixinRetarget.CALLBACK_INFO)) continue;
            boolean same = true; for (int i = 0; i < nativeArguments.length; i++) same &= arguments[i].equals(nativeArguments[i]);
            if (!same || (handler.visibleAnnotations != null && handler.visibleAnnotations.stream().anyMatch(a -> a.desc.endsWith("/Group;")))) continue;
            MethodNode written = MixinCallbackShape.written(handler, source);
            for (AnnotationNode at : MixinFit.atNodes(injector)) {
                if (!"INVOKE".equals(MixinFit.value(at, "value"))) continue;
                if (MixinFit.value(at, "ordinal") instanceof Integer ordinal && ordinal > 0) continue;
                for (var call : CALLS.entrySet()) {
                    String original = "L" + NATIVE + ";" + call.getKey() + call.getValue();
                    if (!MixinCallbackShape.names(at, original, written)) continue;
                    int nativeCount = 0, relays = 0;
                    for (var instruction : constructor.instructions) if (instruction instanceof MethodInsnNode invoke
                            && invoke.name.equals(call.getKey()) && invoke.desc.equals(call.getValue()) && invoke.getOpcode() == Opcodes.INVOKESTATIC) {
                        if (invoke.owner.equals(NATIVE)) nativeCount++;
                        if (invoke.owner.equals(RELAY)) relays++;
                    }
                    if (nativeCount != 0 || relays != 1) continue;
                    for (int i = 0; i < at.values.size(); i += 2) if (at.values.get(i).equals("target")) {
                        at.values.set(i + 1, "L" + RELAY + ";" + call.getKey() + call.getValue()); changed++;
                    }
                }
            }
        }
        return changed;
    }
}
