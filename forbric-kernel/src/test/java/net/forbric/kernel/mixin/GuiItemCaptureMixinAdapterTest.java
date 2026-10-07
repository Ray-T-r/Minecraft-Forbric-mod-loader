package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.zip.ZipFile;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

@ResourceLock("system-properties")
class GuiItemCaptureMixinAdapterTest {
    @AfterEach void reset() { System.clearProperty(MixinGuiItemCaptureAdapter.PROPERTY); }
    private static ClassNode read(Fixture kind, Path jar, String name) throws Exception {
        TestFixtures.require(kind, Files.isRegularFile(jar), "local fixture unavailable");
        try (ZipFile zip = new ZipFile(jar.toFile())) { ClassNode node = new ClassNode(); new ClassReader(zip.getInputStream(zip.getEntry(name + ".class"))).accept(node, 0); return node; }
    }
    private static ClassNode mixin() throws Exception {
        return read(Fixture.THIRD_PARTY, Path.of("build/sweep80-mac/v020-rounds/r3/mods/itemglintrelight-fabric-26.2-0.3.0+26.2.jar"), "celia/adwadg/itemglintrelight/mixin/client/GuiGraphicsItemOutlineMixin");
    }
    @Test void theCaptureSelectsTheSubmissionAndDoesNotMoveTheTooltipCallback() throws Exception {
        ClassNode mixin = mixin(), target = read(Fixture.STAGED, TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar"), "net/minecraft/client/gui/GuiGraphicsExtractor");
        assertEquals(1, MixinGuiItemCaptureAdapter.adapt(mixin, name -> target));
        MethodNode capture = mixin.methods.stream().filter(m -> m.name.equals("itemglintrelight$captureGuiItem")).findFirst().orElseThrow();
        assertEquals("INVOKE", MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(capture)).getFirst(), "value"));
        MethodNode tooltip = mixin.methods.stream().filter(m -> m.name.equals("itemglintrelight$tooltipScheduled")).findFirst().orElseThrow();
        assertEquals("HEAD", MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(tooltip)).getFirst(), "value"));
        assertEquals(0, MixinGuiItemCaptureAdapter.adapt(mixin, name -> target));
        System.setProperty(MixinGuiItemCaptureAdapter.PROPERTY, "off");
        assertEquals(0, MixinGuiItemCaptureAdapter.adapt(mixin(), name -> target));
    }
}
