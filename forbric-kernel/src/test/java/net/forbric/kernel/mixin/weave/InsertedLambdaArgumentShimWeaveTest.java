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

/**
 * InsertedLambdaArgumentShim through the real weave: Litematica's two schematic passes, selected by vanilla's main-pass
 * lambda name and descriptor, on a renderer whose one lambda of that name is NeoForge's, with a pose inserted between the
 * state and the sections.
 *
 * <p>The evidence the shim acts on is the native reference: vanilla's renderer, indexed into the fixture as the FABRIC
 * reference a launch reads from the vanilla-reference sidecar, declares the lambda in the shape the selector spells. (Until
 * Stage 4 the merged base carried vanilla's body beside the carrier's and the duplicate-lambda pruner's record was the
 * evidence; NeoForge's base carries one body.) The probe renders one main pass and reports what was drawn. Shimmed, each
 * pass runs right after its layer group with the arguments it was written for, in its own order, the inserted pose left
 * out. With {@code -Dforbric.insertedLambdaArguments=off} the selector names a shape the class does not have: the fit
 * check, which asks the shim where such a selector lands, finds no anchor and leaves the mixin out of its config, neither
 * pass ever draws, and the mod gets a confirmed finding.
 */
class InsertedLambdaArgumentShimWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/insertedlambda");
	private static final String CONFIG = "insertedlambda.mixins.json";
	private static final String MOD = "litematica";
	/** InsertedLambdaArgumentShim's switch; its constant is package-private, so the census checks the name instead. */
	private static final String SWITCH = "forbric.insertedLambdaArguments";
	private static final String RENDERER = "net/minecraft/client/renderer/LevelRenderer";
	private static final String LAMBDA = "lambda$addMainPass$0";
	private static final String LIVE = "(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lorg/joml/Matrix4fc;"
			+ "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;F)V";
	private static final String DRAWN = WeaveHarnessMain.DONE + " [opaque, schematic opaque(state, sections, 0.25), "
			+ "translucent, schematic translucent(state, sections, 0.25)]";
	private static final String NOT_DRAWN = WeaveHarnessMain.DONE + " [opaque, translucent]";
	private static final String LEFT_OUT = "auto-suppressing guest mixin litematica (" + CONFIG + "):render.MixinLevelRenderer — UNFIT";
	private static final String SHIM_LOG = "MixinLevelRenderer.litematica_renderMainSection_Opaque forwards the uniquely aligned "
			+ "original arguments to the retained " + LAMBDA + LIVE;

	/** Vanilla's renderer: the main-pass lambda without the pose, the shape Litematica's selectors spell. */
	private static final String VANILLA_RENDERER = """
			package net.minecraft.client.renderer;
			import java.util.ArrayList;
			import java.util.List;
			import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
			import net.minecraft.client.renderer.state.level.LevelRenderState;
			public class LevelRenderer {
			 public final List<String> trace = new ArrayList<>();
			 public void addMainPass(LevelRenderState state, ChunkSectionsToRender sections) { lambda$addMainPass$0(state, sections, 0.25F); }
			 private void lambda$addMainPass$0(LevelRenderState state, ChunkSectionsToRender sections, float alpha) {
			  sections.renderGroup(trace, "opaque");
			  sections.renderGroup(trace, "translucent");
			 }
			}
			""";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result shimmed, off;

	@BeforeAll static void weaveBothWays() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
		assertEquals(6, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		Path current = WeaveHarness.fixture(work, "insertedlambda", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		Path vanilla = work.resolve("native-src/net/minecraft/client/renderer/LevelRenderer.java");
		Files.createDirectories(vanilla.getParent());
		Files.writeString(vanilla, VANILLA_RENDERER);
		List<Path> nativeSources = sources.stream().map(p -> p.endsWith(Path.of("net/minecraft/client/renderer/LevelRenderer.java")) ? vanilla : p).toList();
		Path nativeFixture = WeaveHarness.fixture(work, "native-insertedlambda", nativeSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		fixture = NativeWeaveReferences.with(work, current, Map.of(RENDERER, NativeWeaveReferences.classes(nativeFixture).get(RENDERER)));
		shimmed = run("shimmed", Map.of());
		off = run("shim-off", Map.of(SWITCH, "off"));
	}

	@Test void bothPassesDrawWithTheArgumentsTheyWereWrittenFor() throws Exception {
		assertTrue(shimmed.printed(DRAWN), shimmed.describe());
		assertTrue(shimmed.printed(SHIM_LOG), shimmed.describe());
		assertTrue(shimmed.printed("MixinLevelRenderer.litematica_renderMainSection_Translucent forwards"), shimmed.describe());
		assertFalse(shimmed.printed(LEFT_OUT), shimmed.describe());
		assertEquals(List.of(), modFindingsNotResolved(shimmed), shimmed.findings() + "\n" + shimmed.describe());
		assertEquals(2, handlerCallsInLiveLambda(shimmed), shimmed.describe());
		WeaveHarness.assertWovenAndVerified(shimmed, RENDERER, fixture);
	}

	@Test void switchedOffTheSelectorNamesAShapeTheClassLacksAndNeitherPassDraws() throws Exception {
		assertTrue(off.printed(NOT_DRAWN), off.describe());
		assertFalse(off.printed("forwards the uniquely aligned"), off.describe());
		assertTrue(off.printed(LEFT_OUT), off.describe());
		assertEquals(List.of("mixin:" + CONFIG + ":fi.dy.masa.litematica.mixin.render.MixinLevelRenderer CONFIRMED"),
				modFindingsNotResolved(off).stream().map(f -> f.id() + " " + f.confidence()).toList(), off.findings().toString());
		assertEquals(0, handlerCallsInLiveLambda(off), off.describe());
		WeaveHarness.assertWovenAndVerified(off, RENDERER, fixture);
	}

	/** The switch is the runs' only difference, so each run's predicate must reject the other. */
	@Test void theControlFlipsEveryShimAssertion() throws Exception {
		assertTrue(drawnHolds(shimmed) && !drawnHolds(off), "the shim predicate does not separate the runs");
		assertTrue(notDrawnHolds(off) && !notDrawnHolds(shimmed), "the control predicate does not separate the runs");
	}

	private static boolean drawnHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(DRAWN) && run.printed(SHIM_LOG) && !run.printed(LEFT_OUT) && modFindingsNotResolved(run).isEmpty()
				&& handlerCallsInLiveLambda(run) == 2;
	}

	private static boolean notDrawnHolds(WeaveHarness.Result run) throws Exception {
		return run.printed(NOT_DRAWN) && !run.printed(SHIM_LOG) && run.printed(LEFT_OUT) && modFindingsNotResolved(run).size() == 1
				&& handlerCallsInLiveLambda(run) == 0;
	}

	/** Calls from the defined live lambda into a merged handler of the mod's: one per pass woven into it. */
	private static long handlerCallsInLiveLambda(WeaveHarness.Result run) throws Exception {
		ClassNode renderer = new ClassNode();
		new ClassReader(run.defined(RENDERER)).accept(renderer, 0);
		List<MethodNode> lambdas = renderer.methods.stream().filter(m -> m.name.equals(LAMBDA)).toList();
		assertEquals(List.of(LIVE), lambdas.stream().map(m -> m.desc).toList(), "one body of the name, NeoForge's");
		long calls = 0;
		for (var insn : lambdas.get(0).instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(RENDERER) && call.name.contains("litematica_renderMainSection_")) {
				calls++;
			}
		}
		return calls;
	}

	/** The mod's findings that are not settled: a lost injector, or a mixin left out for having nothing to bind. */
	private static List<WeaveHarness.Finding> modFindingsNotResolved(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.required() && !"RESOLVED".equals(f.confidence())).toList();
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, List.of(new WeaveHarness.Config(CONFIG, MOD, Ecosystem.FABRIC)),
				List.of(), EnvType.CLIENT, "fixture.insertedlambda.Probe", "probe", properties);
	}
}
