package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import net.fabricmc.api.EnvType;
import net.forbric.kernel.fabric.FabricModMetadataParser;
import net.forbric.kernel.fabric.KernelFabricLoader;
import net.forbric.kernel.fabric.KernelModContainer;
import net.forbric.kernel.transform.InjectorExecution;
import net.forbric.kernel.interop.protocol.sodium.SodiumProtocolExtension;

@ResourceLock("KernelFabricEcosystem")
@ResourceLock("system-properties")
class ConfigMetadataProviderTest {
    @TempDir Path work;
    private Field fabricField, gameField;
    private Object previousFabric, previousGame;
    private String previousSwitch;
    @BeforeEach void setup() throws Exception {
        previousSwitch=System.getProperty("forbric.sodiumConfigUsers");System.clearProperty("forbric.sodiumConfigUsers");
        fabricField=KernelFabricLoader.class.getDeclaredField("instance");fabricField.setAccessible(true);previousFabric=fabricField.get(null);
        gameField=KernelLifecycle.class.getDeclaredField("gameLoader");gameField.setAccessible(true);previousGame=gameField.get(null);
        Constructor<KernelFabricLoader> constructor=KernelFabricLoader.class.getDeclaredConstructor(EnvType.class,Path.class,Path.class,String[].class,String.class);constructor.setAccessible(true);
        KernelFabricLoader fabric=constructor.newInstance(EnvType.CLIENT,work,work.resolve("config"),new String[0],"26.2");
        fabric.register(new KernelModContainer(FabricModMetadataParser.read(new StringReader("""
            {"schemaVersion":1,"id":"unknown_integration","version":"1.2.3","name":"An unfamiliar integration"}
            """)),null,null));fabricField.set(null,fabric);
        Map<String,byte[]> metadata=InjectorExecution.compile(work,Map.of("net.caffeinemc.mods.sodium.client.config.ConfigManager","""
            package net.caffeinemc.mods.sodium.client.config;
            public class ConfigManager { public record ModMetadata(String modName,String modVersion){} }
            """));KernelLifecycle.bind(InjectorExecution.load(metadata));
    }
    @AfterEach void restore() throws Exception {
        fabricField.set(null,previousFabric);gameField.set(null,previousGame);
        if(previousSwitch==null)System.clearProperty("forbric.sodiumConfigUsers");else System.setProperty("forbric.sodiumConfigUsers",previousSwitch);
    }
    @Test void unknownDeclaredModsUseTheRealPublicMetadataTypeAndNativeValuesWin() throws Exception {
        Object nativeValue=new Object();IllegalStateException missing=new IllegalStateException("not in native ModList");
        Function<String,Object> provider=SodiumProtocolExtension.metadataProvider(id->{if(id.equals("native_mod"))return nativeValue;throw missing;});
        assertSame(nativeValue,provider.apply("native_mod"));Object fallback=provider.apply("unknown_integration");
        assertEquals("An unfamiliar integration",fallback.getClass().getMethod("modName").invoke(fallback));
        assertEquals("1.2.3",fallback.getClass().getMethod("modVersion").invoke(fallback));
    }
    @Test void neitherViewKnowingAnIdPreservesTheNativeFailure() {
        IllegalArgumentException failure=new IllegalArgumentException("original provider does not know this id");
        Function<String,Object> provider=SodiumProtocolExtension.metadataProvider(id->{throw failure;});
        assertSame(failure,assertThrows(IllegalArgumentException.class,()->provider.apply("uninstalled_mod")));
        Function<String,Object> empty=SodiumProtocolExtension.metadataProvider(id->null);
        assertThrows(IllegalStateException.class,()->empty.apply("uninstalled_mod"));
        AssertionError error=new AssertionError("provider error");
        assertSame(error,assertThrows(AssertionError.class,()->SodiumProtocolExtension.metadataProvider(id->{throw error;}).apply("unknown_integration")));
    }
    @Test void disablingTheProtocolAlsoPreservesTheOriginalMetadataProvider() {
        System.setProperty("forbric.sodiumConfigUsers","off");
        Function<String,Object> original=id->null;assertSame(original,SodiumProtocolExtension.metadataProvider(original));
    }
}
