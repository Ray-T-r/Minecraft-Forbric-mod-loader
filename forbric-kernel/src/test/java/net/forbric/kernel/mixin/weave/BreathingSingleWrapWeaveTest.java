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
import net.forbric.kernel.mixin.MixinBreathingCallbackAdapter;
import net.forbric.kernel.transform.BreathingCallbackInjector;

/**
 * {@code MixinBreathingCallbackAdapter} through the real weave, on a mod that is not Create and does not write Create's
 * pair: one {@code @WrapOperation} of {@code MobEffectUtil.hasWaterBreathing} in vanilla's {@code baseTick}, selected by
 * a bare name, with no {@code @Local}; and beside it a wrap of {@code isEyeInFluid(WATER)} whose answer would change
 * whether the entity drowns. On the merged {@code baseTick}, which hands the calculation to NeoForge's
 * {@code CommonHooks.onLivingBreathe}, neither vanilla call is there to wrap.
 *
 * <p>Adapted, the water wrap alone runs inside NeoForge's calculation: the swimmer keeps its air, the mod is asked about
 * its gills each tick, and the eye wrap — which NeoForge has no place to answer — is left as compiled, so it is the
 * mod's one reported loss and its goggles are never asked. With {@code -Dforbric.breathingCallbacks=off} both wraps are
 * lost and the swimmer runs out of air.
 */
class BreathingSingleWrapWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/breathingsingle");
	private static final Path STAND_INS = Path.of("src/test/resources/weave/createbreathing");
	private static final String CONFIG = "breathingsingle.mixins.json";
	private static final String MOD = "scuba";

	private static final String ADAPTED = WeaveHarnessMain.DONE + " air 10 | asked gills, gills, gills";
	private static final String UNADAPTED = WeaveHarnessMain.DONE + " air 7 | asked nothing";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;

	@BeforeAll static void weave() throws Exception {
		fixture = WeaveHarness.fixture(work, "breathingsingle", List.of(
				STAND_INS.resolve("net/minecraft/world/level/material/Fluid.java"),
				STAND_INS.resolve("net/minecraft/tags/TagKey.java"),
				STAND_INS.resolve("net/minecraft/tags/FluidTags.java"),
				STAND_INS.resolve("net/minecraft/world/level/Level.java"),
				STAND_INS.resolve("net/minecraft/server/level/ServerLevel.java"),
				STAND_INS.resolve("net/minecraft/world/entity/LivingEntity.java"),
				STAND_INS.resolve("net/minecraft/world/effect/MobEffectUtil.java"),
				STAND_INS.resolve("net/neoforged/neoforge/common/CommonHooks.java"),
				SOURCES.resolve("fixture/breathingsingle/Swimmer.java"),
				SOURCES.resolve("fixture/breathingsingle/Probe.java"),
				SOURCES.resolve("org/example/scuba/Gills.java"),
				SOURCES.resolve("org/example/scuba/mixin/GillsMixin.java"),
				Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelWrapOperations.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		PreMixinFixture.transform(fixture, BreathingCallbackInjector.TARGET, new BreathingCallbackInjector(), EnvType.SERVER);
		adapted = run("adapted", Map.of());
		off = run("adapter-off", Map.of(MixinBreathingCallbackAdapter.PROPERTY, "off"));
	}

	@Test void theLoneWaterWrapRunsInsideNeoForgesAirCalculationAndTheEyeWrapIsAReportedLoss() throws Exception {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings " + adapted.findings());
		WeaveHarness.assertWovenAndVerified(adapted, "net/minecraft/world/entity/LivingEntity", fixture);
	}

	@Test void switchedOffBothWrapsAreLostAndTheSwimmerRunsOutOfAir() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings " + off.findings());
	}

	@Test void theControlFlipsEveryAdapterAssertion() {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapter predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) {
		List<WeaveHarness.Finding> losses = losses(run);
		return returned(run, ADAPTED) && losses.size() == 1 && losses.getFirst().id().contains("#dryEyes(");
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		List<WeaveHarness.Finding> losses = losses(run);
		return returned(run, UNADAPTED) && losses.size() == 2 && losses.stream().anyMatch(f -> f.id().contains("#gills("))
				&& losses.stream().anyMatch(f -> f.id().contains("#dryEyes("));
	}

	private static boolean returned(WeaveHarness.Result run, String line) {
		return run.output().lines().anyMatch(line::equals);
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:")
				&& !f.confidence().equals("RESOLVED")).toList();
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.breathingsingle.Probe", "probe", properties);
	}
}
