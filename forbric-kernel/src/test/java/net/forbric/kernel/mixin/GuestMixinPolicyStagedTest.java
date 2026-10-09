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
    /**
     * The real pair ContendedCallSites was written for: Shoulder Surfing's CapeLayerMixin redirects
     * {@code RenderTypes.entitySolid} in {@code CapeLayer.submit}, and CustomSkinLoader's cape patch scans for that call
     * from its plugin's postApply. The loader discovers no reason to leave the redirect out (no built-in winner), the
     * observer records the redirect's call exactly, and CustomSkinLoader's own jar names that call — so at run time the
     * pair is reported, by the same rule a renamed pair is (ContendedCallSitesWeaveTest).
     */
    @Test void thirdPartyRedirectConflictsHaveNoLoaderChosenWinner() throws Exception {
        Path ss=Path.of("run/client-merged-pack/mods/ShoulderSurfing-NeoForge-26.2-5.0.7.jar");
        Path csl=Path.of("run/client-merged-pack/mods/[万用皮肤补丁] CustomSkinLoader_Universal-15.0.1.jar");
        TestFixtures.require(Fixture.THIRD_PARTY,Files.isRegularFile(ss)&&Files.isRegularFile(csl),"Shoulder Surfing and CustomSkinLoader fixtures absent");
        String config="shouldersurfing.common.mixins.json";
        try(ZipFile z=new ZipFile(ss.toFile())) {
            java.util.function.Function<String,byte[]> resources=name -> {
                ZipEntry entry=z.getEntry(name);
                if(entry==null)return null;
                try(InputStream in=z.getInputStream(entry)){return in.readAllBytes();}catch(IOException e){throw new UncheckedIOException(e);}
            };
            MergedBaseMixinCompat.reset();
            MergedBaseMixinCompat.discover(config,resources.apply(config),resources);
            assertFalse(MergedBaseMixinCompat.SUPPRESSED_MIXINS.stream().anyMatch(s -> s.startsWith(config+":")),MergedBaseMixinCompat.SUPPRESSED_MIXINS.toString());

            ClassNode cape=MixinFit.parse(resources.apply("com/github/exopandora/shouldersurfing/mixin/CapeLayerMixin.class"));
            ContendedCallSites.reset();
            ContendedCallSites.remember(cape);
            List<ContendedCallSites.Claim> claims=ContendedCallSites.claimsOf(cape.name);
            assertEquals(1,claims.size(),claims.toString());
            assertEquals("Redirect",claims.get(0).kind());
            assertEquals("Lnet/minecraft/client/renderer/rendertype/RenderTypes;entitySolid(Lnet/minecraft/resources/Identifier;)"
                    +"Lnet/minecraft/client/renderer/rendertype/RenderType;",ContendedCallSites.spell(claims.get(0).site()));
            assertTrue(ContendedCallSites.scan(csl,claims.get(0).site()),"CustomSkinLoader's jar should name the call its cape patch scans for");
            // The look-alike: a jar that only calls RenderTypes.entitySolid, as Shoulder Surfing's own handler does, does not name it.
            assertFalse(ContendedCallSites.scan(ss,claims.get(0).site()),"calling a method is not naming it as a patch target");
        } finally {
            MergedBaseMixinCompat.reset();
            ContendedCallSites.reset();
        }
    }
    private ClassNode readNested(InputStream stream,String name)throws IOException {
        try(ZipInputStream zip=new ZipInputStream(stream)) {
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;) if(entry.getName().equals(name)) return MixinFit.parse(zip.readAllBytes());
        }
        throw new AssertionError("missing nested fixture "+name);
    }
}
