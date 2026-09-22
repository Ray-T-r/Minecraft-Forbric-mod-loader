package net.forbric.kernel.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("ModCatalog")
@ResourceLock("system-properties")
class CompatibilityDecisionTest {
	@TempDir Path tmp;
	@BeforeEach @AfterEach
	void reset() {
		CompatibilityDecision.reset();
		CompatibilityFindings.reset();
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
		assertEquals(1, DependencyDialog.askCompatibility(List.of(row), List.of("-Djava.awt.headless=true")));
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
