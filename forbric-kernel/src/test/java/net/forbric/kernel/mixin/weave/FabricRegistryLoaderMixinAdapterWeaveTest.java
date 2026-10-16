package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.FabricRegistryLoaderMixinAdapter;

/**
 * {@code FabricRegistryLoaderMixinAdapter} through the real weave: fabric-registry-sync's "this load is the server's"
 * mark reaches the async load task across the carrier's widened overloads.
 *
 * <p>The fixture's loader has the merged shape: vanilla's four-argument entry is a stub, the body adds pending tags and
 * calls the private loader with a leniency flag, and the load runs on another thread. The guest wrap and argument
 * hook both name vanilla's overloads. With the adapter they move onto the carrier's, the wrap is re-shaped so the
 * original handler still gets its four arguments while the flag goes through untouched (KernelWrapOperations, the
 * kernel's real game-side class, compiled into the fixture), and the task reports {@code server=true} with the
 * flag still {@code true}. With {@code -Dforbric.fabricRegistryLoader=off} the wrap of the loader call the carrier left
 * dead cannot be moved, so the refusal gate leaves the whole mixin out (one confirmed finding naming it) rather than let
 * its other hook apply without it, and the task never hears of the server. The gate recognises the wrap by the call it
 * wraps being dead in the merged class, not by how its handler is written: this fixture's handler is not fabric-api's.
 */
class FabricRegistryLoaderMixinAdapterWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/fabricregistryloader");
	private static final String CONFIG = "fabricregistryloader.mixins.json";
	private static final String MOD = "fabricregistryloader";
	private static final String LOADER = "net/minecraft/resources/RegistryDataLoader";
	private static final String MARKED = "resources lenient=true server=true";
	private static final String UNMARKED = "resources lenient=true";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;

	@BeforeAll static void weaveBoth() throws Exception {
		List<Path> sources = new ArrayList<>(sources());
		// The re-shaped wrap calls the kernel's game-side KernelWrapOperations, which ForbricClassLoader must define.
		sources.add(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelWrapOperations.java"));
		fixture = WeaveHarness.fixture(work, MOD, sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		adapted = run("adapted", "on");
		off = run("off", "off");
	}

	@Test void theServerMarkReachesTheAsyncLoadAndTheFlagSurvives() throws Exception {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings: " + adapted.findings());
		assertTrue(adapted.printed("[Forbric/RegistrySync] restored the native Fabric registry loader callback"), adapted.describe());
		assertTrue(WeaveHarness.hasMergedMethod(adapted.defined(LOADER)), adapted.describe());
		WeaveHarness.assertWovenAndVerified(adapted, LOADER, fixture);
	}

	@Test void withTheSwitchOffTheMixinIsLeftOutWhole() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings: " + off.findings());
		assertFalse(off.printed("[Forbric/RegistrySync]"), off.describe());
		WeaveHarness.assertWovenAndVerified(off, LOADER, fixture);
	}

	/** The run and its control must be told apart by the very predicates the test uses on them. */
	@Test void theControlFlipsEveryAssertion() {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapted predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + MARKED + "\n") && losses(run).isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		List<WeaveHarness.Finding> leftOut = run.findings().stream().filter(f -> f.id().startsWith("mixin:") && f.modId().equals(MOD)
				&& f.id().endsWith("RegistryDataLoaderMixin") && f.confidence().equals("CONFIRMED")).toList();
		return run.printed(WeaveHarnessMain.DONE + " " + UNMARKED + "\n") && losses(run).isEmpty() && leftOut.size() == 1
				&& run.printed("suppressed mixin RegistryDataLoaderMixin");
	}

	/** Every required injector of the mod the runtime audit reports, at any confidence. */
	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:") && f.modId().equals(MOD)
				&& f.required()).toList();
	}

	private static WeaveHarness.Result run(String label, String adapter) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.fabricregistryloader.Probe", "run", Map.of(FabricRegistryLoaderMixinAdapter.PROPERTY, adapter));
	}

	private static List<Path> sources() throws Exception {
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}
}
