package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.forbric.api.Ecosystem;

class MixinDefaultCallbackTransportTest {
    private record Inputs(ClassNode source, Map<String, ClassNode> types, ClassNode original) { }
    @Test void actualSourceTagsExtendTheExactNativeDefaultAndKeepTheOriginalBody() throws Exception {
        Inputs input = inputs(); MethodNode source = handler(input.source);
        String original = MixinInstructionFingerprint.hash(source);
        assertEquals(1, MixinDefaultCallbackTransport.adapt(input.source, input.types::get, name -> input.original));
        assertEquals(List.of("net/neoforged/neoforge/common/extensions/IBlockExtension"), MixinFit.mixinTargets(input.source));
        assertEquals(original, MixinInstructionFingerprint.hash(source));
        assertTrue((input.source.access & Opcodes.ACC_INTERFACE) != 0);
        Executor run = executable(input.source);
        State above = new State(new Block(), false, "east"); State tagged = new State(new Block(), true, "east");
        assertEquals(Boolean.TRUE, run.invoke(tagged, above)); assertEquals(1, tagged.tagReads); assertEquals(1, tagged.blockReads);
        State untagged = new State(new Block(), false, "east"); assertNull(run.invoke(untagged, above));
        State aligned = new State(new Ladder(), true, "east"); assertEquals(Boolean.TRUE, run.invoke(aligned, above));
        State misaligned = new State(new Ladder(), true, "west"); assertNull(run.invoke(misaligned, above));
    }
    @Test void receiverStateRoleAndFullPropertyMemberMustMatch() throws Exception {
        Inputs input = inputs(); ClassNode contract = input.types.get("net/neoforged/neoforge/common/extensions/IBlockExtension");
        MethodNode method = contract.methods.stream().filter(m -> m.name.equals("makesOpenTrapdoorAboveClimbable")).findFirst().orElseThrow();
        ((VarInsnNode) DefaultMethodOverloadBridge.real(method).getFirst()).var = 0;
        Inputs receiver = input;
        assertEquals(0, MixinDefaultCallbackTransport.adapt(receiver.source, receiver.types::get, name -> receiver.original));
        input = inputs(); contract = input.types.get("net/neoforged/neoforge/common/extensions/IBlockExtension");
        method = contract.methods.stream().filter(m -> m.name.equals("makesOpenTrapdoorAboveClimbable")).findFirst().orElseThrow();
        ((FieldInsnNode) DefaultMethodOverloadBridge.real(method).get(5)).owner = "other/PropertyOwner";
        Inputs changed = input;
        assertEquals(0, MixinDefaultCallbackTransport.adapt(changed.source, changed.types::get, name -> changed.original));
    }
    @Test void callbackEffectsThisUseOrAStillLiveSourceAreRejected() throws Exception {
        Inputs input = inputs(); MethodNode source = handler(input.source);
        source.instructions.insert(new InsnNode(Opcodes.POP)); source.instructions.insert(new VarInsnNode(Opcodes.ALOAD, 0));
        Inputs receiver = input;
        assertEquals(0, MixinDefaultCallbackTransport.adapt(receiver.source, receiver.types::get, name -> receiver.original));
        input = inputs(); source = handler(input.source);
        source.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, "other/Effects", "touch", "()V", false));
        Inputs effects = input;
        assertEquals(0, MixinDefaultCallbackTransport.adapt(effects.source, effects.types::get, name -> effects.original));
        input = inputs(); input.types.put(input.original.name, input.original); Inputs live = input;
        assertEquals(0, MixinDefaultCallbackTransport.adapt(live.source, live.types::get, name -> live.original));
    }
    @Test void bootstrapLdcAndNestedDynamicReferencesKeepTheSourceCallbackOnItsOriginalOwner() throws Exception {
        for (int kind = 0; kind < 5; kind++) {
            Inputs input = inputs(); ClassNode current = input.types.get(input.original.name);
            MethodNode old = input.original.methods.stream().filter(m -> m.name.equals("trapdoorUsableAsLadder")).findFirst().orElseThrow();
            Handle reference = new Handle(Opcodes.H_INVOKESPECIAL, current.name, old.name, old.desc, false);
            Handle bootstrap = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/ConstantBootstraps", "invoke",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;Ljava/lang/invoke/MethodHandle;[Ljava/lang/Object;)Ljava/lang/Object;", false);
            MethodNode usage = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "handleUse", "()V", null, null);
            if (kind == 0) usage.instructions.add(new LdcInsnNode(reference));
            if (kind == 1) usage.instructions.add(new InvokeDynamicInsnNode("get", "()Ljava/lang/Object;", bootstrap, reference));
            if (kind == 2) usage.instructions.add(new InvokeDynamicInsnNode("get", "()Ljava/lang/Object;", reference));
            if (kind == 3) usage.instructions.add(new LdcInsnNode(new ConstantDynamic("outer", "Ljava/lang/Object;", bootstrap,
                    new ConstantDynamic("inner", "Ljava/lang/invoke/MethodHandle;", bootstrap, reference))));
            if (kind == 4) usage.instructions.add(new LdcInsnNode(old.name));
            usage.instructions.add(new InsnNode(Opcodes.POP)); usage.instructions.add(new InsnNode(Opcodes.RETURN)); usage.maxStack = 1;
            current.methods.add(usage);
            assertEquals(0, MixinDefaultCallbackTransport.adapt(input.source, input.types::get, name -> input.original), "reference kind " + kind);
            assertEquals(List.of(current.name), MixinFit.mixinTargets(input.source));
        }
    }
    @Test void aHandleToTheSameSignatureOnAnotherOwnerDoesNotBlockTheActualRepair() throws Exception {
        Inputs input = inputs(); ClassNode current = input.types.get(input.original.name);
        MethodNode old = input.original.methods.stream().filter(m -> m.name.equals("trapdoorUsableAsLadder")).findFirst().orElseThrow();
        MethodNode usage = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "unrelated", "()V", null, null);
        usage.instructions.add(new LdcInsnNode(new Handle(Opcodes.H_INVOKESPECIAL, "unrelated/Owner", old.name, old.desc, false)));
        usage.instructions.add(new InsnNode(Opcodes.POP)); usage.instructions.add(new InsnNode(Opcodes.RETURN)); usage.maxStack = 1;
        current.methods.add(usage);
        assertEquals(1, MixinDefaultCallbackTransport.adapt(input.source, input.types::get, name -> input.original));
    }

    public static class Property { }
    public static class Block { }
    public static class Ladder extends Block { public static final Property FACING = new Property(); }
    public static class Door { public static final Property FACING = new Property(); }
    public static class Tags { public static final Object CAN_CLIMB_TRAPDOOR_ABOVE = new Object(); }
    public static class State {
        final Block block; final boolean tagged; final String facing; int tagReads, blockReads;
        State(Block block, boolean tagged, String facing) { this.block = block; this.tagged = tagged; this.facing = facing; }
        public boolean is(Object tag) { tagReads++; assertSame(Tags.CAN_CLIMB_TRAPDOOR_ABOVE, tag); return tagged; }
        public Block getBlock() { blockReads++; return block; }
        public Comparable<?> getValue(Property property) { assertTrue(property == Ladder.FACING || property == Door.FACING); return facing; }
    }
    private record Executor(java.lang.reflect.Method method, Object receiver) {
        Object invoke(State below, State above) throws Exception {
            var callback = new CallbackInfoReturnable<Boolean>("default", true);
            method.invoke(receiver, below, null, null, above, callback); return callback.getReturnValue();
        }
    }
    private static Executor executable(ClassNode source) throws Exception {
        Map<String, String> types = new HashMap<>(); types.put(source.name, "fixture/callback/Original");
        types.put("net/minecraft/world/level/block/state/BlockState", Type.getInternalName(State.class));
        types.put("net/minecraft/world/level/block/Block", Type.getInternalName(Block.class));
        types.put("net/minecraft/world/level/block/LadderBlock", Type.getInternalName(Ladder.class));
        types.put("net/minecraft/world/level/block/TrapDoorBlock", Type.getInternalName(Door.class));
        types.put("net/fabricmc/fabric/api/block/v1/BlockFunctionalityTags", Type.getInternalName(Tags.class));
        for (String name : List.of("net/minecraft/tags/TagKey", "net/minecraft/core/BlockPos", "net/minecraft/world/level/LevelReader")) types.put(name, "java/lang/Object");
        for (String name : List.of("net/minecraft/world/level/block/state/properties/Property", "net/minecraft/world/level/block/state/properties/EnumProperty")) types.put(name, Type.getInternalName(Property.class));
        ClassNode mapped = new ClassNode(); source.accept(new org.objectweb.asm.commons.ClassRemapper(mapped, new org.objectweb.asm.commons.SimpleRemapper(types)));
        mapped.visibleAnnotations = null; mapped.invisibleAnnotations = null;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); mapped.accept(writer);
        Class<?> type = new ClassLoader(MixinDefaultCallbackTransportTest.class.getClassLoader()) {
            Class<?> define() { byte[] bytes = writer.toByteArray(); return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        Object receiver = java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> { throw new AssertionError(method); });
        var method = Arrays.stream(type.getDeclaredMethods()).filter(m -> !m.getName().contains("$forbricdefaultcallback")).findFirst().orElseThrow();
        method.setAccessible(true); return new Executor(method, receiver);
    }
    private static Inputs inputs() throws Exception {
        Path api = Path.of(System.getProperty("forbric.fabricApi", net.forbric.kernel.TestFixtures.fabricApi().toString()));
        Path base = Path.of(System.getProperty("forbric.predicateBase", net.forbric.kernel.TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar").toString()));
        Path neo = net.forbric.kernel.TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");
        net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.STAGED,
                Files.isRegularFile(api) && Files.isRegularFile(base) && Files.isRegularFile(neo), "actual API and indexed native runtimes required");
        ClassNode source = null; Map<String, ClassNode> types = new HashMap<>(); ClassNode original;
        try (var jar = new java.util.zip.ZipFile(api.toFile())) {
            var module = jar.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-block-api-v1-")).findFirst().orElseThrow();
            try (var nested = new java.util.zip.ZipInputStream(jar.getInputStream(module))) {
                for (java.util.zip.ZipEntry entry; (entry = nested.getNextEntry()) != null;)
                    if (entry.getName().equals("net/fabricmc/fabric/mixin/block/LivingEntityMixin.class")) { source = parse(nested.readAllBytes()); break; }
            }
        }
        try (var jar = new java.util.zip.ZipFile(base.toFile())) {
            for (String name : List.of("net/minecraft/world/entity/LivingEntity", "net/minecraft/world/level/block/LadderBlock"))
                types.put(name, parse(jar.getInputStream(jar.getEntry(name + ".class")).readAllBytes()));
            original = new NativeGameReferences(path -> { try { var entry = jar.getEntry(path); return entry == null ? null : jar.getInputStream(entry).readAllBytes(); } catch (Exception e) { return null; } })
                    .get(Ecosystem.FABRIC, "net/minecraft/world/entity/LivingEntity");
        }
        try (var jar = new java.util.zip.ZipFile(neo.toFile())) {
            String owner = "net/neoforged/neoforge/common/extensions/IBlockExtension";
            types.put(owner, parse(jar.getInputStream(jar.getEntry(owner + ".class")).readAllBytes()));
        }
        net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.STAGED, original != null, "indexed source platform required");
        return new Inputs(source, types, original);
    }
    private static ClassNode parse(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
    private static MethodNode handler(ClassNode node) { return node.methods.stream().filter(m -> !m.name.equals("<init>")).findFirst().orElseThrow(); }
}
