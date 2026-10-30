package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer;

@ResourceLock("system-properties")
class KernelClientHookMixinAnchorsTest {
    private static final String CLIENT = "net/minecraft/client/Minecraft";
    private static final String MIXIN = "de/keksuccino/fancymenu/mixin/mixins/neoforge/client/MixinMinecraft";
    @AfterEach void reset() { System.clearProperty(KernelClientHookMixinAnchors.PROPERTY); }
    private static ClassNode read(Fixture kind, Path jar, String name) throws Exception {
        TestFixtures.require(kind, Files.isRegularFile(jar), "local game or mod fixture unavailable");
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ClassNode node = new ClassNode(); new ClassReader(zip.getInputStream(zip.getEntry(name + ".class"))).accept(node, 0); return node;
        }
    }
    private static ClassNode mixin() throws Exception {
        return read(Fixture.THIRD_PARTY, Path.of("build/sweep80-mac/v020-rounds/r1/mods/fancymenu_neoforge_3.9.12_MC_26.2.jar"), MIXIN);
    }
    private static ClassNode game(boolean repaired) throws Exception {
        Path jar = TestFixtures.stagedBase();
        ClassNode node = read(Fixture.STAGED, jar, CLIENT);
        if (!repaired) return node;
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0); node.accept(writer);
        ClassNode result = new ClassNode();
        new ClassReader(new ForbricMergedBaseCompatTransformer().transform(CLIENT.replace('/', '.'), writer.toByteArray(), null)).accept(result, 0);
        return result;
    }
    @Test void fancyMenusOriginalInitializerFollowsTheLiveRelayWithoutChangingItsShift() throws Exception {
        ClassNode mixin = mixin(), target = game(true);
        MethodNode handler = mixin.methods.stream().filter(m -> m.name.startsWith("after_initClientHooks")).findFirst().orElseThrow();
        AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst();
        Object shift = MixinFit.value(at, "shift");
        assertEquals(1, KernelClientHookMixinAnchors.adapt(mixin, name -> target));
        assertTrue(String.valueOf(MixinFit.value(at, "target")).startsWith("Lnet/forbric/kernel/runtime/KernelForgeClientInit;"));
        assertEquals(shift, MixinFit.value(at, "shift"));
        assertEquals(0, KernelClientHookMixinAnchors.adapt(mixin, name -> target));
    }
    @Test void originalHooksAndDisabledAdapterAreLeftAsCompiled() throws Exception {
        assertEquals(0, KernelClientHookMixinAnchors.adapt(mixin(), name -> {
            try { return game(false); } catch (Exception e) { throw new RuntimeException(e); }
        }));
        System.setProperty(KernelClientHookMixinAnchors.PROPERTY, "off");
        ClassNode target = game(true);
        assertEquals(0, KernelClientHookMixinAnchors.adapt(mixin(), name -> target));
    }
}
