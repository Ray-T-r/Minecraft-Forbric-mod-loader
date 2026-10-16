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
import net.forbric.kernel.transform.ScopedClearRelayInjector;

/**
 * ScopedClearRelayInjector through the real Mixin and MixinExtras weave, on a host and guests with names of their own.
 *
 * <p>Natively the host clears a field map after copying it and hands the copy on; the carrier's body asks its own
 * per-key veto, collects what it lets go in a local map, removes exactly those keys and hands that map on — no
 * {@code clear()}. Two guests wrap the native {@code Map.clear()}: one puts back what it keeps (the vetoed entry stays
 * in the copy, so it is still handed on — fabric-api's shape), one puts back what it keeps and takes it out of the copy
 * through its {@code @Local} (Balm's shape). Woven on the native host and on the relayed carrier host, they ask about
 * the same entries and leave the same field and the same handed-on values. With {@code -Dforbric.scopedClearRelay=off}
 * neither guest binds, and both are reported lost.
 */
@net.forbric.kernel.transform.ExecutesInjector(ScopedClearRelayInjector.class)
class ScopedClearRelayWeaveTest {
	private static final String CONFIG = "scopedclear.mixins.json";
	private static final String MOD = "gardenkeepers";
	private static final String TARGET = "net/minecraft/fixture/Garden";
	/** Both guests ran: asked about every entry the clear covered; thorn put back and handed on, weed put back and not. */
	private static final String GUESTS_RAN = "[Garden] asked=[rose, thorn, weed] handedOn=[v:rose, v:thorn] left=[thorn, weed]";
	/** The carrier's own veto runs first, as natively it would not run at all: the guests never see what it kept. */
	private static final String CARRIER_KEPT = "[Garden] carrier: asked=[rose] handedOn=[v:rose] left=[keep_me]";
	private static final String NOTHING_BOUND = "[Garden] asked=[] handedOn=[v:rose, v:thorn, v:weed] left=[]";
	private static final String RELAY_LOG = "[Forbric/ScopedClear] net.minecraft.fixture.Garden: [removeAll()Z(beds)]";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result nativeRun, relayed, off;

	private static final String WARDEN = """
			package net.minecraft.fixture;
			public class Warden { public static boolean keeps(String key){return key.equals("keep_me");} }
			""";

	private static final String NATIVE = """
			package net.minecraft.fixture;
			import java.util.*;
			public class Garden {
			 public static final List<String> asked=new ArrayList<>();
			 final List<String> handedOn=new ArrayList<>();
			 final Map<String,String> beds=new HashMap<>();
			 public boolean removeAll(){
			  if(beds.isEmpty())return false;
			  Map<String,String> copy=new HashMap<>(beds);
			  beds.clear();
			  onCleared(copy.values());
			  return true;
			 }
			 void onCleared(Collection<String> values){handedOn.addAll(values);}
			 public void run(){
			  for(String k:List.of("rose","thorn","weed"))beds.put(k,"v:"+k);
			  removeAll();
			  System.out.println("[Garden] asked="+sorted(asked)+" handedOn="+sorted(handedOn)+" left="+sorted(beds.keySet()));
			 }
			 static List<String> sorted(Collection<String> c){List<String> l=new ArrayList<>(c);Collections.sort(l);return l;}
			}
			""";

	/** The carrier's body: its own per-key veto, a local map of what goes, those keys removed through Map::remove. */
	private static final String MERGED = """
			package net.minecraft.fixture;
			import java.util.*;
			public class Garden {
			 public static final List<String> asked=new ArrayList<>();
			 final List<String> handedOn=new ArrayList<>();
			 final Map<String,String> beds=new HashMap<>();
			 public boolean removeAll(){
			  if(beds.isEmpty())return false;
			  Map<String,String> map=new HashMap<>(beds.size());
			  for(Map.Entry<String,String> entry:beds.entrySet()){
			   if(!Warden.keeps(entry.getKey()))map.put(entry.getKey(),entry.getValue());
			  }
			  map.keySet().forEach(beds::remove);
			  onCleared(map.values());
			  return true;
			 }
			 void onCleared(Collection<String> values){handedOn.addAll(values);}
			 public void run(){
			  for(String k:List.of("rose","thorn","weed"))beds.put(k,"v:"+k);
			  removeAll();
			  System.out.println("[Garden] asked="+sorted(asked)+" handedOn="+sorted(handedOn)+" left="+sorted(beds.keySet()));
			  Garden second=new Garden();asked.clear();
			  for(String k:List.of("rose","keep_me"))second.beds.put(k,"v:"+k);
			  second.removeAll();
			  System.out.println("[Garden] carrier: asked="+sorted(asked)+" handedOn="+sorted(second.handedOn)+" left="+sorted(second.beds.keySet()));
			 }
			 static List<String> sorted(Collection<String> c){List<String> l=new ArrayList<>(c);Collections.sort(l);return l;}
			}
			""";

	private static final String PUT_BACK = """
			package fixture.scopedclear.mixin;
			import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
			import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
			import java.util.Map;
			import java.util.Set;
			import net.minecraft.fixture.Garden;
			import org.spongepowered.asm.mixin.Mixin;
			import org.spongepowered.asm.mixin.injection.At;
			@Mixin(Garden.class)
			public class GardenPutBackMixin {
			 @WrapOperation(method="removeAll",at=@At(value="INVOKE",target="Ljava/util/Map;clear()V"))
			 private void keepThorns(Map<String,String> map,Operation<Void> original){
			  Set<Map.Entry<String,String>> entries=Set.copyOf(map.entrySet());
			  original.call(map);
			  for(Map.Entry<String,String> e:entries){Garden.asked.add(e.getKey());if(e.getKey().equals("thorn"))map.put(e.getKey(),e.getValue());}
			 }
			}
			""";

	private static final String OUT_OF_COPY = """
			package fixture.scopedclear.mixin;
			import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
			import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
			import com.llamalad7.mixinextras.sugar.Local;
			import java.util.HashMap;
			import java.util.Map;
			import net.minecraft.fixture.Garden;
			import org.spongepowered.asm.mixin.Mixin;
			import org.spongepowered.asm.mixin.injection.At;
			@Mixin(Garden.class)
			public class GardenOutOfCopyMixin {
			 @WrapOperation(method="removeAll",at=@At(value="INVOKE",target="Ljava/util/Map;clear()V"))
			 private void keepWeeds(Map<String,String> map,Operation<Void> original,@Local Map<String,String> copy){
			  Map<String,String> retained=new HashMap<>();
			  for(Map.Entry<String,String> e:map.entrySet())if(e.getKey().equals("weed"))retained.put(e.getKey(),e.getValue());
			  original.call(map);
			  map.putAll(retained);
			  retained.keySet().forEach(copy::remove);
			 }
			}
			""";

	@BeforeAll static void weaveEachWay() throws Exception {
		Path json = work.resolve(CONFIG);
		Files.writeString(json, "{\"required\":true,\"package\":\"fixture.scopedclear.mixin\",\"mixins\":[\"GardenOutOfCopyMixin\",\"GardenPutBackMixin\"],"
				+ "\"injectors\":{\"defaultRequire\":1}}");
		List<Path> mixins = List.of(source("mixin/GardenPutBackMixin.java", PUT_BACK), source("mixin/GardenOutOfCopyMixin.java", OUT_OF_COPY));
		List<String> g = List.of("-g");
		Path nativeFixture = WeaveHarness.fixture(work, "native-scopedclear",
				concat(List.of(source("native/Warden.java", WARDEN), source("native/Garden.java", NATIVE)), mixins), Map.of(CONFIG, json), g);
		Path current = WeaveHarness.fixture(work, "scopedclear",
				concat(List.of(source("merged/Warden.java", WARDEN), source("merged/Garden.java", MERGED)), mixins), Map.of(CONFIG, json), g);
		fixture = NativeWeaveReferences.with(work, current, Map.of(TARGET, NativeWeaveReferences.classes(nativeFixture).get(TARGET)));
		nativeRun = run("native", nativeFixture, Map.of());
		relayed = run("relayed", fixture, Map.of());
		off = run("off", fixture, Map.of(ScopedClearRelayInjector.PROPERTY, "off"));
	}

	@Test void natively() {
		assertTrue(nativeRun.printedLine(GUESTS_RAN), nativeRun.describe());
	}

	@Test void relayedTheGuestsRunAsNatively() throws Exception {
		assertTrue(relayedHolds(relayed), relayed.describe() + "\nfindings: " + relayed.findings());
		assertTrue(relayed.printedLine(CARRIER_KEPT), relayed.describe());
		WeaveHarness.assertWovenAndVerified(relayed, TARGET, fixture);
	}

	@Test void switchedOffNeitherGuestBindsAndBothAreReportedLost() {
		assertTrue(offHolds(off), off.describe() + "\nfindings: " + off.findings());
	}

	/** The switch is the runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryAssertion() {
		assertTrue(relayedHolds(relayed) && !relayedHolds(off), "the relay predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(relayed), "the control predicate does not separate the runs");
	}

	private static boolean relayedHolds(WeaveHarness.Result run) {
		return run.printedLine(GUESTS_RAN) && run.printed(RELAY_LOG) && losses(run).isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printedLine(NOTHING_BOUND) && !run.printed(RELAY_LOG) && losses(run).size() == 2;
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:") && f.modId().equals(MOD) && f.required()).toList();
	}

	private static List<Path> concat(List<Path> a, List<Path> b) {
		List<Path> all = new java.util.ArrayList<>(a);
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
				List.of(ScopedClearRelayInjector.class), EnvType.SERVER, "net.minecraft.fixture.Garden", "run", properties);
	}
}
