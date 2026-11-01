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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinFluidReactionAdapter;

/**
 * MixinFluidReactionAdapter's lava-meets-water wrap through the real weave, for a rule that is not Carpet's: another mod
 * turns SOURCE lava beside water into magma, makes its own sound instead of calling the block's fizz, names its target
 * by string, spells its selector with the descriptor and its point with an ordinal. On NeoForge's liquid block placement
 * calls vanilla's shouldSpreadLiquid, where the rule binds as compiled, and a neighbour change asks NeoForge's registry
 * (its own canInteract loop, vanilla's lava-meets-water rule registered), where the adapter wraps the registry question
 * with vanilla's own walk to the point ({@code KernelFluidReactions}, the production source compiled in).
 *
 * <p>Wrapped, source lava placed beside water becomes magma, flowing lava beside water whose neighbour changed becomes
 * cobblestone (the rule declines flowing lava, so the registry's own rule runs), lava in the open ticks, and source lava
 * beside water whose neighbour changed becomes magma too. With {@code -Dforbric.fluidReactionNeighbourChange=off} the
 * rule runs on placement alone, and the last cell becomes the registry's obsidian.
 */
class MixinFluidReactionWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/fluidreaction");
	private static final String CONFIG = "fluidreaction.mixins.json";
	private static final String MOD = "magma";
	private static final String LIQUID = "net/minecraft/world/level/block/LiquidBlock";
	private static final String WALK = "net/forbric/kernel/runtime/KernelFluidReactions";
	private static final String MAGMA = WeaveHarnessMain.DONE + " source=magma_block flowing=cobblestone open=air changed=magma_block"
			+ " events=[event1502@0,64,0, event1501@10,64,0, tick@20,64,0, event1502@30,64,0]";
	private static final String PLACEMENT_ONLY = WeaveHarnessMain.DONE + " source=magma_block flowing=cobblestone open=air changed=obsidian"
			+ " events=[event1502@0,64,0, event1501@10,64,0, tick@20,64,0, event1501@30,64,0]";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result wrapped, off;

	@BeforeAll static void weaveBothWays() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = new ArrayList<>(walk.filter(p -> p.toString().endsWith(".java")).sorted().toList());
		}
		assertEquals(17, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		// The wrap calls the kernel's game-side KernelFluidReactions, which ForbricClassLoader must define from a jar it
		// owns; a fresh clone has no compiled runtime source set, so the production source is compiled in here.
		sources.add(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelFluidReactions.java"));
		fixture = WeaveHarness.fixture(work, "fluidreaction", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		wrapped = run("wrapped", Map.of());
		off = run("neighbour-off", Map.of(MixinFluidReactionAdapter.NEIGHBOUR_PROPERTY, "off"));
	}

	@Test void sourceLavaBesideWaterFollowsTheModsRuleOnANeighbourChangeToo() throws Exception {
		assertTrue(wrapped.printed(MAGMA), wrapped.describe());
		assertEquals(List.of(), unsettled(wrapped), wrapped.findings() + "\n" + wrapped.describe());
		assertTrue(calls(woven(wrapped), "neighborChanged", "forbric$neighbourFluidReaction"), wrapped.describe());
		assertTrue(woven(wrapped).methods.stream().anyMatch(m -> callsOwner(m, WALK, "reachesWaterReaction")), wrapped.describe());
		assertTrue(calls(woven(wrapped), "shouldSpreadLiquid", "magma$sourceMeetsWater"), "placement keeps the rule as compiled");
		WeaveHarness.assertWovenAndVerified(wrapped, LIQUID, fixture);
	}

	@Test void switchedOffTheRuleRunsOnPlacementAloneAndTheRegistryMakesObsidian() throws Exception {
		assertTrue(off.printed(PLACEMENT_ONLY), off.describe());
		assertEquals(List.of(), unsettled(off), off.findings() + "\n" + off.describe());
		assertFalse(calls(woven(off), "neighborChanged", "forbric$neighbourFluidReaction"), off.describe());
		assertTrue(calls(woven(off), "shouldSpreadLiquid", "magma$sourceMeetsWater"), off.describe());
		WeaveHarness.assertWovenAndVerified(off, LIQUID, fixture);
	}

	/** The switch is the runs' only difference, so each run's output must reject the other. */
	@Test void theControlFlipsTheNeighbourChange() {
		assertTrue(wrapped.printed(MAGMA) && !off.printed(MAGMA), "the wrapped output does not separate the runs");
		assertTrue(off.printed(PLACEMENT_ONLY) && !wrapped.printed(PLACEMENT_ONLY), "the control output does not separate the runs");
	}

	/** Whether the woven {@code method} calls a merged handler whose name contains {@code handler}. */
	private static boolean calls(ClassNode woven, String method, String handler) {
		for (MethodNode m : woven.methods) if (m.name.equals(method)) {
			for (var insn : m.instructions) if (insn instanceof MethodInsnNode call && call.name.contains(handler)) return true;
		}
		return false;
	}

	private static boolean callsOwner(MethodNode method, String owner, String name) {
		for (var insn : method.instructions) if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
		return false;
	}

	private static ClassNode woven(WeaveHarness.Result run) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(LIQUID)).accept(node, 0);
		return node;
	}

	private static List<WeaveHarness.Finding> unsettled(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.required() && !"RESOLVED".equals(f.confidence())).toList();
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.fluidreaction.Probe", "probe", properties);
	}
}
