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
import net.forbric.kernel.mixin.MixinBreathingCallbackAdapter;
import net.forbric.kernel.transform.BreathingCallbackInjector;

/**
 * {@code MixinBreathingCallbackAdapter} through the real weave: Create Fly's two breathing hooks, written against vanilla's
 * LivingEntity.baseTick (a wrap of isEyeInFluid(WATER) for the helmet in lava, a wrap of
 * MobEffectUtil.hasWaterBreathing for the backtank), on a merged baseTick that hands the whole calculation to NeoForge's
 * {@code CommonHooks.onLivingBreathe}.
 *
 * <p>The fixture's CommonHooks is the merged one after KernelBoot's {@code BreathingCallbackInjector}
 * ({@link PreMixinFixture}), so NeoForge's calculation consults {@code BreathingCallbackScope} at its start and at its
 * water-breathing check. The probe ticks a diver three times under water and once in lava. Adapted, one wrap around
 * NeoForge's call opens the scope with the mod's original handlers as callbacks: the backtank keeps the air at 10 and the
 * helmet acts in lava. With {@code -Dforbric.breathingCallbacks=off} neither wrap finds its vanilla call: the diver
 * loses air, the gear stays idle, and both handlers are the mod's required (SUSPECTED) losses.
 */
class CreateBreathingMixinAdapterWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/createbreathing");
	private static final String CONFIG = "createbreathing.mixins.json";
	private static final String MOD = "create";
	private static final String TARGET = "net/minecraft/world/entity/LivingEntity";

	private static final String SCOPE = "net/forbric/kernel/interop/BreathingCallbackScope";
	/** The one merged method whose NeoForge call the adapted wrap surrounds. */
	private static final List<String> HOSTS = List.of("baseTick");

	private static final String ADAPTED = WeaveHarnessMain.DONE + " air 10 | gear backtank, backtank, backtank, lava helmet";
	private static final String UNADAPTED = WeaveHarnessMain.DONE + " air 7 | gear idle";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;

	@BeforeAll static void weave() throws Exception {
		fixture = WeaveHarness.fixture(work, "createbreathing", List.of(
				SOURCES.resolve("net/minecraft/world/level/material/Fluid.java"),
				SOURCES.resolve("net/minecraft/tags/TagKey.java"),
				SOURCES.resolve("net/minecraft/tags/FluidTags.java"),
				SOURCES.resolve("net/minecraft/world/level/Level.java"),
				SOURCES.resolve("net/minecraft/server/level/ServerLevel.java"),
				SOURCES.resolve("net/minecraft/world/entity/LivingEntity.java"),
				SOURCES.resolve("net/minecraft/world/effect/MobEffectUtil.java"),
				SOURCES.resolve("net/neoforged/neoforge/common/CommonHooks.java"),
				SOURCES.resolve("fixture/createbreathing/Diver.java"),
				SOURCES.resolve("fixture/createbreathing/DivingHelmet.java"),
				SOURCES.resolve("fixture/createbreathing/Probe.java"),
				SOURCES.resolve("com/zurrtum/create/mixin/LivingEntityMixin.java"),
				// The adapted callbacks hand the mod constant Operations from the kernel's game-side
				// KernelWrapOperations, which ForbricClassLoader must define from a jar it owns; a fresh clone has no
				// compiled runtime source set, so the production source is compiled in here.
				Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelWrapOperations.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		// What the merged base's CommonHooks is once KernelBoot's BreathingCallbackInjector has run.
		PreMixinFixture.transform(fixture, BreathingCallbackInjector.TARGET, new BreathingCallbackInjector(), EnvType.SERVER);
		adapted = run("adapted", Map.of());
		off = run("adapter-off", Map.of(MixinBreathingCallbackAdapter.PROPERTY, "off"));
	}

	@Test void theOriginalHandlersRunInsideNeoForgesAirCalculation() throws Exception {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings " + adapted.findings());
		assertTrue(mixinRow(adapted).stream().allMatch(f -> f.confidence().equals("RESOLVED")), adapted.findings().toString());
		WeaveHarness.assertWovenAndVerified(adapted, TARGET, fixture);
	}

	@Test void switchedOffBothHooksAreLostAndTheDiverRunsOutOfAir() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings " + off.findings());
		WeaveHarness.assertWovenAndVerified(off, TARGET, fixture);
	}

	/** Each run's predicate must fail on the other, or the control proves nothing about the adapter. */
	@Test void theControlFlipsEveryAdapterAssertion() throws Exception {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapter predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) throws Exception {
		return returned(run, ADAPTED) && losses(run).isEmpty() && hostsCallingTheMod(run).equals(HOSTS) && entersScope(run);
	}

	private static boolean offHolds(WeaveHarness.Result run) throws Exception {
		List<WeaveHarness.Finding> losses = losses(run);
		return returned(run, UNADAPTED) && losses.size() == 2 && losses.stream().allMatch(WeaveHarness.Finding::required)
				&& losses.stream().anyMatch(f -> f.id().contains("#breatheInLava("))
				&& losses.stream().anyMatch(f -> f.id().contains("#canBreatheInWater(")) && hostsCallingTheMod(run).isEmpty()
				&& !entersScope(run);
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
		return run.findings().stream().filter(f -> f.id().equals("mixin:" + CONFIG + ":com.zurrtum.create.mixin.LivingEntityMixin")).toList();
	}

	/** Whether any method of the defined LivingEntity opens the kernel's BreathingCallbackScope. */
	private static boolean entersScope(WeaveHarness.Result run) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(TARGET)).accept(node, 0);
		for (MethodNode method : node.methods) {
			for (var insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(SCOPE) && call.name.equals("enter")) return true;
			}
		}
		return false;
	}

	/** Which of {@link #HOSTS} in the defined LivingEntity call a handler the mod's mixin merged into it, in class order. */
	private static List<String> hostsCallingTheMod(WeaveHarness.Result run) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(TARGET)).accept(node, 0);
		List<String> hosts = new java.util.ArrayList<>();
		for (MethodNode method : node.methods) {
			if (!HOSTS.contains(method.name)) continue;
			for (var insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(TARGET) && call.name.contains("$" + MOD + "$")) {
					hosts.add(method.name);
					break;
				}
			}
		}
		return hosts;
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.createbreathing.Probe", "probe", properties);
	}
}
