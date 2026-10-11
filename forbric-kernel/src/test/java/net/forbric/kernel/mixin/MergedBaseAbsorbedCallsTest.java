package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

class MergedBaseAbsorbedCallsTest {
    @Test void actualFogHookCannotReorderTheSourceCallbackPastFluidAndEventEffects()throws Exception{
        Path root=TestFixtures.stagedRoot(),merged=root.resolve("merged-base/patched-mc-merged-26.2.jar"),nativeJar=TestFixtures.vanillaJar(),runtime=root.resolve("neoforge-runtime/neoforge-runtime.jar");
        TestFixtures.requireFiles(Fixture.STAGED,"merged and carrier",merged,runtime);TestFixtures.requireFiles(Fixture.MC_LIBRARIES,"vanilla",nativeJar);
        String owner="net/minecraft/client/renderer/fog/FogRenderer",signature="computeFogColor(Lnet/minecraft/client/Camera;FLnet/minecraft/client/multiplayer/ClientLevel;IFLorg/joml/Vector4f;)V";
        String member="Lorg/joml/Vector4f;set(FFFF)Lorg/joml/Vector4f;";
        try(var ignored=MergedBaseCalleeSwaps.using(n->{ClassNode node=read(merged,n);return node!=null?node:read(runtime,n);},(f,n)->f==Ecosystem.FABRIC?read(nativeJar,n):null)){
            assertTrue(CarrierHelpers.edges(NativeCallChanges.method(read(nativeJar,owner),signature),member).contains(CarrierHelpers.Shape.TAIL));
            assertNull(MergedBaseAbsorbedCalls.find(owner,signature,member,Ecosystem.FABRIC),"the hook continues with fluid/event writes; AFTER-hook changes observable callback ordering");
            var source=read(nativeJar,owner);var current=read(merged,owner);
            // The merged method may reach the helper as MinecraftForge's own body reaches its set (an edit-spliced base keeps
            // its getFogColor hook in front of NeoForge's helper): the other ecosystems' native bodies are its peers.
            var peers=java.util.stream.Stream.of(Ecosystem.FORGE,Ecosystem.NEOFORGE).map(e->NativeCallTestEvidence.staged().apply(e,owner)).filter(java.util.Objects::nonNull).toList();
            var seam=NativeCallbackSeam.derive(source,peers,current,NativeCallChanges.method(source,signature),member,n->{ClassNode node=read(merged,n);return node!=null?node:read(runtime,n);});
            assertNotNull(seam,"the original set remains the helper's unconditional first effect, with exactly the same input wires and a host prefix a native body has");
            assertEquals(owner,seam.host());
            assertEquals(6,seam.hostParameters().length,"every original host argument is available to the unchanged callback");
        }
    }
    private static ClassNode read(Path path,String owner){try(var zip=new ZipFile(path.toFile())){var entry=zip.getEntry(owner+".class");if(entry==null)return null;try(var in=zip.getInputStream(entry)){return MixinFit.parse(in.readAllBytes());}}catch(Exception e){throw new AssertionError(e);}}
}
