/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

class CallOccurrenceAlignmentTest {
    private static final String OWNER = "other/game/Actions", THING = "other/game/Thing";
    private static final String DESC = "(L" + THING + ";L" + THING + ";L" + THING + ";)V";
    private static final String MEMBER = "L" + THING + ";test()Z";
    @AfterEach void clear() { MixinStubRebind.forget(); }

    @Test void arbitraryGameNamesAndShiftedTemporaryLocalsAreDerived() {
        ClassNode original = owner(0, 1, 2), current = owner(2), mixin = mixin(2);
        assertEquals(1, ThinnedCallOrdinals.adapt(mixin, n -> current, (family, n) -> original));
        assertEquals(0, MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(mixin.methods.getFirst())).getFirst(), "ordinal"));
    }
    @Test void theSameCallOnAnotherOperandIsNotAnEquivalentOccurrence() {
        ClassNode original = owner(0, 1, 2), current = owner(1);
        assertEquals(0, ThinnedCallOrdinals.adapt(mixin(2), n -> current, (family, n) -> original));
    }
    @Test void ambiguousOriginsAndChangedContinuationAreRejected() {
        ClassNode ambiguous=owner(2,1,2);
        LabelNode terminal=new LabelNode();ambiguous.methods.getFirst().instructions.insertBefore(ambiguous.methods.getFirst().instructions.getLast(),terminal);
        for(var instruction:ambiguous.methods.getFirst().instructions)if(instruction instanceof JumpInsnNode branch)branch.label=terminal;
        assertEquals(0, ThinnedCallOrdinals.adapt(mixin(2), n -> owner(2), (family, n) -> ambiguous));
        ClassNode current = owner(2);
        for (var instruction : current.methods.getFirst().instructions)
            if (instruction instanceof MethodInsnNode call && call.name.equals("next")) call.name = "different";
        assertEquals(0, ThinnedCallOrdinals.adapt(mixin(2), n -> current, (family, n) -> owner(0, 1, 2)));
    }
    @Test void missingNativeBytesAndGroupsCannotBorrowAPreviousTable() {
        assertEquals(0, ThinnedCallOrdinals.adapt(mixin(2), n -> owner(2), (family, n) -> null));
        ClassNode mixin = mixin(2);
        mixin.methods.getFirst().visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Group;"));
        assertEquals(0, ThinnedCallOrdinals.adapt(mixin, n -> owner(2), (family, n) -> owner(0, 1, 2)));
    }
    private static ClassNode owner(int... slots) {
        ClassNode node = new ClassNode(); node.name = OWNER; node.superName = "java/lang/Object"; node.version = Opcodes.V21;
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "perform", DESC, null, null);
        for (int slot : slots) {
            LabelNode done = new LabelNode();
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, THING, "test", "()Z", false));
            method.instructions.add(new JumpInsnNode(Opcodes.IFNE, done));
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, THING, "next", "()V", false));
            method.instructions.add(done);
        }
        method.instructions.add(new InsnNode(Opcodes.RETURN)); method.maxStack = 1; method.maxLocals = 3; node.methods.add(method);
        return node;
    }
    private static ClassNode mixin(int ordinal) {
        ClassNode node = new ClassNode(); node.name = "unrelated/vendor/Callbacks";
        AnnotationNode annotation = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
        annotation.values = new ArrayList<>(List.of("value", List.of(Type.getObjectType(OWNER))));
        node.visibleAnnotations = new ArrayList<>(List.of(annotation));
        MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "custom", "()V", null, null);
        AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        at.values = new ArrayList<>(List.of("value", "INVOKE", "target", MEMBER, "ordinal", ordinal));
        AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
        inject.values = new ArrayList<>(List.of("method", List.of("perform" + DESC), "at", List.of(at)));
        handler.visibleAnnotations = new ArrayList<>(List.of(inject)); node.methods.add(handler);
        MixinStubRebind.noteEcosystem(node.name, Ecosystem.FABRIC); return node;
    }
}
