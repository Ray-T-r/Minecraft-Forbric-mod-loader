package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Actual guest mixins use the same fit/group policy as differently named structural equivalents. */
class GuestMixinPolicyStagedTest {
    @Test void essentialGuiGroupCannotPoisonAnotherModsGuiContract() throws Exception {
        Path jar=Path.of("build/sweep80-mac/mods/Essential_1-5-0-1_fabric_26-2.jar");
        TestFixtures.require(Fixture.THIRD_PARTY,Files.isRegularFile(jar),"Essential fixture absent");
        ClassNode mixin;
        try(ZipFile outer=new ZipFile(jar.toFile())) {
            ZipEntry nested=outer.stream().filter(e -> e.getName().startsWith("essential-") && !e.getName().startsWith("essential-loader") && e.getName().endsWith(".jar")).findFirst().orElseThrow();
            mixin=readNested(outer.getInputStream(nested),"gg/essential/mixins/transformers/events/Mixin_GuiDrawScreenEvent_Priority.class");
        }
        ClassNode gui=StagedFabricMixinFixture.game("net/minecraft/client/gui/Gui",false);
        List<String> failures=MixinGroupConstraints.failures(mixin,n -> n.equals(gui.name+".class")?StagedFabricMixinFixture.bytes(gui):null);
        assertTrue(failures.stream().anyMatch(s -> s.contains("post_event") && s.contains("at most 0 can bind")),failures.toString());
        mixin.name="another/provider/PriorityGuiMixin";
        assertEquals(failures,MixinGroupConstraints.failures(mixin,n -> n.equals(gui.name+".class")?StagedFabricMixinFixture.bytes(gui):null));
        assertFalse(MergedBaseMixinCompat.SUPPRESSED_MIXINS.stream().anyMatch(s -> s.contains("essential")));
    }
    @Test void jadeDuckContractKeepsItsAssignedShadowAndResolvedCallbackWithoutAnAllowlist() throws Exception {
        Path jar=Path.of("run/client-kernel/mods/[玉 🔍] Jade-mc26.2-Fabric-26.2.10.jar");
        TestFixtures.require(Fixture.THIRD_PARTY,Files.isRegularFile(jar),"Jade fixture absent");
        ClassNode mixin;
        try(ZipFile z=new ZipFile(jar.toFile())) {
            ZipEntry entry=z.stream().filter(e -> e.getName().endsWith("/GuiGraphicsExtractorMixin.class")).findFirst().orElseThrow();
            mixin=MixinFit.parse(z.getInputStream(entry).readAllBytes());
        }
        ClassNode gui=StagedFabricMixinFixture.game("net/minecraft/client/gui/GuiGraphicsExtractor",false);
        assertFalse(mixin.interfaces.isEmpty(),"the guest supplies the overlay's duck interface");
        assertEquals(MixinFit.Verdict.FIT,MixinFit.evaluate(StagedFabricMixinFixture.bytes(mixin),n -> n.equals(gui.name+".class")?StagedFabricMixinFixture.bytes(gui):null).verdict());
        assertFalse(MergedBaseMixinCompat.KEPT_MIXINS.stream().anyMatch(s -> s.startsWith("jade.")));
        // A new mod with the same interface but an orphaned backing field must still be rejected.
        mixin.name="another/provider/GuiDuckMixin";
        for(MethodNode method:gui.methods) for(AbstractInsnNode i:method.instructions.toArray())
            if(i instanceof FieldInsnNode f && f.getOpcode()==Opcodes.PUTFIELD && f.name.equals("minecraft")) method.instructions.set(i,new InsnNode(Opcodes.POP2));
        assertEquals(MixinFit.Verdict.HAZARD,MixinFit.evaluate(StagedFabricMixinFixture.bytes(mixin),n -> n.equals(gui.name+".class")?StagedFabricMixinFixture.bytes(gui):null).verdict());
    }
    @Test void thirdPartyRedirectConflictsHaveNoLoaderChosenWinner() {
        assertFalse(MergedBaseMixinCompat.SUPPRESSED_MIXINS.stream().anyMatch(s -> s.contains("shouldersurfing")));
    }
    private ClassNode readNested(InputStream stream,String name)throws IOException {
        try(ZipInputStream zip=new ZipInputStream(stream)) {
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;) if(entry.getName().equals(name)) return MixinFit.parse(zip.readAllBytes());
        }
        throw new AssertionError("missing nested fixture "+name);
    }
}
