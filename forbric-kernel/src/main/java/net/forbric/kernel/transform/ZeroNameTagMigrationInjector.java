/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.forbric.kernel.util.ByteScan;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Migrates the removed name-tag attribute only when its value is proved to suppress tags (zero). */
public final class ZeroNameTagMigrationInjector implements ClassTransformer {
    public static final String PROPERTY = "forbric.zeroNameTagMigration";
    public static final String NATIVE = "net/neoforged/neoforge/common/NeoForgeMod";
    public static final String ATTRIBUTES = "net/minecraft/world/entity/ai/attributes/Attributes";
    public static final String HOLDER = "Lnet/minecraft/core/Holder;";
    private static final String INSTANCE = "net/minecraft/world/entity/ai/attributes/AttributeInstance";
    private static final String LOOKUP = "net/minecraft/world/entity/ai/attributes/AttributeMap";
    private static final byte[] LEGACY_FIELD = ByteScan.needle("NAMETAG_DISTANCE");
    private final Function<String, ClassNode> declarations;

    public ZeroNameTagMigrationInjector(Function<String, ClassNode> declarations) { this.declarations = declarations; }
    @Override public AnchorSet anchors() { return AnchorSet.scanned("proved zero-distance uses of a removed platform attribute"); }
    @Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
        if (bytes == null || "off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return bytes;
        if (!ByteScan.contains(bytes, LEGACY_FIELD)) return bytes;
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        List<FieldInsnNode> reads = new ArrayList<>();
        for (MethodNode method : node.methods) for (var instruction : method.instructions)
            if (legacy(instruction)) reads.add((FieldInsnNode) instruction);
        if (reads.isEmpty()) return bytes;
        ClassNode old = declarations.apply(NATIVE), replacement = declarations.apply(ATTRIBUTES);
        if (old == null || replacement == null || old.fields.stream().anyMatch(f -> f.name.equals("NAMETAG_DISTANCE"))) return bytes;
        if (replacement.fields.stream().noneMatch(f -> f.name.equals("NAME_TAG_DISTANCE") && f.desc.equals(HOLDER)
                && (f.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))) return bytes;
        for (MethodNode method : node.methods) if (java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false).anyMatch(ZeroNameTagMigrationInjector::legacy)
                && !zeroOnly(node.name, method)) return bytes;
        for (FieldInsnNode field : reads) { field.owner = ATTRIBUTES; field.name = "NAME_TAG_DISTANCE"; }
        ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }

    private static boolean legacy(AbstractInsnNode instruction) {
        return instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                && field.owner.equals(NATIVE) && field.name.equals("NAMETAG_DISTANCE") && field.desc.equals(HOLDER);
    }

    /** Copies keep their producer, so aliases through locals do not hide an escaping or nonzero use. */
    private static final class Origins extends SourceInterpreter {
        Origins() { super(Opcodes.ASM9); }
        @Override public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) { return value; }
    }

    private static boolean only(SourceValue value, AbstractInsnNode producer) {
        return value != null && value.insns.size() == 1 && value.insns.contains(producer);
    }

    private static boolean zeroOnly(String owner, MethodNode method) {
        try {
            Frame<SourceValue>[] frames = new Analyzer<>(new Origins()).analyze(owner, method);
            List<AbstractInsnNode> lookups = new ArrayList<>();
            int reads = 0;
            for (int i = 0; i < method.instructions.size(); i++) {
                var instruction = method.instructions.get(i); if (!legacy(instruction)) continue; reads++;
                AbstractInsnNode next = instruction.getNext(); while (next != null && next.getOpcode() < 0) next = next.getNext();
                if (!(next instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || !LOOKUP.equals(call.owner) || !call.name.equals("getInstance")
                        || !call.desc.equals("(" + HOLDER + ")L" + INSTANCE + ";")) return false;
                lookups.add(call);
            }
            java.util.Map<AbstractInsnNode, Integer> setters = new java.util.IdentityHashMap<>();
            for (AbstractInsnNode lookup : lookups) setters.put(lookup, 0);
            for (int i = 0; i < method.instructions.size(); i++) {
                Frame<SourceValue> frame = frames[i]; if (frame == null) continue;
                AbstractInsnNode instruction = method.instructions.get(i);
                for (AbstractInsnNode lookup : lookups) {
                    boolean top = frame.getStackSize() > 0 && only(frame.getStack(frame.getStackSize() - 1), lookup);
                    if (instruction instanceof MethodInsnNode call) {
                        int arguments = Type.getArgumentTypes(call.desc).length;
                        int receiver = frame.getStackSize() - arguments - (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
                        boolean consumed = receiver >= 0 && only(frame.getStack(receiver), lookup);
                        for (int a = frame.getStackSize() - arguments; a < frame.getStackSize(); a++)
                            if (a >= 0 && only(frame.getStack(a), lookup)) return false;
                        if (!consumed) continue;
                        if (call.getOpcode() != Opcodes.INVOKEVIRTUAL || !INSTANCE.equals(call.owner)
                                || !call.name.equals("setBaseValue") || !call.desc.equals("(D)V")) return false;
                        SourceValue value = frame.getStack(frame.getStackSize() - 1);
                        if (value.insns.size() != 1 || value.insns.iterator().next().getOpcode() != Opcodes.DCONST_0) return false;
                        setters.put(lookup, setters.get(lookup) + 1);
                    } else if (top && instruction.getOpcode() >= 0 && instruction.getOpcode() != Opcodes.ASTORE
                            && instruction.getOpcode() != Opcodes.IFNULL && instruction.getOpcode() != Opcodes.IFNONNULL
                            && instruction.getOpcode() != Opcodes.DCONST_0 && instruction.getOpcode() != Opcodes.ALOAD
                            && instruction.getOpcode() != Opcodes.NOP) return false;
                }
            }
            return reads > 0 && setters.values().stream().allMatch(count -> count == 1);
        } catch (AnalyzerException | RuntimeException invalid) { return false; }
    }
}
