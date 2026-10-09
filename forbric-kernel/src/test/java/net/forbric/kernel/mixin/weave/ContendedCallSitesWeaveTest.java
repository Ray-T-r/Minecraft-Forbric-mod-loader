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

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.ContendedCallSites;
import net.forbric.kernel.transform.GuestMixinPluginGuard;

/**
 * Two mods that want one call site, woven by the real Mixin through the boot's own pipeline (the plugin guard
 * included): {@code glassmod}, a NeoForge mod, takes {@code Paints.solid} in {@code Stage.render} with an
 * {@code @Redirect}; {@code cloakpatch}, a Fabric mod, patches the same call with raw ASM from its config plugin's
 * {@code postApply}. Every name here is the fixture's own.
 *
 * <p>The runs, and what each must show:
 * <ul>
 *   <li>contended — Mixin's own order: the redirect takes the call, the raw patch finds none and changes nothing,
 *       which is what either mod's own loader does with both installed. Forbric picks no winner and reports the
 *       contention once, as a finding on both mods;</li>
 *   <li>contended, {@code -Dforbric.contendedCallSites=off} — the same woven outcome and no report, so the outcome is
 *       Mixin's and the report is the only thing this adds;</li>
 *   <li>watched — glassmod's injector is an {@code @Inject} anchored on the same call, which leaves the call in place:
 *       the patch lands, nothing is reported;</li>
 *   <li>other call — cloakpatch patches the other paint call in the same method: both mods take effect, nothing is
 *       reported;</li>
 *   <li>look-alike — {@code tallypatch}'s postApply does work, changes nothing and names no call: nothing is
 *       reported, although glassmod's redirect took a call in the method it was handed.</li>
 * </ul>
 */
class ContendedCallSitesWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/contendedcallsite");
	private static final String TARGET = "fixture/stagecall/Stage";
	private static final String GLASS = "glassmod.mixins.json";
	private static final String WATCH = "glassmod-watch.mixins.json";
	private static final String CLOAK = "cloakpatch.mixins.json";
	private static final String TALLY = "tallypatch.mixins.json";

	/** The redirect's answer for the solid call, and the outline call untouched: what both mods' own loaders weave. */
	private static final String REDIRECT_WINS = WeaveHarnessMain.DONE + " glass:solid:cloak|outline:trim";
	private static final String REPORT_LINE = "[Forbric/CallSite] contended call site fixture.stagecall.Stage.render -> "
			+ "Paints.solid: glassmod's @Redirect (glassmod.mixins.json:StageGlassMixin.glassmod$solid) took the call "
			+ "before cloakpatch's post-Mixin patch (com.example.cloakpatch.CloakPlugin.postApply) ran";
	private static final String REPORTED = "[Forbric/CallSite] contended call site";
	/** Each negative must be turned away by its own condition, which -Dforbric.debug names. */
	private static final String CHANGED = "[Forbric/CallSite] fixture.stagecall.Stage.render: cloakpatch's postApply changed "
			+ "the method, so glassmod taking Paints.solid cost it nothing it looked for";
	private static final String UNNAMED = "[Forbric/CallSite] fixture.stagecall.Stage.render: tallypatch's jar does not name "
			+ "Paints.solid, so its postApply was not looking for the call glassmod took";
	private static final String FINDING_ID = "call-site-contention:fixture.stagecall.Stage.render()Ljava/lang/String;"
			+ "@Lfixture/stagecall/Paints;solid(Ljava/lang/String;)Ljava/lang/String;";

	@TempDir static Path work;
	private static Path withCloak, withTally;
	private static WeaveHarness.Result contended, contendedOff, watched, otherCall, lookalike;

	@BeforeAll static void weaveEveryPair() throws Exception {
		List<Path> common = sources("common");
		List<Path> cloak = new ArrayList<>(common);
		cloak.addAll(sources("cloakpatch"));
		List<Path> tally = new ArrayList<>(common);
		tally.addAll(sources("tallypatch"));
		assertEquals(6, cloak.size(), "the fixture's sources changed; update this test with it: " + cloak);
		// Two jars, so the look-alike's jar holds no other plugin's names: a plugin is judged by its own jar.
		withCloak = WeaveHarness.fixture(work, "with-cloakpatch", cloak, Map.of(GLASS, resource("common", GLASS),
				WATCH, resource("common", WATCH), CLOAK, resource("cloakpatch", CLOAK)));
		withTally = WeaveHarness.fixture(work, "with-tallypatch", tally, Map.of(GLASS, resource("common", GLASS),
				TALLY, resource("tallypatch", TALLY)));

		contended = run("contended", withCloak, GLASS, CLOAK, Map.of());
		contendedOff = run("contended-off", withCloak, GLASS, CLOAK, Map.of(ContendedCallSites.SWITCH, "off"));
		watched = run("watched", withCloak, WATCH, CLOAK, Map.of("forbric.debug", "true"));
		otherCall = run("other-call", withCloak, GLASS, CLOAK, Map.of("cloakpatch.call", "outline", "forbric.debug", "true"));
		lookalike = run("lookalike", withTally, GLASS, TALLY, Map.of("forbric.debug", "true"));
	}

	@Test void theRedirectTakesTheCallAndThePatchFindsNoneAsOnEitherModsOwnLoader() throws Exception {
		for (WeaveHarness.Result run : List.of(contended, contendedOff)) {
			assertTrue(run.printedLine(REDIRECT_WINS), run.describe());
			assertTrue(run.printed("[cloakpatch] found no solid call to patch"), run.describe());
			WeaveHarness.assertWovenAndVerified(run, TARGET, withCloak);
		}
	}

	@Test void theContentionIsReportedOnceAsAFindingOnBothMods() {
		assertTrue(contended.printed(REPORT_LINE), contended.describe());
		assertEquals(1, contended.output().lines().filter(line -> line.contains(REPORTED)).count(), contended.describe());
		List<WeaveHarness.Finding> findings = contentions(contended);
		assertEquals(List.of("cloakpatch", "glassmod"), findings.stream().map(WeaveHarness.Finding::modId).sorted().toList(),
				contended.describe() + "\nfindings: " + contended.findings());
		for (WeaveHarness.Finding finding : findings) {
			assertEquals(FINDING_ID, finding.id());
			assertEquals("SUSPECTED", finding.confidence(), "a contention is not a loss either loader would not have");
			assertFalse(finding.required());
		}
	}

	@Test void switchedOffTheSameBytesAreWovenAndNothingIsReported() {
		assertFalse(contendedOff.printed(REPORTED), contendedOff.describe());
		assertEquals(List.of(), contentions(contendedOff));
	}

	@Test void anInjectorThatLeavesTheCallInPlaceIsNoContention() {
		assertTrue(watched.printed("[glassmod] watched the solid paint call"), watched.describe());
		assertTrue(watched.printed("[cloakpatch] patched solid"), watched.describe());
		assertTrue(watched.printedLine(WeaveHarnessMain.DONE + " translucent:cloak|outline:trim"), watched.describe());
		// The call is still in the method when the patch runs, so there is not even a candidate to turn away.
		assertFalse(watched.printed("[Forbric/CallSite]"), watched.describe());
		assertEquals(List.of(), contentions(watched));
	}

	@Test void aPatchOfAnotherCallInTheSameMethodIsNoContention() {
		assertTrue(otherCall.printed("[cloakpatch] patched outline"), otherCall.describe());
		assertTrue(otherCall.printedLine(WeaveHarnessMain.DONE + " glass:solid:cloak|translucent:trim"), otherCall.describe());
		assertTrue(otherCall.printed(CHANGED), otherCall.describe());
		assertFalse(otherCall.printed(REPORTED), otherCall.describe());
		assertEquals(List.of(), contentions(otherCall));
	}

	@Test void aPluginThatNamesNoCallIsLeftAlone() {
		assertTrue(lookalike.printed("[tallypatch] counted"), lookalike.describe());
		assertTrue(lookalike.printedLine(REDIRECT_WINS), lookalike.describe());
		assertTrue(lookalike.printed(UNNAMED), lookalike.describe());
		assertFalse(lookalike.printed(REPORTED), lookalike.describe());
		assertEquals(List.of(), contentions(lookalike));
	}

	/** The report's predicate must separate the contended run from every other one, or it proves nothing. */
	@Test void onlyTheContendedRunReports() {
		for (WeaveHarness.Result run : List.of(contendedOff, watched, otherCall, lookalike)) {
			assertFalse(run.printed(REPORTED) || !contentions(run).isEmpty(), run.describe());
		}
		assertTrue(contended.printed(REPORTED) && contentions(contended).size() == 2, contended.describe());
	}

	private static List<WeaveHarness.Finding> contentions(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> f.source().equals("ContendedCallSites")).toList();
	}

	private static WeaveHarness.Result run(String label, Path fixture, String injector, String patcher,
			Map<String, String> properties) throws Exception {
		String patcherMod = patcher.substring(0, patcher.indexOf('.'));
		return WeaveHarness.run(work, label, fixture,
				List.of(new WeaveHarness.Config(injector, "glassmod", Ecosystem.NEOFORGE),
						new WeaveHarness.Config(patcher, patcherMod, Ecosystem.FABRIC)),
				List.of(GuestMixinPluginGuard.class), EnvType.CLIENT, "fixture.stagecall.Stage", "render", properties);
	}

	private static List<Path> sources(String part) throws Exception {
		try (Stream<Path> walk = Files.walk(SOURCES.resolve(part))) {
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}

	private static Path resource(String part, String name) {
		return SOURCES.resolve(part).resolve(name);
	}
}
