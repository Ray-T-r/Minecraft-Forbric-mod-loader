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
 * {@code MixinBlockInteractionAdapters} through the real weave, on its SignalGetter rule: Create Fly's interface mixin
 * wraps vanilla's {@code isRedstoneConductor} call in getSignal so a block of the mod decides whether weak power
 * passes through it; the merged getSignal asks NeoForge's {@code shouldCheckWeakPower(SignalGetter, BlockPos,
 * Direction)} instead.
 *
 * <p>The probe reads the signal at a stone block and at the mod's gearshift, a conducting block that says weak power
 * never passes, each beside a strongly powered neighbour. Adapted, the mod's handler wraps NeoForge's call: the
 * gearshift reads 0, and stone still reads 15 through the handler's original call, which the kernel's reordered
 * Operation turns into NeoForge's. With {@code -Dforbric.blockInteractionAdapters=off} the wrap has no call to bind
 * to: the gearshift reads 15 and the handler is the mod's required (SUSPECTED) loss. The adapter's other three
 * rules are not exercised here.
 */
class CreateInteractionMixinAdaptersWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/createinteraction");
	private static final String CONFIG = "createinteraction.mixins.json";
	private static final String MOD = "create";
	private static final String TARGET = "net/minecraft/world/level/SignalGetter";

	private static final String RUNTIME = "net/forbric/kernel/runtime/KernelWrapOperations";

	private static final String ADAPTED = WeaveHarnessMain.DONE + " stone 15 | gearshift 0";
	private static final String UNADAPTED = WeaveHarnessMain.DONE + " stone 15 | gearshift 15";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;

	@BeforeAll static void weave() throws Exception {
		fixture = WeaveHarness.fixture(work, "createinteraction", List.of(
				SOURCES.resolve("net/minecraft/core/BlockPos.java"),
				SOURCES.resolve("net/minecraft/core/Direction.java"),
				SOURCES.resolve("net/minecraft/world/level/BlockGetter.java"),
				SOURCES.resolve("net/minecraft/world/level/SignalGetter.java"),
				SOURCES.resolve("net/minecraft/world/level/block/Block.java"),
				SOURCES.resolve("net/minecraft/world/level/block/state/BlockState.java"),
				SOURCES.resolve("com/zurrtum/create/foundation/block/WeakPowerControlBlock.java"),
				SOURCES.resolve("fixture/createinteraction/Gearshift.java"),
				SOURCES.resolve("fixture/createinteraction/Probe.java"),
				SOURCES.resolve("com/zurrtum/create/mixin/SignalGetterMixin.java"),
				// The adapted handler hands the mod a reordered Operation from the kernel's game-side
				// KernelWrapOperations, which ForbricClassLoader must define from a jar it owns; a fresh clone has no
				// compiled runtime source set, so the production source is compiled in here.
				Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelWrapOperations.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		adapted = run("adapted", Map.of());
		off = run("adapter-off", Map.of(MixinBlockInteractionAdapters.PROPERTY, "off"));
	}

	@Test void theModsBlockDecidesAtNeoForgesWeakPowerQuery() throws Exception {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings " + adapted.findings());
		assertTrue(mixinRow(adapted).stream().allMatch(f -> f.confidence().equals("RESOLVED")), adapted.findings().toString());
		WeaveHarness.assertWovenAndVerified(adapted, TARGET, fixture);
	}

	@Test void switchedOffTheWrapIsUnboundAndWeakPowerPassesTheModsBlock() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings " + off.findings());
		WeaveHarness.assertWovenAndVerified(off, TARGET, fixture);
	}

	/** Each run's predicate must fail on the other, or the control proves nothing about the adapter. */
	@Test void theControlFlipsEveryAdapterAssertion() throws Exception {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapter predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) throws Exception {
		return returned(run, ADAPTED) && losses(run).isEmpty() && callsHandler(run, "getSignal") && callsRuntime(run);
	}

	private static boolean offHolds(WeaveHarness.Result run) throws Exception {
		List<WeaveHarness.Finding> losses = losses(run);
		return returned(run, UNADAPTED) && losses.size() == 1 && losses.get(0).id().contains("#skip(")
				&& losses.get(0).required() && !callsHandler(run, "getSignal") && !callsRuntime(run);
	}

	/** The probe's whole return line: a value that merely starts with the expected one is a different outcome. */
	private static boolean returned(WeaveHarness.Result run, String line) {
		return run.output().lines().anyMatch(line::equals);
	}

	/** The final audit's rows for the mod's injectors that did not attach. */
	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:")
				&& !f.confidence().equals("RESOLVED")).toList();
	}

	private static List<WeaveHarness.Finding> mixinRow(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.id().equals("mixin:" + CONFIG + ":com.zurrtum.create.mixin.SignalGetterMixin")).toList();
	}

	/** Whether any method of the defined SignalGetter builds the kernel's reordered Operation. */
	private static boolean callsRuntime(WeaveHarness.Result run) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(TARGET)).accept(node, 0);
		for (MethodNode method : node.methods) {
			for (var insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(RUNTIME) && call.name.equals("reordered")) return true;
			}
		}
		return false;
	}

	/** Whether {@code method} of the defined SignalGetter calls a handler the mod's mixin merged into it. */
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
				"fixture.createinteraction.Probe", "probe", properties);
	}
}
