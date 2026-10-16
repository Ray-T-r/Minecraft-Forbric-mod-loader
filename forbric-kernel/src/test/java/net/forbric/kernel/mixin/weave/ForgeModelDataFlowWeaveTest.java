package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.ForgeModelDataFlowTransformer;

/**
 * MinecraftForge's block-model data through the real weave, on a fixture with the merged shape and names unrelated to
 * the game's or any mod's.
 *
 * <p>{@code PebbleMesher.mesh(…, seed)} carries the contextual family's body and is what the "game" calls; its carrier
 * overload {@code mesh(…, seed, ModelData)} is called by nothing. {@code JoiningTileModel} answers MinecraftForge's
 * questions only (it reads its neighbours in {@code getModelData}); the guest mixin hides a block it simulates at the
 * overload's start. The pre-Mixin chain is {@link ForgeModelDataFlowTransformer}, registered as KernelBoot registers
 * it, and the game-side helper it calls is the kernel's own source, compiled into the fixture.
 *
 * <ul>
 *   <li>flow — the tile is asked for its model data through the contextual question (two neighbours, and the block
 *       entity's tint from the level's manager); the guest's edge runs for every block meshed through the vanilla
 *       signature, with the data MinecraftForge's caller would compute, and its cancellation hides the simulated block;
 *       the overload, called directly, still meets the guest; the particle question reaches the block entity's data;</li>
 *   <li>edge-off — {@code -Dforbric.forgeModelDataEdge=off}: the models are still asked, but the guest never runs through
 *       the vanilla signature and the simulated block is drawn;</li>
 *   <li>off — {@code -Dforbric.forgeModelData=off}: as merged — no context reaches the tile, no guest runs, the simulated
 *       block is drawn; only the direct overload call meets the guest.</li>
 * </ul>
 */
class ForgeModelDataFlowWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/modeldataedge");
	private static final Path HELPER = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelForgeModelData.java");
	private static final String CONFIG = "modeldataedge.mixins.json";
	private static final String MOD = "modeldataedge";
	private static final String MESHER = "net/minecraft/client/renderer/pebble/PebbleMesher";
	private static final String GUEST = "[ModelDataEdge] guest edge ran for ";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result flow, edgeOff, off;

	@BeforeAll static void weaveThreeWays() throws Exception {
		List<Path> sources = new ArrayList<>();
		for (String source : List.of("net/minecraft/util/RandomSource", "net/minecraft/core/BlockPos",
				"net/minecraft/world/level/block/state/BlockState", "net/minecraftforge/client/model/data/ModelProperty",
				"net/minecraftforge/client/model/data/ModelData", "net/minecraftforge/client/model/data/ModelDataManager",
				"net/minecraft/client/renderer/block/BlockAndTintGetter", "net/minecraft/client/renderer/block/dispatch/BlockStateModelPart",
				"net/minecraft/client/resources/model/sprite/Material", "net/neoforged/neoforge/client/extensions/BlockStateModelExtension",
				"net/minecraftforge/client/extensions/IForgeBlockStateModel", "net/minecraft/client/renderer/block/dispatch/BlockStateModel",
				"net/minecraft/client/renderer/pebble/PebbleMesher", "fixture/pebble/JoiningTileModel", "fixture/pebble/PlainModel",
				"fixture/pebble/Probe", "fixture/pebble/mixin/PebbleMesherMixin")) {
			sources.add(SOURCES.resolve(source + ".java"));
		}
		sources.add(HELPER);
		fixture = WeaveHarness.fixture(work, "modeldataedge", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		flow = run("flow", Map.of());
		edgeOff = run("edge-off", Map.of("forbric.forgeModelDataEdge", "off"));
		off = run("off", Map.of(ForgeModelDataFlowTransformer.PROPERTY, "off"));
	}

	@Test void theModelIsAskedAndTheGuestRunsWhereverABlockIsMeshed() throws Exception {
		assertTrue(flow.printed(WeaveHarnessMain.DONE + " contextual:tile joined 2 red | contextual:plain"
				+ " | with-data:tile alone || particle tile red"), flow.describe());
		assertTrue(flow.printed(GUEST + "tile with joined 2 red"), "the edge carries the model's own data — " + flow.describe());
		assertTrue(flow.printed(GUEST + "simulated with joined 0"), flow.describe());
		assertTrue(flow.printed(GUEST + "stone with no join"), flow.describe());
		assertEquals(4, count(flow, GUEST), "three blocks meshed the game's way and one through the overload — " + flow.describe());
		assertTrue(flow.printed("now enters MinecraftForge's model-data entry mesh" + ForgeModelDataFlowTransformer.EDGE_SUFFIX),
				flow.describe());
		assertTrue(flow.printed("hideSimulated also starts PebbleMesher.mesh" + ForgeModelDataFlowTransformer.EDGE_SUFFIX),
				flow.describe());
		assertEquals(List.of(), open(flow), flow.describe());

		ClassNode woven = node(flow.defined(MESHER));
		MethodNode twin = method(woven, "mesh" + ForgeModelDataFlowTransformer.EDGE_SUFFIX);
		assertNotNull(twin, "the edge twin is defined");
		assertTrue(callsMergedHandler(twin), "the guest's handler starts the edge twin");
		assertTrue(callsMergedHandler(overload(woven)), "and still starts the overload");
		WeaveHarness.assertWovenAndVerified(flow, MESHER, fixture);
	}

	@Test void theEdgeSwitchKeepsTheModelsAskedAndTheGuestOnTheOverloadAlone() throws Exception {
		assertTrue(edgeOff.printed(WeaveHarnessMain.DONE + " contextual:tile joined 2 red | contextual:tile joined 0"
				+ " | contextual:plain | with-data:tile alone || particle tile red"), edgeOff.describe());
		assertEquals(1, count(edgeOff, GUEST), "only the direct overload call meets the guest — " + edgeOff.describe());
		assertTrue(edgeOff.printed(GUEST + "tile with no join"), edgeOff.describe());
		assertFalse(callsMergedHandler(method(node(edgeOff.defined(MESHER)), "mesh" + ForgeModelDataFlowTransformer.EDGE_SUFFIX)),
				"with the stage off the twin is left as the transformer made it");
		WeaveHarness.assertWovenAndVerified(edgeOff, MESHER, fixture);
	}

	@Test void offLeavesTheMergedShape() throws Exception {
		assertTrue(off.printed(WeaveHarnessMain.DONE + " contextual:tile alone | contextual:tile alone | contextual:plain"
				+ " | with-data:tile alone || particle tile plain"), off.describe());
		assertEquals(1, count(off, GUEST), off.describe());
		assertNull(method(node(off.defined(MESHER)), "mesh" + ForgeModelDataFlowTransformer.EDGE_SUFFIX), "no twin as merged");
		WeaveHarness.assertWovenAndVerified(off, MESHER, fixture);
	}

	/** Each run's outcome must fail on the others, or the controls prove nothing about the mechanism. */
	@Test void theControlsSeparateEveryOutcome() {
		String flowDone = WeaveHarnessMain.DONE + " contextual:tile joined 2 red | contextual:plain";
		assertTrue(flow.printed(flowDone) && !edgeOff.printed(flowDone) && !off.printed(flowDone));
		String asMerged = "contextual:tile alone";
		assertTrue(off.printed(asMerged) && !flow.printed(asMerged) && !edgeOff.printed(asMerged));
		assertTrue(count(flow, GUEST) == 4 && count(edgeOff, GUEST) == 1 && count(off, GUEST) == 1);
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, List.of(new WeaveHarness.Config(CONFIG, MOD, Ecosystem.FORGE)),
				List.of(ForgeModelDataFlowTransformer.class), EnvType.CLIENT, "fixture.pebble.Probe", "mesh", properties);
	}

	private static int count(WeaveHarness.Result run, String text) {
		int n = 0;
		for (String line : run.output().split("\n")) if (line.contains(text)) n++;
		return n;
	}

	private static List<WeaveHarness.Finding> open(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && !"RESOLVED".equals(f.confidence())).toList();
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name) {
		return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElse(null);
	}

	private static MethodNode overload(ClassNode node) {
		return node.methods.stream().filter(m -> m.name.equals("mesh") && m.desc.endsWith("ModelData;)V")).findFirst().orElseThrow();
	}

	private static boolean callsMergedHandler(MethodNode method) {
		if (method == null) return false;
		for (var insn : method.instructions) {
			if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.contains("hideSimulated")) return true;
		}
		return false;
	}
}
