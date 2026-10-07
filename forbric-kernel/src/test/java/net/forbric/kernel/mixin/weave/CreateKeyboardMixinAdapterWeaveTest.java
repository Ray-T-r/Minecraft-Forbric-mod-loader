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
import net.forbric.kernel.mixin.MixinKeyActionAdapter;

/**
 * {@code MixinKeyActionAdapter} through the real weave, on a CLIENT run: Create Fly's key hooks, written for
 * vanilla's keyPress (the release at its sixth return, the press at its TAIL), on a merged keyPress where NeoForge
 * joined every exit into one {@code ClientHooks.onKeyInput} call.
 *
 * <p>The probe presses, repeats and releases one key and reports who heard each, in order. Adapted, the mod hears the
 * press and the repeat as presses after NeoForge's event and the release as a release just before it — once each,
 * because the adapter guards both handlers by the action they were written for. With
 * {@code -Dforbric.keyActionCallbacks=off} the release hook has no sixth return to bind to, so it is the mod's
 * required loss, and the TAIL hook reports the release as one more press.
 */
class CreateKeyboardMixinAdapterWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/createkeyboard");
	private static final String CONFIG = "createkeyboard.mixins.json";
	private static final String MOD = "create";
	private static final String TARGET = "net/minecraft/client/KeyboardHandler";

	private static final String ADAPTED = WeaveHarnessMain.DONE + " neoforge G1, create press G | neoforge G2, create press G"
			+ " | create release G, neoforge G0";
	private static final String UNADAPTED = WeaveHarnessMain.DONE + " neoforge G1, create press G | neoforge G2, create press G"
			+ " | neoforge G0, create press G";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;

	@BeforeAll static void weave() throws Exception {
		fixture = WeaveHarness.fixture(work, "createkeyboard", List.of(
				SOURCES.resolve("net/minecraft/client/input/KeyEvent.java"),
				SOURCES.resolve("net/neoforged/neoforge/client/ClientHooks.java"),
				SOURCES.resolve("net/minecraft/client/KeyboardHandler.java"),
				SOURCES.resolve("fixture/createkeyboard/Trail.java"),
				SOURCES.resolve("fixture/createkeyboard/Probe.java"),
				SOURCES.resolve("com/zurrtum/create/client/mixin/KeyboardHandlerMixin.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		adapted = run("adapted", Map.of());
		off = run("adapter-off", Map.of(MixinKeyActionAdapter.PROPERTY, "off"));
	}

	@Test void eachActionReachesTheModOnceAsWhatItWas() throws Exception {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings " + adapted.findings());
		assertTrue(WeaveHarness.hasMergedMethod(adapted.defined(TARGET)), adapted.describe());
		WeaveHarness.assertWovenAndVerified(adapted, TARGET, fixture);
	}

	@Test void switchedOffTheReleaseHookIsLostAndTheReleaseReadsAsAPress() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings " + off.findings());
		WeaveHarness.assertWovenAndVerified(off, TARGET, fixture);
	}

	/** Each run's predicate must fail on the other, or the control proves nothing about the adapter. */
	@Test void theControlFlipsEveryAdapterAssertion() {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off), "adapter predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted), "control predicate does not separate the runs");
	}

	private static boolean adaptedHolds(WeaveHarness.Result run) {
		return returned(run, ADAPTED) && losses(run).isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		List<WeaveHarness.Finding> losses = losses(run);
		return returned(run, UNADAPTED) && losses.size() == 1 && losses.get(0).id().contains("#onKeyReleased(")
				&& losses.get(0).required();
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
				"fixture.createkeyboard.Probe", "probe", properties);
	}
}
