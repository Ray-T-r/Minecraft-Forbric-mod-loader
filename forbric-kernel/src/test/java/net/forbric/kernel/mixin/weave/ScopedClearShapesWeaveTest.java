/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.ScopedClearRelayInjector;

/**
 * Every shape a clear-all veto has been written in, through the real Mixin and MixinExtras weave, on the scoped clear relay
 * alone: a wrap of the native {@code Map.clear()} binds the clear the relay puts back, runs its own code on what the native
 * clear would cover, and leaves the field and the values handed on exactly as natively.
 *
 * <p>These shapes were once judged one by one, re-hosted on the carrier's per-key question when "per entry" could be proved and
 * refused otherwise (a decision taken from the first entry, a loop left early, a count carried across entries). The relay
 * needs no such judgment: the guest's code runs as written, on the native clear's place, so the shapes that could never be
 * re-hosted run natively too. The host and its six methods share no name with any game or mod; each guest wraps the clear of
 * one method. With {@code -Dforbric.scopedClearRelay=off} no guest binds.
 */
@net.forbric.kernel.transform.ExecutesInjector(ScopedClearRelayInjector.class)
class ScopedClearShapesWeaveTest {
	private static final String CONFIG = "scopedshapes.mixins.json";
	private static final String MOD = "shelfkeepers";
	private static final String TARGET = "net/minecraft/fixture/Shelf";
	private static final List<String> SHAPES = List.of("snapshot", "prune", "stream", "first", "early", "count");

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result nativeRun, relayed, off;

	private static String host(boolean merged) {
		StringBuilder methods = new StringBuilder();
		for (String shape : SHAPES) {
			String field = shape + "Jar";
			methods.append(" public final Map<String,String> ").append(field).append("=new LinkedHashMap<>();\n");
			methods.append(" public boolean ").append(shape).append("(){\n  if(").append(field).append(".isEmpty())return false;\n");
			if (merged) {
				methods.append("  Map<String,String> map=new LinkedHashMap<>(").append(field).append(".size());\n")
						.append("  for(Map.Entry<String,String> e:").append(field).append(".entrySet())if(!Warden.keeps(e.getKey()))map.put(e.getKey(),e.getValue());\n")
						.append("  map.keySet().forEach(").append(field).append("::remove);\n")
						.append("  handedOn.addAll(map.values());\n");
			} else {
				methods.append("  Map<String,String> copy=new LinkedHashMap<>(").append(field).append(");\n")
						.append("  ").append(field).append(".clear();\n")
						.append("  handedOn.addAll(copy.values());\n");
			}
			methods.append("  return true;\n }\n");
		}
		StringBuilder run = new StringBuilder();
		for (String shape : SHAPES) {
			run.append("  {Shelf s=new Shelf();for(String k:List.of(\"apple\",\"pinned\",\"rust\",\"keep_me\"))s.").append(shape)
					.append("Jar.put(k,\"v:\"+k);s.").append(shape).append("();System.out.println(\"[Shelf] ").append(shape)
					.append(" handedOn=\"+s.handedOn+\" left=\"+s.").append(shape).append("Jar.keySet());}\n");
			run.append("  {Shelf s=new Shelf();for(String k:List.of(\"apple\",\"pinned\",\"rust\"))s.").append(shape)
					.append("Jar.put(k,\"v:\"+k);s.").append(shape).append("();System.out.println(\"[Shelf] ").append(shape)
					.append(" plain handedOn=\"+s.handedOn+\" left=\"+s.").append(shape).append("Jar.keySet());}\n");
		}
		return "package net.minecraft.fixture;\nimport java.util.*;\npublic class Shelf {\n final List<String> handedOn=new ArrayList<>();\n" + methods
				+ " public void run(){\n" + run + " }\n}\n";
	}

	private static final String WARDEN = """
			package net.minecraft.fixture;
			public class Warden { public static boolean keeps(String key){return key.equals("keep_me");} }
			""";

	/** Each guest wraps the clear of one method, in one of the shapes a clear-all veto has been written in. */
	private static final String GUESTS = """
			package fixture.scopedshapes.mixin;
			import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
			import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
			import java.util.*;
			import java.util.stream.Collectors;
			import net.minecraft.fixture.Shelf;
			import org.spongepowered.asm.mixin.Mixin;
			import org.spongepowered.asm.mixin.injection.At;
			@Mixin(Shelf.class)
			public class ShelfKeepersMixin {
			 private final String tag="r";
			 // A snapshot, the original clear, then put back what the question keeps.
			 @WrapOperation(method="snapshot",at=@At(value="INVOKE",target="Ljava/util/Map;clear()V"))
			 private void snapshot(Map<String,String> map,Operation<Void> original){
			  Set<Map.Entry<String,String>> before=Set.copyOf(map.entrySet());
			  original.call(map);
			  for(Map.Entry<String,String> e:before)if(e.getKey().startsWith("p"))map.put(e.getKey(),e.getValue());
			 }
			 // A removeIf that never calls the original clear.
			 @WrapOperation(method="prune",at=@At(value="INVOKE",target="Ljava/util/Map;clear()V"))
			 private void prune(Map<String,String> map,Operation<Void> original){
			  map.entrySet().removeIf(e->!e.getKey().startsWith("p"));
			 }
			 // A stream filter whose predicate captures more than the entry.
			 @WrapOperation(method="stream",at=@At(value="INVOKE",target="Ljava/util/Map;clear()V"))
			 private void stream(Map<String,String> map,Operation<Void> original){
			  Map<String,String> kept=map.entrySet().stream().filter(e->e.getKey().startsWith(tag)).collect(Collectors.toMap(Map.Entry::getKey,Map.Entry::getValue));
			  original.call(map);
			  map.putAll(kept);
			 }
			 // The first entry decides the whole clear.
			 @WrapOperation(method="first",at=@At(value="INVOKE",target="Ljava/util/Map;clear()V"))
			 private void first(Map<String,String> map,Operation<Void> original){
			  if(map.keySet().iterator().next().equals("pinned"))return;
			  original.call(map);
			 }
			 // A loop left early: later entries depend on earlier ones.
			 @WrapOperation(method="early",at=@At(value="INVOKE",target="Ljava/util/Map;clear()V"))
			 private void early(Map<String,String> map,Operation<Void> original){
			  List<Map.Entry<String,String>> before=new ArrayList<>(map.entrySet());
			  original.call(map);
			  for(Map.Entry<String,String> e:before){if(!e.getKey().startsWith("a"))break;map.put(e.getKey(),e.getValue());}
			 }
			 // A count carried across entries: only the first stays.
			 @WrapOperation(method="count",at=@At(value="INVOKE",target="Ljava/util/Map;clear()V"))
			 private void count(Map<String,String> map,Operation<Void> original){
			  List<Map.Entry<String,String>> before=new ArrayList<>(map.entrySet());
			  original.call(map);
			  int n=0;
			  for(Map.Entry<String,String> e:before)if(n++<1)map.put(e.getKey(),e.getValue());
			 }
			}
			""";

	@BeforeAll static void weaveEachWay() throws Exception {
		Path json = work.resolve(CONFIG);
		Files.writeString(json, "{\"required\":true,\"package\":\"fixture.scopedshapes.mixin\",\"mixins\":[\"ShelfKeepersMixin\"],\"injectors\":{\"defaultRequire\":1}}");
		List<Path> guests = List.of(source("mixin/ShelfKeepersMixin.java", GUESTS));
		List<String> g = List.of("-g");
		Path nativeFixture = WeaveHarness.fixture(work, "native-scopedshapes",
				concat(List.of(source("native/Warden.java", WARDEN), source("native/Shelf.java", host(false))), guests), Map.of(CONFIG, json), g);
		Path current = WeaveHarness.fixture(work, "scopedshapes",
				concat(List.of(source("merged/Warden.java", WARDEN), source("merged/Shelf.java", host(true))), guests), Map.of(CONFIG, json), g);
		fixture = NativeWeaveReferences.with(work, current, Map.of(TARGET, NativeWeaveReferences.classes(nativeFixture).get(TARGET)));
		nativeRun = run("native", nativeFixture, Map.of());
		relayed = run("relayed", fixture, Map.of());
		off = run("off", fixture, Map.of(ScopedClearRelayInjector.PROPERTY, "off"));
	}

	/** Without an entry the carrier keeps itself, every shape leaves the field and hands on exactly what it does natively. */
	@Test void everyShapeRunsAsNatively() throws Exception {
		for (String shape : SHAPES) {
			String line = "[Shelf] " + shape + " plain ";
			assertEquals(lines(nativeRun, line), lines(relayed, line), shape + "\n" + relayed.describe() + "\n" + nativeRun.describe());
		}
		assertTrue(losses(relayed).isEmpty(), relayed.findings().toString());
		WeaveHarness.assertWovenAndVerified(relayed, TARGET, fixture);
	}

	/** The carrier's own veto runs first, as natively it would not run at all: no guest sees, or puts back, what it kept. */
	@Test void whatTheCarrierKeepsIsNeverTheGuestsToDecide() {
		for (String shape : SHAPES) {
			List<String> line = lines(relayed, "[Shelf] " + shape + " handedOn=");
			assertEquals(1, line.size(), shape + " " + relayed.describe());
			assertTrue(line.getFirst().contains("keep_me") && !line.getFirst().matches(".*handedOn=\\[[^]]*keep_me.*"), shape + ": " + line);
		}
	}

	@Test void switchedOffNoGuestBinds() {
		assertEquals(SHAPES.size(), losses(off).size(), off.findings().toString());
		for (String shape : SHAPES)
			assertEquals(List.of("[Shelf] " + shape + " plain handedOn=[v:apple, v:pinned, v:rust] left=[]"), lines(off, "[Shelf] " + shape + " plain "), off.describe());
	}

	@Test void theControlFlipsTheMainAssertion() {
		assertNotEquals(lines(nativeRun, "[Shelf] snapshot plain "), lines(off, "[Shelf] snapshot plain "), "the native oracle must tell the runs apart");
	}

	private static List<String> lines(WeaveHarness.Result run, String prefix) {
		return run.output().lines().map(String::strip).filter(l -> l.startsWith(prefix)).toList();
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:") && f.modId().equals(MOD) && f.required()).toList();
	}

	private static List<Path> concat(List<Path> a, List<Path> b) {
		List<Path> all = new ArrayList<>(a);
		all.addAll(b);
		return all;
	}

	private static Path source(String name, String text) throws Exception {
		Path file = work.resolve("src").resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}

	private static WeaveHarness.Result run(String label, Path jar, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, jar, List.of(new WeaveHarness.Config(CONFIG, MOD, Ecosystem.FABRIC)),
				List.of(ScopedClearRelayInjector.class), EnvType.SERVER, "net.minecraft.fixture.Shelf", "run", properties);
	}
}
