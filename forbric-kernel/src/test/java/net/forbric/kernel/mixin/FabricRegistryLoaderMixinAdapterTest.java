package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.zip.ZipFile;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

class FabricRegistryLoaderMixinAdapterTest {
	private ClassNode mixin() throws Exception {
		Path path=Path.of("build/compat-inputs/player-loading/api/fabric-registry-sync-v0-7.1.1+c7bd5b8e9e.jar");TestFixtures.require(Fixture.THIRD_PARTY,Files.isRegularFile(path),path+" absent");
		try(ZipFile zip=new ZipFile(path.toFile())){return MixinFit.parse(zip.getInputStream(zip.getEntry("net/fabricmc/fabric/mixin/registry/sync/RegistryDataLoaderMixin.class")).readAllBytes());}
	}
	@Test void serverBindingAndAsyncCaptureUseBothLiveOverloads() throws Exception {
		ClassNode mixin=mixin(),target=StagedFabricMixinFixture.game("net/minecraft/resources/RegistryDataLoader",false);
		assertEquals(2,FabricRegistryLoaderMixinAdapter.adapt(mixin,n->target));
		MethodNode wrap=StagedFabricMixinFixture.method(mixin,"wrapIsServerCall");
		assertTrue(wrap.desc.contains(";ZLcom/llamalad7"));
		assertTrue(String.valueOf(MixinFit.value(MixinFit.injectorOf(wrap),"method")).contains("Executor;Ljava/util/List;"));
		assertTrue(String.valueOf(MixinFit.value(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin,"supplyAsync")),"method")).contains("Executor;Z)"));
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name,wrap);
		ClassNode runtime=MixinFit.parse(Files.readAllBytes(Path.of(System.getProperty("forbric.test.runtimeClasses","build/classes/java/runtime")).resolve("net/forbric/kernel/runtime/KernelWrapOperations.class")));
		for(var i:wrap.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(runtime.name))assertTrue(runtime.methods.stream().anyMatch(m->m.name.equals(c.name)&&m.desc.equals(c.desc)),"the generated wrapper must link to the actual compiled game helper");
		assertNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin,"wrapIsServerCall$forbricOriginal")));
		assertEquals(0,FabricRegistryLoaderMixinAdapter.adapt(mixin,n->target));
	}
	@Test void aVanillaLoaderKeepsTheOriginalHandlers() throws Exception {
		ClassNode vanilla=StagedFabricMixinFixture.game("net/minecraft/resources/RegistryDataLoader",true);
		assertEquals(0,FabricRegistryLoaderMixinAdapter.adapt(mixin(),n->vanilla));
	}
}
