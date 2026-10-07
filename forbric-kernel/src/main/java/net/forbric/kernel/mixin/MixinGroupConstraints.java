/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Proves impossible native @Group bounds before one failed mixin discards a target's other mixins.
 * Unknown custom points, slices and wildcard selectors never become zero. The minimum uses an upper bound;
 * the maximum uses a lower bound from plain @Inject callbacks whose descriptor fits the selected method. */
public final class MixinGroupConstraints {
    private static final String GROUP = "Lorg/spongepowered/asm/mixin/injection/Group;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private MixinGroupConstraints() { }

    public static List<String> failures(ClassNode mixin, Function<String, byte[]> resources) {
        return failures(mixin,resources,MixinAddedMembers.View.NONE);
    }
    public static List<String> failures(ClassNode mixin, Function<String, byte[]> resources, MixinAddedMembers.View added) {
        if(added==null)added=MixinAddedMembers.View.NONE;
        List<String> failures = new ArrayList<>();
        for (String targetName : MixinFit.mixinTargets(mixin)) {
            byte[] bytes = resources.apply(targetName + ".class");
            if (bytes == null) continue;
            ClassNode target = MixinFit.withSelfAddedMethods(mixin, MixinFit.parse(bytes));
            Map<String, Bounds> groups = new LinkedHashMap<>();
            for (MethodNode handler : mixin.methods) {
                AnnotationNode group = annotation(handler, GROUP), injector = MixinFit.injectorOf(handler);
                if (group == null || injector == null) continue;
                Object name = MixinFit.value(group, "name");
                Bounds bounds = groups.computeIfAbsent(name == null ? "" : name.toString(), k -> new Bounds());
                int minimum = integer(group, "min", -1), maximum = integer(group, "max", -1);
                if (minimum > 0) bounds.min = Math.max(bounds.min, minimum);
                if (maximum > 0) bounds.max = Math.min(bounds.max, maximum);
                Count count = count(handler, injector, target, added);
                if (count == null) bounds.unknown = true;
                else { bounds.upper += count.upper; bounds.lower += count.lower; }
            }
            for (var entry : groups.entrySet()) {
                Bounds b = entry.getValue();
                int min = Math.max(1, b.min);
                if (!b.unknown && b.upper < min) failures.add(targetName + " @Group(" + entry.getKey()
                        + ") requires at least " + min + " injection(s), but at most " + b.upper + " can bind");
                else if (b.lower > b.max) failures.add(targetName + " @Group(" + entry.getKey()
                        + ") permits at most " + b.max + " injection(s), but at least " + b.lower + " bind");
            }
        }
        return List.copyOf(failures);
    }
    private static final class Bounds { int min = -1, max = Integer.MAX_VALUE, upper, lower; boolean unknown; }
    private record Count(int upper, int lower) { }

    private static Count count(MethodNode handler, AnnotationNode injector, ClassNode target, MixinAddedMembers.View added) {
        if (MixinFit.value(injector, "slice") != null) return null;
        List<String> selectors = MixinFit.stringList(MixinFit.value(injector, "method"));
        if (selectors.isEmpty()) return null;
        Set<MethodNode> selected = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String selector : selectors) {
            if (selector.contains("*") || selector.startsWith("/") || selector.startsWith("@") || selector.contains(";")) return null;
            int paren = selector.indexOf('(');
            String name = paren < 0 ? selector : selector.substring(0, paren);
            String desc = paren < 0 ? null : selector.substring(paren);
            List<MethodNode> named = target.methods.stream().filter(m -> m.name.equals(name) && (desc == null || m.desc.equals(desc))).toList();
            // Bare-name overload binding depends on Mixin's injector and mapping rules. Do not guess it.
            if (named.size() > 1) return null;
            // The earlier mixin's body is not available through View. Its future method is unknown, not absent.
            // For a bare name View cannot enumerate descriptors, so absence on the base cannot prove zero either.
            if(named.isEmpty() && (desc==null && added!=MixinAddedMembers.View.NONE
                    || desc!=null && added.method(target.name,name,desc)))return null;
            selected.addAll(named);
        }
        List<AnnotationNode> points = MixinFit.atNodes(injector);
        if (points.isEmpty()) return null;
        int upper = 0, lower = 0;
        for (MethodNode method : selected) {
            Set<AbstractInsnNode> hits = Collections.newSetFromMap(new IdentityHashMap<>());
            boolean plain = INJECT.equals(injector.desc) && MixinFit.value(injector, "locals") == null && fits(handler, method);
            for (AnnotationNode at : points) {
                List<AbstractInsnNode> matched = points(at, method);
                if (matched == null) return null;
                hits.addAll(matched);
                Object shift = MixinFit.value(at, "shift");
                if (shift != null || MixinFit.value(at, "by") != null || MixinFit.value(at,"args") != null
                        || "INVOKE_ASSIGN".equals(MixinFit.value(at,"value"))) plain = false;
            }
            upper += hits.size();
            if (plain) lower += hits.size();
        }
        return new Count(upper, lower);
    }

    private static List<AbstractInsnNode> points(AnnotationNode at, MethodNode method) {
        Object raw = MixinFit.value(at, "value"), target = MixinFit.value(at, "target");
        if (!(raw instanceof String kind)) return null;
        List<AbstractInsnNode> hits = new ArrayList<>();
        String member = target instanceof String s ? s : null;
        if (MixinFit.value(at,"slice") != null) return null;
        if (Set.of("INVOKE","INVOKE_ASSIGN","FIELD").contains(kind)) {
            if (member == null || !member.startsWith("L") || member.indexOf(';') < 1 || member.contains("*")
                    || member.contains("{") || member.contains("}") || member.contains("@")) return null;
        }
        if (kind.equals("NEW") && (member == null || member.contains(";") || member.contains("(") || member.contains("*"))) return null;
        int opcode = integer(at, "opcode", -1);
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() < 0) continue;
            boolean matches;
            switch (kind) {
                case "HEAD" -> { hits.add(instruction); return hits; }
                case "RETURN", "TAIL" -> matches = instruction.getOpcode() >= Opcodes.IRETURN && instruction.getOpcode() <= Opcodes.RETURN;
                case "INVOKE", "INVOKE_ASSIGN" -> matches = instruction instanceof MethodInsnNode call && member != null
                        && member.equals("L" + call.owner + ";" + call.name + call.desc);
                case "FIELD" -> matches = instruction instanceof FieldInsnNode field && member != null
                        && member.equals("L" + field.owner + ";" + field.name + ":" + field.desc);
                case "NEW" -> matches = instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                        && member != null && member.equals(type.desc);
                default -> { return null; }
            }
            if (matches && (opcode < 0 || opcode == instruction.getOpcode())) hits.add(instruction);
        }
        if (kind.equals("TAIL") && !hits.isEmpty()) hits = new ArrayList<>(List.of(hits.getLast()));
        int ordinal = integer(at, "ordinal", -1);
        if (ordinal >= 0) return ordinal < hits.size() ? List.of(hits.get(ordinal)) : List.of();
        return hits;
    }
    private static boolean fits(MethodNode handler, MethodNode host) {
        if ((handler.access & Opcodes.ACC_STATIC) != (host.access & Opcodes.ACC_STATIC)) return false;
        String callback = Type.getReturnType(host.desc).equals(Type.VOID_TYPE)
                ? "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;"
                : "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
        return handler.desc.equals("(" + callback + ")V") || handler.desc.equals(host.desc.substring(0, host.desc.indexOf(')')) + callback + ")V");
    }
    private static int integer(AnnotationNode node, String key, int fallback) {
        Object value = MixinFit.value(node, key); return value instanceof Number n ? n.intValue() : fallback;
    }
    private static AnnotationNode annotation(MethodNode method, String desc) {
        for (List<AnnotationNode> list : Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations))
            if (list != null) for (AnnotationNode annotation : list) if (desc.equals(annotation.desc)) return annotation;
        return null;
    }
}
