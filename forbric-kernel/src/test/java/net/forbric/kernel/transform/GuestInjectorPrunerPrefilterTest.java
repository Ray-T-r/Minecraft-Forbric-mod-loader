/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * The pruner runs for every class the game loads. Each closed protocol's callback calls its source API, so a class
 * whose constant pool does not name one is handed back before it is parsed. The fixture is an invented mixin carrying
 * the reader-deserializer protocol's shape; only the platform's own API names are real.
 */
class GuestInjectorPrunerPrefilterTest {
    private static final String MIXIN = "quiet/shapes/LoaderPatch", HOST = "quiet/shapes/ShapeLoader";
    private static final String DESERIALIZER = "net/fabricmc/fabric/api/client/model/loading/v1/UnbakedModelDeserializer";
    private static final String CUBOID = "net/minecraft/client/resources/model/cuboid/CuboidModel";
    private static final String UNBAKED = "Lnet/minecraft/client/resources/model/UnbakedModel;";

    @AfterEach
    void reset() {
        net.forbric.api.CompatibilityFindings.reset();
    }

    private static AnnotationNode at(String target) {
        AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", "INVOKE");
        at.visit("target", target);
        return at;
    }

    private static AnnotationNode injector(String desc, String target) {
        AnnotationNode a = new AnnotationNode(desc);
        a.values = new ArrayList<>(List.of("method", List.of("readShape"), "at", at(target)));
        return a;
    }

    /**
     * The protocol's two callbacks: a redirect that drops the old parser's model, and an argument modifier that hands
     * the captured Reader to {@code deserializerOwner}. With {@code redirect} false only the second exists.
     */
    private static byte[] mixin(String deserializerOwner, boolean redirect) {
        ClassNode node = new ClassNode();
        node.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, MIXIN, null, "java/lang/Object", null);
        AnnotationNode mixin = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
        mixin.values = new ArrayList<>(List.of("targets", List.of(HOST)));
        node.invisibleAnnotations = new ArrayList<>(List.of(mixin));
        if (redirect) {
            MethodNode drop = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "dropOld", "(Ljava/io/Reader;)L" + CUBOID + ";", null, null);
            drop.visibleAnnotations = new ArrayList<>(List.of(injector("Lorg/spongepowered/asm/mixin/injection/Redirect;",
                    "L" + CUBOID + ";fromStream(Ljava/io/Reader;)L" + CUBOID + ";")));
            drop.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
            drop.instructions.add(new InsnNode(Opcodes.ARETURN));
            drop.maxStack = 1;
            drop.maxLocals = 1;
            node.methods.add(drop);
        }
        MethodNode parse = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "readNew", "(Ljava/lang/Object;Ljava/io/Reader;)Ljava/lang/Object;", null, null);
        AnnotationNode modify = injector("Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
                "Lcom/mojang/datafixers/util/Pair;of(Ljava/lang/Object;Ljava/lang/Object;)Lcom/mojang/datafixers/util/Pair;");
        modify.values.addAll(List.of("index", 1));
        parse.visibleAnnotations = new ArrayList<>(List.of(modify));
        AnnotationNode local = new AnnotationNode("Lcom/llamalad7/mixinextras/sugar/Local;");
        local.visit("index", 0);
        @SuppressWarnings("unchecked") List<AnnotationNode>[] parameters = new List[] {null, new ArrayList<>(List.of(local))};
        parse.invisibleParameterAnnotations = parameters;
        parse.invisibleAnnotableParameterCount = 2;
        parse.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        parse.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, deserializerOwner, "deserialize", "(Ljava/io/Reader;)" + UNBAKED, false));
        parse.instructions.add(new InsnNode(Opcodes.ARETURN));
        parse.maxStack = 1;
        parse.maxLocals = 2;
        node.methods.add(parse);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** The host the mixin targets: the current parser consumes the Reader, and Pair.of captures the same value. */
    private static ClassNode host() {
        ClassNode node = new ClassNode();
        node.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, HOST, null, "java/lang/Object", null);
        MethodNode read = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "readShape", "(Ljava/io/Reader;)Lcom/mojang/datafixers/util/Pair;", null, null);
        read.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        read.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/neoforged/neoforge/client/model/UnbakedModelParser", "parse", "(Ljava/io/Reader;)" + UNBAKED, false));
        read.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        read.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/mojang/datafixers/util/Pair", "of",
                "(Ljava/lang/Object;Ljava/lang/Object;)Lcom/mojang/datafixers/util/Pair;", false));
        read.instructions.add(new InsnNode(Opcodes.ARETURN));
        read.maxStack = 2;
        read.maxLocals = 1;
        node.methods.add(read);
        return node;
    }

    private static GuestInjectorPruner pruner() {
        return new GuestInjectorPruner(owner -> owner.equals(HOST) ? host() : null);
    }

    private static List<String> methods(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.methods.stream().map(m -> m.name).toList();
    }

    @Test
    void theProtocolUnderAnInventedNameIsStillPruned() {
        byte[] original = mixin(DESERIALIZER, true);
        GuestInjectorPruner pruner = pruner();
        byte[] pruned = pruner.transform(MIXIN.replace('/', '.'), original, null);
        assertNotSame(original, pruned);
        assertEquals(List.of(), methods(pruned), "both callbacks of the closed pair go together");
        assertEquals(1, pruner.classesParsed());
        assertEquals(2, pruner.prunedInjectors());
    }

    @Test
    void aClassThatNamesNoProtocolSourceIsReturnedUnparsed() {
        GuestInjectorPruner pruner = pruner();
        // The same two callbacks, but the Reader goes to a deserializer of the mod's own: not the protocol.
        byte[] lookalike = mixin("quiet/shapes/OwnDeserializer", true);
        assertSame(lookalike, pruner.transform(MIXIN.replace('/', '.'), lookalike, null));
        // An ordinary class.
        ClassWriter writer = new ClassWriter(0);
        host().accept(writer);
        byte[] plain = writer.toByteArray();
        assertSame(plain, pruner.transform(HOST.replace('/', '.'), plain, null));
        assertEquals(0, pruner.classesParsed(), "neither was parsed");
        assertEquals(0, pruner.prunedInjectors());
    }

    @Test
    void halfTheProtocolPassesThePrefilterAndTheStructuralCheckStillLeavesIt() {
        GuestInjectorPruner pruner = pruner();
        byte[] half = mixin(DESERIALIZER, false);
        assertSame(half, pruner.transform(MIXIN.replace('/', '.'), half, null), "the deserializer callback alone is not the closed pair");
        assertEquals(1, pruner.classesParsed(), "the prefilter only rules out; the shape still decides");
        assertEquals(0, pruner.prunedInjectors());
    }
}
