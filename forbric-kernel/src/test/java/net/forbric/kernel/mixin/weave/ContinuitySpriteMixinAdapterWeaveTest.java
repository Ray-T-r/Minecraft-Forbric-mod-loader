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
import net.forbric.kernel.mixin.MixinSpriteLoaderCallbackAdapter;

/**
 * {@code MixinSpriteLoaderCallbackAdapter} through the real weave, on a CLIENT run: Continuity's atlas hook, written for
 * vanilla's {@code list(ResourceManager)} with a FAILHARD capture of the loader map, on a merged SpriteSourceList whose
 * body moved to the carrier's {@code list(ResourceManager, Set)} and left vanilla's name as a delegating stub.
 *
 * <p>The probe lists an atlas whose pack has an emissive texture for one sprite. Adapted, the hook runs on the body
 * that has the builder call and is handed the real loader map, so the list carries the emissive loader it added. With
 * {@code -Dforbric.spriteLoaderCallbacks=off} the hook has no builder call to bind to in the stub: the list is
 * the plain sprites and the hook is the mod's required loss. The mixin's constructor hook, which the adapter does not
 * touch, applies in both runs (the "ctm_overlay" source), so the control is not a mixin that failed to load.
 */
class ContinuitySpriteMixinAdapterWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/continuitysprite");
	private static final String CONFIG = "continuitysprite.mixins.json";
	private static final String MOD = "continuity";
	private static final String TARGET = "net/minecraft/client/renderer/texture/atlas/SpriteSourceList";
	private static final String ADAPTER_LOG = "[Forbric/Mixin] atlas callbacks now use the metadata-aware list overload";

	private static final String ADAPTED = WeaveHarnessMain.DONE + " emissive:glow_e, sprite:ctm_overlay, sprite:glow, sprite:stone";
	private static final String UNADAPTED = WeaveHarnessMain.DONE + " sprite:ctm_overlay, sprite:glow, sprite:stone";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;

	@BeforeAll static void weave() throws Exception {
		fixture = WeaveHarness.fixture(work, "continuitysprite", List.of(
				SOURCES.resolve("com/google/common/collect/ImmutableList.java"),
				SOURCES.resolve("net/minecraft/server/packs/resources/ResourceManager.java"),
				SOURCES.resolve("net/minecraft/client/renderer/texture/atlas/SpriteSourceList.java"),
				SOURCES.resolve("fixture/continuitysprite/Probe.java"),
				SOURCES.resolve("me/pepperbell/continuity/client/mixin/SpriteSourceListMixin.java")),
				// -g: the merged game and the mod both ship a LocalVariableTable, and the capture is checked against it.
				Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		adapted = run("adapted", Map.of());
		off = run("adapter-off", Map.of(MixinSpriteLoaderCallbackAdapter.PROPERTY, "off"));
	}

	@Test void theHookRunsOnTheLiveOverloadWithTheRealLoaderMap() throws Exception {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings " + adapted.findings());
		WeaveHarness.assertWovenAndVerified(adapted, TARGET, fixture);
	}

	@Test void switchedOffTheHookIsLostAndTheEmissiveLoaderNeverAppears() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings " + off.findings());
		WeaveHarness.assertWovenAndVerified(off, TARGET, fixture);
	}

	/** Each run's predicate must fail on the other, or the control proves nothing about the adapter. */
	@Test void theControlFlipsEveryAdapterAssertion() {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapter predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) {
		return returned(run, ADAPTED) && run.printed(ADAPTER_LOG) && losses(run).isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		List<WeaveHarness.Finding> losses = losses(run);
		return returned(run, UNADAPTED) && !run.printed(ADAPTER_LOG) && losses.size() == 1
				&& losses.get(0).id().contains("#continuity$afterLoadSources(") && losses.get(0).required();
	}

	/** The probe's whole return line: a value that merely starts with the expected one is a different outcome. */
	private static boolean returned(WeaveHarness.Result run, String line) {
		return run.output().lines().anyMatch(line::equals);
	}

	/** The final audit's rows for the mod's injectors that did not attach. */
	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:")
				&& !f.confidence().equals("RESOLVED")).toList();
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.CLIENT,
				"fixture.continuitysprite.Probe", "probe", properties);
	}
}
