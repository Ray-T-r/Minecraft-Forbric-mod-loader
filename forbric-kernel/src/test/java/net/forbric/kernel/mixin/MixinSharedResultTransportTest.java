package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.api.Ecosystem;

class MixinSharedResultTransportTest {
    @Test void actualCraftingClosedGroupPreservesBothSourceBodiesAndProjectionInvocation() throws Exception {
        Inputs input = inputs("net/fabricmc/fabric/mixin/item/CraftingRecipeMixin", "net/minecraft/world/item/crafting/CraftingRecipe");
        String producer = MixinInstructionFingerprint.hash(method(input.mixin, "captureStack"));
        String consumer = MixinInstructionFingerprint.hash(method(input.mixin, "getStackRemainder"));
        assertEquals(2, MixinSharedResultTransport.adapt(input.mixin, name -> input.current, name -> input.reference));
        assertEquals(producer, MixinInstructionFingerprint.hash(input.mixin.methods.stream().filter(m -> m.name.contains("$forbricsharedproducer")).findFirst().orElseThrow()));
        assertEquals(consumer, MixinInstructionFingerprint.hash(input.mixin.methods.stream().filter(m -> m.name.contains("$forbricsharedresult")).findFirst().orElseThrow()));
        Runtime runtime = runtime(input.mixin, "captureStack", "getStackRemainder");
        Snapshot stack = new Snapshot(3, "craft-component"); AtomicReference<Object> shared = new AtomicReference<>();
        Object reference = runtime.reference(shared);
        Remainder result = (Remainder) runtime.effect.invoke(null, stack, reference);
        assertSame(stack, shared.get()); assertSame(stack, result.receiver()); assertEquals(3, result.count());
        assertEquals("craft-component", result.component()); assertEquals(1, stack.projections); assertEquals(1, stack.reads);
    }

    /**
     * The same closed group compiled another way — the producer keeps its receiver and the original's answer in locals and
     * builds its argument array through a local, the consumer keeps the shared value and its answer in locals — is the same
     * group: read by data flow, it is transported, and runs as the released one does.
     */
    @Test void theCraftingGroupWrittenAnotherWayIsTransportedTheSame() throws Exception {
        Inputs input = inputs("net/fabricmc/fabric/mixin/item/CraftingRecipeMixin", "net/minecraft/world/item/crafting/CraftingRecipe");
        MethodNode producer = method(input.mixin, "captureStack"), consumer = method(input.mixin, "getStackRemainder");
        String operation = "com/llamalad7/mixinextras/injector/wrapoperation/Operation", ref = "com/llamalad7/mixinextras/sugar/ref/LocalRef";
        Type item = Type.getReturnType(producer.desc);
        MethodInsnNode remainder = null;
        for (var instruction : consumer.instructions) if (instruction instanceof MethodInsnNode call && !call.owner.equals(ref)) remainder = call;
        MethodInsnNode remainderCall = remainder;
        Type stack = Type.getObjectType(remainderCall.owner);
        rewrite(producer, code -> {
            code.add(new VarInsnNode(Opcodes.ALOAD, 0)); code.add(new VarInsnNode(Opcodes.ASTORE, 3));
            code.add(new VarInsnNode(Opcodes.ALOAD, 2)); code.add(new VarInsnNode(Opcodes.ALOAD, 3));
            code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, ref, "set", "(Ljava/lang/Object;)V", true));
            code.add(new InsnNode(Opcodes.ICONST_1)); code.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
            code.add(new VarInsnNode(Opcodes.ASTORE, 4));
            code.add(new VarInsnNode(Opcodes.ALOAD, 4)); code.add(new InsnNode(Opcodes.ICONST_0)); code.add(new VarInsnNode(Opcodes.ALOAD, 3));
            code.add(new InsnNode(Opcodes.AASTORE));
            code.add(new VarInsnNode(Opcodes.ALOAD, 1)); code.add(new VarInsnNode(Opcodes.ALOAD, 4));
            code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, operation, "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true));
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, item.getInternalName())); code.add(new VarInsnNode(Opcodes.ASTORE, 5));
            code.add(new VarInsnNode(Opcodes.ALOAD, 5)); code.add(new InsnNode(Opcodes.ARETURN));
        });
        rewrite(consumer, code -> {
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, ref, "get", "()Ljava/lang/Object;", true));
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, stack.getInternalName())); code.add(new VarInsnNode(Opcodes.ASTORE, 2));
            code.add(new VarInsnNode(Opcodes.ALOAD, 2));
            code.add(new MethodInsnNode(remainderCall.getOpcode(), remainderCall.owner, remainderCall.name, remainderCall.desc, remainderCall.itf));
            code.add(new VarInsnNode(Opcodes.ASTORE, 3)); code.add(new VarInsnNode(Opcodes.ALOAD, 3)); code.add(new InsnNode(Opcodes.ARETURN));
        });
        assertEquals(2, MixinSharedResultTransport.adapt(input.mixin, name -> input.current, name -> input.reference));
        Runtime runtime = runtime(input.mixin, "captureStack", "getStackRemainder");
        Snapshot shared = new Snapshot(5, "rewritten"); AtomicReference<Object> slot = new AtomicReference<>();
        Remainder result = (Remainder) runtime.effect.invoke(null, shared, runtime.reference(slot));
        assertSame(shared, slot.get()); assertSame(shared, result.receiver()); assertEquals(5, result.count());
        assertEquals(1, shared.projections); assertEquals(1, shared.reads);
    }

    /** A producer that shares something other than its receiver is not a pass-through: nothing is transported. */
    @Test void aProducerSharingAnotherValueIsNotAPassThrough() throws Exception {
        Inputs input = inputs("net/fabricmc/fabric/mixin/item/CraftingRecipeMixin", "net/minecraft/world/item/crafting/CraftingRecipe");
        MethodNode producer = method(input.mixin, "captureStack");
        for (var instruction : producer.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("set")) {
                AbstractInsnNode value = call.getPrevious();
                producer.instructions.set(value, new InsnNode(Opcodes.ACONST_NULL));
                break;
            }
        }
        assertEquals(0, MixinSharedResultTransport.adapt(input.mixin, name -> input.current, name -> input.reference));
    }

    private static void rewrite(MethodNode method, java.util.function.Consumer<InsnList> body) {
        method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables = null;
        body.accept(method.instructions); method.maxLocals = 8; method.maxStack = 6;
    }

    @Test void transformedProducerReceiverOrAdditionalGroupSideEffectIsNotAccepted() throws Exception {
        Inputs input = inputs("net/fabricmc/fabric/mixin/item/CraftingRecipeMixin", "net/minecraft/world/item/crafting/CraftingRecipe");
        MethodNode producer = method(input.mixin, "captureStack");
        producer.instructions.insert(new InsnNode(Opcodes.POP));
        producer.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, "fixture/Effects", "touch", "()I", false));
        assertEquals(0, MixinSharedResultTransport.adapt(input.mixin, name -> input.current, name -> input.reference));
    }

    @Test void closedGroupRequiresTheSameStackSourceAndNoPathBypassingItsProducer() throws Exception {
        Inputs input = inputs("net/fabricmc/fabric/mixin/item/CraftingRecipeMixin", "net/minecraft/world/item/crafting/CraftingRecipe");
        MethodNode current = method(input.current, "defaultCraftingReminder");
        MethodInsnNode live = resultCall(current);
        InsnList wrongReceiver = new InsnList(); wrongReceiver.add(new InsnNode(Opcodes.POP));
        wrongReceiver.add(new FieldInsnNode(Opcodes.GETSTATIC, live.owner, "EMPTY", "L" + live.owner + ";"));
        current.instructions.insertBefore(live, wrongReceiver);
        Inputs changed = input;
        assertEquals(0, MixinSharedResultTransport.adapt(changed.mixin, name -> changed.current, name -> changed.reference));

        input = inputs("net/fabricmc/fabric/mixin/item/CraftingRecipeMixin", "net/minecraft/world/item/crafting/CraftingRecipe");
        MethodNode original = method(input.reference, "defaultCraftingReminder");
        MethodInsnNode projection = Arrays.stream(original.instructions.toArray()).filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast).filter(call -> call.owner.equals("net/minecraft/world/item/ItemStack")
                        && call.name.equals("getItem")).findFirst().orElseThrow();
        LabelNode execute = new LabelNode(), bypassed = new LabelNode();
        InsnList alternate = new InsnList(); alternate.add(new InsnNode(Opcodes.ICONST_0));
        alternate.add(new JumpInsnNode(Opcodes.IFEQ, execute)); alternate.add(new InsnNode(Opcodes.POP));
        alternate.add(new InsnNode(Opcodes.ACONST_NULL)); alternate.add(new JumpInsnNode(Opcodes.GOTO, bypassed));
        alternate.add(execute); original.instructions.insertBefore(projection, alternate);
        original.instructions.insert(projection, bypassed); original.maxStack++;
        Inputs guarded = input;
        assertEquals(0, MixinSharedResultTransport.adapt(guarded.mixin, name -> guarded.current, name -> guarded.reference));
    }

    @Test void actualSourceSharedSnapshotIsExecutedOnceAtTheConservedCarrierResultRegion() throws Exception {
        Inputs input = inputs(); MethodNode original = method(input.mixin, "getCraftingRemainder");
        String fingerprint = MixinInstructionFingerprint.hash(original);
        assertEquals(1, MixinSharedResultTransport.adapt(input.mixin, name -> input.current, name -> input.reference));
        MethodNode preserved = input.mixin.methods.stream().filter(m -> m.name.contains("$forbricsharedresult")).findFirst().orElseThrow();
        assertEquals(fingerprint, MixinInstructionFingerprint.hash(preserved));
        Runtime runtime = runtime(input.mixin);
        Snapshot live = new Snapshot(1, "original-component");
        AtomicReference<Object> shared = new AtomicReference<>(); Object reference = runtime.reference(shared);
        runtime.copy.invoke(null, null, live, null, reference);
        Snapshot copy = (Snapshot) shared.get(); assertNotSame(live, copy);
        live.count = 0; live.component = "changed-component";
        Remainder result = (Remainder) runtime.effect.invoke(null, live, reference);
        assertSame(copy, result.receiver()); assertEquals(1, result.count()); assertEquals("original-component", result.component());
        assertEquals(1, copy.reads); assertEquals(0, live.reads);
        Remainder nativeControl = live.getCraftingRemainder();
        assertSame(live, nativeControl.receiver()); assertEquals(0, nativeControl.count()); assertEquals("changed-component", nativeControl.component());
        assertEquals(1, live.reads);
    }

    @Test void usedOldInputOrAdditionalSourceEffectCannotBeTransported() throws Exception {
        Inputs input = inputs(); MethodNode source = method(input.mixin, "getCraftingRemainder");
        source.instructions.insert(new InsnNode(Opcodes.POP)); source.instructions.insert(new VarInsnNode(Opcodes.ALOAD, 0));
        Inputs used = input;
        assertEquals(0, MixinSharedResultTransport.adapt(used.mixin, name -> used.current, name -> used.reference));
        input = inputs(); source = method(input.mixin, "getCraftingRemainder");
        source.instructions.insert(new InsnNode(Opcodes.POP));
        source.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, "fixture/Effects", "touch", "()I", false));
        Inputs extra = input;
        assertEquals(0, MixinSharedResultTransport.adapt(extra.mixin, name -> extra.current, name -> extra.reference));
    }

    @Test void ambiguousResultCallAndChangedConsumerBranchAreRejected() throws Exception {
        Inputs input = inputs(); MethodNode host = method(input.current, "consumeFuel");
        MethodInsnNode candidate = resultCall(host);
        host.instructions.insert(new InsnNode(Opcodes.POP));
        host.instructions.insert(new MethodInsnNode(candidate.getOpcode(), candidate.owner, candidate.name, candidate.desc, candidate.itf));
        host.instructions.insert(new VarInsnNode(Opcodes.ALOAD, (host.access & Opcodes.ACC_STATIC) == 0 ? 2 : 1));
        Inputs duplicate = input;
        assertEquals(0, MixinSharedResultTransport.adapt(duplicate.mixin, name -> duplicate.current, name -> duplicate.reference));
        input = inputs(); host = method(input.current, "consumeFuel");
        MethodInsnNode call = resultCall(host); LabelNode skip = new LabelNode();
        host.instructions.insert(call, skip);
        host.instructions.insert(call, new JumpInsnNode(Opcodes.IFEQ, skip));
        host.instructions.insert(call, new InsnNode(Opcodes.ICONST_1)); host.maxStack += 1;
        Inputs changed = input;
        assertEquals(0, MixinSharedResultTransport.adapt(changed.mixin, name -> changed.current, name -> changed.reference));
    }

    public static final class Snapshot {
        int count, reads, projections; String component;
        Snapshot(int count, String component) { this.count = count; this.component = component; }
        public Snapshot copy() { return new Snapshot(count, component); }
        public Object getItem() { projections++; return this; }
        public Remainder getCraftingRemainder() { reads++; return new Remainder(this, count, component); }
    }
    public record Remainder(Snapshot receiver, int count, String component) { }

    private record Inputs(ClassNode mixin, ClassNode current, ClassNode reference) { }
    private static Inputs inputs() throws Exception {
        return inputs("net/fabricmc/fabric/mixin/item/AbstractFurnaceBlockEntityMixin", "net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity");
    }
    private static Inputs inputs(String sourceMixin, String owner) throws Exception {
        String api = System.getProperty("forbric.fabricApi");
        java.nio.file.Path apiPath = api == null ? net.forbric.kernel.TestFixtures.fabricApi() : java.nio.file.Path.of(api);
        java.nio.file.Path base = java.nio.file.Path.of(System.getProperty("forbric.predicateBase",
                net.forbric.kernel.TestFixtures.stagedBase().toString()));
        net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.STAGED,
                java.nio.file.Files.isRegularFile(apiPath) && java.nio.file.Files.isRegularFile(base), "actual API and indexed base required");
        ClassNode mixin = null;
        try (java.util.zip.ZipFile jar = new java.util.zip.ZipFile(apiPath.toFile())) {
            var module = jar.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-item-api-v1-")).findFirst().orElseThrow();
            try (java.util.zip.ZipInputStream nested = new java.util.zip.ZipInputStream(jar.getInputStream(module))) {
                for (java.util.zip.ZipEntry entry; (entry = nested.getNextEntry()) != null;)
                    if (entry.getName().equals(sourceMixin + ".class")) { mixin = parse(nested.readAllBytes()); break; }
            }
        }
        ClassNode current, reference;
        try (java.util.zip.ZipFile jar = new java.util.zip.ZipFile(base.toFile())) {
            current = parse(jar.getInputStream(jar.getEntry(owner + ".class")).readAllBytes());
            NativeGameReferences references = new NativeGameReferences(path -> {
                try { var entry = jar.getEntry(path); return entry == null ? null : jar.getInputStream(entry).readAllBytes(); }
                catch (java.io.IOException unavailable) { return null; }
            }, null, net.forbric.kernel.TestFixtures.launchSources(base));
            reference = references.get(Ecosystem.FABRIC, owner);
        }
        net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.STAGED, reference != null, "hash-pinned original platform required");
        return new Inputs(mixin, current, reference);
    }

    private record Runtime(java.lang.reflect.Method copy, java.lang.reflect.Method effect, Class<?> refType, ClassLoader loader) {
        Object reference(AtomicReference<Object> shared) {
            return java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {refType}, (proxy, method, args) -> {
                if (method.getName().equals("get")) return shared.get();
                if (method.getName().equals("set")) { shared.set(args[0]); return null; }
                throw new AssertionError(method);
            });
        }
    }
    private static Runtime runtime(ClassNode mixin) throws Exception {
        return runtime(mixin, "copyStack", "getCraftingRemainder");
    }
    private static Runtime runtime(ClassNode mixin, String producer, String consumer) throws Exception {
        String owner = "fixture/snapshot/OriginalShareEffect";
        Map<String, String> names = Map.of(mixin.name, owner, "net/minecraft/world/item/Item", "java/lang/Object",
                "net/minecraft/world/item/ItemStack", Type.getInternalName(Snapshot.class),
                "net/minecraft/world/item/ItemStackTemplate", Type.getInternalName(Remainder.class),
                "net/minecraft/core/NonNullList", "java/lang/Object");
        var remapper = new org.objectweb.asm.commons.SimpleRemapper(names);
        ClassNode executable = new ClassNode(); mixin.accept(new org.objectweb.asm.commons.ClassRemapper(executable, remapper));
        executable.visibleAnnotations = null; executable.invisibleAnnotations = null;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); executable.accept(writer);
        String extras = System.getProperty("forbric.mixinExtrasForTests", "");
        ClassLoader parent = extras.isBlank() ? MixinSharedResultTransportTest.class.getClassLoader()
                : new java.net.URLClassLoader(new java.net.URL[] {java.nio.file.Path.of(extras).toUri().toURL()}, MixinSharedResultTransportTest.class.getClassLoader());
        Class<?> type = new ClassLoader(parent) {
            Class<?> define() { byte[] bytes = writer.toByteArray(); return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        var copy = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().startsWith(producer)).findFirst().orElseThrow();
        var effect = Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(consumer)).findFirst().orElseThrow();
        copy.setAccessible(true); effect.setAccessible(true);
        return new Runtime(copy, effect, Class.forName("com.llamalad7.mixinextras/sugar/ref/LocalRef".replace('/', '.'), false, type.getClassLoader()), type.getClassLoader());
    }
    private static ClassNode parse(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES); return node; }
    private static MethodNode method(ClassNode node, String name) { return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(); }
    private static MethodInsnNode resultCall(MethodNode method) {
        return Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .filter(c -> c.owner.equals("net/minecraft/world/item/ItemStack") && c.name.equals("getCraftingRemainder")).findFirst().orElseThrow();
    }
}
