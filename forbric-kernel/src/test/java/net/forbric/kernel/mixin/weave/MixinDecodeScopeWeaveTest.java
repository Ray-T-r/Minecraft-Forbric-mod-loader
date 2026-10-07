package net.forbric.kernel.mixin.weave;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;import java.util.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import net.fabricmc.api.EnvType;import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinDecodeScopeAdapter;
/** The lexical source guard runs through the actual service, Mixin and final dispatch witness. */
class MixinDecodeScopeWeaveTest {
 @TempDir Path work;
 @Test void trueFalseAndExceptionPreserveOneJudgementAndScopeCleanup()throws Exception{
  Map<String,String> files=new LinkedHashMap<>();
  files.put("com.google.gson.JsonElement","package com.google.gson;public class JsonElement{public boolean isJsonObject(){return this instanceof JsonObject;}public JsonObject getAsJsonObject(){return (JsonObject)this;}}");
  files.put("com.google.gson.JsonObject","package com.google.gson;public final class JsonObject extends JsonElement{public final int mode;public JsonObject(int mode){this.mode=mode;}}");
  files.put("fixture.decode.Gate","package fixture.decode;import com.google.gson.*;public class Gate{public static int calls;public static boolean evaluate(JsonObject json){calls++;if(json.mode==2)throw new IllegalStateException(\"source-error\");return json.mode!=0;}}");
  files.put("net.forbric.kernel.runtime.ScopeBridge","""
   package net.forbric.kernel.runtime;import com.google.gson.*;import java.lang.invoke.*;import fixture.decode.Gate;
   public class ScopeBridge {private static MethodHandle evaluator;static{try{evaluator=MethodHandles.lookup().findStatic(Gate.class,"evaluate",MethodType.methodType(boolean.class,JsonObject.class));}catch(Exception e){throw new AssertionError(e);}}
    public static String parse(JsonElement input){boolean owned=KernelSourceDecodeScopes.alreadyJudged(input,evaluator);if(!owned)Gate.evaluate(input.getAsJsonObject());int mode=input.getAsJsonObject().mode;
     if(mode==3){fixture.decode.Target.load(new JsonObject(1));if(owned&&!KernelSourceDecodeScopes.alreadyJudged(input,evaluator))throw new AssertionError("parent scope lost");}
     if(mode==4){Thread worker=new Thread(()->{if(KernelSourceDecodeScopes.alreadyJudged(input,evaluator))throw new AssertionError("scope crossed threads");Gate.evaluate(input.getAsJsonObject());});worker.start();try{worker.join();}catch(InterruptedException e){throw new AssertionError(e);}}
     if(mode==5)throw new IllegalArgumentException("native-error");return "value";}}
   """);
  files.put("fixture.decode.Target","""
   package fixture.decode;import com.google.gson.*;import net.forbric.kernel.runtime.ScopeBridge;
   public class Target{public static String load(JsonElement input){JsonElement json=input;return ScopeBridge.parse(json);}
    public static String probe(){StringBuilder result=new StringBuilder();for(int mode:new int[]{1,0,2,1,3,4,5,1}){Gate.calls=0;String value;try{value=load(new JsonObject(mode));}catch(RuntimeException e){value=e.getMessage();}result.append(mode).append(':').append(value).append(':').append(Gate.calls).append(';');}return result.toString();}}
   """);
  files.put("fixture.decode.mixin.Guest","""
   package fixture.decode.mixin;import fixture.decode.*;import com.google.gson.*;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;import com.llamalad7.mixinextras.sugar.Local;
   @Mixin(Target.class)public class Guest{@Inject(method="load",at=@At(value="INVOKE",target="Lnet/forbric/kernel/runtime/ScopeBridge;parse(Lcom/google/gson/JsonElement;)Ljava/lang/String;"),cancellable=true)
    private static void guard(JsonElement input,CallbackInfoReturnable<String>ci,@Local(name="json")JsonElement json){if(json.isJsonObject()&&!Gate.evaluate(json.getAsJsonObject()))ci.setReturnValue("disabled");}}
   """);
  List<Path> sources=new ArrayList<>();for(var file:files.entrySet()){Path path=work.resolve("src").resolve(file.getKey().replace('.','/')+".java");Files.createDirectories(path.getParent());Files.writeString(path,file.getValue());sources.add(path);}
  sources.add(Path.of(System.getProperty("user.dir"),"src/runtime/java/net/forbric/kernel/runtime/KernelSourceDecodeScopes.java"));
  String config="decode-scope.mixins.json";Path json=work.resolve(config);Files.writeString(json,"{\"required\":true,\"package\":\"fixture.decode.mixin\",\"mixins\":[\"Guest\"]}");
  Path fixture=WeaveHarness.fixture(work,"decode",sources,Map.of(config,json),List.of("-g"));
  var on=WeaveHarness.run(work,"on",fixture,config,"decode",Ecosystem.FABRIC,EnvType.SERVER,"fixture.decode.Target","probe",Map.of());
  assertTrue(on.printedLine(WeaveHarnessMain.DONE+" 1:value:1;0:disabled:1;2:source-error:1;1:value:1;3:value:2;4:value:2;5:native-error:1;1:value:1;"),on.describe());WeaveHarness.assertWovenAndVerified(on,"fixture/decode/Target",fixture);
  var off=WeaveHarness.run(work,"off",fixture,config,"decode",Ecosystem.FABRIC,EnvType.SERVER,"fixture.decode.Target","probe",Map.of(MixinDecodeScopeAdapter.PROPERTY,"off"));
  assertTrue(off.printedLine(WeaveHarnessMain.DONE+" 1:value:2;0:disabled:1;2:source-error:1;1:value:2;3:value:4;4:value:3;5:native-error:2;1:value:2;"),off.describe());
 }
}
