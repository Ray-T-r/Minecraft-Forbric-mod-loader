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
        Path nativeJar = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");
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
