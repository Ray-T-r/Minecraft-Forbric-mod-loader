/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Recomputes an incompatible reference frame from the actual resource hierarchy, without loading classes.
 * No ecosystem, mod name, lost-ancestor list or future transformer superclass is predicted. */
public final class MergedBaseFrameRecomputer implements ClassTransformer {
    private final Function<String, byte[]> classBytes;
    private final Map<String, String[]> hierarchy = new ConcurrentHashMap<>();
    public MergedBaseFrameRecomputer(Function<String, byte[]> classBytes) { this.classBytes = classBytes; }
    @Override public String name() { return "forbric-merged-base-frame-recomputer"; }
    @Override public AnchorSet anchors() { return AnchorSet.scanned("checks reference frames against actual class resources"); }

    @Override public byte[] transform(String className, byte[] input, TransformContext context) {
        if (input == null || input.length < 10 || majorVersion(input) < Opcodes.V1_6) return input;
        try {
            ClassNode original = node(input);
            if (original.methods.stream().noneMatch(MergedBaseFrameRecomputer::hasReferenceFrame)) return input;
            // The current definition wins over a raw resource for this class while it is being transformed.
            hierarchy.put(original.name, new String[] {original.superName, (original.access & Opcodes.ACC_INTERFACE) != 0 ? "1" : "0"});
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                @Override protected String getCommonSuperClass(String a, String b) { return commonSuperClass(a, b); }
            };
            // A reader-sharing writer silently copies Code/StackMapTable and never recomputes those methods.
            new ClassReader(input).accept(writer, ClassReader.SKIP_FRAMES);
            byte[] computed = writer.toByteArray();
            ClassNode current = node(computed);
            Set<String> incompatible = new LinkedHashSet<>();
            for (MethodNode before : original.methods) {
                MethodNode after = current.methods.stream().filter(m -> m.name.equals(before.name) && m.desc.equals(before.desc)).findFirst().orElse(null);
                if (after == null || !opcodes(before).equals(opcodes(after))) continue;
                Map<Integer, FrameNode> actual = frames(after);
                for (var entry : frames(before).entrySet()) {
                    FrameNode inferred = actual.get(entry.getKey());
                    if (inferred == null) continue;
                    compare(slots(entry.getValue().local), slots(inferred.local), incompatible);
                    compare(entry.getValue().stack, inferred.stack, incompatible);
                }
            }
            if (incompatible.isEmpty()) return input;
            ForbricLog.info("[Forbric/Frames] recomputed %s: reference frames %s disagree with the actual hierarchy", className, incompatible);
            return computed;
        } catch (Throwable failure) {
            ForbricLog.warn("[Forbric/Frames] cannot prove a frame repair for " + className + "; preserving its original bytes", failure);
            return input;
        }
    }
    private void compare(List<Object> declared, List<Object> actual, Set<String> incompatible) {
        if (declared == null || actual == null) return;
        for (int i = 0; i < Math.min(declared.size(), actual.size()); i++) {
            if (declared.get(i) instanceof String expected && actual.get(i) instanceof String value && !assignable(expected, value)) incompatible.add(expected);
        }
    }
    private static List<Object> slots(List<Object> locals) {
        if (locals == null) return List.of();
        List<Object> out = new ArrayList<>();
        for (Object local : locals) { out.add(local); if (local.equals(Opcodes.LONG) || local.equals(Opcodes.DOUBLE)) out.add(Opcodes.TOP); }
        return out;
    }
    private static Map<Integer, FrameNode> frames(MethodNode method) {
        Map<Integer, FrameNode> out = new LinkedHashMap<>(); int offset = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FrameNode frame) out.put(offset, frame);
            else if (instruction.getOpcode() >= 0) offset++;
        }
        return out;
    }
    private static List<Integer> opcodes(MethodNode method) {
        List<Integer> out = new ArrayList<>(); for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) out.add(instruction.getOpcode()); return out;
    }
    private static boolean hasReferenceFrame(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof FrameNode frame) {
            if (frame.local != null && frame.local.stream().anyMatch(String.class::isInstance) || frame.stack != null && frame.stack.stream().anyMatch(String.class::isInstance)) return true;
        }
        return false;
    }
    private static ClassNode node(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES); return node; }
    private static int majorVersion(byte[] bytes) { return ((bytes[6] & 255) << 8) | (bytes[7] & 255); }

    /** Frame interfaces accept object references; arrays retain component assignability and dimensions. */
    boolean assignable(String expected, String actual) {
        if (expected.equals(actual) || expected.equals("java/lang/Object")) return true;
        if (expected.startsWith("[")) {
            if (!actual.startsWith("[")) return false;
            String a = component(expected), b = component(actual);
            if (a == null || b == null) return expected.equals(actual);
            return assignable(a, b);
        }
        if (actual.startsWith("[")) return expected.equals("java/lang/Cloneable") || expected.equals("java/io/Serializable") || isInterface(expected);
        if (isInterface(expected)) return true;
        Set<String> seen = new HashSet<>(); String owner = actual;
        for (int hop = 0; owner != null && hop < 64; hop++) {
            if (owner.equals(expected)) return true;
            if (!seen.add(owner)) throw new IllegalStateException("Cyclic hierarchy: " + actual);
            owner = header(owner)[0];
        }
        if (owner != null) throw new IllegalStateException("Hierarchy exceeds proof bound: " + actual);
        return false;
    }
    private static String component(String array) {
        String element = array.substring(1);
        return element.startsWith("[") ? element : element.startsWith("L") && element.endsWith(";") ? element.substring(1, element.length() - 1) : null;
    }
    String commonSuperClass(String first, String second) {
        if (first.equals(second)) return first;
        if (first.startsWith("[") || second.startsWith("[")) {
            if (assignable(first, second)) return first;
            if (assignable(second, first)) return second;
            if (first.startsWith("[") && second.startsWith("[")) {
                String a = component(first), b = component(second);
                if (a != null && b != null) { String shared = commonSuperClass(a, b); return shared.startsWith("[") ? "[" + shared : "[L" + shared + ";"; }
            }
            return "java/lang/Object";
        }
        if (isInterface(first) || isInterface(second)) return "java/lang/Object";
        Set<String> others = new LinkedHashSet<>(chain(second));
        for (String candidate : chain(first)) if (others.contains(candidate)) return candidate;
        return "java/lang/Object";
    }
    private List<String> chain(String type) {
        List<String> out = new ArrayList<>(); String current = type;
        for (int hop = 0; current != null && hop < 64; hop++) {
            if (out.contains(current)) throw new IllegalStateException("Cyclic hierarchy: " + type);
            out.add(current); if (current.equals("java/lang/Object")) return out; current = header(current)[0];
        }
        if (current != null) throw new IllegalStateException("Hierarchy exceeds proof bound: " + type);
        return out;
    }
    private boolean isInterface(String type) { return header(type)[1].equals("1"); }
    private String[] header(String type) {
        return hierarchy.computeIfAbsent(type, name -> {
            if (name.equals("java/lang/Object")) return new String[] {null, "0"};
            byte[] bytes = classBytes.apply(name + ".class"); if (bytes == null) throw new TypeNotPresentException(name, null);
            ClassReader reader = new ClassReader(bytes); return new String[] {reader.getSuperName(), (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0 ? "1" : "0"};
        });
    }
}
