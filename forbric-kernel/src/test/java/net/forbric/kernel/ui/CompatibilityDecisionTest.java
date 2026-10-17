package net.forbric.kernel.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.ModCatalog;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.ExpectCaught;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("ModCatalog")
@ResourceLock("system-properties")
class CompatibilityDecisionTest {
	@TempDir Path tmp;
	/** build.gradle runs every test with the dialog switched off; the no-window client cases are about it being on. */
	private String dialogSwitch;

	@BeforeEach
	void rememberTheDialogSwitch() {
		dialogSwitch = System.getProperty(DependencyDialog.SWITCH);
	}

	@AfterEach
	void restoreTheDialogSwitch() {
		if (dialogSwitch == null) System.clearProperty(DependencyDialog.SWITCH); else System.setProperty(DependencyDialog.SWITCH, dialogSwitch);
	}

	@BeforeEach @AfterEach
	void reset() {
		CompatibilityDecision.reset();
		CompatibilityFindings.reset();
		ModCatalog.publish(List.of());
		System.clearProperty(CompatibilityDecision.PROPERTY);
	}

	private static CompatibilityFinding finding(String id, CompatibilityFinding.Confidence confidence, boolean required) {
		return new CompatibilityFinding(id, "demo", "Use item", "test", confidence, required, "result missing", List.of("observed"));
	}

	private static List<CompatibilityFinding> required() {
		return List.of(finding("one", CompatibilityFinding.Confidence.CONFIRMED, true));
	}

	@Test
	void defaultIsAskAndUnknownPoliciesAreStrict() {
		assertEquals(CompatibilityDecision.Policy.ASK, CompatibilityDecision.policy());
		System.setProperty(CompatibilityDecision.PROPERTY, "typo");
		assertEquals(CompatibilityDecision.Policy.STRICT, CompatibilityDecision.policy());
	}

	@Test
	void strictRejectsEvenWhenPlayerPreviouslyAcceptedAndNeverCallsUi() {
		assertTrue(CompatibilityDecision.decide(required(), CompatibilityDecision.Policy.CONTINUE, false, rows -> fail()));
		assertFalse(CompatibilityDecision.decide(required(), CompatibilityDecision.Policy.STRICT, true, rows -> fail()));
		CompatibilityFindings.record(required().getFirst());
		assertEquals(1, CompatibilityFindings.confirmedRequired().size(), "accepting never turns evidence green");
	}

	@ExpectCaught("the confirmation broke part-way")
	@Test
	void askWithoutDisplayCannotApproveAndOnlyExplicitContinueIsRemembered() {
		assertFalse(CompatibilityDecision.decide(required(), CompatibilityDecision.Policy.ASK, false, rows -> fail()));
		for (Integer answer : new Integer[] {null, 1, 2, -1}) {
			assertFalse(CompatibilityDecision.decide(required(), CompatibilityDecision.Policy.ASK, true, rows -> answer));
		}
		assertFalse(CompatibilityDecision.decide(required(), CompatibilityDecision.Policy.ASK, true, rows -> { throw new IllegalStateException(); }));
		AtomicInteger asks = new AtomicInteger();
		assertTrue(CompatibilityDecision.decide(required(), CompatibilityDecision.Policy.ASK, true, rows -> {
			asks.incrementAndGet(); return 0;
		}));
		assertTrue(CompatibilityDecision.decide(required(), CompatibilityDecision.Policy.ASK, true, rows -> fail()));
		assertEquals(1, asks.get());
	}

	@Test
	void optionalAndSuspectedDoNotRequireConsent() {
		List<CompatibilityFinding> findings = List.of(finding("suspect", CompatibilityFinding.Confidence.SUSPECTED, true),
				finding("optional", CompatibilityFinding.Confidence.CONFIRMED, false));
		assertTrue(CompatibilityDecision.decide(findings, CompatibilityDecision.Policy.STRICT, false, rows -> fail()));
	}

	@Test
	void lateQueueIsNonInteractiveAndDropsResolvedFindings() {
		CompatibilityFindings.record(required().getFirst());
		CompatibilityFindings.record(finding("resolved", CompatibilityFinding.Confidence.CONFIRMED, true));
		CompatibilityDecision.queue();
		CompatibilityFindings.resolve("resolved", "demo", "repair proved");
		assertEquals(required(), CompatibilityDecision.drain());
		assertTrue(CompatibilityDecision.drain().isEmpty());
	}

	@Test
	void acceptingMainFailureDoesNotPreapproveALaterClientInitializationFailure() {
		ModCatalog.publish(List.of(new ModCatalog.Entry(Ecosystem.FABRIC, "demo", "Demo", "1", "", List.of(), "demo.jar", "", "")));
		ModCatalog.mark("demo", ModCatalog.Status.FAILED, "its main entrypoint threw");
		System.setProperty(CompatibilityDecision.PROPERTY, "continue");
		assertTrue(CompatibilityDecision.check(false));
		ModCatalog.mark("demo", ModCatalog.Status.FAILED, "its client entrypoint threw");
		System.setProperty(CompatibilityDecision.PROPERTY, "ask");
		assertFalse(CompatibilityDecision.check(false), "a new necessary lifecycle failure requires a new decision");
		CompatibilityDecision.queue();
		assertEquals(List.of("initialization:entrypoint:client"), CompatibilityDecision.drain().stream().map(CompatibilityFinding::id).toList());
		assertEquals(2, CompatibilityFindings.confirmedRequired().size(), "a prior approval never erases its evidence");
	}

	/** Stands in for the child process and records which window a decision opened. */
	private static final class Recorded implements CompatibilityDecision.Windows {
		final List<DependencyReport.Confirmation> confirmations = new java.util.ArrayList<>();
		final List<DependencyDialog.Notice> notices = new java.util.ArrayList<>();
		final List<String> unshown = new java.util.ArrayList<>();
		final List<List<DependencyReport.CompatibilityRow>> noticeDetails = new java.util.ArrayList<>();
		Integer answer = DependencyDialogMain.CONTINUE;
		boolean noticeAnswer = true;
		@Override public Integer confirm(DependencyReport.Confirmation confirmation) { confirmations.add(confirmation); return answer; }
		@Override public boolean notice(DependencyDialog.Notice notice, List<DependencyReport.CompatibilityRow> suspected) {
			notices.add(notice); noticeDetails.add(suspected); return noticeAnswer;
		}
		@Override public void unshown(DependencyDialog.Notice notice, String why) { unshown.add(why); }
	}

	private static DependencyDialog.Notice missingDependency() {
		return new DependencyDialog.Notice(List.of(
				new DependencyReport.Row("forbricdepcanary", "Canary", "FABRIC", "forbricnosuchmod", ">=1.0.0", null),
				new DependencyReport.Row("other", "Other", "FABRIC", "somelib", "*", null)), List.of());
	}

	private static CompatibilityFinding arbitrationOf(String modId, String dependency) {
		return new CompatibilityFinding("arbitration:dependency:" + dependency, modId, "Mod dependency integration",
				"arbitration:canary.jar", CompatibilityFinding.Confidence.CONFIRMED, true, "requires " + dependency, List.of("providers=[]"));
	}

	@Test
	void aRequiredLossAndTheDependencyNoticeShareOneWindowAndTheSameDependencyIsAskedOnce() throws Exception {
		CompatibilityFindings.record(new CompatibilityFinding("mixin:x.json:M", "other", "Mixin M", "mixin:x.json",
				CompatibilityFinding.Confidence.SUSPECTED, true, "preflight miss", List.of("anchor")));
		CompatibilityFinding loss = arbitrationOf("forbricdepcanary", "forbricnosuchmod");
		Recorded windows = new Recorded();
		assertTrue(CompatibilityDecision.decide(List.of(loss), CompatibilityDecision.Policy.ASK, true, true, missingDependency(), windows));
		assertEquals(1, windows.confirmations.size(), "one window");
		assertEquals(List.of(), windows.notices, "the old notice is not opened as a second window");
		DependencyReport.Confirmation shown = windows.confirmations.getFirst();
		assertEquals(List.of("forbricnosuchmod"), shown.coveredDeps().stream().map(DependencyReport.Row::requiredId).toList());
		assertEquals(List.of("somelib"), shown.deps().stream().map(DependencyReport.Row::requiredId).toList());
		assertEquals(List.of("preflight miss"), shown.suspected().stream().map(DependencyReport.CompatibilityRow::detail).toList(),
				"suspicions travel to the details");
		assertEquals(1, shown.required().size(), "and are never part of the question");
		assertTrue(CompatibilityDecision.decide(List.of(loss), CompatibilityDecision.Policy.ASK, true, true,
				DependencyDialog.Notice.EMPTY, windows));
		assertEquals(1, windows.confirmations.size(), "an answered loss is not asked again");
	}

	@Test
	void theAuditHoldsItsFindingsForTheDecisionInsteadOfOpeningAWindowOfItsOwn() {
		net.forbric.kernel.boot.DependencyAudit.report(List.of(new net.forbric.api.DiscoveredMod(Ecosystem.FABRIC,
				"forbricdepcanary", "1.0.0", "Canary", List.of(new net.forbric.api.UnifiedDependency("forbricnosuchmod", ">=1.0.0", true)),
				List.of(), null, "canary.jar")), List.of(), net.forbric.api.Side.CLIENT);
		DependencyDialog.Notice held = DependencyDialog.takeHeld();
		assertEquals(List.of("forbricnosuchmod"), held.rows().stream().map(DependencyReport.Row::requiredId).toList());
		assertTrue(DependencyDialog.takeHeld().isEmpty(), "handed over once");
	}

	@Test
	void theFirstDecisionTakesTheHeldNoticeAndNoLaterOneCanShowItAgain() {
		DependencyDialog.hold(missingDependency().rows(), List.of());
		CompatibilityFindings.record(arbitrationOf("forbricdepcanary", "forbricnosuchmod"));
		CompatibilityFinding loss = arbitrationOf("forbricdepcanary", "forbricnosuchmod");
		System.clearProperty(DependencyDialog.SWITCH);
		assertTrue(CompatibilityDecision.check(true), "no window in the headless test JVM: the game asks instead");
		assertTrue(CompatibilityDecision.askedInGameForTheLaunch(loss), "and nothing was approved");
		assertTrue(DependencyDialog.takeHeld().isEmpty());
	}

	@Test
	void withNothingRequiredTheNoticeKeepsItsFailOpenWindowAndASuspicionOpensNothing() {
		CompatibilityFindings.record(new CompatibilityFinding("s", "demo", "f", "src", CompatibilityFinding.Confidence.SUSPECTED,
				true, "unproved", List.of()));
		Recorded windows = new Recorded();
		assertTrue(CompatibilityDecision.decide(List.of(), CompatibilityDecision.Policy.ASK, true, true, DependencyDialog.Notice.EMPTY, windows));
		assertEquals(List.of(), windows.notices, "a suspicion alone never opens a window");
		assertTrue(CompatibilityDecision.decide(List.of(), CompatibilityDecision.Policy.STRICT, true, true, missingDependency(), windows));
		assertEquals(1, windows.notices.size(), "nothing to decide: the unchanged notice");
		windows.noticeAnswer = false;
		assertFalse(CompatibilityDecision.decide(List.of(), CompatibilityDecision.Policy.ASK, true, true, missingDependency(), windows),
				"quitting the notice is the player's stop, carried to the launcher as the typed stop");
		assertEquals(List.of(), windows.confirmations);
	}

	/**
	 * A mixin whose anchors miss on another mod's class, recorded the way the guest adapter records it: the break
	 * for the dependency window's non-blocking mixin section, and its SUSPECTED twin under MixinCompatibility's own
	 * id, {@code mixin:<config>:<package>.<mixin>}. The break names the mixin as its config lists it, relative to
	 * the package, so a match on {@code ":" + mixin} never found the twin and the window listed the same mixin
	 * twice, once as a break and once as a note.
	 */
	@Test
	void aForeignMixinBreakAloneOpensTheNoticeAndItsOwnSuspicionIsNotListedAgain() throws Exception {
		String config = "mixins.iris.compat.sodium.json";
		Class<?> breaks = Class.forName("net.forbric.kernel.mixin.ForeignMixinBreaks");
		java.lang.reflect.Method forget = breaks.getDeclaredMethod("reset");
		forget.setAccessible(true);
		forget.invoke(null);
		net.forbric.kernel.mixin.MixinConfigOwners.publish(List.of(
				new net.forbric.kernel.mixin.MixinConfigOwners.Owned(config, "iris", Ecosystem.FABRIC)));
		try {
			net.forbric.kernel.mixin.ForeignMixinBreaks.record(config, "MixinRenderRegionManager",
					List.of("@Redirect RenderRegion.clearAllCachedBatches in uploadResults"));
			java.lang.reflect.Method suspect = Class.forName("net.forbric.kernel.mixin.MixinCompatibility").getDeclaredMethod(
					"record", String.class, String.class, String.class, CompatibilityFinding.Confidence.class, boolean.class, List.class);
			suspect.setAccessible(true);
			suspect.invoke(null, config, "net.irisshaders.iris.compat.sodium.mixin.MixinRenderRegionManager",
					"preflight could not resolve this mixin's anchors on another mod", CompatibilityFinding.Confidence.SUSPECTED,
					false, List.of("@Redirect RenderRegion.clearAllCachedBatches in uploadResults"));
			// Unrelated, and the kind every fabric-api boot has: it stays a note.
			CompatibilityFindings.record(new CompatibilityFinding(
					"mixin:fabric-content-registries-v0.mixins.json:net.fabricmc.fabric.mixin.content.registry.FuelValuesMixin",
					"fabric-content-registries-v0", "Mixin FuelValuesMixin", "mixin:fabric-content-registries-v0.mixins.json",
					CompatibilityFinding.Confidence.SUSPECTED, false, "1/2 anchors resolve", List.of("FuelValues.remove")));
			net.forbric.kernel.boot.DependencyAudit.report(List.of(new net.forbric.api.DiscoveredMod(Ecosystem.FABRIC,
					"iris", "1.11.2", "Iris", List.of(), List.of(), null, "iris.jar")), List.of(), net.forbric.api.Side.CLIENT);
			DependencyDialog.Notice held = DependencyDialog.takeHeld();
			assertEquals(List.of("iris:MixinRenderRegionManager"),
					held.mixins().stream().map(m -> m.owner() + ":" + m.mixin()).toList(), "the audit holds the break");
			Recorded windows = new Recorded();
			assertTrue(CompatibilityDecision.decide(List.of(), CompatibilityDecision.Policy.ASK, true, true, held, windows));
			assertEquals(1, windows.notices.size(), "a foreign break alone opens the one fail-open notice");
			assertEquals(List.of("1/2 anchors resolve"),
					windows.noticeDetails.getFirst().stream().map(DependencyReport.CompatibilityRow::detail).toList(),
					"the break's own suspicion is not listed a second time in the details");
			assertEquals(List.of(), windows.confirmations, "and nothing is asked: a suspicion needs no answer");
		} finally {
			forget.invoke(null);
			net.forbric.kernel.mixin.MixinConfigOwners.publish(List.of());
		}
	}

	@Test
	void strictAndNoDisplayOpenNothingAndContinueStillShowsTheNotice() {
		CompatibilityFinding loss = arbitrationOf("forbricdepcanary", "forbricnosuchmod");
		Recorded windows = new Recorded();
		assertFalse(CompatibilityDecision.decide(List.of(loss), CompatibilityDecision.Policy.STRICT, true, true, missingDependency(), windows));
		// A dedicated server has nobody to ask, in a window or in a game: ask fails closed there.
		assertFalse(CompatibilityDecision.decide(List.of(loss), CompatibilityDecision.Policy.ASK, false, false, missingDependency(), windows));
		assertEquals(2, windows.unshown.size());
		assertEquals(List.of(), windows.confirmations);
		assertEquals(List.of(), windows.notices);
		assertTrue(CompatibilityDecision.decide(List.of(loss), CompatibilityDecision.Policy.CONTINUE, true, true, missingDependency(), windows));
		assertEquals(1, windows.notices.size(), "an explicit continue policy still shows the fail-open notice once");
		assertEquals(List.of(), windows.confirmations);
	}

	@Test
	void aStopTellsAnUnattendedOperatorWhichPropertyContinuesExplicitly() {
		CompatibilityFindings.record(required().getFirst());
		System.setProperty(CompatibilityDecision.PROPERTY, "strict");
		java.io.PrintStream err = System.err;
		java.io.ByteArrayOutputStream log = new java.io.ByteArrayOutputStream();
		try {
			System.setErr(new java.io.PrintStream(log, true, java.nio.charset.StandardCharsets.UTF_8));
			assertThrows(CompatibilityDecision.LaunchStopped.class, () -> CompatibilityDecision.requireContinuation(false));
		} finally {
			System.setErr(err);
		}
		String said = log.toString(java.nio.charset.StandardCharsets.UTF_8);
		assertTrue(said.contains("launch stopped: required mod initialization or features are unavailable"), said);
		assertTrue(said.contains("policy STRICT") && said.contains("-Dforbric.compatibilityPolicy=continue"), said);
	}

	@Test
	void closingTheNewDialogRejectsButLegacyDependencyDialogStillContinues() {
		assertEquals(1, DependencyDialogMain.confirmationAnswerFrom(DialogLang.EN, null));
		assertEquals(1, DependencyDialogMain.confirmationAnswerFrom(DialogLang.EN, javax.swing.JOptionPane.UNINITIALIZED_VALUE));
		assertEquals(0, DependencyDialogMain.answerFrom(DialogLang.EN, null));
		assertEquals(0, DependencyDialogMain.confirmationAnswerFrom(DialogLang.ZH_CN, DialogLang.ZH_CN.get("button.continue")));
		assertEquals(DialogLang.EN.get("button.quit"), DependencyDialogMain.confirmationPane(DialogLang.EN,
				new javax.swing.JPanel()).getInitialValue());
	}

	@Test
	void realChildCannotApproveWhenNoDisplayExists() throws Exception {
		var row = new DependencyReport.CompatibilityRow("demo", "Demo", "use item", "missing result", "test", "proof");
		// Not a refusal either: nobody was asked, and the launch takes the question to the game.
		assertEquals(DependencyDialogMain.UNSHOWN, DependencyDialog.askCompatibility(List.of(row), List.of("-Djava.awt.headless=true")));
	}

	/**
	 * Issue #57: on FCL the window's process cannot start (error 13 from execve), and that was read as the player
	 * saying no -- every launch with a required loss stopped, and the player saw nothing. A window that cannot be shown
	 * now moves the question into the game, which accepts nothing until the player answers there.
	 */
	@Test
	void aClientThatCannotShowTheWindowAsksInTheGameAndApprovesNothing() {
		CompatibilityFinding loss = arbitrationOf("forbricdepcanary", "forbricnosuchmod");
		CompatibilityFindings.record(loss);
		Recorded windows = new Recorded();
		windows.answer = DependencyDialogMain.UNSHOWN;
		assertTrue(CompatibilityDecision.decide(List.of(loss), CompatibilityDecision.Policy.ASK, true, true, missingDependency(), windows));
		assertEquals(1, windows.confirmations.size(), "the window was tried first");
		assertEquals(List.of("the confirmation window could not be shown"), windows.unshown);
		assertTrue(CompatibilityDecision.askedInGameForTheLaunch(loss));

		// The next boundary (client setup, inside Minecraft.<init>) neither forks again nor stops the game first.
		assertTrue(CompatibilityDecision.decide(List.of(loss), CompatibilityDecision.Policy.ASK, true, true, DependencyDialog.Notice.EMPTY, windows));
		assertEquals(1, windows.confirmations.size());

		// Nothing was accepted: a dedicated-server style check still sees an open question.
		assertFalse(CompatibilityDecision.decide(List.of(loss), CompatibilityDecision.Policy.ASK, false, false, DependencyDialog.Notice.EMPTY, windows));
		CompatibilityDecision.queue();
		assertEquals(List.of(loss.key()), CompatibilityDecision.drain().stream().map(CompatibilityFinding::key).toList(),
				"the game's prompt picks it up");

		// The player's Continue there is the consent, and the question is no longer the launch's.
		CompatibilityDecision.acknowledge(List.of(loss));
		assertFalse(CompatibilityDecision.askedInGameForTheLaunch(loss));
		assertTrue(CompatibilityDecision.decide(List.of(loss), CompatibilityDecision.Policy.ASK, false, false, DependencyDialog.Notice.EMPTY, windows));
	}

	/**
	 * A launcher's "open this world on start" leaves no moment to ask in the game before the world is loaded and saved,
	 * so a question no window could ask is not moved there: the launch is refused, as before, and says why.
	 */
	@Test
	void aLaunchThatOpensAWorldAtOnceIsNotHandedToTheGame() {
		CompatibilityFinding loss = arbitrationOf("forbricdepcanary", "forbricnosuchmod");
		System.clearProperty(DependencyDialog.SWITCH);
		Recorded windows = new Recorded();
		windows.answer = DependencyDialogMain.UNSHOWN;
		CompatibilityDecision.noteGameArguments(new String[] {"--username", "x", "--quickPlaySingleplayer", "New World"});
		assertFalse(CompatibilityDecision.decide(List.of(loss), CompatibilityDecision.Policy.ASK, true, true, DependencyDialog.Notice.EMPTY, windows));
		assertFalse(CompatibilityDecision.askedInGameForTheLaunch(loss));
		assertFalse(CompatibilityDecision.decide(List.of(loss), CompatibilityDecision.Policy.ASK, false, true, DependencyDialog.Notice.EMPTY, windows),
				"no window at all: the same");

		// --quickPlayPath only names a log file; =value spellings count.
		CompatibilityDecision.noteGameArguments(new String[] {"--quickPlayPath", "quickPlay/log.json"});
		assertTrue(CompatibilityDecision.decide(List.of(loss), CompatibilityDecision.Policy.ASK, true, true, DependencyDialog.Notice.EMPTY, windows));
		CompatibilityDecision.reset();
		CompatibilityDecision.noteGameArguments(new String[] {"--quickPlayMultiplayer=localhost"});
		assertFalse(CompatibilityDecision.decide(List.of(loss), CompatibilityDecision.Policy.ASK, true, true, DependencyDialog.Notice.EMPTY, windows));
	}

	/** {@code -Dforbric.dependencyDialog=off} is "no window, nobody to ask": it fails closed on a headless client too. */
	@Test
	void theOffSwitchStillFailsClosedOnAClientWithNoWindow() {
		CompatibilityFinding loss = arbitrationOf("forbricdepcanary", "forbricnosuchmod");
		Recorded windows = new Recorded();
		System.setProperty(DependencyDialog.SWITCH, "off");
		assertFalse(CompatibilityDecision.decide(List.of(loss), CompatibilityDecision.Policy.ASK, false, true, DependencyDialog.Notice.EMPTY, windows));
		assertFalse(CompatibilityDecision.askedInGameForTheLaunch(loss));
		assertEquals(List.of("-Dforbric.dependencyDialog=off"), windows.unshown);
	}

	@Test
	void aLossFoundAfterTheQuestionMovedToTheGameIsAskedAboutAsWell() {
		CompatibilityFinding first = arbitrationOf("forbricdepcanary", "forbricnosuchmod");
		CompatibilityFinding later = finding("client-entrypoint", CompatibilityFinding.Confidence.CONFIRMED, true);
		Recorded windows = new Recorded();
		windows.answer = DependencyDialogMain.UNSHOWN;
		assertTrue(CompatibilityDecision.decide(List.of(first), CompatibilityDecision.Policy.ASK, true, true, DependencyDialog.Notice.EMPTY, windows));
		assertTrue(CompatibilityDecision.decide(List.of(first, later), CompatibilityDecision.Policy.ASK, true, true, DependencyDialog.Notice.EMPTY, windows));
		assertEquals(2, windows.confirmations.size(), "a new loss is a new question");
		assertEquals(2, windows.confirmations.get(1).required().size());
		assertTrue(CompatibilityDecision.askedInGameForTheLaunch(later));
	}

	@Test
	void aClientWithNoWindowAtAllAsksInTheGameButAnExplicitQuitStillStopsIt() {
		CompatibilityFinding loss = arbitrationOf("forbricdepcanary", "forbricnosuchmod");
		System.clearProperty(DependencyDialog.SWITCH);
		Recorded windows = new Recorded();
		assertTrue(CompatibilityDecision.decide(List.of(loss), CompatibilityDecision.Policy.ASK, false, true, DependencyDialog.Notice.EMPTY, windows));
		assertEquals(List.of(), windows.confirmations, "nothing to fork");
		assertEquals(List.of("no window can be drawn beside the game"), windows.unshown);
		assertTrue(CompatibilityDecision.askedInGameForTheLaunch(loss));

		CompatibilityDecision.reset();
		Recorded refused = new Recorded();
		refused.answer = DependencyDialogMain.QUIT;
		assertFalse(CompatibilityDecision.decide(List.of(loss), CompatibilityDecision.Policy.ASK, true, true, DependencyDialog.Notice.EMPTY, refused),
				"the player's own Quit in the window is the answer");
		assertFalse(CompatibilityDecision.askedInGameForTheLaunch(loss));
	}

	@Test
	void confirmationProtocolRejectsTruncatedInputInsteadOfApprovingAnEmptyReport() throws Exception {
		Path file = tmp.resolve("report.tsv");
		var row = new DependencyReport.CompatibilityRow("demo", "Demo", "use item", "missing result", "test", "proof");
		DependencyReport.writeCompatibility(file, List.of(row));
		assertEquals(List.of(row), DependencyReport.readCompatibility(file));
		Files.writeString(file, "--compatibility-v1--\ntruncated\trow\n");
		assertThrows(java.io.IOException.class, () -> DependencyReport.readCompatibility(file));
	}
}
