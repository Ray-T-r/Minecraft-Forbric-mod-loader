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
 * {@code MixinKeyActionAdapter} through the real weave, on a CLIENT run: Create Fly's key hooks, written for vanilla's
 * keyPress ({@code RETURN} ordinal 5 and TAIL), on a merged keyPress where NeoForge joined every exit into one
 * {@code ClientHooks.onKeyInput} call. The native body is a stand-in with vanilla 26.2's six returns, where ordinal 5 is
 * the final return and the release returns earlier, at ordinal 4.
 *
 * <p>The probe presses, repeats and releases one key and reports who heard each, in order. Natively both hooks run on
 * the press and on the repeat, the ordinal-5 one first, and neither on the release; adapted, the merged run does the
 * same, after NeoForge's event. With {@code -Dforbric.keyActionCallbacks=off} the ordinal-5 hook has no sixth return to
 * bind to, so it is the mod's required loss, and the TAIL hook reports the release as one more press.
 */
class CreateKeyboardMixinAdapterWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/createkeyboard");
	private static final String CONFIG = "createkeyboard.mixins.json";
	private static final String MOD = "create";
	private static final String TARGET = "net/minecraft/client/KeyboardHandler";

	private static final String NATIVE = WeaveHarnessMain.DONE + " 1=[create release G, create press G]"
			+ " | 2=[create release G, create press G] | 0=[]";
	private static final String ADAPTED = WeaveHarnessMain.DONE + " 1=[neoforge G1, create release G, create press G]"
			+ " | 2=[neoforge G2, create release G, create press G] | 0=[neoforge G0]";
	private static final String UNADAPTED = WeaveHarnessMain.DONE + " 1=[neoforge G1, create press G]"
			+ " | 2=[neoforge G2, create press G] | 0=[neoforge G0, create press G]";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result nativeRun;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;

	@BeforeAll static void weave() throws Exception {
		List<Path> shared = List.of(
				SOURCES.resolve("net/minecraft/client/input/KeyEvent.java"),
				SOURCES.resolve("net/neoforged/neoforge/client/ClientHooks.java"),
				SOURCES.resolve("fixture/createkeyboard/Trail.java"),
				SOURCES.resolve("fixture/createkeyboard/Probe.java"),
				SOURCES.resolve("com/zurrtum/create/client/mixin/KeyboardHandlerMixin.java"));
		List<Path> original = new java.util.ArrayList<>(shared), game = new java.util.ArrayList<>(shared);
		original.add(SOURCES.resolve("native/net/minecraft/client/KeyboardHandler.java"));
		game.add(SOURCES.resolve("net/minecraft/client/KeyboardHandler.java"));
		Path nativeFixture = WeaveHarness.fixture(work, "native-createkeyboard", original, Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		fixture = WeaveHarness.fixture(work, "createkeyboard", game,
				KeyActionExitsWeaveTest.withNativeReference(work, nativeFixture, TARGET, CONFIG, SOURCES.resolve(CONFIG)));
		nativeRun = run("native", nativeFixture, Map.of());
		adapted = run("adapted", fixture, Map.of());
		off = run("adapter-off", fixture, Map.of(MixinKeyActionAdapter.PROPERTY, "off"));
	}

	@Test void eachActionReachesTheModAsItDoesNatively() throws Exception {
		assertTrue(nativeRun.printedLine(NATIVE), nativeRun.describe());
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
		return run.printedLine(ADAPTED) && losses(run).isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		List<WeaveHarness.Finding> losses = losses(run);
		return run.printedLine(UNADAPTED) && losses.size() == 1 && losses.get(0).id().contains("#onKeyReleased(")
				&& losses.get(0).required();
	}

	/** The final audit's rows for the mod's injectors that did not attach. */
	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.modId().equals(MOD) && f.id().startsWith("mixin-injector:")
				&& !f.confidence().equals("RESOLVED")).toList();
	}

	private static WeaveHarness.Result run(String label, Path jar, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, jar, CONFIG, MOD, Ecosystem.FABRIC, EnvType.CLIENT,
				"fixture.createkeyboard.Probe", "probe", properties);
	}
}
