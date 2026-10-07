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
import net.forbric.kernel.mixin.MixinEntitySoundCallbackAdapter;
import net.forbric.kernel.transform.BlockSoundQueryInjector;

/**
 * {@code MixinEntitySoundCallbackAdapter} through the real weave, on its step-sound rule: Create Fly's EntityMixin wraps
 * vanilla's {@code state.getSoundType()} in Entity.playStepSound so a block of the mod picks its sound group from the
 * level and the step's position. On the merged base the three step-sound methods hand the step to NeoForge's
 * {@code BlockState.playStepSound}, and NeoForge's IBlockExtension asks the state for the sound group itself.
 *
 * <p>The fixture's IBlockExtension is the merged one after KernelBoot's {@code BlockSoundQueryInjector}
 * ({@link PreMixinFixture}), so NeoForge's sound query asks the kernel's {@code KernelBlockSoundQuery}, compiled in from
 * {@code src/runtime/java}, which consults {@code BlockSoundCallbackScope}. The probe steps onto stone, onto the mod's running
 * belt, and onto stone with the belt muffled under it. Adapted, the mod's handler wraps each of the three native calls,
 * opens the scope around it and is asked from inside NeoForge's own query: the belt sounds "belt" at both volumes.
 * With {@code -Dforbric.entitySoundCallbacks=off} the wrap has no getSoundType() call to bind to: the belt sounds its
 * plain "metal" and the handler is the mod's required (SUSPECTED) loss. The landing-sound rule is not exercised here.
 */
class CreateEntitySoundMixinAdapterWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/createentitysound");
	private static final String CONFIG = "createentitysound.mixins.json";
	private static final String MOD = "create";
	private static final String TARGET = "net/minecraft/world/entity/Entity";

	private static final String SCOPE = "net/forbric/kernel/interop/BlockSoundCallbackScope";
	/** The merged Entity's three step-sound methods, each making one native BlockState.playStepSound call. */
	private static final List<String> HOSTS = List.of("playStepSound", "playCombinationStepSounds", "playMuffledStepSound");

	private static final String ADAPTED = WeaveHarnessMain.DONE + " stone 0.15, belt 0.15, stone 0.15, belt 0.05";
	private static final String UNADAPTED = WeaveHarnessMain.DONE + " stone 0.15, metal 0.15, stone 0.15, metal 0.05";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;

	@BeforeAll static void weave() throws Exception {
		fixture = WeaveHarness.fixture(work, "createentitysound", List.of(
				SOURCES.resolve("net/minecraft/core/BlockPos.java"),
				SOURCES.resolve("net/minecraft/world/level/LevelReader.java"),
				SOURCES.resolve("net/minecraft/world/level/Level.java"),
				SOURCES.resolve("net/minecraft/world/level/block/SoundType.java"),
				SOURCES.resolve("net/minecraft/world/level/block/Block.java"),
				SOURCES.resolve("net/minecraft/world/level/block/state/BlockState.java"),
				SOURCES.resolve("net/neoforged/neoforge/common/extensions/IBlockExtension.java"),
				SOURCES.resolve("net/minecraft/world/entity/Entity.java"),
				SOURCES.resolve("com/zurrtum/create/foundation/block/SoundControlBlock.java"),
				SOURCES.resolve("fixture/createentitysound/Belt.java"),
				SOURCES.resolve("fixture/createentitysound/Probe.java"),
				SOURCES.resolve("com/zurrtum/create/mixin/EntityMixin.java"),
				// The kernel's game-side hooks the adapted code reaches, which ForbricClassLoader must define from a jar it
				// owns; a fresh clone has no compiled runtime source set, so the production sources are compiled in here.
				Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelWrapOperations.java"),
				Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelBlockSoundQuery.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)),
				// KernelBlockSoundQuery names the boot-side BlockSoundCallbackScope: resolved from source, not packed, since
				// the loader always takes that package from the kernel.
				List.of("-sourcepath", "src/main/java", "-implicit:none"));
		// What the merged base's IBlockExtension is once KernelBoot's BlockSoundQueryInjector has run.
		PreMixinFixture.transform(fixture, BlockSoundQueryInjector.TARGET, new BlockSoundQueryInjector(), EnvType.SERVER);
		adapted = run("adapted", Map.of());
		off = run("adapter-off", Map.of(MixinEntitySoundCallbackAdapter.PROPERTY, "off"));
	}

	@Test void theModsBlockPicksItsSoundInsideNeoForgesStepSound() throws Exception {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings " + adapted.findings());
		assertTrue(mixinRow(adapted).stream().allMatch(f -> f.confidence().equals("RESOLVED")), adapted.findings().toString());
		WeaveHarness.assertWovenAndVerified(adapted, TARGET, fixture);
	}

	@Test void switchedOffTheWrapIsUnboundAndTheBeltSoundsItsPlainGroup() throws Exception {
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
		return returned(run, UNADAPTED) && losses.size() == 1 && losses.get(0).id().contains("#getStepSound(")
				&& losses.get(0).required() && hostsCallingTheMod(run).isEmpty() && !entersScope(run);
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
		return run.findings().stream().filter(f -> f.id().equals("mixin:" + CONFIG + ":com.zurrtum.create.mixin.EntityMixin")).toList();
	}

	/** Whether any method of the defined Entity opens the kernel's BlockSoundCallbackScope. */
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

	/** Which of the step-sound methods of the defined Entity call a handler the mod's mixin merged into it, in class order. */
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
				"fixture.createentitysound.Probe", "probe", properties);
	}
}
