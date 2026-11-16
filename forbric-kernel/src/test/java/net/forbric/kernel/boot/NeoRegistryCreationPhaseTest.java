package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.zip.ZipFile;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

/** Pin the native ordering: created registries are filled before mods attach their registry callbacks. */
class NeoRegistryCreationPhaseTest {
    @Test void registryModificationFollowsCreationAsOnNativeNeoForge() throws Exception {
        Path nativeJar = TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR);
        Path kernel = Path.of("build/classes/java/runtime/net/forbric/kernel/runtime/KernelNeoRegistries.class");
        TestFixtures.requireFile(Fixture.STAGED, nativeJar, "staged runtime absent");
        TestFixtures.require(Fixture.GAME_SIDE, Files.isRegularFile(kernel), "staged runtime absent");
        ClassNode nativeNode = new ClassNode();
        try (ZipFile zip = new ZipFile(nativeJar.toFile())) {
            new ClassReader(zip.getInputStream(zip.getEntry("net/neoforged/neoforge/registries/RegistryManager.class"))).accept(nativeNode, 0);
        }
        ClassNode kernelNode = new ClassNode(); new ClassReader(Files.readAllBytes(kernel)).accept(kernelNode, 0);
        MethodNode nativePhase = nativeNode.methods.stream().filter(m -> m.name.equals("postNewRegistryEvent")).findFirst().orElseThrow();
        MethodNode kernelPhase = kernelNode.methods.stream().filter(m -> m.name.equals("postNewRegistryEvent")).findFirst().orElseThrow();
        assertTrue(nativePhase.instructions.indexOf(modify(nativePhase)) > fill(nativePhase));
        assertTrue(kernelPhase.instructions.indexOf(modify(kernelPhase)) > fill(kernelPhase));
        assertEquals(1, java.util.Arrays.stream(kernelPhase.instructions.toArray()).filter(i -> i instanceof LdcInsnNode c
                && c.cst instanceof org.objectweb.asm.Type type && type.getClassName().equals("net.neoforged.neoforge.registries.ModifyRegistriesEvent")).count());
    }
    /**
     * Natively RegistryManager.postNewRegistryEvent posts NewRegistryEvent through ModLoader.postEventWrapContainerInModOrder,
     * so each mod's listener runs with its own mod active in ModLoadingContext (measured on the native .93 server, Stage 4 C5
     * lane THR). The kernel posts it bus by bus; it must make the bus's mod active around each post and clear it after.
     */
    @Test void eachBusTakesNewRegistryEventWithItsOwnModActiveAsNatively() throws Exception {
        Path nativeJar = TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR);
        Path kernel = Path.of("build/classes/java/runtime/net/forbric/kernel/runtime/KernelNeoRegistries.class");
        TestFixtures.requireFile(Fixture.STAGED, nativeJar, "staged runtime absent");
        TestFixtures.require(Fixture.GAME_SIDE, Files.isRegularFile(kernel), "staged runtime absent");
        ClassNode nativeNode = new ClassNode();
        try (ZipFile zip = new ZipFile(nativeJar.toFile())) {
            new ClassReader(zip.getInputStream(zip.getEntry("net/neoforged/neoforge/registries/RegistryManager.class"))).accept(nativeNode, 0);
        }
        MethodNode nativePhase = nativeNode.methods.stream().filter(m -> m.name.equals("postNewRegistryEvent")).findFirst().orElseThrow();
        assertTrue(java.util.Arrays.stream(nativePhase.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c
                && c.owner.equals("net/neoforged/fml/ModLoader") && c.name.equals("postEventWrapContainerInModOrder")),
                "premise: native NeoForge posts NewRegistryEvent with each mod's container active");

        ClassNode kernelNode = new ClassNode(); new ClassReader(Files.readAllBytes(kernel)).accept(kernelNode, 0);
        MethodNode kernelPhase = kernelNode.methods.stream().filter(m -> m.name.equals("postNewRegistryEvent")).findFirst().orElseThrow();
        java.util.List<Integer> sets = new java.util.ArrayList<>();
        int firstPost = -1;
        for (AbstractInsnNode instruction : kernelPhase.instructions) {
            if (!(instruction instanceof MethodInsnNode c)) continue;
            int at = kernelPhase.instructions.indexOf(instruction);
            if (c.name.equals("setNeoActiveContainer") && c.owner.endsWith("/KernelModLoader")) sets.add(at);
            // On the bus itself, or through the kernel's own post, which hands it to the bus's container (acceptEvent) as
            // natively since lane STOP.
            if (firstPost < 0 && c.name.equals("post") && (c.owner.equals("net/neoforged/bus/api/IEventBus")
                    || c.owner.equals("net/forbric/kernel/runtime/KernelNeoRegistries"))) firstPost = at;
        }
        assertTrue(firstPost >= 0, "the kernel posts NewRegistryEvent on the mods' buses");
        assertTrue(sets.size() >= 2 && sets.get(0) < firstPost && sets.get(sets.size() - 1) > firstPost,
                "the bus's mod is made active before NewRegistryEvent is posted on it and cleared after: " + sets + " post at " + firstPost);
    }

    private static AbstractInsnNode modify(MethodNode phase) {
        for (var instruction : phase.instructions) {
            if (instruction instanceof TypeInsnNode c && c.desc.endsWith("/ModifyRegistriesEvent")) return instruction;
            if (instruction instanceof LdcInsnNode c && String.valueOf(c.cst).contains("ModifyRegistriesEvent")) return instruction;
        }
        throw new AssertionError("native registry modification phase missing");
    }
    private static int fill(MethodNode phase) {
        for (var instruction : phase.instructions) {
            if (instruction instanceof MethodInsnNode c && c.name.equals("fill")) return phase.instructions.indexOf(instruction);
            if (instruction instanceof LdcInsnNode c && c.cst.equals("fill")) return phase.instructions.indexOf(instruction);
        }
        throw new AssertionError("registry fill missing");
    }
}
