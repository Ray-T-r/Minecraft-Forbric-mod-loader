package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;
import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The callback runs before native extra context is computed and only on its original non-null return branch. */
class MixinReturnDecorationWeaveTest {
 @TempDir Path work;
 @Test void theOriginalLocalCallbackKeepsItsOrderAndDoesNotRunOnNullReturns()throws Exception {
  Path common=source("Model.java","""
   package fixture.returncontext;
   public class Model {
    public static class Input {public final boolean hit;public Input(boolean hit){this.hit=hit;}}
    public static class State {public final String text;public State(String text){this.text=text;}}
    public static class Mutable {public static int reads;public int value;public Mutable(int value){this.value=value;}public Snapshot freeze(){reads++;return new Snapshot(value);}}
    public record Snapshot(int value) { }
    public record Pair(State state,Snapshot snapshot) {public static Pair of(State state,Snapshot snapshot){return new Pair(state,snapshot);}}
   }
   """);
  Path original=work.resolve("native/Host.java");Files.createDirectories(original.getParent());Files.writeString(original,"""
   package fixture.returncontext;import fixture.returncontext.Model.*;
   public class Host {
    private static State find(Input input){if(!input.hit)return null;Mutable pos=new Mutable(1);State state=new State("hit");return state;}
   }
   """);
  Path nativeJar=WeaveHarness.fixture(work,"return-native",List.of(common,original),Map.of(),List.of("-g"));
  String owner="fixture/returncontext/Host";byte[] nativeBytes;try(ZipFile z=new ZipFile(nativeJar.toFile())){nativeBytes=z.getInputStream(z.getEntry(owner+".class")).readAllBytes();}
  Path binary=work.resolve("native.bin"),index=work.resolve("index.tsv");Files.write(binary,nativeBytes);Files.writeString(index,"# forbric-native-reference-v1\n"+owner+"\t"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(nativeBytes))+"\n");
  Path current=source("Host.java","""
   package fixture.returncontext;import fixture.returncontext.Model.*;
   public class Host {
    public static int callbacks,snapshot=-1;
    private static State find(Input input){Pair pair=locate(input);return pair==null?null:pair.state();}
    private static Pair locate(Input input){if(!input.hit)return null;Mutable pos=new Mutable(1);State state=new State("hit");return Pair.of(state,pos.freeze());}
    public static String probe(){Pair found=locate(new Input(true)),missing=locate(new Input(false));return found.snapshot().value()+";source="+snapshot+";callbacks="+callbacks+";freezes="+Mutable.reads+";missing="+(missing==null);}
   }
   """);
  Path mixin=source("ContextMixin.java","""
   package fixture.returncontext.mixin;
   import fixture.returncontext.*;import fixture.returncontext.Model.*;
   import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
   import com.llamalad7.mixinextras.expression.*;import com.llamalad7.mixinextras.sugar.Local;
   @Mixin(Host.class) public class ContextMixin {
    @Definition(id="state",local=@Local(type=State.class,name="state")) @Expression("return state")
    @Inject(method="find",at=@At("MIXINEXTRAS:EXPRESSION"),require=0)
    private static void callback(CallbackInfoReturnable<State> unused,@Local(name="pos") Mutable pos){Host.callbacks++;pos.value+=7;Host.snapshot=pos.freeze().value();}
   }
   """);
  String config="return-context.mixins.json";Path json=source(config,"""
   {"required":false,"compatibilityLevel":"JAVA_21","package":"fixture.returncontext.mixin","mixins":["ContextMixin"],"injectors":{"defaultRequire":0},"mixinextras":{"minVersion":"0.5.4"}}
   """);
  String prefix="META-INF/forbric/native-reference/FABRIC/";
  Path fixture=WeaveHarness.fixture(work,"return-current",List.of(common,current,mixin),Map.of(config,json,prefix+"index.tsv",index,prefix+owner+".class.bin",binary),List.of("-g"));
  var repaired=WeaveHarness.run(work,"on",fixture,config,"returncontext",Ecosystem.FABRIC,EnvType.CLIENT,owner.replace('/','.'),"probe",Map.of());
  assertTrue(repaired.printed(WeaveHarnessMain.DONE+" 8;source=8;callbacks=1;freezes=2;missing=true"),repaired.describe());
  WeaveHarness.assertWovenAndVerified(repaired,owner,fixture);
  var control=WeaveHarness.run(work,"off",fixture,config,"returncontext",Ecosystem.FABRIC,EnvType.CLIENT,owner.replace('/','.'),"probe",Map.of("forbric.mixinReturnDecorations","off"));
  assertTrue(control.printed(WeaveHarnessMain.DONE+" 1;source=-1;callbacks=0;freezes=1;missing=true"),control.describe());
 }
 private Path source(String name,String text)throws Exception{Path path=work.resolve(name);Files.writeString(path,text);return path;}
}
