package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;

/**
 * {@code MixinRetarget}'s R7, woven by the real pipeline: a guest written against vanilla's {@code StructureTemplate},
 * where {@code placeInWorld} calls {@code placeEntities}, on the merged shape where NeoForge's {@code addEntitiesToWorld}
 * replaced it (MoogsStructureLib's Fabric {@code EntityProcessorMixin}, in miniature). The plan is the adapter's: judged
 * at config time, remembered, applied to the node Mixin receives.
 *
 * <p>The probe places two structures and returns each one's trace:
 * <ul>
 *   <li>kept — the guest's HEAD handler sees the five arguments vanilla read off the settings and lets the game place
 *       the entities: before, head, entities, after;</li>
 *   <li>taken — it cancels, as it does when it placed them itself: before, head, after — and the game's placement is
 *       skipped, as vanilla's was.</li>
 * </ul>
 * With {@code -Dforbric.mixinRetarget.replacedCall=off} both points miss and the HEAD selector binds MinecraftForge's
 * reshaped {@code placeEntities}, whose descriptor the handler does not have: the mixin does not apply, the game places
 * the entities untouched, and the mod is reported.
 *
 * <p>And the HEAD injector alone ({@code StructureTemplateHeadMixin}, its own config): no anchor resolves, so the
 * verdict is UNFIT, and the adapter asks R7 for an UNFIT too — it used to ask only for a PARTIAL, and dropped this
 * mixin as dead weight with a confirmed, required loss. Moved, the HEAD handler runs and cancels as above; with the
 * switch off it is that loss again.
 */
class MixinRetargetReplacedCallWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/replacedcall");
	private static final String CONFIG = "replacedcall.mixins.json";
	private static final String MOD = "replacedcall";
	private static final String TARGET = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate";
	private static final String HEAD_CONFIG = "replacedcall-head.mixins.json";
	private static final String HEAD_MOD = "replacedcallhead";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved;
	private static WeaveHarness.Result off;
	private static WeaveHarness.Result headMoved;
	private static WeaveHarness.Result headOff;

	@BeforeAll static void weaveBoth() throws Exception {
		List<Path> sources = List.of(
				SOURCES.resolve("net/minecraft/world/level/ServerLevelAccessor.java"),
				SOURCES.resolve("net/minecraft/core/BlockPos.java"),
				SOURCES.resolve("net/minecraft/world/level/block/Mirror.java"),
				SOURCES.resolve("net/minecraft/world/level/block/Rotation.java"),
				SOURCES.resolve("net/minecraft/world/level/levelgen/structure/BoundingBox.java"),
				SOURCES.resolve("net/minecraft/util/ProblemReporter.java"),
				SOURCES.resolve("net/minecraft/util/RandomSource.java"),
				SOURCES.resolve("net/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings.java"),
				SOURCES.resolve("net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate.java"),
				SOURCES.resolve("fixture/replacedcall/mixin/StructureTemplateMixin.java"),
				SOURCES.resolve("fixture/replacedcall/head/StructureTemplateHeadMixin.java"));
        fixture=WeaveHarness.fixture(work,"replacedcall",sources,Map.of(CONFIG,SOURCES.resolve(CONFIG),HEAD_CONFIG,SOURCES.resolve(HEAD_CONFIG)));
        Path target=SOURCES.resolve("net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate.java");
        String nativeCode=java.nio.file.Files.readString(target)
            .replace("this.addEntitiesToWorld(level, position, settings, reporter)","this.placeEntities(level, position, settings.getMirror(),settings.getRotation(),settings.getRotationPivot(),settings.getBoundingBox(),settings.shouldFinalizeEntities(),reporter)")
            .replace("private void addEntitiesToWorld(ServerLevelAccessor level, BlockPos position, StructurePlaceSettings settings,", "private void placeEntities(ServerLevelAccessor level, BlockPos position, Mirror mirror,Rotation rotation,BlockPos pivot,BoundingBox box,boolean finalizeEntities,");
        Path nativeTarget=work.resolve("native/StructureTemplate.java");java.nio.file.Files.createDirectories(nativeTarget.getParent());java.nio.file.Files.writeString(nativeTarget,nativeCode);
        List<Path> nativeSources=new java.util.ArrayList<>(sources);nativeSources.remove(target);nativeSources.add(nativeTarget);
        Path original=WeaveHarness.fixture(work,"original",nativeSources,Map.of());
        fixture=NativeWeaveReferences.with(work,fixture,NativeWeaveReferences.classes(original));
		moved = run("moved", CONFIG, MOD, "on");
		off = run("off", CONFIG, MOD, "off");
		headMoved = run("head-moved", HEAD_CONFIG, HEAD_MOD, "on");
		headOff = run("head-off", HEAD_CONFIG, HEAD_MOD, "off");
	}

	@Test void theGuestRunsAroundAndAtTheHeadOfTheCarriersPlacement() throws Exception {
		assertTrue(moved.printed(WeaveHarnessMain.DONE), "the woven probe threw — " + moved.describe());
		assertTrue(movedHolds(moved), moved.describe() + "\nfindings: " + moved.findings());
		assertTrue(moved.printed("retargeted guest mixin"), "the plan was not announced — " + moved.describe());
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	@Test void withTheSwitchOffTheMixinDoesNotApplyAndTheModIsReported() throws Exception {
		assertTrue(off.printed(WeaveHarnessMain.DONE), "the control probe threw — " + off.describe());
		assertTrue(offHolds(off), off.describe() + "\nfindings: " + off.findings());
	}

	/** The run and its control must be told apart by the very predicates the test uses on them. */
	@Test void theControlFlipsEveryMoveAssertion() {
		assertTrue(movedHolds(moved) && !movedHolds(off), "move predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(moved), "control predicate does not separate the runs");
		assertTrue(headMovedHolds(headMoved) && !headMovedHolds(headOff), "head move predicate does not separate the runs");
		assertTrue(headOffHolds(headOff) && !headOffHolds(headMoved), "head control predicate does not separate the runs");
	}

	@Test void anUnfitMixinOfTheHeadAloneIsMovedNotDropped() throws Exception {
		assertTrue(headMoved.printed(WeaveHarnessMain.DONE), "the woven probe threw — " + headMoved.describe());
		assertTrue(headMovedHolds(headMoved), headMoved.describe() + "\nfindings: " + headMoved.findings());
		assertTrue(headMoved.printed("verdict UNFIT→FIT"), "the UNFIT was not retargeted — " + headMoved.describe());
		WeaveHarness.assertWovenAndVerified(headMoved, TARGET, fixture);
	}

	@Test void withTheSwitchOffTheUnfitHeadIsDroppedAndReported() throws Exception {
		assertTrue(headOff.printed(WeaveHarnessMain.DONE), "the control probe threw — " + headOff.describe());
		assertTrue(headOffHolds(headOff), headOff.describe() + "\nfindings: " + headOff.findings());
	}

	private static boolean headMovedHolds(WeaveHarness.Result run) {
		return run.printedLine(WeaveHarnessMain.DONE + " kept=blocks," + HEAD_KEPT + ",entities,done taken=blocks," + HEAD_TAKEN
				+ ",done") && losses(run, HEAD_MOD).isEmpty();
	}

	private static boolean headOffHolds(WeaveHarness.Result run) {
		return run.printedLine(WeaveHarnessMain.DONE + " kept=blocks,entities,done taken=blocks,entities,done")
				&& !losses(run, HEAD_MOD).isEmpty();
	}

	private static final String HEAD_KEPT = "head:0/64/0|NONE|NONE|1/2/3|box16|true";
	private static final String HEAD_TAKEN = "head:0/64/0|FRONT_BACK|CLOCKWISE_90|1/2/3|box16|true";

	private static boolean movedHolds(WeaveHarness.Result run) {
		return run.printedLine(WeaveHarnessMain.DONE + " kept=blocks,before," + HEAD_KEPT + ",entities,after,done taken=blocks,before,"
				+ HEAD_TAKEN + ",after,done") && losses(run, MOD).isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printedLine(WeaveHarnessMain.DONE + " kept=blocks,entities,done taken=blocks,entities,done")
				&& !losses(run, MOD).isEmpty();
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run, String mod) {
		return run.findings().stream().filter(f -> f.modId().equals(mod) && f.confirmedRequired()).toList();
	}

	private static WeaveHarness.Result run(String label, String config, String mod, String rule) throws Exception {
		return WeaveHarness.run(work, label, fixture, config, mod, Ecosystem.FABRIC, EnvType.SERVER,
				"net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate", "probe",
				Map.of("forbric.mixinRetarget.replacedCall", rule));
	}
}
