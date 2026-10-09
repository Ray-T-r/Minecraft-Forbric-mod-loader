/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Type;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.fabric.DispatchFixtures;
import net.forbric.kernel.fabric.EntrypointDispatchScan.Dispatch;
import net.forbric.kernel.fabric.EntrypointDispatchScan.Phase;
import net.forbric.kernel.fabric.FabricModMetadataParser;
import net.forbric.kernel.fabric.KernelFabricLoader;
import net.forbric.kernel.fabric.KernelModContainer;

/**
 * Where, among a phase's entrypoints, the kernel dispatches a custom key only a losing library build dispatched from
 * that phase: where that build's own entrypoint of the phase would have run, so a consumer that natively runs after
 * the library reads what the dispatch set up from its own {@code onInitialize}.
 *
 * <p>The library ("lumen") and the mods ("amber", "quartz", "zircon") exist nowhere. Unlike the case the dispatch was
 * built on, the library dispatches from {@code main}, not {@code preLaunch}.
 */
@ResourceLock("ModCatalog")
@ResourceLock("system-properties")
@ResourceLock("KernelFabricEcosystem")
public class ArbitratedAwayPlacementTest {
	private static final String KEY = "lumen:tuning";
	private static final String HOOK = Type.getInternalName(TuningHook.class);
	static final List<String> JOURNAL = new ArrayList<>();

	@TempDir Path dir;

	/** The library's entrypoint type. Its bytes also go into the losing build, which is where the scan finds it declared. */
	public interface TuningHook {
		void tune();
	}

	public static final class AmberMain implements ModInitializer {
		@Override public void onInitialize() {
			JOURNAL.add("amber main, tuned " + AmberTuning.calls);
		}
	}

	public static final class AmberTuning implements TuningHook {
		static int calls;

		@Override public void tune() {
			calls++;
			JOURNAL.add("amber tuned");
		}
	}

	public static final class QuartzMain implements ModInitializer {
		@Override public void onInitialize() {
			JOURNAL.add("quartz main, tuned " + QuartzTuning.calls);
		}
	}

	public static final class QuartzTuning implements TuningHook {
		static int calls;

		@Override public void tune() {
			calls++;
			JOURNAL.add("quartz tuned");
		}
	}

	public static final class ZirconMain implements ModInitializer {
		@Override public void onInitialize() {
			JOURNAL.add("zircon main");
		}
	}

	@BeforeEach @AfterEach void reset() {
		ArbitratedAwayDispatchers.reset();
		CompatibilityFindings.reset();
		ModCatalog.publish(List.of());
		JOURNAL.clear();
		AmberTuning.calls = 0;
		QuartzTuning.calls = 0;
		System.clearProperty(FabricLoadOrder.SWITCH);
	}

	// -------------------------------------------------------------------------------------------------------------
	// The place, as a pure function of the phase's order.
	// -------------------------------------------------------------------------------------------------------------

	private static ArbitratedAwayDispatchers.Orphan orphan(String library, String... provides) {
		Dispatch dispatch = new Dispatch(KEY, HOOK, true, "tune", "()V", Set.of(Phase.MAIN), "dev.lumen.fabric.LumenMain.onInitialize");
		return new ArbitratedAwayDispatchers.Orphan(library, Path.of(library + "-fabric.jar"), dispatch, Set.of(provides));
	}

	private static ModMetadata mod(String id, String dependencies) {
		String json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\"" + (dependencies == null ? "" : "," + dependencies) + "}";
		return FabricModMetadataParser.read(new StringReader(json));
	}

	@Test void inFabricOrderItRunsWhereTheLibrarysIdSorts() {
		List<ModMetadata> phase = List.of(mod("amber", null), mod("quartz", null), mod("zircon", null));

		assertEquals(1, ArbitratedAwayDispatchers.place(orphan("lumen"), phase, true).before());
		assertEquals(0, ArbitratedAwayDispatchers.place(orphan("aaa-lumen"), phase, true).before(), "sorts first");
		assertEquals(3, ArbitratedAwayDispatchers.place(orphan("zz-lumen"), phase, true).before(), "sorts last: after them all");
		// A plain String comparison, as Fabric Loader's: "lumen" sorts before "lumen-extras", '-' before letters.
		assertEquals(1, ArbitratedAwayDispatchers.place(orphan("lumen"),
				List.of(mod("lumea", null), mod("lumen-extras", null)), true).before());
	}

	/** Natively a dependency does not move a mod in Fabric Loader's order, so it does not move the dispatch either. */
	@Test void inFabricOrderADependentWhoseIdSortsFirstStillRunsFirst() {
		List<ModMetadata> phase = List.of(mod("amber", "\"depends\":{\"lumen\":\"*\"}"), mod("quartz", null));

		assertEquals(1, ArbitratedAwayDispatchers.place(orphan("lumen"), phase, true).before());
	}

	@Test void inDependencyOrderItRunsBeforeTheFirstModThatRequiresTheLibrary() {
		List<ModMetadata> phase = List.of(mod("zircon", null), mod("quartz", "\"depends\":{\"lumen-api\":\">=1\"}"), mod("amber", null));

		assertEquals(1, ArbitratedAwayDispatchers.place(orphan("lumen", "lumen-api"), phase, false).before(),
				"required under an id the library provides");
		assertEquals(1, ArbitratedAwayDispatchers.place(orphan("lumen"),
				List.of(mod("zircon", null), mod("quartz", "\"depends\":{\"lumen\":\"*\"}")), false).before());
	}

	/** Recommending or suggesting the library does not put a mod after it; with no mod requiring it, before them all. */
	@Test void inDependencyOrderASoftDependencyIsNotARequirementAndWithoutOneItRunsFirst() {
		List<ModMetadata> phase = List.of(mod("zircon", null), mod("quartz", "\"recommends\":{\"lumen\":\"*\"}"),
				mod("amber", "\"suggests\":{\"lumen\":\"*\"}"));

		ArbitratedAwayDispatchers.Place place = ArbitratedAwayDispatchers.place(orphan("lumen"), phase, false);
		assertEquals(0, place.before());
		assertTrue(place.why().contains("not known"), place::toString);
	}

	// -------------------------------------------------------------------------------------------------------------
	// The phase, run.
	// -------------------------------------------------------------------------------------------------------------

	private Path losingBuild() throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("fabric.mod.json", DispatchFixtures.fabricModJson("lumen", Map.of("main", List.of("dev.lumen.fabric.LumenMain"))));
		try (InputStream in = TuningHook.class.getClassLoader().getResourceAsStream(HOOK + ".class")) {
			entries.put(HOOK, in.readAllBytes());
		}
		entries.put("dev/lumen/fabric/LumenMain", DispatchFixtures.directDispatcher("dev/lumen/fabric/LumenMain", KEY, HOOK));
		return DispatchFixtures.write(dir.resolve("lumen-fabric.jar"), entries);
	}

	private Path winningBuild() throws Exception {
		return DispatchFixtures.write(dir.resolve("lumen-neoforge.jar"), Map.of(
				"META-INF/neoforge.mods.toml", DispatchFixtures.neoForgeToml("lumen"),
				"dev/lumen/neoforge/LumenMod", DispatchFixtures.plain("dev/lumen/neoforge/LumenMod")));
	}

	private static String mod(String id, Class<?> main, Class<?> tuning, boolean requiresLumen) {
		StringBuilder json = new StringBuilder("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\",\"entrypoints\":{\"main\":[\""
				+ main.getName() + "\"]");
		if (tuning != null) json.append(",\"").append(KEY).append("\":[\"").append(tuning.getName()).append("\"]");
		json.append('}');
		if (requiresLumen) json.append(",\"depends\":{\"lumen\":\"*\"}");
		return json.append('}').toString();
	}

	private KernelFabricLoader loader(boolean fabricOrder, String... mods) throws Exception {
		var constructor = KernelFabricLoader.class.getDeclaredConstructor(EnvType.class, Path.class, Path.class, String[].class, String.class);
		constructor.setAccessible(true);
		KernelFabricLoader loader = constructor.newInstance(EnvType.CLIENT, dir, dir.resolve("config"), new String[0], "26.2");
		loader.setGameLoader(getClass().getClassLoader());
		for (String json : mods) loader.register(new KernelModContainer(FabricModMetadataParser.read(new StringReader(json)), null, null));
		// What KernelFabricEcosystem.putInFabricOrder does before anything runs.
		if (fabricOrder) loader.reorder(FabricLoadOrder.byModId(new ArrayList<>(loader.getAllMods()), mod -> mod.getMetadata().getId()));
		loader.freeze();
		return loader;
	}

	private int runMain(KernelFabricLoader fabric) throws Exception {
		Path losing = losingBuild(), winning = winningBuild();
		Field active = KernelFabricEcosystem.class.getDeclaredField("loader");
		active.setAccessible(true);
		Object previous = active.get(null);
		String shim = System.getProperty(KernelForeignShimContext.SWITCH);
		try {
			active.set(null, fabric);
			System.setProperty(KernelForeignShimContext.SWITCH, "off");
			var orphans = ArbitratedAwayDispatchers.record(new DuplicateModArbiter.Decision(Set.of(losing), Map.of("lumen", winning),
					List.of(), Set.of(losing)), KernelFabricEcosystem.declaredEntrypointKeys(), KernelFabricEcosystem.knownModIds());
			assertEquals(List.of(KEY), orphans.stream().map(ArbitratedAwayDispatchers.Orphan::key).toList());
			assertEquals(Set.of(Phase.MAIN), orphans.getFirst().dispatch().phases());
			return KernelFabricEcosystem.invokePhase("main", ModInitializer.class, ModInitializer::onInitialize, Phase.MAIN);
		} finally {
			active.set(null, previous);
			if (shim == null) System.clearProperty(KernelForeignShimContext.SWITCH); else System.setProperty(KernelForeignShimContext.SWITCH, shim);
		}
	}

	/**
	 * Fabric Loader's order, registered out of it and put in it as the kernel does: amber, quartz, zircon. "lumen" sorts
	 * between amber and quartz, so quartz's {@code onInitialize} sees its tuning done and amber's — which natively runs
	 * before lumen's own {@code main} as well — does not, although amber requires lumen too.
	 */
	@Test void theDispatchRunsBetweenTheMainsWhereTheLibrarySortsSoTheConsumerAfterItSeesItsConfigSetUp() throws Exception {
		KernelFabricLoader fabric = loader(true,
				mod("zircon", ZirconMain.class, null, false),
				mod("quartz", QuartzMain.class, QuartzTuning.class, true),
				mod("amber", AmberMain.class, AmberTuning.class, true));

		assertEquals(3, runMain(fabric), "the phase's own entrypoints, the dispatched ones not counted");

		assertEquals(List.of("amber main, tuned 0", "amber tuned", "quartz tuned", "quartz main, tuned 1", "zircon main"), JOURNAL);
		assertEquals(1, AmberTuning.calls, "once, as the library did");
		assertEquals(1, QuartzTuning.calls);
		assertEquals(0, KernelFabricEcosystem.dispatchArbitratedAwayKeys(Phase.MAIN), "handed out once per boot");
	}

	/** {@code -Dforbric.fabricOrder=off}: every mod after what it requires, so the library comes before quartz. */
	@Test void inDependencyOrderTheDispatchRunsBeforeTheFirstModRequiringTheLibrary() throws Exception {
		System.setProperty(FabricLoadOrder.SWITCH, "off");
		KernelFabricLoader fabric = loader(false,
				mod("zircon", ZirconMain.class, null, false),
				mod("amber", AmberMain.class, AmberTuning.class, false),
				mod("quartz", QuartzMain.class, QuartzTuning.class, true));

		assertEquals(3, runMain(fabric));

		assertEquals(List.of("zircon main", "amber main, tuned 0", "amber tuned", "quartz tuned", "quartz main, tuned 1"), JOURNAL);
	}

	@Test void inDependencyOrderWithNoModRequiringTheLibraryTheDispatchRunsBeforeThePhase() throws Exception {
		System.setProperty(FabricLoadOrder.SWITCH, "off");
		KernelFabricLoader fabric = loader(false,
				mod("zircon", ZirconMain.class, null, false),
				mod("quartz", QuartzMain.class, QuartzTuning.class, false),
				mod("amber", AmberMain.class, AmberTuning.class, false));

		assertEquals(3, runMain(fabric));

		assertEquals(List.of("quartz tuned", "amber tuned", "zircon main", "quartz main, tuned 1", "amber main, tuned 1"), JOURNAL);
	}

	/** The look-alike: a phase no orphan belongs to runs exactly as before, in its own order, with nothing between. */
	@Test void aPhaseNoOrphanIsDueInRunsUntouched() throws Exception {
		KernelFabricLoader fabric = loader(true,
				mod("zircon", ZirconMain.class, null, false),
				mod("quartz", QuartzMain.class, QuartzTuning.class, true));
		Field active = KernelFabricEcosystem.class.getDeclaredField("loader");
		active.setAccessible(true);
		Object previous = active.get(null);
		String shim = System.getProperty(KernelForeignShimContext.SWITCH);
		try {
			active.set(null, fabric);
			System.setProperty(KernelForeignShimContext.SWITCH, "off");
			ArbitratedAwayDispatchers.record(new DuplicateModArbiter.Decision(Set.of(), Map.of(), List.of()),
					KernelFabricEcosystem.declaredEntrypointKeys(), KernelFabricEcosystem.knownModIds());
			assertEquals(2, KernelFabricEcosystem.invokePhase("main", ModInitializer.class, ModInitializer::onInitialize, Phase.MAIN));
		} finally {
			active.set(null, previous);
			if (shim == null) System.clearProperty(KernelForeignShimContext.SWITCH); else System.setProperty(KernelForeignShimContext.SWITCH, shim);
		}
		assertEquals(List.of("quartz main, tuned 0", "zircon main"), JOURNAL);
		assertEquals(0, QuartzTuning.calls, "the library is not installed as another build: nothing dispatches its key");
	}
}
