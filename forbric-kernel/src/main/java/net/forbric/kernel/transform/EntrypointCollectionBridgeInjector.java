/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import net.forbric.kernel.util.ByteScan;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Connects an entrypoint protocol's native collection to foreign declarations through its actual API calls. */
public final class EntrypointCollectionBridgeInjector implements ClassTransformer {
    public record Hook(String owner, String method) { public Hook { owner = owner.replace('.', '/'); } }
    public record Contract(String key, String apiOwner, String registration, String metadataSetter, Hook afterCollection, Hook metadataWrapper) {
        public Contract { apiOwner = apiOwner.replace('.', '/'); }
    }
    private final Contract contract;
    private final byte[] apiNeedle, keyNeedle;
    public EntrypointCollectionBridgeInjector(Contract contract) {
        this.contract = contract; apiNeedle = asciiNeedle(contract.apiOwner); keyNeedle = asciiNeedle(contract.key);
    }
    private static byte[] asciiNeedle(String value) {
        return !value.isEmpty() && value.chars().allMatch(c -> c > 0 && c < 128) ? ByteScan.needle(value) : null;
    }
    @Override public AnchorSet anchors() { return AnchorSet.scanned("declared entrypoint API collection contracts, independent of implementation names"); }
    @Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
        if (bytes == null) return bytes;
        if (apiNeedle != null && !ByteScan.contains(bytes, apiNeedle) || keyNeedle != null && !ByteScan.contains(bytes, keyNeedle)) return bytes;
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES); int changed = 0;
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) == 0 || !method.desc.equals("()V")) continue;
            boolean key = false, register = false, already = false; MethodInsnNode setter = null; int setters = 0;
            for (var instruction : method.instructions) {
                if (instruction instanceof LdcInsnNode constant) key |= contract.key.equals(constant.cst);
                if (!(instruction instanceof MethodInsnNode call)) continue;
                already |= call.owner.equals(contract.afterCollection.owner) && call.name.equals(contract.afterCollection.method);
                if (call.getOpcode() != Opcodes.INVOKESTATIC || !call.owner.equals(contract.apiOwner)) continue;
                register |= call.name.equals(contract.registration) && call.desc.equals("(Ljava/lang/String;Ljava/lang/String;)V");
                if (call.name.equals(contract.metadataSetter) && call.desc.equals("(Ljava/util/function/Function;)V")) { setter = call; setters++; }
            }
            if (!key || !register || already || setters != 1) continue;
            method.instructions.insertBefore(setter, new MethodInsnNode(Opcodes.INVOKESTATIC,
                contract.metadataWrapper.owner, contract.metadataWrapper.method,
                "(Ljava/util/function/Function;)Ljava/util/function/Function;", false));
            for (var instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN) {
                method.instructions.insertBefore(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    contract.afterCollection.owner, contract.afterCollection.method, "()V", false)); changed++;
            }
        }
        if (changed == 0) return bytes;
        ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }
}
