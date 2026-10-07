package net.forbric.kernel.mixin.weave;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;import java.util.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;
import net.fabricmc.api.EnvType;import net.forbric.api.Ecosystem;import net.forbric.kernel.mixin.MixinPredicateDelegateAdapter;

class MixinPredicateDelegateWeaveTest {
    @TempDir static Path work;private static Path fixture;private static WeaveHarness.Result on,off,mutated;
    @BeforeAll static void weave()throws Exception {
        Path dir=Files.createDirectories(work.resolve("source"));List<Path> common=new ArrayList<>();
        common.add(write(dir,"Id.java","""
            package fixture.delegate;
            public final class Id {public final String namespace,path;public int reads;
              public Id(String namespace,String path){this.namespace=namespace;this.path=path;}
              public String namespace(){reads++;return namespace;}public String path(){return path;}
              public String toString(){return namespace+":"+path;}}
            """));
        common.add(write(dir,"Value.java","package fixture.delegate; public record Value(String category){}"));
        common.add(write(dir,"Registry.java","""
            package fixture.delegate;import java.util.*;
            public class Registry {public static final Map<Id,Value> ENTRIES=new LinkedHashMap<>();
              public static Map<Id,Value> entries(){return Map.copyOf(ENTRIES);}}
            """));
        common.add(write(dir,"Carrier.java","""
            package fixture.delegate;import java.util.*;import java.util.function.*;
            public class Carrier {public static int factories,predicates,otherChecks;
              public static void deliver(String query,Consumer<String> category,Consumer<Id> item){factories++;
                Map<String,List<Id>> groups=new TreeMap<>();
                Registry.entries().forEach((id,value)->{if(accept(query,id))groups.computeIfAbsent(value.category(),x->new ArrayList<>()).add(id);});
                groups.forEach((name,ids)->{category.accept(name);ids.sort((a,b)->a.toString().compareTo(b.toString()));ids.forEach(item);});}
              private static boolean accept(String query,Id id){predicates++;String namespace=id.namespace();return namespace.startsWith(query)||id.path().startsWith(query);}}
            """));
        String header="package fixture.delegate;import java.util.*;public class Menu {public final List<String> shown=new ArrayList<>();";
        Path original=write(work.resolve("original"),"Menu.java",header+"public void refresh(String query){shown.clear();for(var entry:Registry.entries().entrySet()){if(entry.getKey().path().contains(query))shown.add(entry.getKey().toString());}}}");
        List<Path> oldSources=new ArrayList<>(common);oldSources.add(original);Path nativeJar=WeaveHarness.fixture(work,"native",oldSources,Map.of(),List.of("-g"));
        byte[] originalBytes;try(var jar=new java.util.zip.ZipFile(nativeJar.toFile())){originalBytes=jar.getInputStream(jar.getEntry("fixture/delegate/Menu.class")).readAllBytes();}
        Path bytes=work.resolve("menu.bin");Files.write(bytes,originalBytes);String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(originalBytes));
        Path index=work.resolve("index.tsv");Files.writeString(index,"# forbric-native-reference-v1\nfixture/delegate/Menu\t"+hash+"\n");
        Path current=write(dir,"Menu.java",header+"""
            public void refresh(String query){shown.clear();Carrier.deliver(query,category->shown.add(category),id->shown.add(id.toString()));}
            public String probe(){Id id=new Id("my_mod","some_metric");Registry.ENTRIES.put(id,new Value("category"));refresh("od");
              return shown+"|native="+Carrier.factories+"|predicate="+Carrier.predicates+"|namespaceReads="+id.reads+"|other="+Carrier.otherChecks;}}
            """);
        Path guest=write(dir,"Guest.java","""
            package fixture.delegate.mixin;import java.util.*;import fixture.delegate.*;
            import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;
            import com.llamalad7.mixinextras.injector.wrapoperation.*;import com.llamalad7.mixinextras.sugar.Local;
            @Mixin(Menu.class) public class Guest {
              @WrapOperation(method="refresh",at=@At(value="INVOKE",target="Ljava/lang/String;contains(Ljava/lang/CharSequence;)Z"),require=0)
              private boolean decorate(String instance,CharSequence query,Operation<Boolean> original,@Local(name="entry") Map.Entry<Id,Value> entry){
                String namespace=entry.getKey().namespace();return original.call(instance,query)||!"default".equals(namespace)&&namespace.contains(query);}}
            """);
        Path other=write(dir,"NativeMutation.java","""
            package fixture.delegate.mutation;import fixture.delegate.*;import org.spongepowered.asm.mixin.Mixin;
            import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
            @Mixin(Carrier.class) public class NativeMutation {
              @Inject(method="accept",at=@At("HEAD"))private static void modify(String query,Id id,CallbackInfoReturnable<Boolean> ci){Carrier.otherChecks++;}}
            """);
        Path config=write(dir,"delegate.mixins.json","{\"required\":true,\"package\":\"fixture.delegate.mixin\",\"compatibilityLevel\":\"JAVA_21\",\"mixins\":[\"Guest\"],\"injectors\":{\"defaultRequire\":0}}");
        Path nativeConfig=write(dir,"native.mixins.json","{\"required\":true,\"package\":\"fixture.delegate.mutation\",\"compatibilityLevel\":\"JAVA_21\",\"mixins\":[\"NativeMutation\"]}");
        List<Path> sources=new ArrayList<>(common);sources.addAll(List.of(current,guest,other));
        fixture=WeaveHarness.fixture(work,"delegate",sources,Map.of("delegate.mixins.json",config,"native.mixins.json",nativeConfig,"META-INF/forbric/native-reference/FABRIC/index.tsv",index,"META-INF/forbric/native-reference/FABRIC/fixture/delegate/Menu.class.bin",bytes),List.of("-g"));
        on=run("on",Map.of());off=run("off",Map.of(MixinPredicateDelegateAdapter.PROPERTY,"off"));
        mutated=WeaveHarness.run(work,"mutated",fixture,List.of(new WeaveHarness.Config("delegate.mixins.json","delegate",Ecosystem.FABRIC),new WeaveHarness.Config("native.mixins.json","native",Ecosystem.NEOFORGE)),List.of(),EnvType.SERVER,"fixture.delegate.Menu","probe",Map.of());
    }
    @Test void sourceDecoratorNativePredicateAndConsumersExecuteOnceWithTheNewNamespaceHit()throws Exception {
        assertTrue(on.printedLine(WeaveHarnessMain.DONE+" [category, my_mod:some_metric]|native=1|predicate=1|namespaceReads=2|other=0"),on.describe());WeaveHarness.assertWovenAndVerified(on,"fixture/delegate/Menu",fixture);
    }
    @Test void offRetainsTheOriginalNativeNegativeResult()throws Exception {
        assertTrue(off.printedLine(WeaveHarnessMain.DONE+" []|native=1|predicate=1|namespaceReads=1|other=0"),off.describe());WeaveHarness.assertWovenAndVerified(off,"fixture/delegate/Menu",fixture);
    }
    @Test void finalNativeCallbackMutationRunsOriginalOperationOnceAndNoGuestGetter()throws Exception {
        assertTrue(mutated.printedLine(WeaveHarnessMain.DONE+" []|native=1|predicate=1|namespaceReads=1|other=1"),mutated.describe());WeaveHarness.assertWovenAndVerified(mutated,"fixture/delegate/Menu",fixture);
    }
    private static WeaveHarness.Result run(String label,Map<String,String> properties)throws Exception{return WeaveHarness.run(work,label,fixture,"delegate.mixins.json","delegate",Ecosystem.FABRIC,EnvType.SERVER,"fixture.delegate.Menu","probe",properties);}
    private static Path write(Path directory,String name,String source)throws Exception{Files.createDirectories(directory);Path path=directory.resolve(name);Files.writeString(path,source);return path;}
}
