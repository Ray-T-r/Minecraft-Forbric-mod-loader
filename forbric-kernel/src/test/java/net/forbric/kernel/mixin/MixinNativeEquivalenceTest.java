package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

@ResourceLock("ModCatalog")
class MixinNativeEquivalenceTest {
    private static final String SUBJECT = Type.getInternalName(Subject.class);
    private static final String SUBJECT_TYPE = "L" + SUBJECT + ";";
    private static final String OWNER = "fixture/renamed/NativeRoute";
    private static final String GUEST = "arbitrary/consumer/UnrelatedMixin";
    private static final String HANDLER = "renamedConsumer";
    private static final String HANDLER_DESC = "(Ljava/lang/Object;" + SUBJECT_TYPE + "I)I";
    private static final String HOST_DESC = "(" + SUBJECT_TYPE + "I)I";

    @BeforeEach @AfterEach void reset() { MixinNativeEquivalence.reset(); }

    @Test void renamedPureDelegationIsProvedFromExactCapturedReceiverAndArgument() {
        ClassNode mixin = mixin("renamedRoute" + HOST_DESC, 0, 1);
        ClassNode target = target("renamedRoute", HOST_DESC, 0, 1);
        assertNotNull(proof(mixin, target));
        // None of the carrier, consumer, method or delegated operation identities is configured by the proof.
        mixin.name = "other/tenant/AnotherConsumer";
        target.name = "other/carrier/OtherRoute";
        mixin.visibleAnnotations.getFirst().values = new ArrayList<>(List.of("value", List.of(Type.getObjectType(target.name))));
        assertNotNull(proof(mixin, target));
    }

    @Test void wrongReceiverCannotBorrowASameNamedOperation() {
        ClassNode mixin = mixin("route", 0, 2);
        assertNull(proof(mixin, target("route", "(" + SUBJECT_TYPE + SUBJECT_TYPE + "I)I", 1, 2)));
    }

    @Test void wrongOperandCannotBorrowASameNamedOperation() {
        ClassNode mixin = mixin("route", 0, 1);
        assertNull(proof(mixin, target("route", "(" + SUBJECT_TYPE + "II)I", 0, 2)));
    }

    @Test void aHandlerSideEffectMakesTheDelegationUnproved() {
        ClassNode mixin = mixin("route", 0, 1);
        handler(mixin).instructions.insert(new FieldInsnNode(Opcodes.GETSTATIC, "other/Effects", "state", "I"));
        handler(mixin).instructions.insert(new FieldInsnNode(Opcodes.PUTSTATIC, "other/Effects", "state", "I"));
        assertNull(proof(mixin, target("route", HOST_DESC, 0, 1)));
    }

    @Test void surroundingCarrierEffectsArePreservedButADiscardedResultIsNotAProof() {
        ClassNode target = target("route", HOST_DESC, 0, 1);
        MethodNode host = target.methods.getFirst();
        host.instructions.insert(new FieldInsnNode(Opcodes.PUTSTATIC, "other/Effects", "state", "I"));
        host.instructions.insert(new InsnNode(Opcodes.ICONST_1));
        assertNotNull(proof(mixin("route", 0, 1), target));
        target = target("route", HOST_DESC, 0, 1);
        host = target.methods.getFirst();
        host.instructions.insertBefore(host.instructions.getLast(), new InsnNode(Opcodes.POP));
        host.instructions.insertBefore(host.instructions.getLast(), new InsnNode(Opcodes.ICONST_1));
        assertNull(proof(mixin("route", 0, 1), target));
        target = target("route", HOST_DESC, 0, 1);
        host = target.methods.getFirst();
        host.instructions.insertBefore(host.instructions.getLast(), new InsnNode(Opcodes.ICONST_1));
        host.instructions.insertBefore(host.instructions.getLast(), new InsnNode(Opcodes.IADD));
        host.instructions.insertBefore(host.instructions.getLast(), new InsnNode(Opcodes.POP));
        host.instructions.insertBefore(host.instructions.getLast(), new InsnNode(Opcodes.ICONST_1));
        assertNull(proof(mixin("route", 0, 1), target));
    }

    @Test void twoSelectorMatchesOrTwoOperationsAreNotAnUnambiguousProof() {
        ClassNode target = target("route", HOST_DESC, 0, 1);
        target.methods.add(target("route", "(" + SUBJECT_TYPE + "II)I", 0, 1).methods.getFirst());
        assertNull(proof(mixin("route", 0, 1), target));
        target = target("route", HOST_DESC, 0, 1);
        MethodNode host = target.methods.getFirst();
        host.instructions.insert(new InsnNode(Opcodes.POP));
        host.instructions.insert(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SUBJECT, "derive", "(I)I", false));
        host.instructions.insert(new VarInsnNode(Opcodes.ILOAD, 1));
        host.instructions.insert(new VarInsnNode(Opcodes.ALOAD, 0));
        assertNull(proof(mixin("route", 0, 1), target));
    }

    @Test void aLocalAliasPreservesIdentityButASecondSameTypedLocalIsNotGuessed() {
        ClassNode target = target("route", HOST_DESC, 0, 1);
        MethodNode host = target.methods.getFirst();
        host.instructions.insert(new VarInsnNode(Opcodes.ASTORE, 2));
        host.instructions.insert(new VarInsnNode(Opcodes.ALOAD, 0));
        ((VarInsnNode) host.instructions.get(2)).var = 2;
        assertNotNull(proof(mixin("route", 0, 1), target));

        ClassNode mixin = mixin("route", 0, 2);
        handler(mixin).invisibleParameterAnnotations[1].getFirst().values = List.of("argsOnly", true);
        assertNull(proof(mixin, target("route", "(" + SUBJECT_TYPE + SUBJECT_TYPE + "I)I", 0, 2)));
    }

    @Test void aNamedCaptureNeedsItsLiveScopeAndType() {
        ClassNode mixin = mixin("route", 0, 1);
        handler(mixin).invisibleParameterAnnotations[1].getFirst().values = List.of("name", List.of("input"));
        ClassNode target = target("route", HOST_DESC, 0, 1);
        assertNull(proof(mixin, target));
        MethodNode host = target.methods.getFirst();
        LabelNode start = new LabelNode(), end = new LabelNode();
        host.instructions.insert(start); host.instructions.add(end);
        host.localVariables = List.of(new LocalVariableNode("input", SUBJECT_TYPE, null, start, end, 0));
        assertNotNull(proof(mixin, target));
        host.localVariables = List.of(new LocalVariableNode("input", "Ljava/lang/Object;", null, start, end, 0));
        assertNull(proof(mixin, target));
    }

    @Test void aReceiverLeftOnTheStackBeforeTheCapturedLocalChangesIsNotEquivalent() {
        ClassNode target = target("route", "(" + SUBJECT_TYPE + SUBJECT_TYPE + "I)I", 0, 2);
        MethodNode host = target.methods.getFirst();
        // Receiver is already on the operand stack, but the @Local capture would now read the other subject.
        AbstractInsnNode argument = host.instructions.get(1);
        host.instructions.insertBefore(argument, new VarInsnNode(Opcodes.ALOAD, 1));
        host.instructions.insertBefore(argument, new VarInsnNode(Opcodes.ASTORE, 0));
        assertNull(proof(mixin("route", 0, 2), target));
    }

    @Test void aMergedBranchOfDifferentReceiverValuesIsNotGuessed() {
        ClassNode target = target("route", "(" + SUBJECT_TYPE + SUBJECT_TYPE + "I)I", 0, 2);
        MethodNode host = target.methods.getFirst();
        host.instructions.clear();
        LabelNode other = new LabelNode(), join = new LabelNode();
        host.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        host.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, other));
        host.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        host.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, join));
        host.instructions.add(other);
        host.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        host.instructions.add(join);
        host.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        host.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SUBJECT, "derive", "(I)I", false));
        host.instructions.add(new InsnNode(Opcodes.IRETURN));
        assertNull(proof(mixin("route", 0, 2), target));
    }

    @Test void actualUpstreamBrewingRedirectHasTheSameCapturedReceiverInTheCarrier() throws Exception {
        ClassNode mixin = upstreamMixin("fabric-item-api-v1",
                "net/fabricmc/fabric/mixin/item/BrewingStandBlockEntityMixin");
        // Proof needs the actual debug capture scope, which the convenience fixture parser discards.
        ClassNode target = stagedWithDebug("net/minecraft/world/level/block/entity/BrewingStandBlockEntity");
        MethodNode source = StagedFabricMixinFixture.method(mixin, "getCraftingRemainder");
        MixinNativeEquivalence.remember(mixin);
        assertNotNull(MixinNativeEquivalence.proof(mixin.name, source.name, source.desc,
                MixinInstructionFingerprint.hash(source), target));

        MethodNode host = target.methods.stream().filter(m -> m.name.equals("doBrew")).findFirst().orElseThrow();
        MethodInsnNode call = java.util.Arrays.stream(host.instructions.toArray())
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .filter(c -> c.owner.equals("net/minecraft/world/item/ItemStack") && c.name.equals("getCraftingRemainder"))
                .findFirst().orElseThrow();
        VarInsnNode receiver = (VarInsnNode) call.getPrevious();
        int local = receiver.var;
        host.instructions.insertBefore(call, new FieldInsnNode(Opcodes.GETSTATIC, "fixture/OtherInput", "replacement",
                "Lnet/minecraft/world/item/ItemStack;"));
        host.instructions.insertBefore(call, new VarInsnNode(Opcodes.ASTORE, local));
        host.maxStack += 1;
        assertNull(MixinNativeEquivalence.proof(mixin.name, source.name, source.desc,
                MixinInstructionFingerprint.hash(source), target));
    }

    @Test void actualUpstreamFurnaceCopiedSharedReceiverCannotBorrowTheLiveStackCall() throws Exception {
        ClassNode mixin = upstreamMixin("fabric-item-api-v1",
                "net/fabricmc/fabric/mixin/item/AbstractFurnaceBlockEntityMixin");
        ClassNode target = stagedWithDebug("net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity");
        MethodNode source = StagedFabricMixinFixture.method(mixin, "getCraftingRemainder");
        MixinNativeEquivalence.remember(mixin);
        assertNull(MixinNativeEquivalence.proof(mixin.name, source.name, source.desc,
                MixinInstructionFingerprint.hash(source), target));
    }

    @Test void actualSourceRedirectAndCarrierCallDispatchTheSameStackSensitiveOverrideOnce() throws Exception {
        ClassNode mixin = upstreamMixin("fabric-item-api-v1", "net/fabricmc/fabric/mixin/item/BrewingStandBlockEntityMixin");
        MethodNode source = StagedFabricMixinFixture.method(mixin, "getCraftingRemainder");
        ClassNode carrier = stagedWithDebug("net/minecraft/world/level/block/entity/BrewingStandBlockEntity");
        MethodInsnNode actualCall = carrier.methods.stream().filter(m -> m.name.equals("doBrew"))
                .flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .filter(c -> c.owner.equals("net/minecraft/world/item/ItemStack") && c.name.equals("getCraftingRemainder"))
                .findFirst().orElseThrow();
        // Execute the unmodified upstream handler instructions and the actual carrier invocation. Only linked
        // game value types are renamed to small fixtures; no Minecraft bootstrap or fake remainder result is used.
        java.util.Map<String, String> types = java.util.Map.of(
                "net/minecraft/world/item/Item", "java/lang/Object",
                "net/minecraft/world/item/ItemStack", Type.getInternalName(RemainderStack.class),
                "net/minecraft/world/item/ItemStackTemplate", Type.getInternalName(Remainder.class));
        org.objectweb.asm.commons.Remapper remapper = new org.objectweb.asm.commons.SimpleRemapper(types);
        ClassNode executable = clazz("fixture/renamed/OriginalRemainderContract");
        MethodNode original = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "source",
                remapper.mapMethodDesc(source.desc), null, null);
        source.accept(new org.objectweb.asm.commons.MethodRemapper(original, remapper));
        executable.methods.add(original);
        MethodNode nativeCall = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "carrier",
                "(" + Type.getDescriptor(RemainderStack.class) + ")" + Type.getDescriptor(Remainder.class), null, null);
        nativeCall.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        nativeCall.instructions.add(new MethodInsnNode(actualCall.getOpcode(), remapper.map(actualCall.owner),
                actualCall.name, remapper.mapMethodDesc(actualCall.desc), actualCall.itf));
        nativeCall.instructions.add(new InsnNode(Opcodes.ARETURN));
        executable.methods.add(nativeCall);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        executable.accept(writer);
        Class<?> runtime = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { byte[] bytes = writer.toByteArray(); return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        RemainderStack sourceInput = new ComponentRemainderStack(19), nativeInput = new ComponentRemainderStack(19);
        Object expected = runtime.getMethod("source", Object.class, RemainderStack.class).invoke(null, null, sourceInput);
        Object result = runtime.getMethod("carrier", RemainderStack.class).invoke(null, nativeInput);
        assertEquals(new Remainder(209), expected);
        assertEquals(expected, result);
        assertEquals(1, sourceInput.calls); assertEquals(1, nativeInput.calls);
        assertEquals(sourceInput, sourceInput.observed); assertEquals(nativeInput, nativeInput.observed);
    }

    @Test void changedSourceContractAndConstrainedOccurrenceCannotBorrowTheProof() {
        ClassNode mixin = mixin("route", 0, 1);
        ClassNode target = target("route", HOST_DESC, 0, 1);
        MixinNativeEquivalence.remember(mixin);
        assertNull(MixinNativeEquivalence.proof(mixin.name, HANDLER, HANDLER_DESC, "different source", target));
        AnnotationNode at = (AnnotationNode) handler(mixin).visibleAnnotations.getFirst().values.get(3);
        at.values.add("ordinal"); at.values.add(1);
        assertNull(proof(mixin, target));
    }

    @Test void opaqueSharedCaptureIsNotAssumedToBeTheLiveOriginalValue() {
        ClassNode mixin = mixin("route", 0, 1);
        handler(mixin).invisibleParameterAnnotations[1].getFirst().desc = "Lcom/llamalad7/mixinextras/sugar/Share;";
        assertNull(proof(mixin, target("route", HOST_DESC, 0, 1)));
    }

    @Test void virtualDelegationPreservesCustomReceiverBehaviorAndCallsItOnce() throws Exception {
        ClassNode mixin = mixin("route", 0, 1), target = target("route", HOST_DESC, 0, 1);
        assertNotNull(proof(mixin, target));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        target.accept(writer);
        Class<?> actual = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { byte[] bytes = writer.toByteArray(); return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        Subject input = new Subject(41);
        assertEquals(48, actual.getMethod("route", Subject.class, int.class).invoke(null, input, 7));
        assertEquals(1, input.calls);
        Subject sourceControl = new Subject(41);
        assertEquals(input.lastOperand, sourceControl.derive(7) - sourceControl.base);
        assertEquals(1, sourceControl.calls);
    }

    public static class Subject {
        final int base;
        int calls, lastOperand;
        public Subject(int base) { this.base = base; }
        public int derive(int operand) { calls++; lastOperand = operand; return base + operand; }
    }

    public record Remainder(int componentValue) { }

    public static class RemainderStack {
        final int component;
        int calls;
        RemainderStack observed;
        RemainderStack(int component) { this.component = component; }
        public Remainder getCraftingRemainder() { throw new AssertionError("custom override was bypassed"); }
    }

    public static class ComponentRemainderStack extends RemainderStack {
        ComponentRemainderStack(int component) { super(component); }
        @Override public Remainder getCraftingRemainder() {
            calls++; observed = this; return new Remainder(component * 11);
        }
    }

    private String proof(ClassNode mixin, ClassNode target) {
        MixinNativeEquivalence.remember(mixin);
        MethodNode handler = handler(mixin);
        return MixinNativeEquivalence.proof(mixin.name, handler.name, handler.desc,
                MixinInstructionFingerprint.hash(handler), target);
    }

    private static MethodNode handler(ClassNode mixin) { return mixin.methods.getFirst(); }

    @SuppressWarnings("unchecked")
    private static ClassNode mixin(String selector, int receiverIndex, int argumentIndex) {
        ClassNode node = clazz(GUEST);
        AnnotationNode mixin = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
        mixin.values = new ArrayList<>(List.of("value", List.of(Type.getObjectType(OWNER))));
        node.visibleAnnotations = List.of(mixin);
        MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, HANDLER, HANDLER_DESC, null, null);
        handler.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        handler.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        handler.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SUBJECT, "derive", "(I)I", false));
        handler.instructions.add(new InsnNode(Opcodes.IRETURN));
        AnnotationNode injection = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Redirect;");
        AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        at.values = new ArrayList<>(List.of("value", "INVOKE", "target", "Lunrelated/OldOperation;supply()I"));
        injection.values = new ArrayList<>(List.of("method", List.of(selector), "at", at));
        handler.visibleAnnotations = List.of(injection);
        handler.invisibleParameterAnnotations = new List[3];
        handler.invisibleParameterAnnotations[1] = List.of(local(receiverIndex));
        handler.invisibleParameterAnnotations[2] = List.of(local(argumentIndex));
        handler.maxLocals = 3; handler.maxStack = 2;
        node.methods.add(handler);
        return node;
    }

    private static AnnotationNode local(int index) {
        AnnotationNode local = new AnnotationNode("Lcom/llamalad7/mixinextras/sugar/Local;");
        local.values = new ArrayList<>(List.of("index", index)); return local;
    }

    private static ClassNode target(String name, String descriptor, int receiverIndex, int argumentIndex) {
        ClassNode node = clazz(OWNER);
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, receiverIndex));
        method.instructions.add(new VarInsnNode(Opcodes.ILOAD, argumentIndex));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SUBJECT, "derive", "(I)I", false));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxLocals = 4; method.maxStack = 2;
        node.methods.add(method); return node;
    }

    private static ClassNode clazz(String name) {
        ClassNode node = new ClassNode(); node.version = Opcodes.V21;
        node.access = Opcodes.ACC_PUBLIC; node.name = name; node.superName = "java/lang/Object";
        return node;
    }

    private static ClassNode stagedWithDebug(String name) throws Exception {
        java.nio.file.Path base = net.forbric.kernel.TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
        net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.STAGED,
                java.nio.file.Files.isRegularFile(base), "actual staged carrier required");
        try (java.util.zip.ZipFile jar = new java.util.zip.ZipFile(base.toFile())) {
            ClassNode node = new ClassNode();
            new org.objectweb.asm.ClassReader(jar.getInputStream(jar.getEntry(name + ".class")).readAllBytes())
                    .accept(node, org.objectweb.asm.ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static ClassNode upstreamMixin(String module, String name) throws Exception {
        String configured = System.getProperty("forbric.fabricApi");
        java.nio.file.Path api = configured == null ? net.forbric.kernel.TestFixtures.fabricApi()
                : java.nio.file.Path.of(configured);
        net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.STAGED,
                java.nio.file.Files.isRegularFile(api), "actual Fabric API source contract required");
        try (java.util.zip.ZipFile jar = new java.util.zip.ZipFile(api.toFile())) {
            java.util.zip.ZipEntry moduleJar = jar.stream()
                    .filter(entry -> entry.getName().startsWith("META-INF/jars/" + module + "-")).findFirst().orElseThrow();
            try (java.util.zip.ZipInputStream inner = new java.util.zip.ZipInputStream(jar.getInputStream(moduleJar))) {
                for (java.util.zip.ZipEntry entry; (entry = inner.getNextEntry()) != null;)
                    if (entry.getName().equals(name + ".class")) return MixinFit.parse(inner.readAllBytes());
            }
        }
        throw new AssertionError("upstream source contract absent: " + name);
    }
}
