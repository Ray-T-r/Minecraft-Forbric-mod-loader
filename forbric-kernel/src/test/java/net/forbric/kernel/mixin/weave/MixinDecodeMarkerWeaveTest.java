/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinDecodeScopeAdapter;

/**
 * The marker half of {@link MixinDecodeScopeAdapter} through the real service and Mixin, with names of no mod: a
 * {@code @WrapOperation} of {@code Codec.parse} that judges its input and returns {@code DataResult.success} of a private
 * marker when the verdict is false, and a cancellable {@code HEAD} consumer on the result lambda that drops the marker.
 * Both are compiled another way than any released guard — the rejection and the forwarded result go through locals, the
 * consumer returns early on the unequal side, the producer captures a different local — and are judged by what they
 * compute: transported, the input is judged once inside the native parse (the native parse judges it again without), and
 * the marker never reaches the output either way. A consumer that also reads another of its parameters is no closed
 * marker consumer, and neither half is moved.
 */
class MixinDecodeMarkerWeaveTest {
	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result on, off, reading;

	private static final String GUEST = """
			package fixture.marker.mixin;
			import fixture.marker.*;import com.google.gson.*;import com.mojang.serialization.*;import java.util.Map;
			import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;
			import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
			import com.llamalad7.mixinextras.injector.wrapoperation.*;import com.llamalad7.mixinextras.sugar.Local;
			@Mixin(Loader.class)public class NAME {
			 private static final Object DROPPED=new Object();
			 @WrapOperation(method="scan",at=@At(value="INVOKE",target="Lcom/mojang/serialization/Codec;parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"))
			 private static DataResult<?> judge(Codec<?> codec,DynamicOps<?> ops,Object input,Operation<DataResult<?>> original,@Local(argsOnly=true) String id){
			  JsonElement json=(JsonElement)input;
			  if(json.isJsonObject()&&!Gate.evaluate(json.getAsJsonObject())){DataResult<?> dropped=DataResult.success(DROPPED);return dropped;}
			  DataResult<?> parsed=original.call(codec,ops,input);
			  return parsed;
			 }
			 @Inject(method="lambda$scan$0",at=@At("HEAD"),cancellable=true)
			 private static void drop(Map<?,?> out,String id,Object value,CallbackInfo ci){BODY}
			}
			""";

	@BeforeAll static void weave() throws Exception {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("com.google.gson.JsonElement", "package com.google.gson;public class JsonElement{public boolean isJsonObject(){return this instanceof JsonObject;}public JsonObject getAsJsonObject(){return (JsonObject)this;}}");
		files.put("com.google.gson.JsonObject", "package com.google.gson;public final class JsonObject extends JsonElement{public final boolean keep;public JsonObject(boolean keep){this.keep=keep;}}");
		files.put("com.mojang.serialization.DynamicOps", "package com.mojang.serialization;public interface DynamicOps<T>{}");
		files.put("com.mojang.serialization.DataResult", "package com.mojang.serialization;public final class DataResult<R>{private final R value;private DataResult(R value){this.value=value;}"
				+ "public static <R> DataResult<R> success(R value){return new DataResult<>(value);}public DataResult<R> ifSuccess(java.util.function.Consumer<? super R> consumer){consumer.accept(value);return this;}}");
		files.put("com.mojang.serialization.Codec", "package com.mojang.serialization;public interface Codec<A>{<T> DataResult<A> parse(DynamicOps<T> ops,T input);}");
		files.put("fixture.marker.Gate", "package fixture.marker;import com.google.gson.*;public class Gate{public static int calls;public static boolean evaluate(JsonObject json){calls++;return json.keep;}}");
		// The native parse judges the input itself unless the guest's guard already did inside this parse.
		files.put("net.forbric.kernel.runtime.MarkerCodec", """
				package net.forbric.kernel.runtime;import com.google.gson.*;import com.mojang.serialization.*;import java.lang.invoke.*;import fixture.marker.Gate;
				public class MarkerCodec implements Codec<String>{private static final MethodHandle EVALUATOR;static{try{EVALUATOR=MethodHandles.lookup().findStatic(Gate.class,"evaluate",MethodType.methodType(boolean.class,JsonObject.class));}catch(Exception e){throw new AssertionError(e);}}
				 public <T> DataResult<String> parse(DynamicOps<T> ops,T input){if(!KernelSourceDecodeScopes.alreadyJudged(input,EVALUATOR))Gate.evaluate(((JsonElement)input).getAsJsonObject());return DataResult.success("value");}}
				""");
		files.put("fixture.marker.Loader", """
				package fixture.marker;import com.google.gson.*;import com.mojang.serialization.*;import java.util.*;
				public class Loader{
				 static final Codec<?> CODEC=new net.forbric.kernel.runtime.MarkerCodec();static final DynamicOps<JsonElement> OPS=new DynamicOps<>(){};
				 public static void scan(Map<String,Object> out,String id,JsonElement json){CODEC.parse(OPS,json).ifSuccess(value->out.put(id,value));}
				 public static String probe(){StringBuilder result=new StringBuilder();for(boolean keep:new boolean[]{true,false}){Gate.calls=0;Map<String,Object> out=new TreeMap<>();scan(out,"a",new JsonObject(keep));result.append(keep).append(':').append(out.get("a")==null?"-":out.get("a") instanceof String s?s:"marker").append(':').append(Gate.calls).append(';');}return result.toString();}}
				""");
		files.put("fixture.marker.mixin.Guest", GUEST.replace("NAME", "Guest").replace("BODY", "if(value!=DROPPED)return;ci.cancel();"));
		files.put("fixture.marker.reading.Reading", GUEST.replace("fixture.marker.mixin", "fixture.marker.reading").replace("NAME", "Reading")
				.replace("BODY", "if(value!=DROPPED)return;if(out!=null)ci.cancel();"));
		List<Path> sources = new ArrayList<>();
		for (var file : files.entrySet()) {
			Path path = work.resolve("src").resolve(file.getKey().replace('.', '/') + ".java");
			Files.createDirectories(path.getParent());
			Files.writeString(path, file.getValue());
			sources.add(path);
		}
		sources.add(Path.of(System.getProperty("user.dir"), "src/runtime/java/net/forbric/kernel/runtime/KernelSourceDecodeScopes.java"));
		Path guest = work.resolve("marker.mixins.json"), other = work.resolve("reading.mixins.json");
		Files.writeString(guest, "{\"required\":true,\"package\":\"fixture.marker.mixin\",\"compatibilityLevel\":\"JAVA_21\",\"mixins\":[\"Guest\"],\"mixinextras\":{\"minVersion\":\"0.5.4\"}}");
		Files.writeString(other, "{\"required\":true,\"package\":\"fixture.marker.reading\",\"compatibilityLevel\":\"JAVA_21\",\"mixins\":[\"Reading\"],\"mixinextras\":{\"minVersion\":\"0.5.4\"}}");
		fixture = WeaveHarness.fixture(work, "marker", sources, Map.of("marker.mixins.json", guest, "reading.mixins.json", other), List.of("-g"));
		on = WeaveHarness.run(work, "on", fixture, "marker.mixins.json", "marker", Ecosystem.FABRIC, EnvType.SERVER, "fixture.marker.Loader", "probe", Map.of());
		off = WeaveHarness.run(work, "off", fixture, "marker.mixins.json", "marker", Ecosystem.FABRIC, EnvType.SERVER, "fixture.marker.Loader", "probe",
				Map.of(MixinDecodeScopeAdapter.PROPERTY, "off"));
		reading = WeaveHarness.run(work, "reading", fixture, "reading.mixins.json", "marker", Ecosystem.FABRIC, EnvType.SERVER, "fixture.marker.Loader", "probe", Map.of());
	}

	@Test void theGuardAndItsMarkerConsumerWrittenAnotherWayAreTransported() throws Exception {
		assertTrue(on.printedLine(WeaveHarnessMain.DONE + " true:value:1;false:-:1;"), on.describe());
		assertTrue(on.printed("retains source decode guards and their closed marker consumers"), on.describe());
		WeaveHarness.assertWovenAndVerified(on, "fixture/marker/Loader", fixture);
	}

	@Test void switchedOffTheNativeParseJudgesTheInputAgain() {
		assertTrue(off.printedLine(WeaveHarnessMain.DONE + " true:value:2;false:-:1;"), off.describe());
	}

	@Test void aConsumerReadingAnotherParameterMovesNeitherHalf() throws Exception {
		assertTrue(reading.printedLine(WeaveHarnessMain.DONE + " true:value:2;false:-:1;"), reading.describe());
		assertFalse(reading.printed("retains source decode guards"), reading.describe());
		WeaveHarness.assertWovenAndVerified(reading, "fixture/marker/Loader", fixture);
	}
}
