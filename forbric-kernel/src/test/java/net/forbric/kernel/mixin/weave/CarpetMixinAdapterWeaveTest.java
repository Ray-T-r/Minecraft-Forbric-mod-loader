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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinPlayerWorldCallbackAdapter;

/**
 * MixinPlayerWorldCallbackAdapter through the real weave: Carpet's fill-without-updates hooks, written for vanilla's
 * {@code Level.setBlock}, on a level whose setBlock hands the notification to NeoForge's {@code markAndNotifyBlock},
 * where the neighbour update and the UPDATE_KNOWN_SHAPE test now live.
 *
 * <p>The probe sets one block normally and one the way Carpet's fill does with updates turned off. Restored, both hooks
 * act in markAndNotifyBlock: the normal set updates neighbours and shapes, the fill updates neither. With
 * {@code -Dforbric.playerWorldCallbacks=off} both hooks name setBlock, which makes neither the call nor the constant: neither
 * attaches, the final audit confirms both lost, and the fill updates its neighbours and their shapes like any set. The
 * preflight fit check reads the mixin through the adapter too ({@code MixinPlayerWorldCallbackAdapter.asLoaded}), so it calls the
 * mixin partial only in the control.
 * (Carpet's hand-swap and block-break hooks, the adapter's other two cases, stay ClassNode-level.)
 */
class CarpetMixinAdapterWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/carpetfill");
	private static final String CONFIG = "carpetfill.mixins.json";
	private static final String MOD = "carpet";
	private static final String LEVEL = "net/minecraft/world/level/Level";
	private static final String QUIET_FILL = WeaveHarnessMain.DONE + " [neighbours@1,2,3, shapes@1,2,3]";
	private static final String NOISY_FILL = WeaveHarnessMain.DONE + " [neighbours@1,2,3, shapes@1,2,3, neighbours@4,5,6, shapes@4,5,6]";
	private static final String RESTORED = "[Forbric/Mixin] restored 2 callback(s) in carpet/mixins/Level_fillUpdatesMixin";
	private static final String PARTIAL = "guest mixin carpet (" + CONFIG + "):Level_fillUpdatesMixin applies only partially";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result restored, off;

	@BeforeAll static void weaveBothWays() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
		assertEquals(9, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		fixture = WeaveHarness.fixture(work, "carpetfill", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		restored = run("restored", Map.of());
		off = run("carpet-off", Map.of(MixinPlayerWorldCallbackAdapter.PROPERTY, "off"));
	}

	@Test void aFillWithUpdatesOffUpdatesNeitherNeighboursNorShapes() throws Exception {
		assertTrue(restored.printed(QUIET_FILL), restored.describe());
		assertTrue(restored.printed(RESTORED), restored.describe());
		assertFalse(restored.printed(PARTIAL), restored.describe());
		assertEquals(List.of(), unsettled(restored), restored.findings() + "\n" + restored.describe());
		// Both hooks live in markAndNotifyBlock now: the redirect replaced its neighbour update, setBlock has none.
		assertEquals(List.of("markAndNotifyBlock"), callersOf(restored, "updateNeighborsMaybe"), restored.describe());
		assertEquals(List.of("markAndNotifyBlock"), callersOf(restored, "addFillUpdatesInt"), restored.describe());
		WeaveHarness.assertWovenAndVerified(restored, LEVEL, fixture);
	}

	@Test void switchedOffTheHooksNameSetBlockAndTheFillUpdatesLikeAnySet() throws Exception {
		assertTrue(off.printed(NOISY_FILL), off.describe());
		assertFalse(off.printed(RESTORED), off.describe());
		assertTrue(off.printed(PARTIAL), off.describe());
		assertEquals(List.of("addFillUpdatesInt CONFIRMED", "updateNeighborsMaybe CONFIRMED"), lost(off), off.findings().toString());
		assertEquals(List.of(), callersOf(off, "updateNeighborsMaybe"), off.describe());
		WeaveHarness.assertWovenAndVerified(off, LEVEL, fixture);
	}

	/** The switch is the runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryFillAssertion() throws Exception {
		assertTrue(quietHolds(restored) && !quietHolds(off), "the restored predicate does not separate the runs");
		assertTrue(noisyHolds(off) && !noisyHolds(restored), "the control predicate does not separate the runs");
	}

	private static boolean quietHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(QUIET_FILL) && run.printed(RESTORED) && !run.printed(PARTIAL) && unsettled(run).isEmpty()
				&& callersOf(run, "updateNeighborsMaybe").equals(List.of("markAndNotifyBlock"));
	}

	private static boolean noisyHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(NOISY_FILL) && !run.printed(RESTORED) && run.printed(PARTIAL) && lost(run).size() == 2
				&& callersOf(run, "updateNeighborsMaybe").isEmpty();
	}

	/** The woven level's methods that call the merged handler ending in {@code handler}. */
	private static List<String> callersOf(WeaveHarness.Result run, String handler) throws Exception {
		ClassNode level = new ClassNode();
		new ClassReader(run.defined(LEVEL)).accept(level, 0);
		return level.methods.stream().filter(m -> calls(m, handler)).map(m -> m.name).toList();
	}

	private static boolean calls(MethodNode method, String handler) {
		for (var insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(LEVEL) && call.name.endsWith(handler)) return true;
		}
		return false;
	}

	/** The mod's required findings that the final class did not settle. */
	private static List<WeaveHarness.Finding> unsettled(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.required() && !"RESOLVED".equals(f.confidence())).toList();
	}

	/** The final audit's rows for the mod's injectors: handler name and confidence, sorted. */
	private static List<String> lost(WeaveHarness.Result run) {
		return unsettled(run).stream().filter(f -> f.id().startsWith("mixin-injector:"))
				.map(f -> f.id().substring(f.id().indexOf('#') + 1, f.id().indexOf('(')) + " " + f.confidence()).sorted().toList();
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.carpetfill.Probe", "probe", properties);
	}
}
