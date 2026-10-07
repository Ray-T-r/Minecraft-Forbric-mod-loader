package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;import java.util.*;import java.util.zip.*;import java.security.MessageDigest;
import net.fabricmc.api.EnvType;import net.forbric.api.Ecosystem;import net.forbric.kernel.transform.NativeConstructorFacadeRestorer;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;

/** Exact restoration retains original @Local argument captures and the actual Operation rather than inventing context. */
class NativeConstructorFacadeWeaveTest {
 @TempDir Path work;
 @Test void theSourceWrapperAndArgumentCapturesRunOnceOnTheRestoredFacade()throws Exception{
  Path b=source("Builder.java","""
   package fixture.facade;public class Builder{public static int creates,removes;public final String label;public final Object flags;
    public Builder(String label,Object flags){creates++;this.label=label;this.flags=flags;}public Builder add(int units){return this;}public Builder remove(String tag){removes++;return this;}public String finish(){return label;}}
   """);
  Path nativeSource=work.resolve("native/Facade.java");Files.createDirectories(nativeSource.getParent());Files.writeString(nativeSource,"""
   package fixture.facade;public class Facade{public static String create(String label,Object flags,int units){return new Builder(label,flags).add(units).remove("filter").finish();}}
   """);
  Path nativeJar=WeaveHarness.fixture(work,"native",List.of(b,nativeSource),Map.of(),List.of("-g"));byte[] ref;try(ZipFile z=new ZipFile(nativeJar.toFile())){ref=z.getInputStream(z.getEntry("fixture/facade/Facade.class")).readAllBytes();}
  Path current=source("Facade.java","""
   package fixture.facade;public class Facade{public static String create(String label,Object flags,int units){return piece(new Builder(label,flags),units);}private static String piece(Builder builder,int units){return builder.add(units).remove("filter").finish();}
    public static String probe(){Object flags=new Object();Trace.expected=flags;return create("label",flags,7)+";callback="+Trace.callbacks+";native="+Builder.removes+";constructors="+Builder.creates+";seen="+Trace.seen;}}
   """);
  Path trace=source("Trace.java","package fixture.facade;public class Trace{public static int callbacks;public static Object expected;public static String seen;}");
  Path mixin=source("FacadeMixin.java","""
   package fixture.facade.mixin;import fixture.facade.*;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.At;
   import com.llamalad7.mixinextras.injector.wrapoperation.*;import com.llamalad7.mixinextras.sugar.Local;
   @Mixin(Facade.class)public class FacadeMixin{
    @WrapOperation(method="create",at=@At(value="INVOKE",target="Lfixture/facade/Builder;remove(Ljava/lang/String;)Lfixture/facade/Builder;"),require=0)
    private static Builder wrap(Builder builder,String tag,Operation<Builder> original,@Local(argsOnly=true) String label,@Local(argsOnly=true) Object flags,@Local(argsOnly=true) int units){
     if(flags!=Trace.expected||flags!=builder.flags||!label.equals(builder.label))throw new AssertionError("constructor closure changed arguments");
     Trace.callbacks++;Trace.seen=label+":"+units;return original.call(builder,tag);}
   }
   """);
  String config="facade.mixins.json";Path json=source(config,"{\"required\":false,\"package\":\"fixture.facade.mixin\",\"mixins\":[\"FacadeMixin\"],\"injectors\":{\"defaultRequire\":0}}");
  Path fixture=WeaveHarness.fixture(work,"facade",List.of(b,current,trace,mixin),Map.of(config,json),List.of("-g"));
  String index="# forbric-native-reference-v1\nfixture/facade/Facade\t"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ref))+"\n";
  var restorer=new NativeConstructorFacadeRestorer(path->path.endsWith("index.tsv")&&path.contains("/FABRIC/")?index.getBytes(java.nio.charset.StandardCharsets.UTF_8):path.endsWith("fixture/facade/Facade.class.bin")&&path.contains("/FABRIC/")?ref:null);
  Path restored=work.resolve("restored.jar");try(ZipFile z=new ZipFile(fixture.toFile());var o=new ZipOutputStream(Files.newOutputStream(restored))){for(var entries=z.entries();entries.hasMoreElements();){var e=entries.nextElement();byte[] bytes=z.getInputStream(e).readAllBytes();if(e.getName().equals("fixture/facade/Facade.class"))bytes=restorer.transform("fixture.facade.Facade",bytes,null);o.putNextEntry(new ZipEntry(e.getName()));o.write(bytes);o.closeEntry();}}
  var result=WeaveHarness.run(work,"restored",restored,config,"facade",Ecosystem.FABRIC,EnvType.SERVER,"fixture.facade.Facade","probe",Map.of());
  assertTrue(result.printed(WeaveHarnessMain.DONE+" label;callback=1;native=1;constructors=1;seen=label:7"),result.describe());WeaveHarness.assertWovenAndVerified(result,"fixture/facade/Facade",restored);
  var control=WeaveHarness.run(work,"off",fixture,config,"facade",Ecosystem.FABRIC,EnvType.SERVER,"fixture.facade.Facade","probe",Map.of());
  assertTrue(control.printed(WeaveHarnessMain.DONE+" label;callback=0;native=1;constructors=1;seen=null"),control.describe());
 }
 private Path source(String name,String text)throws Exception{Path path=work.resolve(name);Files.writeString(path,text);return path;}
}
