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
import net.forbric.kernel.mixin.MixinBuilderStubRebind;

/**
 * MixinBuilderStubRebind through the real weave: a guest {@code @WrapOperation} on the {@code Faces.quad} call of
 * {@code Geometry.bake(List, String)} that also reads the element being baked as a by-type {@code @Local} (Create's normals
 * fix on {@code UnbakedCuboidGeometry.bake}, which reads the {@code CuboidModelElement}), on a merged class whose carrier
 * kept that signature as a builder stub over a {@code bake(Builder, List, String, Function)} overload that makes the calls.
 * Natively, and moved to the overload, every quad is wrapped with its element. With {@code -Dforbric.builderStubRebind=off}
 * the wrap binds the stub and wraps nothing.
 */
class MixinBuilderStubRebindWeaveTest {
	private static final String CONFIG = "builderstub.mixins.json";
	private static final String MOD = "normalsfix";
	private static final String TARGET = "net/minecraft/fixture/Geometry";
	private static final String WRAPPED = "[BuilderStub] quads=[A:S!, B:S!] wrapped=2";
	private static final String PLAIN = "[BuilderStub] quads=[a:s!, b:s!] wrapped=0";
	private static final String MOVED_LOG = "[Forbric/Mixin] fixture.builderstub.mixin.GeometryMixin: normals now injects into net.minecraft.fixture.Geometry.bake";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, off, nativeRun;

	private static final String FACES = """
			package net.minecraft.fixture;
			public class Faces { public static String quad(String element,String sprite){return element+":"+sprite;} }
			""";

	private static final String ELEMENT = """
			package net.minecraft.fixture;
			public class Element { public final String name; public Element(String name){this.name=name;} }
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
			 public static List<String> bake(List<Element> elements,String sprite){
			  Builder builder=new Builder();
			  bake(builder,elements,sprite,s->s+"!");
			  return builder.build();
			 }
			 public static void bake(Builder builder,List<Element> elements,String sprite,Function<String,String> look){
			  for(Element element:elements)builder.quads.add(Faces.quad(element.name,look.apply(sprite)));
			 }
			 public void run(){System.out.println("[BuilderStub] quads="+bake(List.of(new Element("a"),new Element("b")),"s")+" wrapped="+wrapped);}
			}
			""";

	private static final String NATIVE = """
			package net.minecraft.fixture;
			import java.util.ArrayList;
			import java.util.List;
			public class Geometry {
			 public static int wrapped;
			 public static List<String> bake(List<Element> elements,String sprite){
			  List<String> quads=new ArrayList<>();
			  for(Element element:elements)quads.add(Faces.quad(element.name,sprite+"!"));
			  return quads;
			 }
			 public void run(){System.out.println("[BuilderStub] quads="+bake(List.of(new Element("a"),new Element("b")),"s")+" wrapped="+wrapped);}
			}
			""";

	private static final String MIXIN = """
			package fixture.builderstub.mixin;
			import net.minecraft.fixture.Element;
			import net.minecraft.fixture.Geometry;
			import com.llamalad7.mixinextras.sugar.Local;
			import org.spongepowered.asm.mixin.Mixin;
			import org.spongepowered.asm.mixin.injection.At;
			import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
			import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
			@Mixin(Geometry.class)
			public class GeometryMixin {
			 @WrapOperation(method="bake(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;",at=@At(value="INVOKE",target="Lnet/minecraft/fixture/Faces;quad(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"))
			 private static String normals(String element,String sprite,Operation<String> original,@Local Element baked){Geometry.wrapped++;return original.call(baked.name,sprite).toUpperCase();}
			}
			""";




	@BeforeAll static void weaveEachWay() throws Exception {
		Path json = work.resolve(CONFIG);
		Files.writeString(json, "{\"required\":true,\"package\":\"fixture.builderstub.mixin\",\"mixins\":[\"GeometryMixin\"],\"injectors\":{\"defaultRequire\":1}}");
		Path mixin = source("mixin/GeometryMixin.java", MIXIN);
		Path nativeFixture = WeaveHarness.fixture(work, "native-builderstub", List.of(source("native/Faces.java", FACES), source("native/Element.java", ELEMENT), source("native/Geometry.java", NATIVE), mixin), Map.of(CONFIG, json), List.of("-g"));
		Path current = WeaveHarness.fixture(work, "builderstub", List.of(source("merged/Faces.java", FACES), source("merged/Element.java", ELEMENT), source("merged/Geometry.java", MERGED), mixin), Map.of(CONFIG, json), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, current, Map.of(TARGET, NativeWeaveReferences.classes(nativeFixture).get(TARGET)));
		nativeRun = run("native", nativeFixture, Map.of());
		moved = run("moved", fixture, Map.of());
		off = run("off", fixture, Map.of(MixinBuilderStubRebind.PROPERTY, "off"));
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
		// The config-time preflight still calls the mixin PARTIAL (it reads the stub); what counts is the woven class.
		return run.printedLine(WRAPPED) && run.printed(MOVED_LOG);
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printedLine(PLAIN) && !run.printed(MOVED_LOG);
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
