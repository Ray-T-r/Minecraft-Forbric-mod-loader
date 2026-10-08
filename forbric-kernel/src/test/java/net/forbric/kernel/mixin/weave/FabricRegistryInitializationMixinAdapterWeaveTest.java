package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.FabricRegistryInitializationMixinAdapter;

/**
 * {@code FabricRegistryInitializationMixinAdapter} through the real weave: fabric-registry-sync's bootstrap and server
 * hooks keep their trackers while the native bootstrap keeps the one registration freeze.
 *
 * <p>The fixture boots a fake game the way a dedicated server does — {@code Bootstrap.bootStrap()}, then
 * {@code Main.main} — and every step writes to one trace. Both guest mixins bind cleanly with the switch on or off;
 * what differs is only what runs. With the adapter, the freeze stays in the bootstrap, Fabric's trackers run at its
 * end, and the server hook no longer freezes a second time. With {@code -Dforbric.fabricRegistryInitialization=off}
 * Fabric's redirect replaces the freeze with {@code createContents} and its server hook freezes again — nothing is
 * reported, which is why only the trace can tell the runs apart.
 */
class FabricRegistryInitializationMixinAdapterWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/fabricregistryinit");
	private static final String CONFIG = "fabricregistryinit.mixins.json";
	private static final String MOD = "fabricregistryinit";
	private static final String BOOTSTRAP = "net/minecraft/server/Bootstrap";
	private static final String MAIN = "net/minecraft/server/Main";
	private static final String KEPT = "bootstrap:start,freeze,wrapStreams,bootstrap:end,fabric:trackers,"
			+ "main:start,fabric:postFreeze,timerHack,main:end";
	private static final String FABRICS = "bootstrap:start,createContents,fabric:trackers,wrapStreams,bootstrap:end,"
			+ "main:start,freeze,fabric:postFreeze,timerHack,main:end";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;

	@BeforeAll static void weaveBoth() throws Exception {
		fixture = WeaveHarness.fixture(work, MOD, sources(), Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		adapted = run("adapted", "on");
		off = run("off", "off");
	}

	@Test void theKernelKeepsTheFreezeAndFabricKeepsItsTrackers() throws Exception {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings: " + adapted.findings());
		assertTrue(adapted.printed("[Forbric/RegistrySync] retained net/fabricmc/fabric/mixin/registry/sync/BootstrapMixin's bootstrap tracker callback"),
				adapted.describe());
		assertTrue(adapted.printed("[Forbric/RegistrySync] retained net/fabricmc/fabric/mixin/registry/sync/MainMixin's "
				+ "post-freeze callback without repeating the registry bootstrap"), adapted.describe());
		for (String target : List.of(BOOTSTRAP, MAIN)) {
			assertTrue(WeaveHarness.hasMergedMethod(adapted.defined(target)), target + " was not woven — " + adapted.describe());
			WeaveHarness.assertWovenAndVerified(adapted, target, fixture);
		}
	}

	@Test void withTheSwitchOffFabricMovesTheFreezeAndFreezesTwice() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings: " + off.findings());
		assertFalse(off.printed("[Forbric/RegistrySync]"), off.describe());
		for (String target : List.of(BOOTSTRAP, MAIN)) {
			assertTrue(WeaveHarness.hasMergedMethod(off.defined(target)), target + " was not woven — " + off.describe());
			WeaveHarness.assertWovenAndVerified(off, target, fixture);
		}
	}

	/** The run and its control must be told apart by the very predicates the test uses on them. */
	@Test void theControlFlipsEveryAssertion() {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapted predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
	}

	/** Both mixins bind in both runs, so neither run may report a loss: the difference is the order of the trace. */
	private static boolean adaptedHolds(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + KEPT) && losses(run).isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + FABRICS) && losses(run).isEmpty();
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.confirmedRequired()).toList();
	}

	private static WeaveHarness.Result run(String label, String adapter) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.fabricregistryinit.Probe", "run", Map.of(FabricRegistryInitializationMixinAdapter.PROPERTY,adapter,"forbric.mergedBaseCompat","off"));
	}

	private static List<Path> sources() throws Exception {
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}
}
