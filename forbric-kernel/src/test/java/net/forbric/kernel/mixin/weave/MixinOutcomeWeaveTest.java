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

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;

/**
 * gate-m36's mixin-outcome canary, woven by the real pipeline in CI.
 *
 * <p>The canary's sources are read in place from {@code canary/mixin-outcome}, so this test and the gate judge the
 * same mixins. Its {@code OutcomePlugin} applies one mixin per mode, chosen by {@code -Dforbric.outcomeMode}:
 * <ul>
 *   <li>required — a HEAD handler that must run, and an absent INVOKE anchor under {@code defaultRequire 1} that
 *       must become exactly one CONFIRMED, required loss;</li>
	 *   <li>optional — the same pair with {@code require=0} on the absent one: the surviving handler runs, the
	 *       missing one warns, and nothing is a required loss;</li>
 *   <li>declined — the plugin applies nothing: no handler runs, nothing is a loss;</li>
 *   <li>widened — an {@code @ModifyArg} written against {@code ValueCarrier.apply(String)} while the target calls
 *       {@code apply(String,String)}. {@code MixinAtWidenedCall} retargets it, so the argument changes;</li>
 *   <li>widened-off — the same with {@code -Dforbric.mixinAtWiden=off}: the RED control. The argument does not
 *       change and the handler is a confirmed loss.</li>
 * </ul>
 * What stays only in gate-m36 needs a running server: the completed-tick halt under strict, the third tick under
 * continue, and the saved shutdown. Everything below is the gate's own assertion set (gate-m36 lines 39-49).
 */
class MixinOutcomeWeaveTest {
	private static final Path CANARY = Path.of("canary/mixin-outcome");
	private static final String CONFIG = "outcome-canary.mixins.json";
	private static final String TARGET = "forbric/outcome/OutcomeTarget";

	@TempDir static Path work;
	private static Path fixture;
	private static final Map<String, WeaveHarness.Result> RUNS = new LinkedHashMap<>();

	@BeforeAll static void weaveEveryMode() throws Exception {
		List<Path> sources = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(CANARY.resolve("src"))) {
			// OutcomeCanary is the @Mod that drives the target from a server tick; it needs NeoForge, and here the
			// harness calls the target itself.
			walk.filter(p -> p.toString().endsWith(".java") && !p.getFileName().toString().equals("OutcomeCanary.java"))
					.sorted().forEach(sources::add);
		}
		assertEquals(6, sources.size(), "the canary's sources changed; update this test with it: " + sources);
		fixture = WeaveHarness.fixture(work, "mixin-outcome", sources, Map.of(CONFIG, CANARY.resolve(CONFIG)));
		for (String phase : List.of("required", "optional", "declined", "widened", "widened-off")) {
			String mode = phase.equals("widened-off") ? "widened" : phase;
			RUNS.put(phase, WeaveHarness.run(work, phase, fixture, CONFIG, "forbricoutcome", Ecosystem.NEOFORGE,
					EnvType.SERVER, "forbric.outcome.OutcomeTarget", "run", Map.of("forbric.outcomeMode", mode,
							"forbric.mixinAtWiden", phase.equals("widened-off") ? "off" : "on")));
		}
	}

	@Test void eachModeDoesWhatTheGateRequires() throws Exception {
		for (var run : RUNS.entrySet()) {
			String phase = run.getKey();
			WeaveHarness.Result result = run.getValue();
			String mode = phase.equals("widened-off") ? "widened" : phase;
			boolean required = mode.equals("required") || phase.equals("widened-off");

			assertTrue(result.printed("[M36Outcome] target ran"), result.describe());
			assertTrue(result.printed(WeaveHarnessMain.DONE), "the woven target threw — " + result.describe());
			List<WeaveHarness.Finding> losses = losses(result);
			assertEquals(required, !losses.isEmpty(), phase + " losses " + losses + "\n" + result.describe());
			if (required) {
				assertEquals(1, losses.size(), phase + " " + losses);
				assertTrue(losses.get(0).id().contains(mode.equals("widened") ? "change" : "missing"), losses.toString());
			}
			assertEquals(mode.equals("required"), result.printed("required present handler ran"), phase);
			assertEquals(mode.equals("optional"), result.printed("optional present handler ran"), phase);
			assertFalse(result.printed("absent site executed"), phase);
			WeaveHarness.assertWovenAndVerified(result, TARGET, fixture);
		}
		assertTrue(RUNS.get("widened").printed("[M36Outcome] value=changed|context"), RUNS.get("widened").describe());
		assertTrue(RUNS.get("widened-off").printed("[M36Outcome] value=initial|context"), RUNS.get("widened-off").describe());
	}

	/** The widened run and its control must be told apart by the very predicates the test uses on them. */
	@Test void theWidenedControlFlipsEveryWideningAssertion() {
		WeaveHarness.Result widened = RUNS.get("widened");
		WeaveHarness.Result off = RUNS.get("widened-off");
		assertTrue(widenedHolds(widened) && !widenedHolds(off), "widening predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(widened), "control predicate does not separate the runs");
		// And the declined run, which applies no mixin at all, is the control for every "handler ran" line.
		WeaveHarness.Result declined = RUNS.get("declined");
		assertFalse(declined.printed("present handler ran"), declined.describe());
		assertTrue(losses(declined).isEmpty(), declined.findings().toString());
	}

	@Test void anAbsentOptionalHandlerWarnsWhileTheOtherHandlerStillExecutes() {
		WeaveHarness.Result optional = RUNS.get("optional");
		List<String> warnings = optional.output().lines().filter(line -> line.contains("[Forbric/WARN]") && line.contains("injection warning:")
				&& line.contains(CONFIG) && line.contains("forbric.outcome.mixin.OptionalMixin")
				&& line.contains("missing") && line.contains("forbric.outcome.OutcomeTarget")).toList();
		assertEquals(1, warnings.size(), "the absent optional handler must be visible once in the real weave\n" + optional.describe());
		assertTrue(warnings.getFirst().contains("forbricoutcome"), warnings.toString());
		assertTrue(optional.printed("optional present handler ran"), optional.describe());
		assertFalse(optional.printed("absent site executed"), optional.describe());
		assertTrue(losses(optional).isEmpty(), "logging an optional miss must not make it required\n" + optional.describe());
		assertFalse(optional.output().lines().anyMatch(line -> line.contains("[Forbric/WARN]") && line.contains(CONFIG)
				&& line.contains("injection warning:") && line.contains("present(")), "the handler that actually attached must not warn\n" + optional.describe());
		assertFalse(RUNS.get("declined").output().lines().anyMatch(line -> line.contains("[Forbric/WARN]")
				&& line.contains("injection warning:") && line.contains(CONFIG)), "a plugin-declined mixin is not a missing injection\n" + RUNS.get("declined").describe());
	}

	private static boolean widenedHolds(WeaveHarness.Result run) {
		return run.printed("value=changed|context") && losses(run).isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printed("value=initial|context") && losses(run).size() == 1 && losses(run).get(0).id().contains("change");
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:")
				&& f.modId().equals("forbricoutcome") && f.confirmedRequired()).toList();
	}
}
