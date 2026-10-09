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

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.FabricEntityMixinAnchors;

/**
 * A clear-all effect veto that is neither fabric-api's nor balm's, through the real weave: another mod's
 * {@code @WrapOperation} of {@code activeEffects.clear()} written as a snapshot loop. The merged entity clears effect by
 * effect through NeoForge's keep-or-remove question and has no {@code clear()}.
 *
 * <p>Proved per-effect, the handler runs on a one-effect map for each effect NeoForge lets go: the mod is asked about
 * exactly those, the one it keeps stays, and so does the one NeoForge keeps. With
 * {@code -Dforbric.effectClearVeto=off} the wrap binds nothing: the mod is never asked, only NeoForge's effect survives,
 * and the audit reports the wrap.
 */
class EffectClearVetoWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/effectclearveto");
	private static final String CONFIG = "effectclearveto.mixins.json";
	private static final String MOD = "effectclearveto";
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final String VETOED = "asked=[mod_kept, speed] removed=true left=[mod_kept, neo_kept]";
	private static final String NATIVE_ONLY = "asked=[] removed=true left=[neo_kept]";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;

	@BeforeAll static void weaveBoth() throws Exception {
		fixture = WeaveHarness.fixture(work, MOD, sources(), Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		adapted = run("adapted", "on");
		off = run("off", "off");
	}

	@Test void theModIsAskedPerEffectAndItsKeptEffectStays() throws Exception {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings: " + adapted.findings());
		assertTrue(adapted.printed("[Forbric/Mixin] org.example.effects.mixin.RetainEffectsMixin's clear-all effect veto now wraps "
				+ "NeoForge's per-effect EventHooks.onEffectRemoved"), adapted.describe());
		assertTrue(WeaveHarness.hasMergedMethod(adapted.defined(LIVING)), adapted.describe());
		WeaveHarness.assertWovenAndVerified(adapted, LIVING, fixture);
	}

	@Test void withTheSwitchOffTheWrapIsAReportedLoss() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings: " + off.findings());
		WeaveHarness.assertWovenAndVerified(off, LIVING, fixture);
	}

	/** The run and its control must be told apart by the very predicates the test uses on them. */
	@Test void theControlFlipsEveryAssertion() {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapted predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + VETOED) && losses(run).isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		List<WeaveHarness.Finding> losses = losses(run);
		return run.printed(WeaveHarnessMain.DONE + " " + NATIVE_ONLY) && losses.size() == 1 && losses.get(0).id().contains("#retain");
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:") && f.modId().equals(MOD) && f.required()).toList();
	}

	private static WeaveHarness.Result run(String label, String veto) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.effectclearveto.Probe", "run", Map.of(FabricEntityMixinAnchors.CLEAR_VETO_PROPERTY, veto));
	}

	private static List<Path> sources() throws Exception {
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}
}
