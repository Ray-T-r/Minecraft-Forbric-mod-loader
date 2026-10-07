package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.zip.ZipFile;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

@ResourceLock("system-properties")
class MixinPluginPlatformInjectorTest {
    @AfterEach void reset() { System.clearProperty(MixinPluginPlatformInjector.PROPERTY); }
    @Test void actualPluginsKeepValidLocalsAndNativeBranches() throws Exception {
        Path mods=Path.of("build/sweep100-mac-network/mods");
        for(var entry:java.util.Map.of("dev.isxander.controlify.compatibility.CompatMixinPlugin","controlify-3.5.3+mc26.2-universal.jar","me.towdium.jecharacters.mixin.JechMixinPlugin","jecharacters-26.1.2-fabric-4.6.8.jar").entrySet()) {
            Path jar=mods.resolve(entry.getValue());TestFixtures.require(Fixture.THIRD_PARTY,Files.isRegularFile(jar),"local mod fixture absent");byte[] original;
            try(ZipFile zip=new ZipFile(jar.toFile())){original=zip.getInputStream(zip.getEntry(entry.getKey().replace('.','/')+".class")).readAllBytes();}
            var repair=new MixinPluginPlatformInjector(name -> net.forbric.api.Ecosystem.FABRIC);byte[] bytes=repair.transform(entry.getKey(),original,null);assertNotSame(original,bytes);
            ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);
            MethodNode method=node.methods.stream().filter(m->m.name.equals(entry.getKey().contains("controlify")?"loadPlatform":"hook")).findFirst().orElseThrow();
            new Analyzer<>(new BasicVerifier()).analyze(node.name,method);
            assertSame(bytes,repair.transform(entry.getKey(),bytes,null));
            System.setProperty(MixinPluginPlatformInjector.PROPERTY,"off");assertSame(original,repair.transform(entry.getKey(),original,null));System.clearProperty(MixinPluginPlatformInjector.PROPERTY);
        }
    }
}
