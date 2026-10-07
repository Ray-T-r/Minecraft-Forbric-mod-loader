/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.forbric.kernel.util.ByteScan;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Adapts declared consumers of an optional entrypoint API only when that interface is absent. */
public final class MissingEntrypointInterfaceInjector implements ClassTransformer {
    private record Method(String name, String descriptor) { }
    private final String original, bridge;
    private final List<Method> methods;
    private final BooleanSupplier originalAvailable;
    private final byte[] originalMarker;
    public MissingEntrypointInterfaceInjector(String originalInterface, Class<?> bridgeInterface, BooleanSupplier originalAvailable) {
        if (!bridgeInterface.isInterface()) throw new IllegalArgumentException("entrypoint bridge must be an interface");
        this.original = originalInterface.replace('.', '/'); this.bridge = Type.getInternalName(bridgeInterface);
        originalMarker = original.chars().allMatch(c -> c > 0 && c < 128) ? ByteScan.needle(original) : null;
        this.originalAvailable = originalAvailable;
        methods = java.util.Arrays.stream(bridgeInterface.getMethods()).filter(m -> Modifier.isAbstract(m.getModifiers()))
                .map(m -> new Method(m.getName(), Type.getMethodDescriptor(m))).toList();
        if (methods.isEmpty()) throw new IllegalArgumentException("entrypoint bridge must declare an executable contract");
    }
    @Override public AnchorSet anchors() { return AnchorSet.scanned("declared consumers of an absent optional entrypoint interface"); }
    @Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
        if (bytes == null || originalMarker != null && !ByteScan.contains(bytes, originalMarker)) return bytes;
        ClassReader reader = new ClassReader(bytes);
        if (!java.util.Arrays.asList(reader.getInterfaces()).contains(original)) return bytes;
        if (originalAvailable.getAsBoolean()) return bytes;
        ClassNode node = new ClassNode(); reader.accept(node, 0);
        if (node.fields.stream().anyMatch(field -> field.desc.contains("L" + original + ";"))) return bytes;
        for (Method required : methods) if (node.methods.stream().noneMatch(m -> m.name.equals(required.name)
                && m.desc.equals(required.descriptor) && m.instructions.size() > 0
                && (m.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == Opcodes.ACC_PUBLIC)) return bytes;
        // A missing API's default/static methods or embedded types cannot be invented by this contract.
        for (MethodNode method : node.methods) {
            if (method.desc.contains("L" + original + ";")) return bytes;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(original)) return bytes;
                if (instruction instanceof FieldInsnNode field && (field.owner.equals(original) || field.desc.contains("L" + original + ";"))) return bytes;
                if (instruction instanceof TypeInsnNode type && type.desc.equals(original)) return bytes;
                if (instruction instanceof InvokeDynamicInsnNode dynamic) for (Object argument : dynamic.bsmArgs) {
                    if (argument instanceof Handle handle && (handle.getOwner().equals(original) || handle.getDesc().contains("L" + original + ";"))) return bytes;
                    if (argument instanceof Type type && type.getDescriptor().contains("L" + original + ";")) return bytes;
                }
            }
        }
        node.interfaces.replaceAll(i -> i.equals(original) ? bridge : i);
        ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }
}
