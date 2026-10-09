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
import net.forbric.kernel.mixin.MixinBlockInteractionAdapters;

/**
 * {@code MixinBlockInteractionAdapters}' SignalGetter rule through the real weave, for a mod that is not the rule's
 * sample and writes the same control another way: a relay mod's wrap of vanilla's {@code isRedstoneConductor} in
 * getSignal, by a bare selector, taking getSignal's position and side as Mixin appends a target's arguments after the
 * {@code Operation} — unannotated — where the sample took the side as an {@code @Local(argsOnly = true)}. The two are
 * the same value; the adapter must hand the moved wrap both, from the slots holding them at NeoForge's
 * {@code shouldCheckWeakPower}.
 *
 * <p>The probe reads the signal at a stone block and at the mod's baffle from the north and from the south, each beside
 * a strongly powered neighbour. The baffle passes weak power read from the south only, and only at its own position:
 * adapted it reads 0 from the north and 15 from the south, so the handler was handed the side and the position asked
 * about, and stone still reads 15 through the reordered original. Switched off, the wrap has no call to bind to and the
 * baffle conducts from both sides.
 */
class RelaySignalAppendedArgumentsWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/relaysignal");
	private static final Path STANDINS = Path.of("src/test/resources/weave/createinteraction");
	private static final String CONFIG = "relaysignal.mixins.json";
	private static final String MOD = "relay";
	private static final String TARGET = "net/minecraft/world/level/SignalGetter";

	private static final String ADAPTED = WeaveHarnessMain.DONE + " stone 15 | baffle north 0 | baffle south 15";
	private static final String UNADAPTED = WeaveHarnessMain.DONE + " stone 15 | baffle north 15 | baffle south 15";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;

	@BeforeAll static void weave() throws Exception {
		fixture = WeaveHarness.fixture(work, "relaysignal", List.of(
				STANDINS.resolve("net/minecraft/core/BlockPos.java"),
				STANDINS.resolve("net/minecraft/core/Direction.java"),
				STANDINS.resolve("net/minecraft/world/level/BlockGetter.java"),
				STANDINS.resolve("net/minecraft/world/level/SignalGetter.java"),
				STANDINS.resolve("net/minecraft/world/level/block/Block.java"),
				STANDINS.resolve("net/minecraft/world/level/block/state/BlockState.java"),
				SOURCES.resolve("org/example/relay/Damper.java"),
				SOURCES.resolve("fixture/relaysignal/Baffle.java"),
				SOURCES.resolve("fixture/relaysignal/Probe.java"),
				SOURCES.resolve("org/example/relay/mixin/RelayDampMixin.java"),
				// The moved wrap is handed a reordered Operation from the kernel's game-side KernelWrapOperations.
				Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelWrapOperations.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		adapted = run("adapted", Map.of());
		off = run("adapter-off", Map.of(MixinBlockInteractionAdapters.PROPERTY, "off"));
	}

	@Test void theAppendedSideAndPositionReachTheMovedWrap() throws Exception {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings " + adapted.findings());
		WeaveHarness.assertWovenAndVerified(adapted, TARGET, fixture);
	}

	@Test void switchedOffTheWrapIsUnboundAndTheBaffleConductsFromBothSides() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings " + off.findings());
		WeaveHarness.assertWovenAndVerified(off, TARGET, fixture);
	}

	/** Each run's predicate must fail on the other, or the control proves nothing about the adapter. */
	@Test void theControlFlipsEveryAdapterAssertion() throws Exception {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapter predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) throws Exception {
		return returned(run, ADAPTED) && losses(run).isEmpty() && callsHandler(run);
	}

	private static boolean offHolds(WeaveHarness.Result run) throws Exception {
		List<WeaveHarness.Finding> losses = losses(run);
		return returned(run, UNADAPTED) && losses.size() == 1 && losses.get(0).required() && !callsHandler(run);
	}

	private static boolean returned(WeaveHarness.Result run, String line) {
		return run.output().lines().anyMatch(line::equals);
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:")
				&& !f.confidence().equals("RESOLVED")).toList();
	}

	/** Whether the defined getSignal calls a handler the relay mod's mixin merged into SignalGetter. */
	private static boolean callsHandler(WeaveHarness.Result run) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(TARGET)).accept(node, 0);
		MethodNode body = node.methods.stream().filter(m -> m.name.equals("getSignal")).findFirst().orElseThrow();
		for (var insn : body.instructions)
			if (insn instanceof MethodInsnNode call && call.owner.equals(TARGET) && call.name.contains("$" + MOD + "$")) return true;
		return false;
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.relaysignal.Probe", "probe", properties);
	}
}
