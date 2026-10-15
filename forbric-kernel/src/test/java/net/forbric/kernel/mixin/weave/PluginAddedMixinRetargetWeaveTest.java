/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.KernelGuestMixinAdapter;

/**
 * KernelGuestMixinAdapter's late judgment through the real weave: a guest {@code @WrapOperation} on the {@code Faces.quad}
 * call of {@code Geometry.bake(List, String)} (Create's normals fix on {@code UnbakedCuboidGeometry.bake}, in miniature) in a
 * mixin its config lists nowhere — the config's plugin adds it from {@code getMixins()}, as Create's client plugin adds its
 * model-baking mixins — on a merged class whose carrier kept that signature as a builder stub over a
 * {@code bake(Builder, List, String, Function)} overload that makes the calls. A listed mixin is retargeted there at config
 * time; this one is judged when Mixin reads it, and every quad is wrapped, as natively. With
 * {@code -Dforbric.lateMixinJudgment=off} nothing judges it: the wrap binds the stub and wraps nothing, without a word.
 */
class PluginAddedMixinRetargetWeaveTest {
	private static final String CONFIG = "builderstub.mixins.json";
	private static final String MOD = "normalsfix";
	private static final String TARGET = "net/minecraft/fixture/Geometry";
	private static final String WRAPPED = "[BuilderStub] quads=[A:S!, B:S!] wrapped=2";
	private static final String PLAIN = "[BuilderStub] quads=[a:s!, b:s!] wrapped=0";
	private static final String MOVED_LOG = "[Forbric/Mixin] retargeted guest mixin normalsfix (builderstub.mixins.json):GeometryMixin";
	private static final String LATE_LOG = "was added by its config's plugin, so it is judged as Mixin reads it";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, off, nativeRun;

	private static final String FACES = """
			package net.minecraft.fixture;
			public class Faces { public static String quad(String element,String sprite){return element+":"+sprite;} }
			""";

	/** The merged class: vanilla's bake kept as a builder stub over the carrier's overload. */
	private static final String MERGED = """
			package net.minecraft.fixture;
			import java.util.ArrayList;
			import java.util.List;
			import java.util.function.Function;
			public class Geometry {
			 public static int wrapped;
			 public static class Builder { final List<String> quads=new ArrayList<>(); public List<String> build(){return quads;} }
			 public static List<String> bake(List<String> elements,String sprite){
			  Builder builder=new Builder();
			  bake(builder,elements,sprite,s->s+"!");
			  return builder.build();
			 }
			 public static void bake(Builder builder,List<String> elements,String sprite,Function<String,String> look){
			  for(String element:elements)builder.quads.add(Faces.quad(element,look.apply(sprite)));
			 }
			 public void run(){System.out.println("[BuilderStub] quads="+bake(List.of("a","b"),"s")+" wrapped="+wrapped);}
			}
			""";

	private static final String NATIVE = """
			package net.minecraft.fixture;
			import java.util.ArrayList;
			import java.util.List;
			public class Geometry {
			 public static int wrapped;
			 public static List<String> bake(List<String> elements,String sprite){
			  List<String> quads=new ArrayList<>();
			  for(String element:elements)quads.add(Faces.quad(element,sprite+"!"));
			  return quads;
			 }
			 public void run(){System.out.println("[BuilderStub] quads="+bake(List.of("a","b"),"s")+" wrapped="+wrapped);}
			}
			""";

	private static final String MIXIN = """
			package fixture.builderstub.mixin;
			import net.minecraft.fixture.Geometry;
			import org.spongepowered.asm.mixin.Mixin;
			import org.spongepowered.asm.mixin.injection.At;
			import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
			import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
			@Mixin(Geometry.class)
			public class GeometryMixin {
			 @WrapOperation(method="bake(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;",at=@At(value="INVOKE",target="Lnet/minecraft/fixture/Faces;quad(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"))
			 private static String normals(String element,String sprite,Operation<String> original){Geometry.wrapped++;return original.call(element,sprite).toUpperCase();}
			}
			""";


	private static final String PLUGIN = """
			package fixture.builderstub.mixin;
			import java.util.List;
			import java.util.Set;
			import org.objectweb.asm.tree.ClassNode;
			import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
			import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
			public class Plugin implements IMixinConfigPlugin {
			 public void onLoad(String mixinPackage){}
			 public String getRefMapperConfig(){return null;}
			 public boolean shouldApplyMixin(String targetClassName,String mixinClassName){return true;}
			 public void acceptTargets(Set<String> myTargets,Set<String> otherTargets){}
			 public List<String> getMixins(){return List.of("GeometryMixin");}
			 public void preApply(String targetClassName,ClassNode targetClass,String mixinClassName,IMixinInfo mixinInfo){}
			 public void postApply(String targetClassName,ClassNode targetClass,String mixinClassName,IMixinInfo mixinInfo){}
			}
			""";

	@BeforeAll static void weaveEachWay() throws Exception {
		Path json = work.resolve(CONFIG);
		Files.writeString(json, "{\"required\":true,\"package\":\"fixture.builderstub.mixin\",\"plugin\":\"fixture.builderstub.mixin.Plugin\",\"mixins\":[],\"injectors\":{\"defaultRequire\":1}}");
		Path mixin = source("mixin/GeometryMixin.java", MIXIN), plugin = source("mixin/Plugin.java", PLUGIN);
		Path nativeFixture = WeaveHarness.fixture(work, "native-builderstub", List.of(source("native/Faces.java", FACES), source("native/Geometry.java", NATIVE), mixin, plugin), Map.of(CONFIG, json));
		Path current = WeaveHarness.fixture(work, "builderstub", List.of(source("merged/Faces.java", FACES), source("merged/Geometry.java", MERGED), mixin, plugin), Map.of(CONFIG, json));
		fixture = NativeWeaveReferences.with(work, current, Map.of(TARGET, NativeWeaveReferences.classes(nativeFixture).get(TARGET)));
		nativeRun = run("native", nativeFixture, Map.of());
		moved = run("moved", fixture, Map.of());
		off = run("off", fixture, Map.of(KernelGuestMixinAdapter.LATE_PROPERTY, "off"));
	}

	@Test void everyQuadIsWrappedAsItIsNatively() throws Exception {
		assertTrue(nativeRun.printedLine(WRAPPED), nativeRun.describe());
		assertTrue(movedHolds(moved), moved.describe());
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	@Test void switchedOffTheWrapBindsTheStubAndWrapsNothing() {
		assertTrue(offHolds(off), off.describe());
	}

	/** The switch is the runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryAssertion() {
		assertTrue(movedHolds(moved) && !movedHolds(off), "the move predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(moved), "the control predicate does not separate the runs");
	}

	private static boolean movedHolds(WeaveHarness.Result run) {
		return run.printedLine(WRAPPED) && run.printed(LATE_LOG) && run.printed(MOVED_LOG) && losses(run).isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		// Silent, which is the defect: the wrap binds the stub and wraps nothing, and no finding says so.
		return run.printedLine(PLAIN) && !run.printed(LATE_LOG) && !run.printed(MOVED_LOG);
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.required()).toList();
	}

	private static Path source(String name, String text) throws Exception {
		Path file = work.resolve("src").resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}

	private static WeaveHarness.Result run(String label, Path jar, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, jar, CONFIG, MOD, Ecosystem.FABRIC, EnvType.CLIENT, "net.minecraft.fixture.Geometry", "run", properties);
	}
}
