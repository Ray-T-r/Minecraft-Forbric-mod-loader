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
 * MixinFluidReactionAdapter through the real weave: Carpet's renewable-blackstone rule, a TAIL hook on vanilla's
 * {@code shouldSpreadLiquid}, on a liquid block whose placement and neighbour change ask the two families' fluid
 * interaction registries instead and never call that method.
 *
 * <p>The probe places lava under blue ice, changes a neighbour of lava under blue ice, places lava in the open, and
 * places lava under blue ice in a cell a native interaction handles. Restored, the rule runs where the registries
 * answer "not handled": the first two cells turn to blackstone and fizz instead of ticking, the open cell ticks, and
 * the natively handled cell is left to the registry. With {@code -Dforbric.playerWorldCallbacks=off} (the switch this adapter
 * shares with MixinPlayerWorldCallbackAdapter) the hook is woven into the method nothing calls: every lava cell just ticks, and
 * the final audit, seeing an attached injector, reports nothing.
 */
class CarpetFluidMixinAdapterWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/carpetfluid");
	private static final String CONFIG = "carpetfluid.mixins.json";
	private static final String MOD = "carpet";
	private static final String LIQUID = "net/minecraft/world/level/block/LiquidBlock";
	private static final String CONVERTED = WeaveHarnessMain.DONE + " placed=blackstone changed=blackstone open=air handled=air"
			+ " events=[fizz@0,64,0, fizz@10,64,0, tick@20,64,0]";
	private static final String ONLY_TICKS = WeaveHarnessMain.DONE + " placed=air changed=air open=air handled=air"
			+ " events=[tick@0,64,0, tick@10,64,0, tick@20,64,0]";
	private static final String RESTORED = "[Forbric/Mixin] fluid reaction callbacks now follow the native interaction sites";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result restored, off;

	@BeforeAll static void weaveBothWays() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
		assertEquals(16, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		fixture = WeaveHarness.fixture(work, "carpetfluid", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		restored = run("restored", Map.of());
		off = run("carpet-off", Map.of(MixinPlayerWorldCallbackAdapter.PROPERTY, "off"));
	}

	@Test void lavaUnderBlueIceTurnsToBlackstoneWhereTheRegistriesLeaveIt() throws Exception {
		assertTrue(restored.printed(CONVERTED), restored.describe());
		assertTrue(restored.printed(RESTORED), restored.describe());
		assertEquals(List.of(), unsettled(restored), restored.findings() + "\n" + restored.describe());
		// Each registry question is wrapped, and the vanilla method keeps no hook.
		assertEquals(List.of("neighborChanged", "onPlace"), wrapped(restored), restored.describe());
		assertFalse(hooked(restored, "shouldSpreadLiquid"), restored.describe());
		WeaveHarness.assertWovenAndVerified(restored, LIQUID, fixture);
	}

	@Test void switchedOffTheRuleHooksTheMethodNothingCallsAndLavaJustTicks() throws Exception {
		assertTrue(off.printed(ONLY_TICKS), off.describe());
		assertFalse(off.printed(RESTORED), off.describe());
		assertEquals(List.of(), unsettled(off), "attached to a dead method is attached — " + off.findings());
		assertEquals(List.of(), wrapped(off), off.describe());
		assertTrue(hooked(off, "shouldSpreadLiquid"), off.describe());
		WeaveHarness.assertWovenAndVerified(off, LIQUID, fixture);
	}

	/** The switch is the runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryFluidAssertion() throws Exception {
		assertTrue(convertedHolds(restored) && !convertedHolds(off), "the restored predicate does not separate the runs");
		assertTrue(ticksHold(off) && !ticksHold(restored), "the control predicate does not separate the runs");
	}

	private static boolean convertedHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(CONVERTED) && run.printed(RESTORED) && wrapped(run).equals(List.of("neighborChanged", "onPlace"))
				&& !hooked(run, "shouldSpreadLiquid");
	}

	private static boolean ticksHold(WeaveHarness.Result run) throws Exception {
		return run.printed(ONLY_TICKS) && !run.printed(RESTORED) && wrapped(run).isEmpty() && hooked(run, "shouldSpreadLiquid");
	}

	/** The woven methods that no longer ask a registry themselves but hand the question to a merged Carpet wrap. */
	private static List<String> wrapped(WeaveHarness.Result run) throws Exception {
		return woven(run).methods.stream().filter(m -> calls(m, "forbric$unhandledFluidReaction$")).map(m -> m.name).sorted().toList();
	}

	private static boolean hooked(WeaveHarness.Result run, String method) throws Exception {
		MethodNode body = woven(run).methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
		return calls(body, "receiveFluidToBlackstone");
	}

	private static boolean calls(MethodNode method, String handler) {
		for (var insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(LIQUID) && call.name.contains(handler)) return true;
		}
		return false;
	}

	private static ClassNode woven(WeaveHarness.Result run) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(LIQUID)).accept(node, 0);
		return node;
	}

	/** The mod's required findings that the final class did not settle. */
	private static List<WeaveHarness.Finding> unsettled(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.required() && !"RESOLVED".equals(f.confidence())).toList();
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.carpetfluid.Probe", "probe", properties);
	}
}
