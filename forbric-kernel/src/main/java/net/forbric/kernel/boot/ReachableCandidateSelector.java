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
	private final Map<Path, List<Path>> parents = new HashMap<>();
	private final Map<Path, Integer> depth = new HashMap<>();
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
		for (var edge : graph.edges()) {
			parents.computeIfAbsent(edge.child(), ignored -> new ArrayList<>()).add(edge.parent());
			if (edge.coordinate() == null || graph.nodes().get(edge.child()).excluded()) continue;
			String id = artifactId(edge.coordinate().id());
			artifacts.computeIfAbsent(id, ignored -> new LinkedHashMap<>()).computeIfAbsent(edge.child(), ignored -> new LinkedHashSet<>()).add(edge.coordinate().version());
			List<Path> candidates = identities.computeIfAbsent(id, ignored -> new ArrayList<>());
			if (!candidates.contains(edge.child())) candidates.add(edge.child());
		}
		// Shallowest nesting level of each node, so a parent's identity is always decided before its children's.
		Map<Path, List<Path>> children = new HashMap<>();
		for (var edge : graph.edges()) children.computeIfAbsent(edge.parent(), ignored -> new ArrayList<>()).add(edge.child());
		Deque<Path> frontier = new ArrayDeque<>();
		for (var node : graph.nodes().values()) if (node.root()) { depth.put(node.path(), 0); frontier.add(node.path()); }
		while (!frontier.isEmpty()) {
			Path parent = frontier.removeFirst();
			for (Path child : children.getOrDefault(parent, List.of())) if (!depth.containsKey(child)) {
				depth.put(child, depth.get(parent) + 1); frontier.add(child);
			}
		}
		List<JointCandidateSelector.Rule> expanded = new ArrayList<>(contracts);
		for (var edge : graph.edges()) {
			if (edge.coordinate() == null || graph.nodes().get(edge.child()).excluded()) continue;
			Set<Path> providers = new LinkedHashSet<>(), unknown = new LinkedHashSet<>();
			String range = edge.coordinate().range();
			String predicate;
			boolean malformed = false;
			// Hand-written metadata.json can carry "[1.0" or "[]". The legacy extractor never parsed ranges and
			// loaded such packs; one bad constraint must leave its choice unproved, not abort the whole boot.
			try { predicate = ForgeVersionRangeTranslator.toFabricPredicate(range); }
			catch (IllegalArgumentException unparseable) { predicate = "*"; malformed = true; }
			boolean unreadable = malformed || "*".equals(predicate) && range != null && !range.isBlank()
					&& !Set.of("*", "(,)", "[,)", "(,]", "[,]").contains(range);
			String translated = predicate; boolean open = unreadable;
			Map<Path, Set<String>> sameArtifact = artifacts.get(artifactId(edge.coordinate().id()));
			for (Path candidate : edgeProviders(edge)) {
				// The coordinate's own artifact is judged by the version the parent's metadata recorded for it;
				// another build of the same mod (a top-level copy, another ecosystem's platform artifact, a
				// Fabric JiJ child that carries no JarJar metadata at all) by the version that build declares.
				Set<String> versions = sameArtifact.containsKey(candidate) ? sameArtifact.get(candidate)
						: Set.of(modVersion(candidate, graph.nodes().get(edge.child()).claim().modIds().getFirst()));
				boolean all = !open && versions.stream().allMatch(v -> VersionPredicate.matchesStrictly(translated, v));
				boolean any = open || versions.stream().anyMatch(v -> VersionPredicate.matches(translated, v));
				if (all) providers.add(candidate); else if (any) unknown.add(candidate);
			}
			expanded.add(new JointCandidateSelector.Rule("jarjar:" + edge.entry(), edge.parent(), providers, unknown, true,
					(malformed ? "malformed JarJar version range, left unproved: " : "") + "requires bundled artifact "
							+ edge.coordinate().id() + " " + range));
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
					for (Path parent : parents.getOrDefault(node.path(), List.of())) reachable.add(variable(parent));
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
					for (Path provider : edgeProviders(edge)) required.add(variable(provider));
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
		for (String id : decisionOrder()) {
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
			if (ar != br) return Integer.compare(ar, br);
			// Two builds of one mod from the SAME ecosystem: both genuine loaders keep the highest version
			// (cc2ec44). The candidate directory is a content digest, so the path is only the last resort.
			if (!artifacts.containsKey(id) && family(a) == family(b)) {
				int version = VersionPredicate.compare(modVersion(b, id), modVersion(a, id)); if (version != 0) return version;
			}
			return a.toString().compareTo(b.toString());
		};
	}

	/**
	 * Roots first, then every other identity from the shallowest nesting level down, mod ids before JarJar
	 * artifacts at each level. A child identity is only decided once its parents are, so trying "absent" first
	 * for a nested id can never switch off the parent that bundles it, and an artifact identity cannot take a
	 * build away from the mod-id contest that owns the preference.
	 */
	private List<String> decisionOrder() {
		List<String> order = new ArrayList<>(rootIds), nested = new ArrayList<>();
		for (String id : identities.keySet()) if (!rootIds.contains(id)) nested.add(id);
		nested.sort(Comparator.<String>comparingInt(id -> identities.get(id).stream().mapToInt(p -> depth.getOrDefault(p, Integer.MAX_VALUE)).min().orElse(Integer.MAX_VALUE))
				.thenComparing(id -> id.startsWith("@jarjar:")).thenComparing(Comparator.naturalOrder()));
		order.addAll(nested);
		return order;
	}

	/**
	 * What can satisfy a JarJar coordinate: the named artifact, or any build that claims every mod id the bundled
	 * child claims. FML resolves by mod id; a platform-specific artifact name (xaerolib-forge-26.2 against
	 * xaerolib-neoforge-26.2) or a Fabric parent that carries no JarJar metadata must not make one library
	 * two mutually required, mutually exclusive jars.
	 */
	private Set<Path> edgeProviders(NestedCandidateInventory.Edge edge) {
		Set<Path> providers = new LinkedHashSet<>(identities.getOrDefault(artifactId(edge.coordinate().id()), List.of()));
		var child = graph.nodes().get(edge.child()).claim();
		if (child == null || child.modIds().isEmpty()) return providers;
		Set<String> needed = new HashSet<>(); for (String id : child.modIds()) needed.add(JointCandidateSelector.key(id));
		for (Path candidate : identities.getOrDefault(JointCandidateSelector.key(child.modIds().getFirst()), List.of())) {
			var claim = graph.nodes().get(candidate).claim();
			if (claim.modIds().stream().map(JointCandidateSelector::key).collect(java.util.stream.Collectors.toSet()).containsAll(needed)) providers.add(candidate);
		}
		return providers;
	}

	/** The version a candidate declares for {@code id} (either spelling), or "0" when it declares none. */
	private String modVersion(Path candidate, String id) {
		var claim = graph.nodes().get(candidate).claim(); if (claim == null) return "0";
		String key = JointCandidateSelector.key(id);
		for (String raw : claim.modIds()) if (JointCandidateSelector.key(raw).equals(key)) return claim.versionOf(raw);
		return "0";
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
					List<Path> candidates = new ArrayList<>(identities.get(id)); candidates.sort(candidateOrder(rootIds.contains(id), id));
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
