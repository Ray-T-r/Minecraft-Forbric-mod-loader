/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.function.Function;
import net.forbric.kernel.mixin.MixinInstructionFingerprint;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** NeoForge 26.2.0.30 removed NAMETAG_DISTANCE in favor of vanilla's NAME_TAG_DISTANCE.
 * This is deliberately scoped to Corpse's audited constructor, which only sets that distance to zero.
 * The old and new attributes differ for some nonzero crouching distances (NeoForge PR #3333), so this
 * is not a general field alias. The vanilla attribute expresses this constructor's intended suppression.
 */
public final class CorpseNameTagAdapter implements ClassTransformer {
    public static final String PROPERTY = "forbric.corpseNameTag";
    public static final String TARGET = "de/maxhenkel/corpse/entities/DummyPlayer";
    public static final String NATIVE = "net/neoforged/neoforge/common/NeoForgeMod";
    public static final String ATTRIBUTES = "net/minecraft/world/entity/ai/attributes/Attributes";
    public static final String HOLDER = "Lnet/minecraft/core/Holder;";
    public static final String CONSTRUCTOR = "(Lnet/minecraft/client/multiplayer/ClientLevel;Lcom/mojang/authlib/GameProfile;Ljava/util/EnumMap;B)V";
    private static final String REVIEWED_BODY = "342e63525d1b1856bfb1be8f82e66e7ade8a4a4de9b32553e419c88eea1134c8";
    private final Function<String, ClassNode> declarations;
    public CorpseNameTagAdapter(Function<String, ClassNode> declarations) { this.declarations = declarations; }
    @Override public String name() { return "forbric-corpse-name-tag"; }
    @Override public AnchorSet anchors() { return AnchorSet.scanned("audited zero-distance legacy attribute read, only when its native field was removed"); }
    @Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
        if (bytes == null || !TARGET.replace('/', '.').equals(name) || "off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return bytes;
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        if (!TARGET.equals(node.name)) return bytes;
        MethodNode constructor = node.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.equals(CONSTRUCTOR)).findFirst().orElse(null);
        if (constructor == null || !REVIEWED_BODY.equals(MixinInstructionFingerprint.hash(constructor))) return bytes;
        ClassNode oldOwner = declarations.apply(NATIVE), replacement = declarations.apply(ATTRIBUTES);
        if (oldOwner == null || replacement == null || oldOwner.fields.stream().anyMatch(f -> f.name.equals("NAMETAG_DISTANCE"))) return bytes;
        if (replacement.fields.stream().noneMatch(f -> f.name.equals("NAME_TAG_DISTANCE") && f.desc.equals(HOLDER)
                && (f.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))) return bytes;
        int changed = 0;
        for (var instruction : constructor.instructions) if (instruction instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.GETSTATIC && field.owner.equals(NATIVE)
                && field.name.equals("NAMETAG_DISTANCE") && field.desc.equals(HOLDER)) {
            field.owner = ATTRIBUTES; field.name = "NAME_TAG_DISTANCE"; changed++;
        }
        if (changed != 1) return bytes;
        ForbricLog.info("[Forbric/AttributeMigration] Corpse's audited dummy-player constructor suppresses name tags through the vanilla attribute that replaced NeoForgeMod.NAMETAG_DISTANCE");
        ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }
}
