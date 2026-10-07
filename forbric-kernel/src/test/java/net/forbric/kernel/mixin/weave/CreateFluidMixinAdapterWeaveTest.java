package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinFluidInteractionAdapter;

/**
 * {@code MixinFluidInteractionAdapter} through the real weave: Create Fly's fluid reaction hook, a cancellable HEAD
 * {@code @Inject} on vanilla's {@code shouldSpreadLiquid}, on a merged LiquidBlock where onPlace asks MinecraftForge's
 * interaction registry and neighborChanged NeoForge's, and nothing calls shouldSpreadLiquid any more.
 *
 * <p>The probe places a fluid where the mod's registry reacts it and where it does not, then updates each from a
 * neighbour. Adapted, the mod's original handler guards both live registry calls: where it reacts, neither native
 * registry is asked and the fluid does not flow; elsewhere the native registry decides as before. With
 * {@code -Dforbric.fluidInteractionCallbacks=off} the hook still binds — to the dead method — so it never runs: the reactive
 * fluid flows on both updates, and no finding says so.
 */
class CreateFluidMixinAdapterWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/createfluid");
	private static final String CONFIG = "createfluid.mixins.json";
	private static final String MOD = "create";
	private static final String TARGET = "net/minecraft/world/level/block/LiquidBlock";
	private static final String ADAPTER_LOG = "[Forbric/Mixin] original fluid interaction callback now guards both live carrier reaction sites";

	private static final String ADAPTED = WeaveHarnessMain.DONE + " place: create reacted lava | place: forge none water, flow water"
			+ " | neighbor: create reacted lava | neighbor: neoforge none water, flow water";
	private static final String UNADAPTED = WeaveHarnessMain.DONE + " place: forge none lava, flow lava | place: forge none water, flow water"
			+ " | neighbor: neoforge none lava, flow lava | neighbor: neoforge none water, flow water";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;

	@BeforeAll static void weave() throws Exception {
		fixture = WeaveHarness.fixture(work, "createfluid", List.of(
				SOURCES.resolve("net/minecraft/core/BlockPos.java"),
				SOURCES.resolve("net/minecraft/world/level/block/state/BlockState.java"),
				SOURCES.resolve("net/minecraft/world/level/block/Block.java"),
				SOURCES.resolve("net/minecraft/world/level/redstone/Orientation.java"),
				SOURCES.resolve("net/minecraft/world/level/Level.java"),
				SOURCES.resolve("net/minecraftforge/fluids/FluidInteractionRegistry.java"),
				SOURCES.resolve("net/neoforged/neoforge/fluids/FluidInteractionRegistry.java"),
				SOURCES.resolve("net/minecraft/world/level/block/LiquidBlock.java"),
				SOURCES.resolve("com/zurrtum/create/infrastructure/fluids/FluidInteractionRegistry.java"),
				SOURCES.resolve("fixture/createfluid/Probe.java"),
				SOURCES.resolve("com/zurrtum/create/mixin/LiquidBlockMixin.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		adapted = run("adapted", Map.of());
		off = run("adapter-off", Map.of(MixinFluidInteractionAdapter.PROPERTY, "off"));
	}

	@Test void theOriginalHandlerGuardsBothLiveRegistryCalls() throws Exception {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings " + adapted.findings());
		assertEquals(List.of(), adapted.findings().stream().filter(f -> f.modId().equals(MOD)).toList(), adapted.describe());
		WeaveHarness.assertWovenAndVerified(adapted, TARGET, fixture);
	}

	@Test void switchedOffTheHookBindsToTheDeadMethodAndTheReactiveFluidFlows() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings " + off.findings());
		// Silent: the hook attached, so no audit row says the reaction is gone.
		assertEquals(List.of(), off.findings().stream().filter(f -> f.modId().equals(MOD)).toList(), off.describe());
		WeaveHarness.assertWovenAndVerified(off, TARGET, fixture);
	}

	/** Each run's predicate must fail on the other, or the control proves nothing about the adapter. */
	@Test void theControlFlipsEveryAdapterAssertion() throws Exception {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapter predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) throws Exception {
		return returned(run, ADAPTED) && run.printed(ADAPTER_LOG) && callsHandler(run, "onPlace") && callsHandler(run, "neighborChanged")
				&& !callsHandler(run, "shouldSpreadLiquid");
	}

	private static boolean offHolds(WeaveHarness.Result run) throws Exception {
		return returned(run, UNADAPTED) && !run.printed(ADAPTER_LOG) && !callsHandler(run, "onPlace")
				&& !callsHandler(run, "neighborChanged") && callsHandler(run, "shouldSpreadLiquid");
	}

	/** The probe's whole return line: a value that merely starts with the expected one is a different outcome. */
	private static boolean returned(WeaveHarness.Result run, String line) {
		return run.output().lines().anyMatch(line::equals);
	}

	/** Whether {@code method} of the defined LiquidBlock calls a handler the mod's mixin merged into it. */
	private static boolean callsHandler(WeaveHarness.Result run, String method) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(TARGET)).accept(node, 0);
		MethodNode body = node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
		for (var insn : body.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(TARGET) && call.name.contains("$" + MOD + "$")) return true;
		}
		return false;
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.createfluid.Probe", "probe", properties);
	}
}
