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
import net.forbric.kernel.mixin.MixinBlockQueryAdapters;

/**
 * {@code MixinBlockQueryAdapters} through the real weave, on its block-receiver rule for explosion resistance:
 * Create Fly's ExplosionDamageCalculatorMixin wraps vanilla's {@code Block.getExplosionResistance()} so a block of the
 * mod answers with the calculator's level and position ({@code @Local(argsOnly)}); the merged calculator asks
 * NeoForge's {@code BlockState.getExplosionResistance(BlockGetter, BlockPos, Explosion)} instead.
 *
 * <p>The probe asks how much a stone block and the mod's casing, standing reinforced, resist one explosion. Adapted, the
 * mod's handler wraps NeoForge's state call: the casing answers 1200 for the position it was handed, and stone still
 * answers 6 through the handler's original call, which the kernel's reordered Operation turns into NeoForge's. With
 * {@code -Dforbric.blockQueryAdapters=off} the wrap has no call to bind to: the casing answers its plain 3 and the
 * handler is the mod's required (SUSPECTED) loss. The friction and scaffolding rules are not exercised here.
 */
class CreateContextualBlockAdaptersWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/createcontextualblock");
	private static final String CONFIG = "createcontextualblock.mixins.json";
	private static final String MOD = "create";
	private static final String TARGET = "net/minecraft/world/level/ExplosionDamageCalculator";

	private static final String RUNTIME = "net/forbric/kernel/runtime/KernelWrapOperations";

	private static final String ADAPTED = WeaveHarnessMain.DONE + " stone 6.0 | casing 1200.0";
	private static final String UNADAPTED = WeaveHarnessMain.DONE + " stone 6.0 | casing 3.0";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;

	@BeforeAll static void weave() throws Exception {
		fixture = WeaveHarness.fixture(work, "createcontextualblock", List.of(
				SOURCES.resolve("net/minecraft/core/BlockPos.java"),
				SOURCES.resolve("net/minecraft/world/level/BlockGetter.java"),
				SOURCES.resolve("net/minecraft/world/level/Explosion.java"),
				SOURCES.resolve("net/minecraft/world/level/block/Block.java"),
				SOURCES.resolve("net/minecraft/world/level/block/state/BlockState.java"),
				SOURCES.resolve("net/minecraft/world/level/material/FluidState.java"),
				SOURCES.resolve("net/minecraft/world/level/ExplosionDamageCalculator.java"),
				SOURCES.resolve("com/zurrtum/create/foundation/block/ResistanceControlBlock.java"),
				SOURCES.resolve("fixture/createcontextualblock/Casing.java"),
				SOURCES.resolve("fixture/createcontextualblock/Probe.java"),
				SOURCES.resolve("com/zurrtum/create/mixin/ExplosionDamageCalculatorMixin.java"),
				// The adapted handler hands the mod a reordered Operation from the kernel's game-side
				// KernelWrapOperations, which ForbricClassLoader must define from a jar it owns; a fresh clone has no
				// compiled runtime source set, so the production source is compiled in here.
				Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelWrapOperations.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		adapted = run("adapted", Map.of());
		off = run("adapter-off", Map.of(MixinBlockQueryAdapters.PROPERTY, "off"));
	}

	@Test void theModsBlockAnswersAtNeoForgesStateQuery() throws Exception {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings " + adapted.findings());
		assertTrue(mixinRow(adapted).stream().allMatch(f -> f.confidence().equals("RESOLVED")), adapted.findings().toString());
		WeaveHarness.assertWovenAndVerified(adapted, TARGET, fixture);
	}

	@Test void switchedOffTheWrapIsUnboundAndTheCasingAnswersItsPlainResistance() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings " + off.findings());
		WeaveHarness.assertWovenAndVerified(off, TARGET, fixture);
	}

	/** Each run's predicate must fail on the other, or the control proves nothing about the adapter. */
	@Test void theControlFlipsEveryAdapterAssertion() throws Exception {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapter predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) throws Exception {
		return returned(run, ADAPTED) && losses(run).isEmpty() && callsHandler(run, "getBlockExplosionResistance") && callsRuntime(run);
	}

	private static boolean offHolds(WeaveHarness.Result run) throws Exception {
		List<WeaveHarness.Finding> losses = losses(run);
		return returned(run, UNADAPTED) && losses.size() == 1 && losses.get(0).id().contains("#getBlastResistance(")
				&& losses.get(0).required() && !callsHandler(run, "getBlockExplosionResistance") && !callsRuntime(run);
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
		return run.findings().stream().filter(f -> f.id().equals("mixin:" + CONFIG + ":com.zurrtum.create.mixin.ExplosionDamageCalculatorMixin")).toList();
	}

	/** Whether any method of the defined calculator builds the kernel's reordered Operation. */
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

	/** Whether {@code method} of the defined calculator calls a handler the mod's mixin merged into it. */
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
				"fixture.createcontextualblock.Probe", "probe", properties);
	}
}
