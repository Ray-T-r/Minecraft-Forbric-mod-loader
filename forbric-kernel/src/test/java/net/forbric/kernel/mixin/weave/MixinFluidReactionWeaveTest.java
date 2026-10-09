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
 * MixinFluidReactionAdapter's lava-meets-water move through the real weave, for a rule that is not Carpet's: another mod
 * turns SOURCE lava beside water into magma, makes its own sound instead of calling the block's fizz, names its target
 * by string, spells its selector with the descriptor and its point with an ordinal. On a liquid block whose placement and
 * neighbour change ask the two families' interaction registries (each with its own canInteract loop and vanilla's
 * lava-meets-water rule registered), the rule runs where the registries make that reaction.
 *
 * <p>Moved, source lava placed beside water becomes magma (the mod's rule, which declines flowing lava), flowing lava
 * beside water becomes cobblestone (the registry's own rule), and lava in the open ticks. Switched off, the rule is woven
 * into the shouldSpreadLiquid nothing calls, and source lava becomes the registry's obsidian.
 */
class MixinFluidReactionWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/fluidreaction");
	private static final String CONFIG = "fluidreaction.mixins.json";
	private static final String MOD = "magma";
	private static final String LIQUID = "net/minecraft/world/level/block/LiquidBlock";
	private static final List<String> REGISTRIES = List.of("net/neoforged/neoforge/fluids/FluidInteractionRegistry",
			"net/minecraftforge/fluids/FluidInteractionRegistry");
	private static final String MAGMA = WeaveHarnessMain.DONE + " source=magma_block flowing=cobblestone open=air"
			+ " events=[event1502@0,64,0, event1501@10,64,0, tick@20,64,0]";
	private static final String NATIVE = WeaveHarnessMain.DONE + " source=obsidian flowing=cobblestone open=air"
			+ " events=[event1501@0,64,0, event1501@10,64,0, tick@20,64,0]";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, off;

	@BeforeAll static void weaveBothWays() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
		assertEquals(18, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		fixture = WeaveHarness.fixture(work, "fluidreaction", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		moved = run("moved", Map.of());
		off = run("off", Map.of(MixinPlayerWorldCallbackAdapter.PROPERTY, "off"));
	}

	@Test void sourceLavaBesideWaterFollowsTheModsRuleWhereTheRegistriesReact() throws Exception {
		assertTrue(moved.printed(MAGMA), moved.describe());
		assertEquals(List.of(), unsettled(moved), moved.findings() + "\n" + moved.describe());
		for (String registry : REGISTRIES) assertTrue(calls(woven(moved, registry), "canInteract", "forbric$flowingFluidReaction"), registry);
		assertFalse(calls(woven(moved, LIQUID), "shouldSpreadLiquid", "magma$sourceMeetsWater"), moved.describe());
	}

	@Test void switchedOffTheRuleSitsInTheMethodNothingCalls() throws Exception {
		assertTrue(off.printed(NATIVE), off.describe());
		assertTrue(calls(woven(off, LIQUID), "shouldSpreadLiquid", "magma$sourceMeetsWater"), off.describe());
	}

	/** Whether the woven {@code method} calls a merged handler whose name contains {@code handler}. */
	private static boolean calls(ClassNode woven, String method, String handler) {
		for (MethodNode m : woven.methods) if (m.name.equals(method)) {
			for (var insn : m.instructions) if (insn instanceof MethodInsnNode call && call.name.contains(handler)) return true;
		}
		return false;
	}

	private static ClassNode woven(WeaveHarness.Result run, String name) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(name)).accept(node, 0);
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
