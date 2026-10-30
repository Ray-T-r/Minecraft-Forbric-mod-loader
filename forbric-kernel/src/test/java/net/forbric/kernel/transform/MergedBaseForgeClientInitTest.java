package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

class MergedBaseForgeClientInitTest {
    private static final Path STAGE = TestFixtures.stagedRoot();
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String MODELS = "net/minecraft/client/resources/model/ModelManager";
    private static final String NEO = "net/neoforged/neoforge/client/ClientHooks";
    private static final String KERNEL = "net/forbric/kernel/runtime/KernelForgeClientInit";
    private static final String FORGE = "net/minecraftforge/client/ForgeHooksClient";
    private static final String GEOMETRY = "net/minecraftforge/client/model/geometry/GeometryLoaderManager";
    private static final String INIT = "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/server/packs/resources/ReloadableResourceManager;)V";
    private static final String PARTICLES = "(Lnet/minecraft/client/particle/ParticleResources;)V";
    private static final String RELOAD = "(Lnet/minecraft/server/packs/resources/PreparableReloadListener$SharedState;Ljava/util/concurrent/Executor;Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;";

    @AfterEach void restoreSwitch() { System.clearProperty("forbric.forgeClientInit"); }

    /**
     * NeoForge's two calls are found once each. Where the merge kept no MinecraftForge call, both go to the kernel's
     * composition. Where it kept MinecraftForge's own call ahead of NeoForge's (a merge that keeps both sides' edits),
     * MinecraftForge's client init goes to the kernel's Forge half and NeoForge's to the composition, which takes that
     * half's capture, and a native particle pair is left: MinecraftForge's init and each family's particles run once.
     * Only owners and names change; NeoForge's init keeps the relay name guest mixins are re-anchored to.
     */
    @Test void twoRealMinecraftCallsKeepTheirDescriptorsAndInstructionCounts() throws Exception {
        byte[] input = baseline(MINECRAFT);
        ClassNode before = parse(input);
        byte[] output = transform(MINECRAFT, input);
        ClassNode after = parse(output);
        for (String name : List.of("initClientHooks", "onRegisterParticleProviders")) {
            assertEquals(1, calls(before, NEO, name));
            long forge = calls(before, FORGE, name);
            assertTrue(forge <= 1, name);
            boolean initializer = name.equals("initClientHooks");
            if (forge == 0 || initializer) {
                assertEquals(0, calls(after, NEO, name));
                assertEquals(1, calls(after, KERNEL, name), "NeoForge's call relayed under its own name");
                MethodInsnNode redirected = call(after, KERNEL, name);
                assertEquals(initializer ? INIT : PARTICLES, redirected.desc);
                assertEquals(Opcodes.INVOKESTATIC, redirected.getOpcode());
                assertFalse(redirected.itf);
            }
            if (forge == 1 && initializer) {
                assertEquals(0, calls(after, FORGE, name), "MinecraftForge's own init is the kernel's Forge half, never run twice");
                MethodInsnNode half = call(after, KERNEL, "initForgeClientHooks");
                assertEquals(INIT, half.desc);
                assertEquals(Opcodes.INVOKESTATIC, half.getOpcode());
                assertTrue(index(after, half) < index(after, call(after, KERNEL, name)), "MinecraftForge's half runs first, where its call was");
            } else if (forge == 1) {
                assertEquals(1, calls(after, NEO, name), "a native particle pair registers each family's providers itself");
                assertEquals(1, calls(after, FORGE, name));
                assertEquals(0, calls(after, KERNEL, name));
            } else assertEquals(0, calls(after, KERNEL, "initForgeClientHooks"));
        }
        assertEquals(instructionCount(before), instructionCount(after));
        assertSame(output, transform(MINECRAFT, output), "already redirected byte array must be returned unchanged");
        System.setProperty("forbric.forgeClientInit", "off");
        assertSame(input, transform(MINECRAFT, input), "off leaves both original calls intact");
    }

    @Test void missingDuplicateWrongDescriptorAndSplitConstructorAnchorsStandDownAtomically() throws Exception {
        byte[] input = baseline(MINECRAFT);
        for (String damage : List.of("missing", "duplicate", "descriptor", "different-constructor")) {
            ClassNode node = parse(input);
            MethodInsnNode target = call(node, NEO, "initClientHooks");
            MethodNode method = node.methods.stream().filter(m -> m.instructions.indexOf(target) >= 0).findFirst().orElseThrow();
            switch (damage) {
                case "missing" -> method.instructions.remove(target);
                case "duplicate" -> method.instructions.insert(target,
                        new MethodInsnNode(Opcodes.INVOKESTATIC, NEO, target.name, target.desc, false));
                case "descriptor" -> target.desc = "(Ljava/lang/Object;)V";
                case "different-constructor" -> {
                    method.instructions.remove(target);
                    MethodNode extra = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
                    extra.instructions.add(target); node.methods.add(extra);
                }
            }
            byte[] before = bytes(node);
            assertFalse(ForbricMergedBaseCompatTransformer.restoreForgeClientInit(node), damage);
            assertArrayEquals(before, bytes(node), damage + " must not partially redirect particles");
        }
    }

    /**
     * MinecraftForge's geometry loaders initialise first in every reload, before any async work, exactly once: through
     * the repair, or by the merged body's own GeometryLoaderManager.init() where a merge kept MinecraftForge's edit,
     * which the repair then leaves as it is.
     */
    @Test void geometryInitializationRunsAtEveryRealReloadEntryBeforeAnyAsyncWork() throws Exception {
        byte[] input = baseline(MODELS);
        boolean nativeInit = calls(parse(input), GEOMETRY, "init") > 0;
        byte[] output = transform(MODELS, input);
        if (nativeInit) assertSame(input, output, "a reload that already initialises MinecraftForge's geometry is left alone");
        ClassNode node = parse(output);
        MethodNode reload = method(node, "reload", RELOAD);
        AbstractInsnNode first = code(reload.instructions.getFirst());
        assertInstanceOf(MethodInsnNode.class, first);
        MethodInsnNode initialises = (MethodInsnNode) first;
        assertTrue(initialises.owner.equals(KERNEL) && initialises.name.equals("initGeometryLoaders")
                || initialises.owner.equals(GEOMETRY) && initialises.name.equals("init"), initialises.owner + "." + initialises.name);
        assertEquals("()V", initialises.desc);
        assertEquals(1, calls(node, KERNEL, "initGeometryLoaders") + calls(node, GEOMETRY, "init"));
        assertEquals(0, calls(node, KERNEL, "initClientHooks"), "geometry must not be attached to the one-time client hook");
        assertDoesNotThrow(() -> new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, reload));
        assertSame(output, transform(MODELS, output));
        System.setProperty("forbric.forgeClientInit", "off");
        assertSame(input, transform(MODELS, input));
    }

    @Test void anExistingForgeGeometryCallAndAnUnrecognizedReloadBodyAreUntouched() throws Exception {
        byte[] input = neoForgeReload(baseline(MODELS));
        ClassNode existing = parse(input);
        method(existing, "reload", RELOAD).instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "net/minecraftforge/client/model/geometry/GeometryLoaderManager", "init", "()V", false));
        byte[] before = bytes(existing);
        assertFalse(ForbricMergedBaseCompatTransformer.restoreForgeGeometryReload(existing));
        assertArrayEquals(before, bytes(existing));
        ClassNode shifted = parse(input);
        assertTrue(ForbricMergedBaseCompatTransformer.restoreForgeGeometryReload(parse(input)), "control: NeoForge's own reload is repaired");
        AbstractInsnNode first = code(method(shifted, "reload", RELOAD).instructions.getFirst());
        ((VarInsnNode) first).var = 2;
        before = bytes(shifted);
        assertFalse(ForbricMergedBaseCompatTransformer.restoreForgeGeometryReload(shifted));
        assertArrayEquals(before, bytes(shifted));
    }

    @Test void runtimeCallsLinkAgainstBothActualCarriersAndMergedOptions() throws Exception {
        for (String family : List.of("forge", "neoforge")) {
            String owner = family.equals("forge") ? "net/minecraftforge/client/ForgeHooksClient" : NEO;
            ClassNode node = parse(read(STAGE.resolve(family + "-runtime/" + family + "-runtime.jar"), owner));
            for (String name : List.of("initClientHooks", "onRegisterParticleProviders")) {
                MethodNode hook = method(node, name, name.equals("initClientHooks") ? INIT : PARTICLES);
                assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        hook.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
            }
        }
        ClassNode options = parse(game("net/minecraft/client/Options"));
        assertTrue((method(options, "load", "(Z)V").access & Opcodes.ACC_PUBLIC) != 0);
        ClassNode forgeModels = parse(read(STAGE.resolve("forge-patched/patched-mc-forge-26.2.jar"), MODELS));
        MethodInsnNode first = (MethodInsnNode) code(method(forgeModels, "reload", RELOAD).instructions.getFirst());
        assertEquals("net/minecraftforge/client/model/geometry/GeometryLoaderManager", first.owner);
        assertEquals("init", first.name);
    }

    /**
     * The staged reload with any MinecraftForge geometry initialisation the merge kept taken out: NeoForge's own body,
     * which opens by asking its shared state for the resource manager.
     */
    private static byte[] neoForgeReload(byte[] models) {
        ClassNode node = parse(models);
        MethodNode reload = method(node, "reload", RELOAD);
        for (AbstractInsnNode instruction : reload.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(GEOMETRY) && call.name.equals("init")) reload.instructions.remove(call);
        }
        return bytes(node);
    }
    /** The position of {@code call} in the method that holds it. */
    private static long index(ClassNode node, MethodInsnNode call) {
        for (MethodNode method : node.methods) {
            int at = 0;
            for (AbstractInsnNode instruction : method.instructions) { if (instruction == call) return at; at++; }
        }
        throw new AssertionError("not in the class");
    }
    private static byte[] baseline(String owner) throws Exception {
        System.setProperty("forbric.forgeClientInit", "off");
        byte[] result = transform(owner, game(owner));
        System.clearProperty("forbric.forgeClientInit");
        return result;
    }
    private static byte[] transform(String owner, byte[] input) {
        return new ForbricMergedBaseCompatTransformer().transform(owner.replace('/', '.'), input, null);
    }
    private static byte[] game(String owner) throws Exception {return read(STAGE.resolve(TestFixtures.BASE_JAR), owner);}
    private static byte[] read(Path jar, String owner) throws Exception {
        TestFixtures.require(Fixture.STAGED, Files.isRegularFile(jar), "staged jar absent: " + jar);
        try (ZipFile zip = new ZipFile(jar.toFile())) {return zip.getInputStream(zip.getEntry(owner + ".class")).readAllBytes();}
    }
    private static ClassNode parse(byte[] bytes) {ClassNode node = new ClassNode();new ClassReader(bytes).accept(node, 0);return node;}
    private static byte[] bytes(ClassNode node) {ClassWriter writer = new ClassWriter(0);node.accept(writer);return writer.toByteArray();}
    private static AbstractInsnNode code(AbstractInsnNode node) {while (node != null && node.getOpcode() < 0) node = node.getNext();return node;}
    private static MethodNode method(ClassNode node, String name, String desc) {return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();}
    private static long instructionCount(ClassNode node) {return node.methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray())).filter(i -> i.getOpcode() >= 0).count();}
    private static long calls(ClassNode node, String owner, String name) {return node.methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray())).filter(i -> i instanceof MethodInsnNode c && owner.equals(c.owner) && name.equals(c.name)).count();}
    private static MethodInsnNode call(ClassNode node, String owner, String name) {return node.methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray())).filter(i -> i instanceof MethodInsnNode c && owner.equals(c.owner) && name.equals(c.name)).map(i -> (MethodInsnNode)i).findFirst().orElseThrow();}
}
