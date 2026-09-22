/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.nio.file.Path;
import java.util.*;
import net.forbric.api.Ecosystem;
import net.forbric.api.VersionPredicate;
import net.forbric.kernel.metadata.forge.ForgeVersionRangeTranslator;
import org.sat4j.core.VecInt;
import org.sat4j.minisat.SolverFactory;
import org.sat4j.specs.*;

/** Whole-instance Boolean model: nested candidates exist only through selected parents. */
final class ReachableCandidateSelector {
	private final NestedCandidateInventory graph;
	private final List<JointCandidateSelector.Rule> rules;
	private final Map<Path, Integer> variables = new LinkedHashMap<>();
	private final Map<String, List<Path>> identities = new TreeMap<>();
	private final Set<String> rootIds = new TreeSet<>();
	private final Map<String, Map<Path, Set<String>>> artifacts = new TreeMap<>();
	private final Map<String, Ecosystem> overrides;
	private final List<Ecosystem> rootPreference, nestedPreference;
	private final long deadline;
	private final int checkLimit;
	private long checks;

	private ReachableCandidateSelector(NestedCandidateInventory graph, List<JointCandidateSelector.Rule> contracts,
			List<Ecosystem> rootPreference, List<Ecosystem> nestedPreference, Map<String, Ecosystem> overrides, int checkLimit) {
		this.graph = graph; this.rootPreference = rootPreference; this.nestedPreference = nestedPreference;
		Map<String, Ecosystem> pins = new LinkedHashMap<>(); overrides.forEach((id, family) -> pins.put(JointCandidateSelector.key(id), family));
		this.overrides = pins; this.checkLimit = Math.max(1, checkLimit);
		long millis = Math.max(1, Math.min(30_000, Long.getLong("forbric.arbitrationTimeoutMillis", 5_000L)));
		deadline = System.nanoTime() + millis * 1_000_000;
		for (var node : graph.nodes().values()) {
			variables.put(node.path(), variables.size() + 1);
			if (node.excluded() || node.claim() == null) continue;
			for (String raw : node.claim().modIds()) {
				String id = JointCandidateSelector.key(raw);
				identities.computeIfAbsent(id, ignored -> new ArrayList<>()).add(node.path());
				if (node.root()) rootIds.add(id);
			}
		}
		for (var edge : graph.edges()) if (edge.coordinate() != null && !graph.nodes().get(edge.child()).excluded()) {
			String id = artifactId(edge.coordinate().id());
			artifacts.computeIfAbsent(id, ignored -> new LinkedHashMap<>()).computeIfAbsent(edge.child(), ignored -> new LinkedHashSet<>()).add(edge.coordinate().version());
			List<Path> candidates = identities.computeIfAbsent(id, ignored -> new ArrayList<>());
			if (!candidates.contains(edge.child())) candidates.add(edge.child());
		}
		List<JointCandidateSelector.Rule> expanded = new ArrayList<>(contracts);
		for (var edge : graph.edges()) {
			if (edge.coordinate() == null || graph.nodes().get(edge.child()).excluded()) continue;
			Set<Path> providers = new LinkedHashSet<>(), unknown = new LinkedHashSet<>();
			String range = edge.coordinate().range();
			String predicate = ForgeVersionRangeTranslator.toFabricPredicate(range);
			boolean unreadable = "*".equals(predicate) && range != null && !range.isBlank()
					&& !Set.of("*", "(,)", "[,)", "(,]", "[,]").contains(range);
			for (var candidate : artifacts.get(artifactId(edge.coordinate().id())).entrySet()) {
				boolean all = !unreadable && candidate.getValue().stream().allMatch(v -> VersionPredicate.matchesStrictly(predicate, v));
				boolean any = unreadable || candidate.getValue().stream().anyMatch(v -> VersionPredicate.matches(predicate, v));
				if (all) providers.add(candidate.getKey()); else if (any) unknown.add(candidate.getKey());
			}
			expanded.add(new JointCandidateSelector.Rule("jarjar:" + edge.entry(), edge.parent(), providers, unknown, true,
					"requires bundled artifact " + edge.coordinate().id() + " " + range));
		}
		this.rules = List.copyOf(expanded);
	}

	static JointCandidateSelector.Result solve(NestedCandidateInventory graph, List<JointCandidateSelector.Rule> contracts,
			List<Ecosystem> roots, List<Ecosystem> nested, Map<String, Ecosystem> overrides, int limit) {
		ReachableCandidateSelector model = new ReachableCandidateSelector(graph, contracts, roots, nested, overrides, limit);
		JointCandidateSelector.Status status = JointCandidateSelector.Status.SOLVED;
		Set<Path> selected;
		try { selected = model.solve(true); if (selected == null) status = JointCandidateSelector.Status.UNSATISFIABLE; }
		catch (TimeoutException bounded) { selected = null; status = JointCandidateSelector.Status.SEARCH_LIMIT; }
		if (selected == null) {
			try { selected = model.solve(false); } catch (TimeoutException ignored) { }
			if (selected == null) selected = model.fallback();
		}
		List<JointCandidateSelector.Rule> failed = new ArrayList<>(), uncertain = new ArrayList<>();
		for (var rule : model.rules) {
			if (!selected.contains(rule.consumer()) || intersects(selected, rule.providers())) continue;
			if (rule.hard() && !intersects(selected, rule.uncertainProviders())) failed.add(rule); else uncertain.add(rule);
		}
		for (var issue : graph.issues()) if (selected.contains(issue.source())) uncertain.add(new JointCandidateSelector.Rule(
				"nested-scan:" + issue.detail(), issue.source(), Set.of(), Set.of(), true, issue.detail()));
		if (status == JointCandidateSelector.Status.SOLVED && uncertain.stream().anyMatch(JointCandidateSelector.Rule::hard)) status = JointCandidateSelector.Status.UNPROVED;
		return new JointCandidateSelector.Result(status, Set.copyOf(selected), List.copyOf(failed), List.copyOf(uncertain), model.checks);
	}

	private Set<Path> solve(boolean contracts) throws TimeoutException {
		if (variables.size() > 1024 || rules.size() > 20_000 || System.nanoTime() >= deadline) throw new TimeoutException("candidate model size/time bound");
		ISolver solver = SolverFactory.newDefault(); solver.newVar(variables.size());
		try {
			for (var node : graph.nodes().values()) {
				if (node.excluded()) { clause(solver, -variable(node.path())); continue; }
				if (!node.root()) {
					List<Integer> reachable = new ArrayList<>(List.of(-variable(node.path())));
					for (var edge : graph.edges()) if (edge.child().equals(node.path())) reachable.add(variable(edge.parent()));
					clause(solver, reachable);
				}
			}
			for (var group : identities.entrySet()) {
				if (System.nanoTime() >= deadline) throw new TimeoutException("candidate model time bound");
				List<Path> candidates = group.getValue();
				for (int a = 0; a < candidates.size(); a++) for (int b = a + 1; b < candidates.size(); b++) {
					if (!graph.payloadRelated(candidates.get(a), candidates.get(b))) clause(solver, -variable(candidates.get(a)), -variable(candidates.get(b)));
				}
				if (rootIds.contains(group.getKey())) clause(solver, candidates.stream().map(this::variable).toList());
				Ecosystem pin = overrides.get(group.getKey());
				if (pin != null) {
					List<Integer> pinned = candidates.stream().filter(p -> family(p) == pin).map(this::variable).toList();
					clause(solver, pinned);
				}
			}
			for (var edge : graph.edges()) {
				var child = graph.nodes().get(edge.child()); if (child.excluded()) continue;
				if (edge.payload() || (child.claim() == null && edge.coordinate() == null)) {
					clause(solver, -variable(edge.parent()), variable(edge.child()));
				} else if (edge.coordinate() != null) {
					List<Integer> required = new ArrayList<>(List.of(-variable(edge.parent())));
					for (Path provider : identities.get(artifactId(edge.coordinate().id()))) required.add(variable(provider));
					clause(solver, required);
				} else if (child.claim() != null) {
					for (String id : child.claim().modIds()) {
						List<Integer> required = new ArrayList<>(List.of(-variable(edge.parent())));
						for (Path provider : identities.get(JointCandidateSelector.key(id))) required.add(variable(provider));
						clause(solver, required);
					}
				}
			}
			if (contracts) for (var rule : rules) if (rule.hard()) {
				List<Integer> required = new ArrayList<>(List.of(-variable(rule.consumer())));
				for (Path provider : rule.possible()) if (variables.containsKey(provider)) required.add(variable(provider));
				clause(solver, required);
			}
		} catch (ContradictionException impossible) { return null; }
		VecInt assumptions = new VecInt();
		if (!satisfiable(solver, assumptions)) return null;
		List<String> order = new ArrayList<>(rootIds);
		for (String id : identities.keySet()) if (!rootIds.contains(id)) order.add(id);
		for (String id : order) {
			List<Path> candidates = new ArrayList<>(identities.get(id)); candidates.sort(candidateOrder(rootIds.contains(id), id));
			if (!rootIds.contains(id)) {
				VecInt absent = copy(assumptions); for (Path candidate : candidates) absent.push(-variable(candidate));
				if (satisfiable(solver, absent)) { assumptions = absent; continue; }
			}
			boolean chosen = false;
			for (Path candidate : candidates) {
				VecInt attempt = copy(assumptions); attempt.push(variable(candidate));
				if (satisfiable(solver, attempt)) { assumptions = attempt; chosen = true; break; }
			}
			if (!chosen) return null;
		}
		if (!satisfiable(solver, assumptions)) return null;
		Set<Integer> positive = new HashSet<>(); for (int value : solver.model()) if (value > 0) positive.add(value);
		Set<Path> result = new LinkedHashSet<>(); variables.forEach((path, variable) -> { if (positive.contains(variable)) result.add(path); });
		return result;
	}

	private boolean satisfiable(ISolver solver, VecInt assumptions) throws TimeoutException {
		long remaining = deadline - System.nanoTime();
		if (++checks > checkLimit || remaining <= 0) throw new TimeoutException("bounded candidate search");
		solver.setTimeoutMs(Math.max(1, remaining / 1_000_000));
		return solver.isSatisfiable(assumptions);
	}

	private Comparator<Path> candidateOrder(boolean root, String id) {
		return (a, b) -> {
			if (root && graph.nodes().get(a).root() != graph.nodes().get(b).root()) return graph.nodes().get(a).root() ? -1 : 1;
			if (artifacts.containsKey(id)) {
				String av = artifacts.get(id).get(a).iterator().next(), bv = artifacts.get(id).get(b).iterator().next();
				int version = VersionPredicate.compare(bv, av); if (version != 0) return version;
			}
			List<Ecosystem> preference = root ? rootPreference : nestedPreference;
			int ar = family(a) == null || !preference.contains(family(a)) ? preference.size() : preference.indexOf(family(a));
			int br = family(b) == null || !preference.contains(family(b)) ? preference.size() : preference.indexOf(family(b));
			return ar != br ? Integer.compare(ar, br) : a.toString().compareTo(b.toString());
		};
	}

	/** An explicit non-solution: retain chosen roots and only reachable descendants, never every losing jar. */
	private Set<Path> fallback() {
		Set<Path> selected = new LinkedHashSet<>();
		for (String id : rootIds) {
			List<Path> candidates = new ArrayList<>(identities.get(id)); candidates.sort(candidateOrder(true, id));
			Path choice = candidates.stream().filter(p -> graph.nodes().get(p).root())
					.filter(p -> !overrides.containsKey(id) || family(p) == overrides.get(id)).findFirst().orElse(candidates.getFirst());
			selected.add(choice);
		}
		for (var pin : overrides.entrySet()) {
			Path forced = identities.getOrDefault(pin.getKey(), List.of()).stream().filter(p -> family(p) == pin.getValue()).findFirst().orElse(null);
			if (forced != null) addWithParent(forced, selected, new HashSet<>());
		}
		for (int round = 0; round < graph.nodes().size(); round++) {
			boolean changed = false;
			for (var edge : graph.edges()) {
				if (!selected.contains(edge.parent()) || graph.nodes().get(edge.child()).excluded()) continue;
				var child = graph.nodes().get(edge.child());
				if (edge.payload() || (child.claim() == null && edge.coordinate() == null)) { changed |= selected.add(edge.child()); continue; }
				List<String> ids = child.claim() == null ? List.of(artifactId(edge.coordinate().id())) : child.claim().modIds().stream().map(JointCandidateSelector::key).toList();
				for (String id : ids) if (!intersects(selected, Set.copyOf(identities.get(id)))) {
					List<Path> candidates = new ArrayList<>(identities.get(id)); candidates.sort(candidateOrder(false, id));
					Path choice = candidates.stream().filter(p -> graph.nodes().get(p).root() || graph.edges().stream().anyMatch(e -> e.child().equals(p) && selected.contains(e.parent())))
							.findFirst().orElse(edge.child());
					changed |= selected.add(choice);
				}
			}
			if (!changed) break;
		}
		return selected;
	}
	private void addWithParent(Path path, Set<Path> selected, Set<Path> seen) {
		if (!seen.add(path)) return;
		selected.add(path); if (graph.nodes().get(path).root()) return;
		graph.edges().stream().filter(e -> e.child().equals(path)).findFirst().ifPresent(e -> addWithParent(e.parent(), selected, seen));
	}
	private Ecosystem family(Path path) { var claim = graph.nodes().get(path).claim(); return claim == null ? null : claim.ecosystem(); }
	private int variable(Path path) { return variables.get(path); }
	private static String artifactId(String id) { return "@jarjar:" + id; }
	private static VecInt copy(VecInt values) {
		// SAT4J's toArray() exposes capacity, including unused zero literals. Copy only logical entries and
		// never share its backing array: each trial must preserve the preceding assumptions unchanged.
		int[] entries = new int[values.size()]; values.copyTo(entries); return new VecInt(entries);
	}
	private static void clause(ISolver solver, int... values) throws ContradictionException { solver.addClause(new VecInt(values)); }
	private static void clause(ISolver solver, List<Integer> values) throws ContradictionException { clause(solver, values.stream().mapToInt(Integer::intValue).toArray()); }
	private static boolean intersects(Set<Path> selected, Set<Path> candidates) { return candidates.stream().anyMatch(selected::contains); }
}
