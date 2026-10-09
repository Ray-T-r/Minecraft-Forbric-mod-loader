package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinStructurePlacementAdapter;

/**
 * {@code MixinStructurePlacementAdapter} through the real weave, on a mod that is not Create and has none of Create's
 * companions: one {@code @WrapOperation} of the entity iterator inside vanilla's {@code placeEntities}, selected by its
 * bare name — which only the class the mod was compiled against can resolve, since the merged class has no such
 * method — with no {@code @Local}. Adapted, it wraps the iterator of NeoForge's {@code addEntitiesToWorld} and the pig
 * is never placed. The mixin is judged as the adapter will hand it to Mixin, so a mixin holding nothing but this
 * wrap is not left out as binding nothing before the adapter sees it. With the adapter and MixinRetarget's R7 off it
 * binds nothing and is the mod's one loss.
 */
class StructureSingleWrapWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/structuresingle");
	private static final Path STAND_INS = Path.of("src/test/resources/weave/createstructure");
	private static final String CONFIG = "structuresingle.mixins.json";
	private static final String MOD = "ruins";

	private static final String ADAPTED = WeaveHarnessMain.DONE + " placed cow";
	private static final String UNADAPTED = WeaveHarnessMain.DONE + " placed pig, cow";

	@TempDir static Path work;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;

	@BeforeAll static void weave() throws Exception {
		String structure = "net/minecraft/world/level/levelgen/structure/templatesystem/";
		Path target = STAND_INS.resolve(structure + "StructureTemplate.java");
		List<Path> sources = new ArrayList<>(List.of(
				STAND_INS.resolve("net/minecraft/world/level/ServerLevelAccessor.java"),
				STAND_INS.resolve("net/minecraft/world/level/Level.java"),
				STAND_INS.resolve("net/minecraft/core/BlockPos.java"),
				STAND_INS.resolve("net/minecraft/util/RandomSource.java"),
				STAND_INS.resolve("net/minecraft/util/ProblemReporter.java"),
				STAND_INS.resolve(structure + "StructureProcessor.java"),
				STAND_INS.resolve(structure + "StructurePlaceSettings.java"),
				target,
				SOURCES.resolve("fixture/structuresingle/Probe.java"),
				SOURCES.resolve("org/example/ruins/mixin/RuinsMixin.java")));
		Path shared = Path.of("src/test/resources/weave/replacedcall");
		sources.addAll(List.of(shared.resolve("net/minecraft/world/level/block/Mirror.java"), shared.resolve("net/minecraft/world/level/block/Rotation.java"),
				shared.resolve("net/minecraft/world/level/levelgen/structure/BoundingBox.java")));
		Path fixture = WeaveHarness.fixture(work, "structuresingle", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		// The class the mod was compiled against: vanilla's placeInWorld calls placeEntities, which iterates the entities.
		Path nativeTarget = work.resolve("native/StructureTemplate.java");
		Files.createDirectories(nativeTarget.getParent());
		Files.writeString(nativeTarget, Files.readString(target)
				.replace("addEntitiesToWorld(level, pos, settings, new ProblemReporter())", "placeEntities(level,pos,settings.getMirror(),settings.getRotation(),settings.getRotationPivot(),settings.getBoundingBox(),settings.shouldFinalizeEntities(),new ProblemReporter())")
				.replace("private void addEntitiesToWorld(ServerLevelAccessor level, BlockPos pos, StructurePlaceSettings settings, ProblemReporter reporter)", "private void placeEntities(ServerLevelAccessor level,BlockPos pos,net.minecraft.world.level.block.Mirror mirror,net.minecraft.world.level.block.Rotation rotation,BlockPos pivot,net.minecraft.world.level.levelgen.structure.BoundingBox box,boolean fin,ProblemReporter reporter)"));
		List<Path> originals = new ArrayList<>(sources);
		originals.remove(target);
		originals.add(nativeTarget);
		Path original = WeaveHarness.fixture(work, "original", originals, Map.of());
		fixture = NativeWeaveReferences.with(work, fixture, NativeWeaveReferences.classes(original));
		adapted = WeaveHarness.run(work, "adapted", fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.structuresingle.Probe", "probe", Map.of());
		off = WeaveHarness.run(work, "adapter-off", fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.structuresingle.Probe", "probe",
				Map.of(MixinStructurePlacementAdapter.PROPERTY, "off", "forbric.mixinRetarget.replacedCall", "off"));
	}

	@Test void theLoneIteratorWrapRunsOnTheLivePlacementCall() {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings " + adapted.findings());
	}

	@Test void switchedOffTheWrapIsTheModsOneLoss() {
		assertTrue(offHolds(off), off.describe() + "\nfindings " + off.findings());
	}

	@Test void theControlFlipsEveryAdapterAssertion() {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapter predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) {
		return run.output().lines().anyMatch(ADAPTED::equals) && losses(run).isEmpty();
	}

	/** Switched off, the lone wrap binds nothing: the mixin is the mod's one reported loss and the pig is placed. */
	private static boolean offHolds(WeaveHarness.Result run) {
		List<WeaveHarness.Finding> losses = losses(run);
		return run.output().lines().anyMatch(UNADAPTED::equals) && losses.size() == 1 && losses.getFirst().id().contains("RuinsMixin");
	}

	/** The mod's findings that are not resolved: a mixin left out, or an injector that did not attach. */
	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && (f.id().startsWith("mixin-injector:") || f.id().startsWith("mixin:"))
				&& !f.confidence().equals("RESOLVED")).toList();
	}
}
