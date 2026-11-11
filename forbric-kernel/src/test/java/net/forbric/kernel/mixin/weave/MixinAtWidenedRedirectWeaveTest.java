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
import net.forbric.kernel.mixin.MixinAtWidenedCall;

/**
 * {@code MixinAtWidenedCall}'s redirect of a widened static call, woven by the real pipeline: a guest {@code @Redirect} of
 * {@code RegistryFriendlyByteBuf.decorator(RegistryAccess)}, which the patched game calls with NeoForge's connection type
 * appended (creativecore's decorator redirects on the configuration listeners, in miniature).
 *
 * <p>The probe returns what each target method built:
 * <ul>
 *   <li>finish — moved, the guest's own decorator builds the payload; with the switch off the carrier's does, and the
 *       required redirect is a confirmed loss (the strict launch EnhancedVisuals' CreativeCore could not get past);</li>
 *   <li>captured — the same with the target's own argument captured after the call's: the wrapper hands it on;</li>
 *   <li>encoded — a static call widened the same way that no {@code REDIRECTABLE} row reviews: never moved, since the
 *       appended argument may be the carrier's mechanism. {@code require=0}, so it is no loss in either run;</li>
 *   <li>wrapped — an INSTANCE call widened the same way. Never moved: replacing it would replace every override of the
 *       carrier's method. {@code require=0} too.</li>
 * </ul>
 */
class MixinAtWidenedRedirectWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/widenedredirect");
	private static final String CONFIG = "widenedredirect.mixins.json";
	private static final String MOD = "widenedredirect";
	private static final String TARGET = "net/minecraft/widenedredirect/ConfigurationListener";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved;
	private static WeaveHarness.Result off;

	@BeforeAll static void weaveBoth() throws Exception {
		fixture = WeaveHarness.fixture(work, "widenedredirect", List.of(
				SOURCES.resolve("net/minecraft/core/RegistryAccess.java"),
				SOURCES.resolve("net/neoforged/neoforge/network/connection/ConnectionType.java"),
				SOURCES.resolve("net/minecraft/network/RegistryFriendlyByteBuf.java"),
				SOURCES.resolve("net/minecraft/widenedredirect/ConfigurationListener.java"),
				SOURCES.resolve("fixture/widenedredirect/mixin/ConfigurationListenerMixin.java")),
				Map.of(CONFIG, SOURCES.resolve(CONFIG)));
		moved = run("moved", "on");
		off = run("off", "off");
	}

	@Test void theGuestsRedirectReplacesTheWidenedStaticCall() throws Exception {
		assertTrue(moved.printed(WeaveHarnessMain.DONE), "the woven probe threw — " + moved.describe());
		assertTrue(movedHolds(moved), moved.describe() + "\nfindings: " + moved.findings());
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	@Test void withTheSwitchOffTheRequiredRedirectIsAReportedLoss() throws Exception {
		assertTrue(off.printed(WeaveHarnessMain.DONE), "the control probe threw — " + off.describe());
		assertTrue(offHolds(off), off.describe() + "\nfindings: " + off.findings());
	}

	/** The run and its control must be told apart by the very predicates the test uses on them. */
	@Test void theControlFlipsEveryMoveAssertion() {
		assertTrue(movedHolds(moved) && !movedHolds(off), "move predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(moved), "control predicate does not separate the runs");
	}

	private static boolean movedHolds(WeaveHarness.Result run) {
		return run.printedLine(WeaveHarnessMain.DONE + " finish=mine(registries:p) captured=mine(registries:q|q) encoded=codec(r,NEOFORGE) "
				+ "wrapped=wrap(s,NEOFORGE)")
				&& losses(run, "mine").isEmpty() && losses(run, "mineCaptured").isEmpty() && losses(run, "mineCodec").isEmpty()
				&& losses(run, "mineWrapped").isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printedLine(WeaveHarnessMain.DONE + " finish=typed(registries,NEOFORGE:p) captured=typed(registries,NEOFORGE:q) "
				+ "encoded=codec(r,NEOFORGE) wrapped=wrap(s,NEOFORGE)")
				&& losses(run, "mine").size() == 1 && losses(run, "mineCaptured").size() == 1 && losses(run, "mineCodec").isEmpty()
				&& losses(run, "mineWrapped").isEmpty();
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run, String handler) {
		return run.findings().stream().filter(f -> f.id().startsWith("mixin-injector:") && f.id().contains("#" + handler + "(")
				&& f.modId().equals(MOD) && f.confirmedRequired()).toList();
	}

	private static WeaveHarness.Result run(String label, String rule) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER,
				"net.minecraft.widenedredirect.ConfigurationListener", "probe", Map.of(MixinAtWidenedCall.REDIRECT_PROPERTY, rule));
	}
}
