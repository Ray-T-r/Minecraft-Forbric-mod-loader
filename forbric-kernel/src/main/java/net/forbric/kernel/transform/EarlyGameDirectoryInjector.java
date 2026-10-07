/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.forbric.kernel.util.ByteScan;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Resolves an early Mixin plugin's declared game-directory getter without constructing a game provider. */
public final class EarlyGameDirectoryInjector implements ClassTransformer {
    public static final String PROPERTY = "forbric.earlyGameDirectory";
    private static final String PLUGIN = "org/spongepowered/asm/mixin/extensibility/IMixinConfigPlugin";
    private static final byte[] PLUGIN_MARKER = ByteScan.needle(PLUGIN);
    private static final String FABRIC = "net/fabricmc/loader/api/FabricLoader";
    private final Function<String, ClassNode> declarations;
    public EarlyGameDirectoryInjector(Function<String, ClassNode> declarations) { this.declarations = declarations; }
    @Override public AnchorSet anchors() { return AnchorSet.scanned("a unique declared directory getter in a Mixin plugin initializer"); }
    @Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
        if (bytes == null || "off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return bytes;
        if (!ByteScan.contains(bytes, PLUGIN_MARKER)) return bytes;
        ClassReader reader = new ClassReader(bytes);
        if (!java.util.Arrays.asList(reader.getInterfaces()).contains(PLUGIN)) return bytes;
        ClassNode node = new ClassNode(); reader.accept(node, 0);
        MethodNode initializer = node.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElse(null);
        if (initializer == null) return bytes;
        List<MethodInsnNode> getters = new ArrayList<>();
        for (var instruction : initializer.instructions)
            if (instruction instanceof MethodInsnNode call && call.name.equals("getGameDir") && call.desc.equals("()Ljava/nio/file/Path;")) getters.add(call);
        if (getters.size() != 1) return bytes;
        MethodInsnNode getter = getters.getFirst(); AbstractInsnNode before = getter.getPrevious();
        while (before != null && before.getOpcode() < 0) before = before.getPrevious();
        if (!(before instanceof MethodInsnNode instance) || instance.getOpcode() != Opcodes.INVOKESTATIC
                || getter.getOpcode() != Opcodes.INVOKEINTERFACE || getter.owner.equals(FABRIC)
                || !instance.owner.equals(getter.owner) || !instance.name.equals("getInstance")
                || !instance.desc.equals("()L" + getter.owner + ";")) return bytes;
        ClassNode contract = declarations.apply(getter.owner);
        if (contract == null || (contract.access & Opcodes.ACC_INTERFACE) == 0
                || contract.methods.stream().filter(m -> m.name.equals("getGameDir") && m.desc.equals(getter.desc)
                    && (m.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT)) == (Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT)).count() != 1
                || contract.methods.stream().noneMatch(m -> m.name.equals("getInstance") && m.desc.equals(instance.desc)
                    && (m.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))) return bytes;
        // Another use of this provider in the initializer would still initialize it and defeats this contract.
        for (var instruction : initializer.instructions)
            if (instruction instanceof MethodInsnNode call && call != getter && call != instance && call.owner.equals(getter.owner)) return bytes;
        instance.owner = FABRIC; instance.desc = "()L" + FABRIC + ";"; instance.itf = true;
        getter.owner = FABRIC;
        ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }
}
