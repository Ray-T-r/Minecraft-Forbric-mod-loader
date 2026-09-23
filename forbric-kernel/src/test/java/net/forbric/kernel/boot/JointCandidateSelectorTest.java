package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.Test;

class JointCandidateSelectorTest {
	private static final List<Ecosystem> ORDER = List.of(Ecosystem.NEOFORGE, Ecosystem.FABRIC, Ecosystem.FORGE);
	private static DuplicateModArbiter.Claim candidate(String name, Ecosystem family, String... ids) {
		return new DuplicateModArbiter.Claim(Path.of("/mods/" + name + ".jar"), family, List.of(ids));
	}
	private static JointCandidateSelector.Rule requires(DuplicateModArbiter.Claim from, DuplicateModArbiter.Claim to) {
		return new JointCandidateSelector.Rule(from.jar() + "->" + to.jar(), from.jar(), Set.of(to.jar()), Set.of(), true, "required contract");
	}

	@Test void twoLocalPreferredChoicesMustBacktrackToTheGloballyValidPair() {
		var an = candidate("a-neo", Ecosystem.NEOFORGE, "a"); var af = candidate("a-fab", Ecosystem.FABRIC, "a");
		var bn = candidate("b-neo", Ecosystem.NEOFORGE, "b"); var bf = candidate("b-fab", Ecosystem.FABRIC, "b");
		var result = JointCandidateSelector.solve(List.of(an, af, bn, bf),
				List.of(requires(an, bf), requires(bf, af), requires(af, bn), requires(bn, af)), ORDER, Map.of(), 100);
		assertEquals(JointCandidateSelector.Status.SOLVED, result.status());
		assertEquals(Set.of(af.jar(), bn.jar()), result.selected(), "choosing each mod independently cannot find this pair");
		assertTrue(result.unsatisfied().isEmpty());
	}

	@Test void anUnsatisfiableCycleIsReportedAndTheFallbackIsNeverLabelledValid() {
		var an = candidate("a-neo", Ecosystem.NEOFORGE, "a"); var af = candidate("a-fab", Ecosystem.FABRIC, "a");
		var bn = candidate("b-neo", Ecosystem.NEOFORGE, "b"); var bf = candidate("b-fab", Ecosystem.FABRIC, "b");
		var result = JointCandidateSelector.solve(List.of(an, af, bn, bf),
				List.of(requires(an, bf), requires(bf, af), requires(af, bn), requires(bn, an)), ORDER, Map.of(), 100);
		assertEquals(JointCandidateSelector.Status.UNSATISFIABLE, result.status());
		assertFalse(result.unsatisfied().isEmpty());
	}

	@Test void everyIdInOneJarIsSelectedAtomically() {
		var preferred = candidate("x-only", Ecosystem.NEOFORGE, "x");
		var bundle = candidate("bundle", Ecosystem.FABRIC, "x", "y");
		var result = JointCandidateSelector.solve(List.of(preferred, bundle), List.of(), ORDER, Map.of(), 100);
		assertEquals(JointCandidateSelector.Status.SOLVED, result.status());
		assertEquals(Set.of(bundle.jar()), result.selected(), "y must survive and x must not be loaded twice");
	}

	@Test void conflictingExplicitPinsAreKeptVisibleAsAnUnsatisfiedSelection() {
		var x = candidate("x", Ecosystem.FABRIC, "x"); var bundle = candidate("bundle", Ecosystem.NEOFORGE, "x", "y");
		var result = JointCandidateSelector.solve(List.of(x, bundle), List.of(), ORDER,
				Map.of("x", Ecosystem.FABRIC, "y", Ecosystem.NEOFORGE), 100);
		assertEquals(JointCandidateSelector.Status.UNSATISFIABLE, result.status());
		assertEquals(Set.of(x.jar(), bundle.jar()), result.selected(), "do not silently change either explicit pin");
	}

	@Test void optionalOrUnprovedContractsDoNotRejectThePreferredCandidate() {
		var app = candidate("app", Ecosystem.FABRIC, "app"); var neo = candidate("dep-neo", Ecosystem.NEOFORGE, "dep");
		var fab = candidate("dep-fab", Ecosystem.FABRIC, "dep");
		var optional = new JointCandidateSelector.Rule("optional", app.jar(), Set.of(fab.jar()), Set.of(), false, "optional target");
		var unknown = new JointCandidateSelector.Rule("unknown", app.jar(), Set.of(fab.jar()), Set.of(neo.jar()), true, "unresolved ancestor");
		// A soft contract never moves the choice.
		var soft = JointCandidateSelector.solve(List.of(app, neo, fab), List.of(optional), ORDER, Map.of(), 100);
		assertEquals(JointCandidateSelector.Status.SOLVED, soft.status());
		assertTrue(soft.selected().contains(neo.jar()));
		// A proved provider is preferred over one that only might link (PLAN: preference among satisfying builds).
		var proved = JointCandidateSelector.solve(List.of(app, neo, fab), List.of(optional, unknown), ORDER, Map.of(), 100);
		assertEquals(JointCandidateSelector.Status.SOLVED, proved.status());
		assertTrue(proved.selected().contains(fab.jar()));
		assertTrue(proved.uncertain().isEmpty());
		// ...but the unproved one is never rejected: where the proved one is not allowed it runs, UNPROVED.
		var result = JointCandidateSelector.solve(List.of(app, neo, fab), List.of(optional, unknown), ORDER, Map.of("dep", Ecosystem.NEOFORGE), 100);
		assertEquals(JointCandidateSelector.Status.UNPROVED, result.status());
		assertTrue(result.selected().contains(neo.jar()));
		assertEquals(2, result.uncertain().size());
		assertTrue(result.unsatisfied().isEmpty());
	}

	@Test void aDeclaredBreakMakesThePreferenceYieldAndAnUnavoidableOneIsUnsatisfiable() {
		var app = candidate("app", Ecosystem.FABRIC, "app"); var neo = candidate("dep-neo", Ecosystem.NEOFORGE, "dep");
		var fab = candidate("dep-fab", Ecosystem.FABRIC, "dep");
		var breaks = new JointCandidateSelector.Rule("breaks:dep", app.jar(), Set.of(neo.jar()), Set.of(), true, "cannot run with dep", true);
		var result = JointCandidateSelector.solve(List.of(app, neo, fab), List.of(breaks), ORDER, Map.of(), 100);
		assertEquals(JointCandidateSelector.Status.SOLVED, result.status());
		assertEquals(Set.of(app.jar(), fab.jar()), result.selected());
		var both = new JointCandidateSelector.Rule("breaks:dep", app.jar(), Set.of(neo.jar(), fab.jar()), Set.of(), true, "cannot run with dep", true);
		result = JointCandidateSelector.solve(List.of(app, neo, fab), List.of(both), ORDER, Map.of(), 100);
		assertEquals(JointCandidateSelector.Status.UNSATISFIABLE, result.status());
		assertEquals(List.of(both), result.unsatisfied());
	}

	@Test void aBoundedSearchCannotReturnSolvedWhenTheLimitWasReached() {
		var a = candidate("a", Ecosystem.FABRIC, "a"); var b = candidate("b", Ecosystem.FABRIC, "b");
		var result = JointCandidateSelector.solve(List.of(a, b), List.of(), ORDER, Map.of(), 1);
		assertEquals(JointCandidateSelector.Status.SEARCH_LIMIT, result.status());
		assertTrue(result.visited() <= 2);
	}
}
