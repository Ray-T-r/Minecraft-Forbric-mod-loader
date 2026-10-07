package net.forbric.kernel.mixin;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import net.forbric.kernel.transform.LootTableEventBridgeInjector;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class LootSupersessionProofTest {
	@Test void aNameOrAnUnmodifiedLoaderCannotResolveTheMissingLootMixin() throws Exception {
		String mixin="net.fabricmc.fabric.mixin.loot.ReloadableServerRegistriesMixin";
		String target="net.minecraft.server.ReloadableServerRegistries";
		byte[] raw=StagedFabricMixinFixture.bytes(StagedFabricMixinFixture.game(target.replace('.','/'),false));
		byte[] repaired=new LootTableEventBridgeInjector().transform(target,raw,null);
		SupersededMixins.reset();
		try{
			assertNull(SupersededMixins.provedReplacement(mixin));
			SupersededMixins.observeDefinition(target,raw);assertNull(SupersededMixins.provedReplacement(mixin));
			SupersededMixins.observeDefinition(target,repaired);assertNull(SupersededMixins.provedReplacement(mixin),"two bridge calls do not prove the original source group, its helper, or the final typed dispatch");
			String before=System.setProperty(LootTableEventBridgeInjector.PROPERTY,"off");
			try{assertNull(SupersededMixins.provedReplacement(mixin));}finally{if(before==null)System.clearProperty(LootTableEventBridgeInjector.PROPERTY);else System.setProperty(LootTableEventBridgeInjector.PROPERTY,before);}
		}finally{SupersededMixins.reset();}
	}
    @Test void aStructurallyProvedSourceGroupWaitsForEveryActualDefinition()throws Exception{
        org.objectweb.asm.tree.ClassNode source=new org.objectweb.asm.tree.ClassNode();
        java.nio.file.Path api=java.nio.file.Path.of(System.getProperty("forbric.fabricApi",net.forbric.kernel.TestFixtures.fabricApi().toString()));net.forbric.kernel.TestFixtures.requireFiles(net.forbric.kernel.TestFixtures.Fixture.STAGED,"current raw loot source",api);
        try(var outer=new java.util.zip.ZipFile(api.toFile())){var module=outer.stream().filter(e->e.getName().startsWith("META-INF/jars/fabric-loot-api-v3-")).findFirst().orElseThrow();try(var inner=new java.util.zip.ZipInputStream(outer.getInputStream(module))){for(var entry=inner.getNextEntry();entry!=null;entry=inner.getNextEntry())if(entry.getName().equals("net/fabricmc/fabric/mixin/loot/ReloadableServerRegistriesMixin.class")){new org.objectweb.asm.ClassReader(inner.readAllBytes()).accept(source,org.objectweb.asm.ClassReader.EXPAND_FRAMES);break;}}}
        var plan=net.forbric.kernel.boot.LootSourceCallbacks.plan(source);assertNotNull(plan);
        java.nio.file.Path base=java.nio.file.Path.of(System.getProperty("forbric.predicateBase",net.forbric.kernel.TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar").toString()));
        net.forbric.kernel.TestFixtures.requireFiles(net.forbric.kernel.TestFixtures.Fixture.STAGED,"native-indexed actual loot source proof",base);
        try(var jar=new java.util.zip.ZipFile(base.toFile());var loader=new net.forbric.kernel.classloading.ForbricClassLoader(new java.net.URL[0],getClass().getClassLoader())){
            String targetPath=plan.targetName().replace('.','/')+".class";byte[] raw=jar.getInputStream(jar.getEntry(targetPath)).readAllBytes(),host=new LootTableEventBridgeInjector().transform(plan.targetName(),raw,null);
            java.nio.file.Path bridgePath=java.nio.file.Path.of("build/classes/java/runtime/net/forbric/kernel/runtime/KernelLootBridge.class");if(!java.nio.file.Files.exists(bridgePath))bridgePath=java.nio.file.Path.of("forbric-kernel").resolve(bridgePath);byte[] bridge=java.nio.file.Files.readAllBytes(bridgePath);
            SupersededMixins.reset();net.forbric.kernel.boot.LootSourceCallbacks.bind(loader);
            try{
                assertNull(SupersededMixins.replacementFor(plan.sourceName()));
                assertTrue(net.forbric.kernel.boot.LootSourceCallbacks.offer(loader,source,path->{if(path.equals(targetPath))return host;if(path.equals("net/forbric/kernel/runtime/KernelLootBridge.class"))return bridge;try{var entry=jar.getEntry(path);return entry==null?null:jar.getInputStream(entry).readAllBytes();}catch(java.io.IOException failure){throw new IllegalStateException(failure);}}));
                assertNotNull(SupersededMixins.replacementFor(plan.sourceName()));assertNull(SupersededMixins.provedReplacement(plan.sourceName()));
                SupersededMixins.observeDefinition(loader,plan.targetName(),host);assertNull(SupersededMixins.provedReplacement(plan.sourceName()));
                SupersededMixins.observeDefinition(loader,"net.forbric.kernel.runtime.KernelLootBridge",bridge);assertNull(SupersededMixins.provedReplacement(plan.sourceName()));
                SupersededMixins.observeDefinition(loader,plan.helperName().replace('/','.'),plan.helperBytes());assertNull(SupersededMixins.provedReplacement(plan.sourceName()));SupersededMixins.observeDefinition(loader,"net.minecraft.world.level.storage.loot.LootDataType",jar.getInputStream(jar.getEntry("net/minecraft/world/level/storage/loot/LootDataType.class")).readAllBytes());SupersededMixins.observeDefinition(loader,"net.minecraft.core.registries.Registries",jar.getInputStream(jar.getEntry("net/minecraft/core/registries/Registries.class")).readAllBytes());assertNotNull(SupersededMixins.provedReplacement(plan.sourceName()));
            }finally{SupersededMixins.reset();}
        }
    }

}
