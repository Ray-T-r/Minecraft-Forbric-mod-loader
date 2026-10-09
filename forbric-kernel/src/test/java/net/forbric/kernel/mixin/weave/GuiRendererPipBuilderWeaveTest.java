package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer;

/**
 * The picture-in-picture map judged against vanilla: vanilla's GuiRenderer constructor fills it from
 * {@code ImmutableMap.builder()}, the one point there a mod can hook; NeoForge's has no builder at all, so every injector
 * at that point bound nothing. The kernel now builds the map in vanilla's shape ({@code ForbricMergedBaseCompatTransformer}),
 * and no adapter rewrites any mod's injector.
 *
 * <p>Four unrelated mods hook the builder four ways: an {@code @Inject} before it swapping the constructor's renderer list
 * through a {@code LocalRef} (SuperMartijn642's core lib's form), an {@code @ModifyExpressionValue}, a
 * {@code @WrapOperation} with an {@code @Local(argsOnly = true)} capture, and — in a run of its own, as it replaces the call
 * outright — a {@code @Redirect}. Woven onto vanilla-shaped and onto merged-shaped classes, the map must hold the same
 * renderers. On the merged classes as NeoForge left them, every one of those injectors is lost.
 */
class GuiRendererPipBuilderWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/pipbuilder");
	private static final String GUI = "net/minecraft/client/gui/render/GuiRenderer";
	private static final List<WeaveHarness.Config> COMPOSED = List.of(config("lens"), config("frame"), config("dial"));
	private static final List<WeaveHarness.Config> REDIRECT = List.of(config("plate"));

	@TempDir static Path work;
	private static Path merged;
	private static WeaveHarness.Result vanillaComposed, mergedComposed, untouchedComposed, vanillaRedirect, mergedRedirect;

	@BeforeAll static void weave() throws Exception {
		Map<String, Path> configs = new LinkedHashMap<>();
		for (String mod : List.of("lens", "frame", "plate", "dial")) configs.put(mod + ".mixins.json", SOURCES.resolve("mods/" + mod + ".mixins.json"));
		// Guava is the game's library, not the harness's: the fixture carries a stand-in with Guava's contracts.
		List<String> javac = List.of("-g");

		List<Path> vanillaSources = new ArrayList<>(sources("common"));
		vanillaSources.addAll(sources("vanilla"));
		vanillaSources.addAll(sources("mods"));
		Path vanilla = WeaveHarness.fixture(work, "pip-vanilla", vanillaSources, configs, javac);

		List<Path> mergedSources = new ArrayList<>(sources("common"));
		mergedSources.addAll(sources("merged"));
		mergedSources.addAll(sources("mods"));
		mergedSources.add(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelForgePipRenderers.java"));
		List<String> withKernel = new ArrayList<>(javac);
		withKernel.addAll(List.of("-sourcepath", "src/main/java", "-implicit:none"));
		Path untouched = WeaveHarness.fixture(work, "pip-untouched", mergedSources, configs, withKernel);
		merged = WeaveHarness.fixture(work, "pip-merged", mergedSources, configs, withKernel);
		// What the merged base's GuiRenderer is once the kernel's compat transformer has run.
		PreMixinFixture.transform(merged, GUI.replace('/', '.'), new ForbricMergedBaseCompatTransformer(), EnvType.CLIENT);

		vanillaComposed = run(vanilla, "vanilla-composed", COMPOSED);
		mergedComposed = run(merged, "merged-composed", COMPOSED);
		untouchedComposed = run(untouched, "untouched-composed", COMPOSED);
		vanillaRedirect = run(vanilla, "vanilla-redirect", REDIRECT);
		mergedRedirect = run(merged, "merged-redirect", REDIRECT);
	}

	@Test void vanillaTakesEveryModsRendererThroughTheBuilder() {
		assertTrue(vanillaComposed.printedLine(WeaveHarnessMain.DONE + " {DialState=dial, FrameState=frame, LensState=lens}"), vanillaComposed.describe());
		assertTrue(vanillaRedirect.printedLine(WeaveHarnessMain.DONE + " {PlateState=plate}"), vanillaRedirect.describe());
	}

	@Test void theMergedMapHoldsExactlyVanillasRenderersForEveryInjectorForm() throws Exception {
		assertEquals(probeLine(vanillaComposed), probeLine(mergedComposed), mergedComposed.describe());
		assertEquals(probeLine(vanillaRedirect), probeLine(mergedRedirect), mergedRedirect.describe());
		for (WeaveHarness.Result run : List.of(mergedComposed, mergedRedirect)) {
			assertEquals(List.of(), losses(run), "findings " + run.findings() + "\n" + run.describe());
			WeaveHarness.assertWovenAndVerified(run, GUI, merged);
		}
	}

	/** The builder exists once, in the constructor, made where vanilla makes it: nothing was written for any one mod. */
	@Test void theKernelMakesOneBuilderInTheConstructorAndNowhereElse() throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(mergedComposed.defined(GUI)).accept(node, 0);
		List<String> makers = new ArrayList<>();
		for (MethodNode method : node.methods) for (var insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals("com/google/common/collect/ImmutableMap") && call.name.equals("builder")) makers.add(method.name);
		}
		// Woven: the wrapped call now sits in MixinExtras' bridge, the redirect-free builder() in the constructor or its bridge.
		assertFalse(makers.isEmpty(), "no builder() left anywhere in the woven GuiRenderer");
		assertTrue(makers.stream().allMatch(name -> name.equals("<init>") || name.contains("$")), makers.toString());
	}

	/** Where NeoForge's constructor is left as it is, the point does not exist: every mod's injector is lost and nothing is filled. */
	@Test void onTheConstructorNeoForgeLeftEveryInjectorIsLost() {
		assertNotEquals(probeLine(vanillaComposed), probeLine(untouchedComposed), untouchedComposed.describe());
		assertEquals(List.of("dial", "frame", "lens"), losses(untouchedComposed).stream().map(WeaveHarness.Finding::modId).sorted().toList(),
				untouchedComposed.describe());
	}

	private static WeaveHarness.Config config(String mod) {
		return new WeaveHarness.Config(mod + ".mixins.json", mod, Ecosystem.FABRIC);
	}

	private static String probeLine(WeaveHarness.Result run) {
		return run.output().lines().filter(line -> line.startsWith(WeaveHarnessMain.DONE) || line.startsWith(WeaveHarnessMain.THREW))
				.findFirst().orElse("no probe line");
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:") && !f.confidence().equals("RESOLVED")).toList();
	}

	private static List<Path> sources(String part) throws Exception {
		try (Stream<Path> walk = Files.walk(SOURCES.resolve(part))) {
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}

	private static WeaveHarness.Result run(Path fixture, String label, List<WeaveHarness.Config> configs) throws Exception {
		return WeaveHarness.run(work, label, fixture, configs, List.of(), EnvType.CLIENT, "fixture.pipbuilder.Probe", "probe", Map.of());
	}
}
