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
import net.forbric.kernel.mixin.MixinStructurePlacementAdapter;

/**
 * A {@code @WrapWithCondition} of a field write, moved by {@code MixinStructurePlacementAdapter} from vanilla's
 * {@code placeEntities} to the merged {@code addEntitiesToWorld} through {@code MixinCallbackProofs.land} and
 * {@code pinLocals}, through the real weave. A census mod wraps the template's {@code added} count write and takes the
 * level the way Mixin appends a target argument after the injector's operands. Those operands are the write's receiver
 * and the value written — the write is what the instruction is, not what the handler returns (a condition returns a
 * boolean either way) — so the level is the first appended value: placeEntities' level, which the move pins as a
 * {@code @Local(index)} of addEntitiesToWorld's level. Read the other way, the value would be taken for the level and the
 * handler refused.
 *
 * <p>On vanilla the level hears "tally 1" and "tally 2" after the pig and the cow and the count stops at 1. The merged
 * class must do the same, proved against the class the mod was compiled against and without it; switched off, the wrap
 * binds nothing, the level hears no tally, the count reaches 2, and the wrap is the mod's required loss.
 */
class CensusFieldWriteConditionWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/censusfield");
	private static final Path STAND_INS = Path.of("src/test/resources/weave/createstructure"), SHARED = Path.of("src/test/resources/weave/replacedcall");
	private static final String CONFIG = "census.mixins.json";
	private static final String MOD = "census";
	private static final String TARGET = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate";

	private static final String TALLIED = WeaveHarnessMain.DONE + " placed pig, tally 1, cow, tally 2 | added 1";
	private static final String UNTALLIED = WeaveHarnessMain.DONE + " placed pig, cow | added 2";

	@TempDir static Path work;
	private static Path merged, indexed;
	private static WeaveHarness.Result vanilla, unrecorded, proved, off;

	@BeforeAll static void weave() throws Exception {
		String structure = "net/minecraft/world/level/levelgen/structure/templatesystem/";
		List<Path> common = new ArrayList<>(List.of(
				STAND_INS.resolve("net/minecraft/world/level/ServerLevelAccessor.java"),
				STAND_INS.resolve("net/minecraft/world/level/Level.java"),
				STAND_INS.resolve("net/minecraft/core/BlockPos.java"),
				STAND_INS.resolve("net/minecraft/util/RandomSource.java"),
				STAND_INS.resolve("net/minecraft/util/ProblemReporter.java"),
				STAND_INS.resolve(structure + "StructureProcessor.java"),
				STAND_INS.resolve(structure + "StructurePlaceSettings.java"),
				SHARED.resolve("net/minecraft/world/level/block/Mirror.java"),
				SHARED.resolve("net/minecraft/world/level/block/Rotation.java"),
				SHARED.resolve("net/minecraft/world/level/levelgen/structure/BoundingBox.java")));
		common.addAll(sources(SOURCES.resolve("mods")));
		Map<String, Path> configs = Map.of(CONFIG, SOURCES.resolve("mods/" + CONFIG));
		List<Path> vanillaSources = new ArrayList<>(common);
		vanillaSources.addAll(sources(SOURCES.resolve("vanilla")));
		Path vanillaFixture = WeaveHarness.fixture(work, "census-vanilla", vanillaSources, configs);
		List<Path> mergedSources = new ArrayList<>(common);
		mergedSources.addAll(sources(SOURCES.resolve("merged")));
		merged = WeaveHarness.fixture(work, "census-merged", mergedSources, configs);
		indexed = NativeWeaveReferences.with(work, merged, NativeWeaveReferences.classes(vanillaFixture));
		vanilla = run(vanillaFixture, "vanilla", Map.of());
		unrecorded = run(merged, "merged-unrecorded", Map.of());
		proved = run(indexed, "merged-proved", Map.of());
		off = run(indexed, "adapter-off", Map.of(MixinStructurePlacementAdapter.PROPERTY, "off"));
	}

	@Test void onVanillaTheCensusTalliesOnceAndHearsEachWrite() {
		assertTrue(returned(vanilla, TALLIED), vanilla.describe());
		assertEquals(List.of(), losses(vanilla), vanilla.describe());
	}

	@Test void offTheMergedBodyAloneTheMovedConditionGetsTheLevelItAppended() throws Exception {
		assertTrue(adaptedHolds(unrecorded), unrecorded.describe() + "\nfindings " + unrecorded.findings());
		WeaveHarness.assertWovenAndVerified(unrecorded, TARGET, merged);
	}

	@Test void provedAgainstTheNativeClassTheMovedConditionGetsTheLevelItAppended() throws Exception {
		assertTrue(adaptedHolds(proved), proved.describe() + "\nfindings " + proved.findings());
		WeaveHarness.assertWovenAndVerified(proved, TARGET, indexed);
	}

	@Test void switchedOffTheConditionIsUnboundAndTheCountRunsOn() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings " + off.findings());
	}

	/** Each run's predicate must fail on the other, or the control proves nothing about the adapter. */
	@Test void theControlFlipsEveryAdapterAssertion() throws Exception {
		assertTrue(adaptedHolds(unrecorded) && adaptedHolds(proved) && !adaptedHolds(off), "adapter predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(unrecorded) && !offHolds(proved), "control predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) throws Exception {
		return returned(run, TALLIED) && losses(run).isEmpty() && callsHandler(run);
	}

	private static boolean offHolds(WeaveHarness.Result run) throws Exception {
		List<WeaveHarness.Finding> losses = losses(run);
		return returned(run, UNTALLIED) && losses.size() == 1 && losses.get(0).required() && !callsHandler(run);
	}

	private static boolean returned(WeaveHarness.Result run, String line) {
		return run.output().lines().anyMatch(line::equals);
	}

	/** The mod's injectors that did not attach, or its mixin left out whole when its one injector binds nothing. */
	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && (f.id().startsWith("mixin-injector:") || f.id().startsWith("mixin:"))
				&& !f.confidence().equals("RESOLVED")).toList();
	}

	/** Whether the defined addEntitiesToWorld calls a handler the census mod's mixin merged into StructureTemplate. */
	private static boolean callsHandler(WeaveHarness.Result run) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(TARGET)).accept(node, 0);
		MethodNode body = node.methods.stream().filter(m -> m.name.equals("addEntitiesToWorld")).findFirst().orElseThrow();
		for (var insn : body.instructions)
			if (insn instanceof MethodInsnNode call && call.owner.equals(TARGET) && call.name.contains("$" + MOD + "$")) return true;
		return false;
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.censusfield.Probe", "probe", properties);
	}

	private static List<Path> sources(Path root) throws Exception {
		try (Stream<Path> walk = Files.walk(root)) {
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}
}
