package net.forbric.kernel.mixin.weave;
import static org.junit.jupiter.api.Assertions.*;import java.nio.file.*;import java.util.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;
import net.fabricmc.api.EnvType;import net.forbric.api.Ecosystem;import net.forbric.kernel.mixin.MixinResourceContinuationAdapter;

class MixinResourceContinuationWeaveTest {
    @TempDir static Path work;private static Path fixture;private static WeaveHarness.Result on,off,changed;
    @BeforeAll static void weave()throws Exception {
        Path source=Files.createDirectories(work.resolve("source"));List<Path> common=new ArrayList<>();
        common.add(write(source,"Wood.java","package fixture.resource;public final class Wood {private final String name;public Wood(String name){this.name=name;}public String name(){return name;}}"));
        common.add(write(source,"Base.java","package fixture.resource;public class Base {protected final Wood woodType;public Base(Wood wood){woodType=wood;}}"));
        common.add(write(source,"Trace.java","package fixture.resource;import java.util.*;public class Trace {public static final List<String> names=new ArrayList<>();public static int nativeChanges;}"));
        common.add(write(source,"Key.java","""
            package fixture.resource;import java.util.function.UnaryOperator;
            public final class Key {private final String namespace,path;private Key(String namespace,String path){this.namespace=namespace;this.path=path;}
              private static String validNamespace(String namespace,String path){Trace.names.add(path);return namespace;}
              private static String validPath(String namespace,String path){return path;}
              private static Key create(String namespace,String path){return new Key(validNamespace(namespace,path),validPath(namespace,path));}
              public static Key decode(String name){return split(name,':');}
              public static Key defaultKey(String path){return new Key("minecraft",validPath("minecraft",path));}
              private static Key split(String name,char separator){int i=name.indexOf(separator);if(i>=0){String path=name.substring(i+1);
                if(i!=0){String namespace=name.substring(0,i);return create(namespace,path);}return defaultKey(path);}return defaultKey(name);}
              public String namespace(){return namespace;}public String path(){return path;}
              public Key path(String path){return new Key(namespace,validPath(namespace,path));}
              public Key path(UnaryOperator<String> operator){return path(operator.apply(path));}
              public Key prefix(String prefix){return path(prefix+path);}
              public String toString(){return namespace+":"+path;}}
            """));
        Path original=write(work.resolve("original"),"Widget.java","""
            package fixture.resource;public class Widget extends Base {private final Key texture;
              public Widget(Wood wood){super(wood);texture=Key.defaultKey("textures/gui/sheet/"+woodType.name()+".png");}}
            """);
        List<Path> oldSources=new ArrayList<>(common);oldSources.add(original);Path nativeJar=WeaveHarness.fixture(work,"native",oldSources,Map.of());
        byte[] old;try(var jar=new java.util.zip.ZipFile(nativeJar.toFile())){old=jar.getInputStream(jar.getEntry("fixture/resource/Widget.class")).readAllBytes();}
        Path bytes=work.resolve("widget.bin");Files.write(bytes,old);String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(old));
        Path index=work.resolve("index.tsv");Files.writeString(index,"# forbric-native-reference-v1\nfixture/resource/Widget\t"+hash+"\n");
        Path current=write(source,"Widget.java","""
            package fixture.resource;import java.util.*;public class Widget extends Base {private final Key texture;
              public Widget(Wood wood){super(wood);texture=Key.decode(woodType.name()+".png").prefix("textures/gui/sheet/");}
              public String probe(){Trace.names.clear();Widget named=new Widget(new Wood("my_mod:folder/oak"));
                String resource=named.texture.toString();boolean found=Set.of("my_mod:textures/gui/sheet/folder/oak.png").contains(resource);
                String bare=new Widget(new Wood("oak")).texture.toString();String nullable;
                try{nullable=new Widget(new Wood(null)).texture.toString();}catch(NullPointerException expected){nullable="source-npe";}
                return resource+"|found="+found+"|bare="+bare+"|null="+nullable+"|sourceParses="+Trace.names.stream().filter(p->p.equals("folder/oak")).count()+"|nativeChanges="+Trace.nativeChanges;}}
            """);
        Path probe=write(source,"Probe.java","package fixture.resource;public class Probe {public String probe(){return new Widget(new Wood(\"oak\")).probe();}}" );
        Path guest=write(source,"Guest.java","""
            package fixture.resource.mixin;import fixture.resource.*;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;
            import com.llamalad7.mixinextras.injector.wrapoperation.*;
            @Mixin(Widget.class)public abstract class Guest extends Base {private Guest(Wood wood){super(wood);}
              @WrapOperation(method="<init>",at=@At(value="INVOKE",target="Lfixture/resource/Key;defaultKey(Ljava/lang/String;)Lfixture/resource/Key;"),require=0)
              private Key resource(String id,Operation<Key> original){if(woodType.name().indexOf(':')!=-1){Key key=Key.decode(woodType.name());return key.path(path->"textures/gui/sheet/"+path+".png");}return original.call(id);}}
            """);
        Path mutation=write(source,"NativeMutation.java","""
            package fixture.resource.nativechange;import fixture.resource.*;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
            @Mixin(Key.class)public class NativeMutation {@Inject(method="prefix",at=@At("HEAD"))private void changed(String prefix,CallbackInfoReturnable<Key> ci){Trace.nativeChanges++;}}
            """);
        Path config=write(source,"resource.mixins.json","{\"required\":true,\"package\":\"fixture.resource.mixin\",\"compatibilityLevel\":\"JAVA_21\",\"mixins\":[\"Guest\"],\"injectors\":{\"defaultRequire\":0}}");
        Path nativeConfig=write(source,"native.mixins.json","{\"required\":true,\"package\":\"fixture.resource.nativechange\",\"compatibilityLevel\":\"JAVA_21\",\"mixins\":[\"NativeMutation\"]}");
        List<Path> sources=new ArrayList<>(common);sources.addAll(List.of(current,probe,guest,mutation));fixture=WeaveHarness.fixture(work,"resource",sources,Map.of("resource.mixins.json",config,"native.mixins.json",nativeConfig,"META-INF/forbric/native-reference/FABRIC/index.tsv",index,"META-INF/forbric/native-reference/FABRIC/fixture/resource/Widget.class.bin",bytes));
        on=run("on",Map.of());off=run("off",Map.of(MixinResourceContinuationAdapter.PROPERTY,"off"));
        changed=WeaveHarness.run(work,"changed",fixture,List.of(new WeaveHarness.Config("resource.mixins.json","resource",Ecosystem.FABRIC),new WeaveHarness.Config("native.mixins.json","native",Ecosystem.NEOFORGE)),List.of(),EnvType.SERVER,"fixture.resource.Probe","probe",Map.of());
    }
    @Test void crossNamespaceTextureSourceCallbackAndNullFailureAreRestored()throws Exception {
        assertTrue(on.printedLine(WeaveHarnessMain.DONE+" my_mod:textures/gui/sheet/folder/oak.png|found=true|bare=minecraft:textures/gui/sheet/oak.png|null=source-npe|sourceParses=1|nativeChanges=0"),on.describe());WeaveHarness.assertWovenAndVerified(on,"fixture/resource/Widget",fixture);
    }
    @Test void offShowsCorrectNativeResourceButTheLostSourceCallbackAndNullContract()throws Exception {
        assertTrue(off.printedLine(WeaveHarnessMain.DONE+" my_mod:textures/gui/sheet/folder/oak.png|found=true|bare=minecraft:textures/gui/sheet/oak.png|null=minecraft:textures/gui/sheet/null.png|sourceParses=0|nativeChanges=0"),off.describe());WeaveHarness.assertWovenAndVerified(off,"fixture/resource/Widget",fixture);
    }
    @Test void changedFinalNativeResourceApiKeepsNativeOperationAndDoesNotExecuteGuest()throws Exception {
        assertTrue(changed.printedLine(WeaveHarnessMain.DONE+" my_mod:textures/gui/sheet/folder/oak.png|found=true|bare=minecraft:textures/gui/sheet/oak.png|null=minecraft:textures/gui/sheet/null.png|sourceParses=0|nativeChanges=4"),changed.describe());WeaveHarness.assertWovenAndVerified(changed,"fixture/resource/Widget",fixture);
    }
    private static WeaveHarness.Result run(String label,Map<String,String> properties)throws Exception{return WeaveHarness.run(work,label,fixture,"resource.mixins.json","resource",Ecosystem.FABRIC,EnvType.SERVER,"fixture.resource.Probe","probe",properties);}
    private static Path write(Path directory,String name,String source)throws Exception{Files.createDirectories(directory);Path path=directory.resolve(name);Files.writeString(path,source);return path;}
}
