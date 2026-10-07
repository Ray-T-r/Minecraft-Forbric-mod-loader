/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.List;
import java.util.function.Function;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ByteScan;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Adapts declared Mixin plugins' loader contracts from their actual resource ownership. */
public final class MixinPluginPlatformInjector implements ClassTransformer {
    public static final String PROPERTY = "forbric.mixinPluginPlatforms";
    private static final String PLUGIN = "org/spongepowered/asm/mixin/extensibility/IMixinConfigPlugin";
    private static final byte[] PLUGIN_MARKER = ByteScan.needle(PLUGIN);
    private static final String SERVICE = "org/spongepowered/asm/service/MixinService";
    private static final String CONTRACT = "org/spongepowered/asm/service/IMixinService";
    private final Function<String, Ecosystem> classOwner;
    public MixinPluginPlatformInjector(Function<String, Ecosystem> classOwner) { this.classOwner = classOwner; }
    @Override public AnchorSet anchors() { return AnchorSet.scanned("declared Mixin service and reflective transformer contracts"); }
    @Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
        if (bytes == null || "off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return bytes;
        if (!ByteScan.contains(bytes, PLUGIN_MARKER)) return bytes;
        ClassReader reader = new ClassReader(bytes);
        if (!java.util.Arrays.asList(reader.getInterfaces()).contains(PLUGIN)) return bytes;
        ClassNode node = new ClassNode(); reader.accept(node, 0);
        Ecosystem owner = context != null && context.getEcosystem() != null ? context.getEcosystem() : classOwner.apply(node.name.replace('/', '.'));
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (owner != null) changed |= serviceView(node, method, owner);
            changed |= reflectiveSlot(node, method);
        }
        if (!changed) return bytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    private static boolean serviceView(ClassNode node, MethodNode method, Ecosystem owner) {
        String forgeName = null;
        boolean fabric = false, ambiguous = false, readsName = false, already = false;
        for (var instruction : method.instructions) {
            if (instruction instanceof LdcInsnNode value) {
                fabric |= "Knot/Fabric".equals(value.cst);
                if ("ModLauncher".equals(value.cst) || "FML".equals(value.cst)) {
                    if (forgeName != null && !forgeName.equals(value.cst)) ambiguous = true;
                    forgeName = (String) value.cst;
                }
            }
            if (instruction instanceof MethodInsnNode call) {
                readsName |= call.getOpcode() == Opcodes.INVOKEINTERFACE && call.owner.equals(CONTRACT) && call.name.equals("getName") && call.desc.equals("()Ljava/lang/String;");
                already |= call.owner.equals("net/forbric/kernel/interop/CallerMixinService");
            }
        }
        if (!fabric || forgeName == null || ambiguous || !readsName || already) return false;
        String nativeName = owner == Ecosystem.FABRIC ? "Knot/Fabric" : forgeName;
        int changes = 0;
        for (var instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(SERVICE) && call.name.equals("getService") && call.desc.equals("()L" + CONTRACT + ";")) {
            InsnList arguments = new InsnList(); arguments.add(new LdcInsnNode(Type.getObjectType(node.name))); arguments.add(new LdcInsnNode(nativeName));
            method.instructions.insertBefore(call, arguments); call.owner = "net/forbric/kernel/interop/CallerMixinService";
            call.desc = "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;";
            method.instructions.insert(call, new TypeInsnNode(Opcodes.CHECKCAST, CONTRACT)); changes++;
        }
        return changes > 0;
    }
    private static boolean method(AbstractInsnNode instruction, String owner, String name, String descriptor) {
        return instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor);
    }
    private static boolean local(AbstractInsnNode instruction, int opcode, int slot) {
        return instruction instanceof VarInsnNode variable && variable.getOpcode() == opcode && variable.var == slot;
    }
    private static boolean text(AbstractInsnNode instruction, String value) { return instruction instanceof LdcInsnNode constant && value.equals(constant.cst); }
    private static boolean reflectiveSlot(ClassNode node, MethodNode method) {
        if ((method.access & Opcodes.ACC_STATIC) == 0 || !method.desc.equals("()V")) return false;
        List<AbstractInsnNode> code = java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false).filter(i -> i.getOpcode() >= 0).toList();
        if (code.size() < 23 || !(code.get(0) instanceof LdcInsnNode type) || !Type.getObjectType(node.name).equals(type.cst)
                || !method(code.get(1), "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;")) return false;
        int[] slots = new int[4]; int[] stores = {2, 7, 14, 19};
        for (int i = 0; i < stores.length; i++) {
            if (!(code.get(stores[i]) instanceof VarInsnNode variable) || variable.getOpcode() != Opcodes.ASTORE) return false;
            slots[i] = variable.var;
        }
        if (java.util.Arrays.stream(slots).distinct().count() != 4) return false;
        if (!local(code.get(3), Opcodes.ALOAD, slots[0]) || !method(code.get(4), "java/lang/Object", "getClass", "()Ljava/lang/Class;")
                || !text(code.get(5), "delegate") || !method(code.get(6), "java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;")
                || !local(code.get(8), Opcodes.ALOAD, slots[1]) || code.get(9).getOpcode() != Opcodes.ICONST_1
                || !method(code.get(10), "java/lang/reflect/Field", "setAccessible", "(Z)V")
                || !local(code.get(11), Opcodes.ALOAD, slots[1]) || !local(code.get(12), Opcodes.ALOAD, slots[0])
                || !method(code.get(13), "java/lang/reflect/Field", "get", "(Ljava/lang/Object;)Ljava/lang/Object;")
                || !local(code.get(15), Opcodes.ALOAD, slots[2]) || !method(code.get(16), "java/lang/Object", "getClass", "()Ljava/lang/Class;")
                || !text(code.get(17), "mixinTransformer") || !method(code.get(18), "java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;")
                || !local(code.get(20), Opcodes.ALOAD, slots[3]) || code.get(21).getOpcode() != Opcodes.ICONST_1
                || !method(code.get(22), "java/lang/reflect/Field", "setAccessible", "(Z)V")) return false;
        // Removing a prefix crossing a handler/control-flow boundary would not preserve the method's contract.
        AbstractInsnNode end = code.get(22); java.util.Set<AbstractInsnNode> prefix = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (var instruction = method.instructions.getFirst(); instruction != end.getNext(); instruction = instruction.getNext()) prefix.add(instruction);
        if (method.tryCatchBlocks.stream().anyMatch(block -> prefix.contains(block.start) || prefix.contains(block.end) || prefix.contains(block.handler))) return false;
        for (var instruction : method.instructions) if (instruction instanceof JumpInsnNode jump && prefix.contains(jump.label)) return false;
        AbstractInsnNode following = end.getNext();
        for (var instruction = method.instructions.getFirst(); instruction != following;) { var next = instruction.getNext(); method.instructions.remove(instruction); instruction = next; }
        InsnList replacement = new InsnList();
        for (int i = 0; i < 2; i++) { replacement.add(new InsnNode(Opcodes.ACONST_NULL)); replacement.add(new VarInsnNode(Opcodes.ASTORE, slots[i])); }
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/forbric/kernel/mixin/ReflectiveWeaverBridge", "holder", "()Lnet/forbric/kernel/mixin/ReflectiveWeaverBridge$Holder;", false));
        replacement.add(new VarInsnNode(Opcodes.ASTORE, slots[2]));
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/forbric/kernel/mixin/ReflectiveWeaverBridge", "field", "()Ljava/lang/reflect/Field;", false));
        replacement.add(new VarInsnNode(Opcodes.ASTORE, slots[3]));
        method.instructions.insert(replacement); method.localVariables = null; return true;
    }
}
