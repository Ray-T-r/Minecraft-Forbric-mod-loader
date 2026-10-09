package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
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
import net.forbric.kernel.mixin.MixinEntitySoundCallbackAdapter;
import net.forbric.kernel.transform.BlockSoundQueryInjector;

/**
 * {@code MixinEntitySoundCallbackAdapter} and {@code BlockSoundCallbackScope} judged against vanilla itself: the same three
 * mods, none shaped like the adapter's sample, woven once onto vanilla-shaped game classes and once onto merged-shaped
 * ones (NeoForge hands step and landing sounds to the block, which asks the state through the kernel's scope), must make
 * the same sounds.
 *
 * <p>The mods: two unrelated step-sound wraps of the same {@code getSoundType()} call — one with a bare-name selector, no
 * captures, and an original it hands ANOTHER state (felt heard as wool); one fully described, capturing the step's
 * position as an argument — and a landing-sound wrap capturing two of the three block coordinates, the third one first
 * ({@code @Local(ordinal = 2) int z, @Local(ordinal = 0) int x}). The probe steps onto stone and felt, steps onto stone with
 * felt muffled under it, and lands on felt.
 *
 * <p>Vanilla nests the two step wraps, so both marks appear and the inner one hears what the outer passed on; it does not
 * wrap the combination or muffled steps (they never call playStepSound); it lands with the z and x it captured. The merged
 * run must say exactly that: both frames composed in vanilla's order, the passed state honoured, only the step method the
 * selectors name, the captures read at their proven slots. With {@code -Dforbric.entitySoundCallbacks=off} the wraps bind
 * nothing on the merged classes and the run says something else.
 */
class EntitySoundCallbackParityWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/soundparity");
	private static final String ENTITY = "net/minecraft/world/entity/Entity";
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final List<WeaveHarness.Config> CONFIGS = List.of(
			new WeaveHarness.Config("echoes.mixins.json", "echoes", Ecosystem.FABRIC),
			new WeaveHarness.Config("chime.mixins.json", "chime", Ecosystem.FABRIC),
			new WeaveHarness.Config("thud.mixins.json", "thud", Ecosystem.FABRIC));
	/** Either nesting of the two step wraps; vanilla decides which, and the merged run must agree with it. */
	private static final List<String> VANILLA_ORDERS = List.of(
			WeaveHarnessMain.DONE + " stone+chime64+echo 0.15, wool+chime64+echo 0.15, stone 0.15, cloth 0.05, cloth@3,-6 0.5",
			WeaveHarnessMain.DONE + " stone+echo+chime64 0.15, wool+echo+chime64 0.15, stone 0.15, cloth 0.05, cloth@3,-6 0.5");

	@TempDir static Path work;
	private static Path merged;
	private static WeaveHarness.Result vanillaRun, mergedRun, offRun;

	@BeforeAll static void weave() throws Exception {
		List<Path> common = sources("common"), mods = sources("mods");
		Map<String, Path> configs = new LinkedHashMap<>();
		for (WeaveHarness.Config config : CONFIGS) configs.put(config.name(), SOURCES.resolve("mods").resolve(config.name()));
		List<Path> vanillaSources = new ArrayList<>(common);
		vanillaSources.addAll(sources("vanilla"));
		vanillaSources.addAll(mods);
		Path vanilla = WeaveHarness.fixture(work, "soundparity-vanilla", vanillaSources, configs, List.of("-g"));

		// The vanilla-shaped classes, as the merged-base builder records what a Fabric mod was compiled against.
		Map<String, Path> mergedResources = new LinkedHashMap<>(configs);
		StringBuilder index = new StringBuilder("# forbric-native-reference-v1\n");
		for (String owner : List.of(ENTITY, LIVING)) {
			byte[] bytes = Files.readAllBytes(work.resolve("soundparity-vanilla-classes/" + owner + ".class"));
			Path bin = work.resolve(owner.substring(owner.lastIndexOf('/') + 1) + ".native.bin");
			Files.write(bin, bytes);
			index.append(owner).append('\t').append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).append('\n');
			mergedResources.put("META-INF/forbric/native-reference/FABRIC/" + owner + ".class.bin", bin);
		}
		Path indexFile = work.resolve("native-index.tsv");
		Files.writeString(indexFile, index);
		mergedResources.put("META-INF/forbric/native-reference/FABRIC/index.tsv", indexFile);
		List<Path> mergedSources = new ArrayList<>(common);
		mergedSources.addAll(sources("merged"));
		mergedSources.addAll(mods);
		// The kernel's game-side hooks the adapted code reaches; the boot-side scope resolves from source, not packed.
		mergedSources.add(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelWrapOperations.java"));
		mergedSources.add(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelBlockSoundQuery.java"));
		merged = WeaveHarness.fixture(work, "soundparity-merged", mergedSources, mergedResources,
				List.of("-g", "-sourcepath", "src/main/java", "-implicit:none"));
		PreMixinFixture.transform(merged, BlockSoundQueryInjector.TARGET, new BlockSoundQueryInjector(), EnvType.SERVER);

		vanillaRun = run(vanilla, "vanilla", Map.of());
		mergedRun = run(merged, "merged", Map.of());
		offRun = run(merged, "merged-off", Map.of(MixinEntitySoundCallbackAdapter.PROPERTY, "off"));
	}

	@Test void vanillaNestsBothStepWrapsAndLandsWithTheCapturedCoordinates() {
		assertTrue(VANILLA_ORDERS.stream().anyMatch(vanillaRun::printedLine), vanillaRun.describe());
		assertEquals(List.of(), losses(vanillaRun), vanillaRun.describe());
	}

	@Test void theMergedGameMakesExactlyVanillasSounds() throws Exception {
		assertEquals(probeLine(vanillaRun), probeLine(mergedRun), mergedRun.describe());
		assertEquals(List.of(), losses(mergedRun), "findings " + mergedRun.findings() + "\n" + mergedRun.describe());
		WeaveHarness.assertWovenAndVerified(mergedRun, ENTITY, merged);
		WeaveHarness.assertWovenAndVerified(mergedRun, LIVING, merged);
	}

	/** The step wraps name playStepSound; the merged combination and muffled steps play through the same native call, unwrapped. */
	@Test void onlyTheMethodTheSelectorsNameIsWrapped() throws Exception {
		assertEquals(List.of("playStepSound"), hostsCalling(mergedRun, ENTITY, List.of("echoes", "chime")));
		assertEquals(List.of("playBlockFallSound"), hostsCalling(mergedRun, LIVING, List.of("thud")));
	}

	/** Without the adapter the wraps bind nothing on the merged classes: other sounds, and every mod's loss reported. */
	@Test void switchedOffTheMergedGameSoundsOtherwise() {
		assertNotEquals(probeLine(vanillaRun), probeLine(offRun), offRun.describe());
		assertEquals(List.of("chime", "echoes", "thud"), losses(offRun).stream().map(WeaveHarness.Finding::modId).sorted().toList(),
				offRun.describe());
	}

	private static String probeLine(WeaveHarness.Result run) {
		return run.output().lines().filter(line -> line.startsWith(WeaveHarnessMain.DONE) || line.startsWith(WeaveHarnessMain.THREW))
				.findFirst().orElse("no probe line");
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:") && !f.confidence().equals("RESOLVED")).toList();
	}

	/** The methods of the woven {@code owner} that call a handler one of {@code mods} merged into it, in class order. */
	private static List<String> hostsCalling(WeaveHarness.Result run, String owner, List<String> mods) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(owner)).accept(node, 0);
		List<String> hosts = new ArrayList<>();
		for (MethodNode method : node.methods) {
			if (method.name.contains("$")) continue;
			for (var insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && mods.stream().anyMatch(mod -> call.name.contains(mod + "$"))) {
					hosts.add(method.name);
					break;
				}
			}
		}
		return hosts;
	}

	private static List<Path> sources(String part) throws Exception {
		try (Stream<Path> walk = Files.walk(SOURCES.resolve(part))) {
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIGS, List.of(), EnvType.SERVER, "fixture.soundparity.Probe", "probe", properties);
	}
}
