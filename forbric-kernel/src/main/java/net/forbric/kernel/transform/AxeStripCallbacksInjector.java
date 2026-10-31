/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.Set;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Native tool decisions ask the held axe's original stripping method, where Fabric mods attach their callbacks. */
public final class AxeStripCallbacksInjector implements ClassTransformer {
    public static final String PROPERTY = "forbric.axeStripCallbacks";
    static final String AXE = "net/minecraft/world/item/AxeItem";
    static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    static final String CONTEXT = "Lnet/minecraft/world/item/context/UseOnContext;";
    static final String ITEM = "net/minecraft/world/item/Item";
    static final String STACK = "net/minecraft/world/item/ItemStack";
    static final String HELPER = "forbric$getStrippedState";
    static final String DESC = "(" + STATE + CONTEXT + ")" + STATE;
    private static final Set<String> BLOCKS = Set.of("net/neoforged/neoforge/common/extensions/IBlockExtension");
    private static boolean enabled() { return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on")); }
    @Override public String name() { return "forbric-axe-strip-callbacks"; }
    @Override public AnchorSet anchors() {
        if (!enabled()) return AnchorSet.scanned("axe stripping callbacks left disconnected by request");
        return AnchorSet.of(new AnchorSet.Anchor(AXE.replace('/', '.'), AnchorSet.Severity.REQUIRED, "Fabric stripping callbacks never run on native tool modification"),
                new AnchorSet.Anchor("net.neoforged.neoforge.common.extensions.IBlockExtension", AnchorSet.Severity.REQUIRED, "NeoForge tool actions must reach the axe's stripping callbacks"));
    }
    @Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
        String internal = name.replace('.', '/');
        if (!enabled() || bytes == null || (!AXE.equals(internal) && !BLOCKS.contains(internal))) return bytes;
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        int changed = 0;
        if (AXE.equals(internal)) {
            if (node.methods.stream().anyMatch(m -> m.name.equals(HELPER))) return bytes;
            if (node.methods.stream().noneMatch(m -> m.name.equals("getStripped") && m.desc.equals("(" + STATE + ")Ljava/util/Optional;"))
                    || node.methods.stream().noneMatch(m -> m.name.equals("getAxeStrippingState") && m.desc.equals("(" + STATE + ")" + STATE))) return bytes;
            MethodNode helper = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, HELPER, DESC, null, null);
            var code = helper.instructions;
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/item/context/UseOnContext", "getItemInHand", "()L" + STACK + ";", false));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, STACK, "getItem", "()L" + ITEM + ";", false));
            code.add(new VarInsnNode(Opcodes.ASTORE, 2));
            code.add(new VarInsnNode(Opcodes.ALOAD, 2)); code.add(new TypeInsnNode(Opcodes.INSTANCEOF, AXE));
            LabelNode nativePath = new LabelNode(); code.add(new JumpInsnNode(Opcodes.IFEQ, nativePath));
            code.add(new VarInsnNode(Opcodes.ALOAD, 2)); code.add(new TypeInsnNode(Opcodes.CHECKCAST, AXE));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, AXE, "getStripped", "(" + STATE + ")Ljava/util/Optional;", false));
            code.add(new InsnNode(Opcodes.ACONST_NULL));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "orElse", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/world/level/block/state/BlockState")); code.add(new InsnNode(Opcodes.ARETURN));
            code.add(nativePath); code.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[] {ITEM}, 0, null));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, AXE, "getAxeStrippingState", "(" + STATE + ")" + STATE, false));
            code.add(new InsnNode(Opcodes.ARETURN)); helper.maxLocals = 3; node.methods.add(helper); changed++;
        } else {
            for (MethodNode method : node.methods) {
                if (!method.name.equals("getToolModifiedState") || !method.desc.startsWith("(" + STATE + CONTEXT)) continue;
                for (var instruction : method.instructions.toArray()) if (instruction instanceof MethodInsnNode call
                        && call.owner.equals(AXE) && call.name.equals("getAxeStrippingState") && call.desc.equals("(" + STATE + ")" + STATE)) {
                    method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 2));
                    call.name = HELPER; call.desc = DESC; changed++;
                }
            }
        }
        if (changed == 0) return bytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
}
