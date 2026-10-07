/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.List;
import java.util.function.Predicate;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Structural source contracts for callback relocation. Class and handler names never identify a contract. */
final class MixinCallbackShape {
    private MixinCallbackShape() { }
    static boolean targets(ClassNode mixin, String target) {
        return mixin != null && List.of(target).equals(MixinFit.mixinTargets(mixin));
    }
    static MethodNode unique(ClassNode mixin, Predicate<MethodNode> contract) {
        MethodNode found = null;
        if (mixin == null || mixin.methods == null) return null;
        for (MethodNode method : mixin.methods) if (contract.test(method)) {
            if (found != null) return null;
            found = method;
        }
        return found;
    }
    static boolean kind(MethodNode method, String kind) {
        AnnotationNode injector = MixinFit.injectorOf(method);
        return injector != null && injector.desc.endsWith("/" + kind + ";")
                && MixinFit.value(injector, "slice") == null
                && MixinFit.value(injector, "target") == null
                && !grouped(method.visibleAnnotations) && !grouped(method.invisibleAnnotations);
    }
    static boolean selects(MethodNode method, String selector) {
        AnnotationNode injector = MixinFit.injectorOf(method);
        return injector != null && List.of(selector).equals(MixinFit.stringList(MixinFit.value(injector, "method")));
    }
    static boolean point(MethodNode method, String kind, String target) {
        AnnotationNode injector = MixinFit.injectorOf(method);
        if (injector == null) return false;
        List<AnnotationNode> ats = MixinFit.atNodes(injector);
        if (ats.size() != 1) return false;
        AnnotationNode at = ats.getFirst();
        return kind.equals(MixinFit.value(at, "value"))
                && (target == null || target.equals(MixinFit.value(at, "target")))
                && MixinFit.value(at, "shift") == null && MixinFit.value(at, "by") == null
                && MixinFit.value(at, "opcode") == null && MixinFit.value(at, "args") == null;
    }
    static boolean beforePoint(MethodNode method, String kind, String target) {
        AnnotationNode injector = MixinFit.injectorOf(method);
        if (injector == null || MixinFit.atNodes(injector).size() != 1) return false;
        AnnotationNode at = MixinFit.atNodes(injector).getFirst();
        Object shift = MixinFit.value(at, "shift");
        return kind.equals(MixinFit.value(at, "value")) && target.equals(MixinFit.value(at, "target"))
                && (shift == null || shift instanceof String[] value && value.length == 2 && (value[1].equals("BEFORE") || value[1].equals("NONE")))
                && MixinFit.value(at, "by") == null && MixinFit.value(at, "opcode") == null && MixinFit.value(at, "args") == null;
    }
    static boolean plainPoint(MethodNode method, String kind, String target) {
        if (!point(method, kind, target)) return false;
        Object ordinal = MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(method)).getFirst(), "ordinal");
        return ordinal == null || Integer.valueOf(-1).equals(ordinal);
    }
    static boolean instance(MethodNode method) { return (method.access & Opcodes.ACC_STATIC) == 0; }
    static boolean noReceiver(MethodNode method) {
        for (var instruction : method.instructions) {
            if (instruction instanceof VarInsnNode variable && variable.var == 0) return false;
            if (instruction instanceof IincInsnNode increment && increment.var == 0) return false;
        }
        return true;
    }
    static void uniqueMember(MethodNode method) {
        if(method.visibleAnnotations==null)method.visibleAnnotations=new java.util.ArrayList<>();
        if(method.visibleAnnotations.stream().noneMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Unique;")))
            method.visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/Unique;"));
    }
    private static boolean grouped(List<AnnotationNode> annotations) {
        return annotations != null && annotations.stream().anyMatch(a -> a.desc.endsWith("/Group;"));
    }
}
