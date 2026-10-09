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

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinEntitySoundCallbackAdapter;
import net.forbric.kernel.mixin.MixinHudContextAdapter;
import net.forbric.kernel.mixin.MixinStructurePlacementAdapter;
import net.forbric.kernel.transform.BlockSoundQueryInjector;
import net.forbric.kernel.transform.HudContextQueryInjector;

/**
 * Three callbacks that take their target's arguments the way Mixin appends them after the injector's own operands — no
 * {@code @Local} — and write their points with a dotted owner and whitespace, through the real weave on vanilla-shaped
 * and merged-shaped game classes, which must come out the same:
 * <ul>
 * <li>a sundial wrap of the HUD's contextual bar taking only the graphics, beside the compass and locator wraps of
 * {@link HudContextCallbackParityWeaveTest};
 * <li>a step-sound wrap taking the step's position, on the stand-ins of {@link EntitySoundCallbackParityWeaveTest};
 * <li>a crypt's {@code @Redirect} of the entity iterator inside vanilla's {@code placeEntities} taking the level after the
 * iterator's receiver, on the stand-ins of {@link StructureSingleWrapWeaveTest}.
 * </ul>
 * Each adapter moves the callback and hands it the value the target argument held natively; switched off, each binds
 * nothing on the merged classes and the run says something else.
 */
class TargetArgumentCallbackWeaveTest {
	private static final Path OWN = Path.of("src/test/resources/weave/targetargs");
	private static final Path HUD_SOURCES = Path.of("src/test/resources/weave/hudparity"), SOUND_SOURCES = Path.of("src/test/resources/weave/soundparity");
	private static final Path STAND_INS = Path.of("src/test/resources/weave/createstructure");

	@TempDir static Path work;
	private static WeaveHarness.Result hudVanilla, hudMerged, hudOff, soundVanilla, soundMerged, soundOff, cryptNative, cryptMerged, cryptOff;
	private static Path hudFixture, soundFixture;

	@BeforeAll static void weave() throws Exception {
		hud();
		sound();
		structure();
	}

	// ---- the HUD -----------------------------------------------------------------------------------------------------

	private static final List<WeaveHarness.Config> HUD_CONFIGS = List.of(
			new WeaveHarness.Config("compass.mixins.json", "compass", Ecosystem.FABRIC),
			new WeaveHarness.Config("locator.mixins.json", "locator", Ecosystem.FABRIC),
			new WeaveHarness.Config("sundial.mixins.json", "sundial", Ecosystem.FABRIC));

	private static void hud() throws Exception {
		Map<String, Path> configs = new LinkedHashMap<>();
		configs.put("compass.mixins.json", HUD_SOURCES.resolve("mods/compass.mixins.json"));
		configs.put("locator.mixins.json", HUD_SOURCES.resolve("mods/locator.mixins.json"));
		configs.put("sundial.mixins.json", OWN.resolve("hud/sundial.mixins.json"));
		List<Path> mods = new ArrayList<>(sources(HUD_SOURCES.resolve("mods")));
		mods.addAll(sources(OWN.resolve("hud")));
		List<Path> vanillaSources = new ArrayList<>(sources(HUD_SOURCES.resolve("common")));
		vanillaSources.addAll(sources(HUD_SOURCES.resolve("vanilla")));
		vanillaSources.addAll(mods);
		Path vanilla = WeaveHarness.fixture(work, "hud-vanilla", vanillaSources, configs, List.of("-g"));
		List<Path> mergedSources = new ArrayList<>(sources(HUD_SOURCES.resolve("common")));
		mergedSources.addAll(sources(HUD_SOURCES.resolve("merged")));
		mergedSources.addAll(mods);
		mergedSources.add(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelWrapOperations.java"));
		mergedSources.add(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelHudContextQuery.java"));
		hudFixture = WeaveHarness.fixture(work, "hud-merged", mergedSources, configs, List.of("-g", "-sourcepath", "src/main/java", "-implicit:none"));
		PreMixinFixture.transform(hudFixture, HudContextQueryInjector.TARGET, new HudContextQueryInjector(), EnvType.CLIENT);
		hudVanilla = run(vanilla, "hud-vanilla", HUD_CONFIGS, EnvType.CLIENT, "fixture.hudparity.Probe", Map.of());
		hudMerged = run(hudFixture, "hud-merged", HUD_CONFIGS, EnvType.CLIENT, "fixture.hudparity.Probe", Map.of());
		hudOff = run(hudFixture, "hud-off", HUD_CONFIGS, EnvType.CLIENT, "fixture.hudparity.Probe", Map.of(MixinHudContextAdapter.PROPERTY, "off"));
	}

	@Test void theSundialDrawsWithTheGraphicsItAppendedAsVanillaDraws() throws Exception {
		assertTrue(probeLine(hudVanilla).contains("sundial"), hudVanilla.describe());
		assertEquals(List.of(), losses(hudVanilla), hudVanilla.describe());
		assertEquals(probeLine(hudVanilla), probeLine(hudMerged), hudMerged.describe());
		assertEquals(List.of(), losses(hudMerged), "findings " + hudMerged.findings() + "\n" + hudMerged.describe());
		WeaveHarness.assertWovenAndVerified(hudMerged, "net/minecraft/client/gui/Hud", hudFixture);
	}

	@Test void switchedOffTheSundialSitsInTheDeadVanillaMethod() {
		assertNotEquals(probeLine(hudVanilla), probeLine(hudOff), hudOff.describe());
		assertTrue(losses(hudOff).stream().anyMatch(f -> f.modId().equals("sundial")), hudOff.describe());
	}

	// ---- the step sound ------------------------------------------------------------------------------------------------

	private static final List<WeaveHarness.Config> SOUND_CONFIGS = List.of(new WeaveHarness.Config("peal.mixins.json", "peal", Ecosystem.FABRIC));

	private static void sound() throws Exception {
		Map<String, Path> configs = Map.of("peal.mixins.json", OWN.resolve("sound/peal.mixins.json"));
		List<Path> common = sources(SOUND_SOURCES.resolve("common")), mods = sources(OWN.resolve("sound"));
		List<Path> vanillaSources = new ArrayList<>(common);
		vanillaSources.addAll(sources(SOUND_SOURCES.resolve("vanilla")));
		vanillaSources.addAll(mods);
		Path vanilla = WeaveHarness.fixture(work, "sound-vanilla", vanillaSources, configs, List.of("-g"));
		// The vanilla-shaped classes, as the merged-base builder records what a Fabric mod was compiled against.
		Map<String, Path> resources = new LinkedHashMap<>(configs);
		StringBuilder index = new StringBuilder("# forbric-native-reference-v1\n");
		for (String owner : List.of("net/minecraft/world/entity/Entity", "net/minecraft/world/entity/LivingEntity")) {
			byte[] bytes = Files.readAllBytes(work.resolve("sound-vanilla-classes/" + owner + ".class"));
			Path bin = work.resolve(owner.substring(owner.lastIndexOf('/') + 1) + ".native.bin");
			Files.write(bin, bytes);
			index.append(owner).append('\t').append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).append('\n');
			resources.put("META-INF/forbric/native-reference/FABRIC/" + owner + ".class.bin", bin);
		}
		Path indexFile = work.resolve("sound-native-index.tsv");
		Files.writeString(indexFile, index);
		resources.put("META-INF/forbric/native-reference/FABRIC/index.tsv", indexFile);
		List<Path> mergedSources = new ArrayList<>(common);
		mergedSources.addAll(sources(SOUND_SOURCES.resolve("merged")));
		mergedSources.addAll(mods);
		mergedSources.add(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelWrapOperations.java"));
		mergedSources.add(Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelBlockSoundQuery.java"));
		soundFixture = WeaveHarness.fixture(work, "sound-merged", mergedSources, resources, List.of("-g", "-sourcepath", "src/main/java", "-implicit:none"));
		PreMixinFixture.transform(soundFixture, BlockSoundQueryInjector.TARGET, new BlockSoundQueryInjector(), EnvType.SERVER);
		soundVanilla = run(vanilla, "sound-vanilla", SOUND_CONFIGS, EnvType.SERVER, "fixture.soundparity.Probe", Map.of());
		soundMerged = run(soundFixture, "sound-merged", SOUND_CONFIGS, EnvType.SERVER, "fixture.soundparity.Probe", Map.of());
		soundOff = run(soundFixture, "sound-off", SOUND_CONFIGS, EnvType.SERVER, "fixture.soundparity.Probe", Map.of(MixinEntitySoundCallbackAdapter.PROPERTY, "off"));
	}

	@Test void thePealHearsTheStepPositionItAppendedAsVanillaHearsIt() throws Exception {
		assertTrue(probeLine(soundVanilla).contains("+peal64"), soundVanilla.describe());
		assertEquals(List.of(), losses(soundVanilla), soundVanilla.describe());
		assertEquals(probeLine(soundVanilla), probeLine(soundMerged), soundMerged.describe());
		assertEquals(List.of(), losses(soundMerged), "findings " + soundMerged.findings() + "\n" + soundMerged.describe());
		WeaveHarness.assertWovenAndVerified(soundMerged, "net/minecraft/world/entity/Entity", soundFixture);
	}

	@Test void switchedOffThePealIsLost() {
		assertNotEquals(probeLine(soundVanilla), probeLine(soundOff), soundOff.describe());
		assertTrue(losses(soundOff).stream().anyMatch(f -> f.modId().equals("peal")), soundOff.describe());
	}

	// ---- the structure ------------------------------------------------------------------------------------------------

	private static final List<WeaveHarness.Config> CRYPT_CONFIGS = List.of(new WeaveHarness.Config("crypt.mixins.json", "crypt", Ecosystem.FABRIC));

	private static void structure() throws Exception {
		String structure = "net/minecraft/world/level/levelgen/structure/templatesystem/";
		Path target = STAND_INS.resolve(structure + "StructureTemplate.java");
		Path shared = Path.of("src/test/resources/weave/replacedcall");
		List<Path> common = new ArrayList<>(List.of(
				STAND_INS.resolve("net/minecraft/world/level/ServerLevelAccessor.java"),
				STAND_INS.resolve("net/minecraft/world/level/Level.java"),
				STAND_INS.resolve("net/minecraft/core/BlockPos.java"),
				STAND_INS.resolve("net/minecraft/util/RandomSource.java"),
				STAND_INS.resolve("net/minecraft/util/ProblemReporter.java"),
				STAND_INS.resolve(structure + "StructureProcessor.java"),
				STAND_INS.resolve(structure + "StructurePlaceSettings.java"),
				Path.of("src/test/resources/weave/structuresingle/fixture/structuresingle/Probe.java"),
				shared.resolve("net/minecraft/world/level/block/Mirror.java"), shared.resolve("net/minecraft/world/level/block/Rotation.java"),
				shared.resolve("net/minecraft/world/level/levelgen/structure/BoundingBox.java")));
		common.addAll(sources(OWN.resolve("structure")));
		Map<String, Path> configs = Map.of("crypt.mixins.json", OWN.resolve("structure/crypt.mixins.json"));
		List<Path> mergedSources = new ArrayList<>(common);
		mergedSources.add(target);
		Path merged = WeaveHarness.fixture(work, "crypt-merged", mergedSources, configs);
		// The class the mod was compiled against: vanilla's placeInWorld calls placeEntities, which iterates the entities.
		Path nativeTarget = work.resolve("crypt-native/StructureTemplate.java");
		Files.createDirectories(nativeTarget.getParent());
		Files.writeString(nativeTarget, Files.readString(target)
				.replace("addEntitiesToWorld(level, pos, settings, new ProblemReporter())", "placeEntities(level,pos,settings.getMirror(),settings.getRotation(),settings.getRotationPivot(),settings.getBoundingBox(),settings.shouldFinalizeEntities(),new ProblemReporter())")
				.replace("private void addEntitiesToWorld(ServerLevelAccessor level, BlockPos pos, StructurePlaceSettings settings, ProblemReporter reporter)", "private void placeEntities(ServerLevelAccessor level,BlockPos pos,net.minecraft.world.level.block.Mirror mirror,net.minecraft.world.level.block.Rotation rotation,BlockPos pivot,net.minecraft.world.level.levelgen.structure.BoundingBox box,boolean fin,ProblemReporter reporter)"));
		List<Path> nativeSources = new ArrayList<>(common);
		nativeSources.add(nativeTarget);
		Path nativeFixture = WeaveHarness.fixture(work, "crypt-native", nativeSources, configs);
		Path indexed = NativeWeaveReferences.with(work, merged, NativeWeaveReferences.classes(nativeFixture));
		cryptNative = run(nativeFixture, "crypt-native", CRYPT_CONFIGS, EnvType.SERVER, "fixture.structuresingle.Probe", Map.of());
		cryptMerged = run(indexed, "crypt-merged", CRYPT_CONFIGS, EnvType.SERVER, "fixture.structuresingle.Probe", Map.of());
		cryptOff = run(indexed, "crypt-off", CRYPT_CONFIGS, EnvType.SERVER, "fixture.structuresingle.Probe",
				Map.of(MixinStructurePlacementAdapter.PROPERTY, "off", "forbric.mixinRetarget.replacedCall", "off"));
	}

	@Test void theCryptRedirectGetsTheLevelItAppendedOnTheLivePlacement() {
		assertEquals(WeaveHarnessMain.DONE + " placed skeleton, cow", probeLine(cryptNative), cryptNative.describe());
		assertEquals(probeLine(cryptNative), probeLine(cryptMerged), cryptMerged.describe() + "\nfindings " + cryptMerged.findings());
		assertEquals(List.of(), losses(cryptMerged), cryptMerged.describe());
	}

	@Test void switchedOffTheCryptRedirectIsLost() {
		assertEquals(WeaveHarnessMain.DONE + " placed pig, cow", probeLine(cryptOff), cryptOff.describe());
		assertTrue(losses(cryptOff).stream().anyMatch(f -> f.modId().equals("crypt")), cryptOff.describe());
	}

	// -------------------------------------------------------------------------------------------------------------------

	private static WeaveHarness.Result run(Path fixture, String label, List<WeaveHarness.Config> configs, EnvType side, String probe,
			Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, configs, List.of(), side, probe, "probe", properties);
	}

	private static String probeLine(WeaveHarness.Result run) {
		return run.output().lines().filter(line -> line.startsWith(WeaveHarnessMain.DONE) || line.startsWith(WeaveHarnessMain.THREW))
				.findFirst().orElse("no probe line");
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> (f.id().startsWith("mixin-injector:") || f.id().startsWith("mixin:"))
				&& !f.confidence().equals("RESOLVED")).toList();
	}

	private static List<Path> sources(Path root) throws Exception {
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}
}
