/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.List;
import java.util.function.Predicate;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

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
    /**
     * Whether the injector's selector list is spelled exactly {@code selector}. A spelling is not a binding: callback
     * adapters that have the target class ask {@link #binds}; this stays for adapters that are given none.
     */
    static boolean selects(MethodNode method, String selector) {
        AnnotationNode injector = MixinFit.injectorOf(method);
        return injector != null && List.of(selector).equals(MixinFit.stringList(MixinFit.value(injector, "method")));
    }
    /**
     * Whether Mixin binds the handler's injector to {@code member} ({@code name + desc}) of {@code target} and to nothing
     * else, however its selectors are written ({@link MixinTargetSelectors}).
     */
    static boolean binds(MethodNode method, ClassNode target, String member) {
        return target != null && MixinTargetSelectors.bindsOnly(method, target, member);
    }
    /**
     * Whether the handler's operands and return are {@code descriptor}'s and its extras exactly {@code extras}
     * ({@link MixinHandlerShape}): the callback contract by platform types and parameter roles, not by one mod's descriptor.
     * Read alone: a target argument the handler appends is no {@code @Local}; an adapter that knows the method the
     * handler is written for reads it there ({@link #shape(MethodNode, MethodNode, String, MixinHandlerShape.Want...)}).
     */
    static boolean shape(MethodNode method, String descriptor, MixinHandlerShape.Want... extras) {
        return shape(method, null, descriptor, extras);
    }
    /**
     * {@link #shape(MethodNode, String, MixinHandlerShape.Want...)}, the handler read against {@code written}, the method
     * it is written for (the native one when at hand, else the one its selectors bind; null: read alone): a target argument
     * it appends after its operands is that method's parameter, the {@code @Local(argsOnly = true)} it is equivalent to
     * ({@link MixinHandlerShape#of(MethodNode, String, MethodNode)}), and is served wherever that {@code @Local} is.
     */
    static boolean shape(MethodNode method, MethodNode written, String descriptor, MixinHandlerShape.Want... extras) {
        MixinHandlerShape shape = read(method, written);
        return shape != null && shape.matches(descriptor, extras);
    }
    /**
     * Whether the handler's operands and return are {@code descriptor}'s and every extra is a {@code @Local} — annotated,
     * or a target argument read against {@code written} as the one it stands for — whatever ones and however many: which
     * value each reads is then proved where the callback moves, never assumed from a list one mod declared.
     */
    static boolean captures(MethodNode method, MethodNode written, String descriptor) {
        MixinHandlerShape shape = read(method, written);
        return shape != null && shape.operands(descriptor)
                && shape.extras().stream().allMatch(extra -> extra.role() == MixinHandlerShape.Role.LOCAL);
    }
    /** The handler read against {@code written} (its descriptor and body); alone where {@code written} is null. */
    static MixinHandlerShape read(MethodNode method, MethodNode written) {
        return written == null ? MixinHandlerShape.of(method) : MixinHandlerShape.of(method, written.desc, written);
    }
    /**
     * Whether the handler's one {@code @At} is a {@code kind} point at {@code target} (an {@code Lowner;name(desc)}
     * member, or null for a point that names none), unshifted. The target is compared as Mixin resolves it, not as it is
     * spelled ({@link #names}); {@code body} — the method the handler was written for, null when not at hand — is what
     * lets a target without an owner or a descriptor name that member.
     */
    static boolean point(MethodNode method, String kind, String target, MethodNode body) {
        AnnotationNode injector = MixinFit.injectorOf(method);
        if (injector == null) return false;
        List<AnnotationNode> ats = MixinFit.atNodes(injector);
        if (ats.size() != 1) return false;
        AnnotationNode at = ats.getFirst();
        return kind.equals(MixinFit.asString(MixinFit.value(at, "value")))
                && (target == null || names(at, target, body))
                && MixinFit.value(at, "shift") == null && MixinFit.value(at, "by") == null
                && MixinFit.value(at, "opcode") == null && MixinFit.value(at, "args") == null;
    }
    static boolean point(MethodNode method, String kind, String target) {
        return point(method, kind, target, null);
    }
    /** {@link #point}, also with a shift that keeps it before the instruction ({@code BEFORE}, or {@code NONE} spelled out). */
    static boolean beforePoint(MethodNode method, String kind, String target, MethodNode body) {
        AnnotationNode injector = MixinFit.injectorOf(method);
        if (injector == null || MixinFit.atNodes(injector).size() != 1) return false;
        AnnotationNode at = MixinFit.atNodes(injector).getFirst();
        Object shift = MixinFit.value(at, "shift");
        return kind.equals(MixinFit.asString(MixinFit.value(at, "value"))) && (target == null || names(at, target, body))
                && (shift == null || shift instanceof String[] value && value.length == 2 && (value[1].equals("BEFORE") || value[1].equals("NONE")))
                && MixinFit.value(at, "by") == null && MixinFit.value(at, "opcode") == null && MixinFit.value(at, "args") == null;
    }
    static boolean beforePoint(MethodNode method, String kind, String target) {
        return beforePoint(method, kind, target, null);
    }
    /** {@link #point} on every occurrence: no ordinal (or -1). */
    static boolean plainPoint(MethodNode method, String kind, String target, MethodNode body) {
        if (!point(method, kind, target, body)) return false;
        Object ordinal = MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(method)).getFirst(), "ordinal");
        return ordinal == null || Integer.valueOf(-1).equals(ordinal);
    }
    static boolean plainPoint(MethodNode method, String kind, String target) {
        return plainPoint(method, kind, target, null);
    }

    /**
     * Whether {@code at}'s target can name {@code member} ({@code Lowner;name(desc)}, or {@code Lowner;name:desc} for a
     * field) as Mixin resolves a target ({@link MixinFit#parseMember}: whitespace dropped, a dotted owner read as the
     * class): the same name, and the same owner and descriptor wherever the target gives them.
     */
    static boolean covers(AnnotationNode at, String member) {
        MixinFit.Member named = MixinFit.parseMember(MixinFit.asString(MixinFit.value(at, "target"))), wanted = MixinFit.parseMember(member);
        return named != null && wanted != null && named.name().equals(wanted.name())
                && (named.owner() == null || named.owner().equals(wanted.owner()))
                && (named.desc() == null || named.desc().equals(wanted.desc()));
    }

    /**
     * Whether {@code at}'s target names {@code member} and nothing else. Spelled with its owner and descriptor, it names
     * that member however it is written ({@link #covers}). Without the owner (which matches the name on any class) or the
     * descriptor (any overload), it names that member only where it selects exactly that member's instructions in
     * {@code body}, the method the handler was written for, and that body has at least one: there Mixin injects at the
     * same instructions. Null {@code body}: only the full spelling names it.
     */
    static boolean names(AnnotationNode at, String member, MethodNode body) {
        if (!covers(at, member)) return false;
        MixinFit.Member named = MixinFit.parseMember(MixinFit.asString(MixinFit.value(at, "target")));
        if (named.owner() != null && named.desc() != null) return true;
        List<AbstractInsnNode> selected = selected(at, body);
        if (selected.isEmpty()) return false;
        MixinFit.Member wanted = MixinFit.parseMember(member);
        for (AbstractInsnNode instruction : selected) {
            boolean same = instruction instanceof MethodInsnNode call ? call.owner.equals(wanted.owner()) && call.desc.equals(wanted.desc())
                    : instruction instanceof FieldInsnNode field && field.owner.equals(wanted.owner()) && field.desc.equals(wanted.desc());
            if (!same) return false;   // the target also selects another member here
        }
        return true;
    }

    /**
     * The calls and field accesses of {@code body} whose member {@code at}'s target names as Mixin matches one — the name,
     * and the owner and descriptor wherever the target gives them — before any {@code ordinal} picks among them. Empty
     * for no body, or a target that names no member.
     */
    static List<AbstractInsnNode> selected(AnnotationNode at, MethodNode body) {
        MixinFit.Member named = MixinFit.parseMember(MixinFit.asString(MixinFit.value(at, "target")));
        if (named == null || body == null || body.instructions == null) return List.of();
        List<AbstractInsnNode> found = new java.util.ArrayList<>();
        for (AbstractInsnNode instruction : body.instructions) {
            String owner, name, desc;
            if (instruction instanceof MethodInsnNode call) { owner = call.owner; name = call.name; desc = call.desc; }
            else if (instruction instanceof FieldInsnNode field) { owner = field.owner; name = field.name; desc = field.desc; }
            else continue;
            if (name.equals(named.name()) && (named.owner() == null || named.owner().equals(owner)) && (named.desc() == null || named.desc().equals(desc)))
                found.add(instruction);
        }
        return found;
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
