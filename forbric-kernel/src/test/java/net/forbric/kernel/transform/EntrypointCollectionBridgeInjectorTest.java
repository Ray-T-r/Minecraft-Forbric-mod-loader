package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.fabricmc.api.EnvType;

@ExecutesInjector(EntrypointCollectionBridgeInjector.class)
class EntrypointCollectionBridgeInjectorTest {
    private static final String OWNER = "unknown/implementation/ForeignCollector";
    private static EntrypointCollectionBridgeInjector injector() {
        return new EntrypointCollectionBridgeInjector(new EntrypointCollectionBridgeInjector.Contract(
            "unknown:config_consumers", "fixture/protocol/ConfigApi", "register", "metadata",
            new EntrypointCollectionBridgeInjector.Hook("fixture/bridge/Declarations", "collect"),
            new EntrypointCollectionBridgeInjector.Hook("fixture/bridge/Declarations", "wrap")));
    }
    private static Map<String, String> sources(String body) {
        return Map.of(
            "fixture.protocol.ConfigApi", """
                package fixture.protocol;
                public class ConfigApi {
                    public static final java.util.List<String> registrations=new java.util.ArrayList<>();
                    public static java.util.function.Function<String,String> names;
                    public static void register(String entry,String id){registrations.add(id+"="+entry);}
                    public static void metadata(java.util.function.Function<String,String> value){names=value;}
                }
                """,
            "fixture.bridge.Declarations", """
                package fixture.bridge;
                public class Declarations {
                    public static void collect(){fixture.protocol.ConfigApi.register("external.Page","unfamiliar_mod");}
                    public static java.util.function.Function<String,String> wrap(java.util.function.Function<String,String> original){
                        return id->id.equals("unfamiliar_mod")?"Foreign metadata":original.apply(id);
                    }
                }
                """,
            OWNER.replace('/', '.'), "package unknown.implementation; public class ForeignCollector { public static boolean early; public static void discover(){" + body + "} }");
    }
    @Test void declaredCallsBridgeUnknownCollectorsAndKeepNativeMetadata(@TempDir Path work) throws Throwable {
        Map<String, byte[]> classes = new HashMap<>(InjectorExecution.compile(work, sources("""
            String key="unknown:config_consumers";
            fixture.protocol.ConfigApi.metadata(id->"Native "+id);
            if(early)return;
            fixture.protocol.ConfigApi.register("native.Page","native_mod");
            """)));
        byte[] original = classes.get(OWNER); byte[] output = InjectorExecution.transform(injector(), OWNER.replace('/', '.'), original, EnvType.CLIENT);
        assertNotSame(original, output); classes.put(OWNER, output); assertSame(output, injector().transform(OWNER, output, null));
        ClassLoader loader = InjectorExecution.load(classes); assertEquals("", InjectorExecution.verify(output, loader));
        InjectorExecution.invokeStatic(loader.loadClass(OWNER.replace('/', '.')), "discover");
        Class<?> api = loader.loadClass("fixture.protocol.ConfigApi");
        assertEquals(List.of("native_mod=native.Page", "unfamiliar_mod=external.Page"), InjectorExecution.getStatic(api, "registrations"));
        @SuppressWarnings("unchecked") var names = (java.util.function.Function<String,String>) InjectorExecution.getStatic(api, "names");
        assertEquals("Native native_mod", names.apply("native_mod")); assertEquals("Foreign metadata", names.apply("unfamiliar_mod"));
    }
    @Test void aSimilarMethodWithoutEveryDeclaredApiOperationIsRefused(@TempDir Path work) throws Exception {
        for (String body : List.of(
            "fixture.protocol.ConfigApi.metadata(id->id);fixture.protocol.ConfigApi.register(\"Page\",\"id\");",
            "String key=\"unknown:config_consumers\";fixture.protocol.ConfigApi.register(\"Page\",\"id\");",
            "String key=\"unknown:config_consumers\";fixture.protocol.ConfigApi.metadata(id->id);")) {
            byte[] bytes = InjectorExecution.compile(work, sources(body)).get(OWNER);
            assertSame(bytes, injector().transform(OWNER, bytes, null));
        }
    }
}
