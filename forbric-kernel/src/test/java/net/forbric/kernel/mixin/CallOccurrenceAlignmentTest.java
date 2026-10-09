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

    static int first(int value) { return value; }
    static int second(int value) { return -value; }
    static void sink(int value) { }
    /**
     * The loop's own condition is a stack phi (a conditional expression), so each of its alternatives is guarded by the
     * loop branch that consumes it, and describing that branch describes the phi again.
     */
    static int loopCarried(boolean pick, int n) {
        int i = 0;
        while ((pick ? first(i) : second(i)) < n) {
            sink(pick ? first(i) : second(i));
            i++;
        }
        return i;
    }

    /** Describing a loop whose condition is a conditional expression ends — with a description or as unprovable — instead of overflowing the stack. */
    @Test void describingALoopWhoseConditionIsAConditionalExpressionTerminates() throws Exception {
        ClassNode node = new ClassNode();
        try (var in = CallOccurrenceAlignmentTest.class.getResourceAsStream("CallOccurrenceAlignmentTest.class")) {
            new org.objectweb.asm.ClassReader(in.readAllBytes()).accept(node, 0);
        }
        MethodNode loop = node.methods.stream().filter(m -> m.name.equals("loopCarried")).findFirst().orElseThrow();
        List<AbstractInsnNode> all = new ArrayList<>();
        for (AbstractInsnNode instruction : loop.instructions) all.add(instruction);
        String sink = "L" + node.name + ";sink(I)V";
        long start = System.nanoTime();
        assertDoesNotThrow(() -> CallOccurrenceAlignment.effects(node.name, loop).apply(all));
        // The thinned-ordinal path: describing the sink call's operand describes the phi, its guards, the loop branch,
        // and the loop condition's own phi — which before the cycle guard recursed until the stack overflowed.
        assertDoesNotThrow(() -> CallOccurrenceAlignment.retainedOrdinal(node, loop, node, loop, sink, 0));
        assertTrue(System.nanoTime() - start < 5_000_000_000L, "describing one method must not take seconds");
    }

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
