/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.nio.file.Path;
import java.util.*;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModPresence;

/** Bounded exact-cover search over whole jars, with conditional dependency and symbol clauses. */
public final class JointCandidateSelector {
	public enum Status { SOLVED, UNPROVED, UNSATISFIABLE, SEARCH_LIMIT }
	/**
	 * If consumer is selected, at least one provider must be selected. Uncertain providers are never proof.
	 *
	 * <p>{@code excludes} turns it around (Fabric {@code breaks}/{@code conflicts}, NeoForge
	 * {@code incompatible}/{@code discouraged}): if consumer is selected, no provider may be. Providers are then
	 * the candidates proved to be in the excluded range and uncertain providers those that only might be.
	 */
	public record Rule(String id, Path consumer, Set<Path> providers, Set<Path> uncertainProviders,
			boolean hard, String detail, boolean excludes) {
		public Rule {
			consumer = consumer.toAbsolutePath().normalize();
			providers = absolute(providers); uncertainProviders = absolute(uncertainProviders);
		}
		public Rule(String id, Path consumer, Set<Path> providers, Set<Path> uncertainProviders, boolean hard, String detail) {
			this(id, consumer, providers, uncertainProviders, hard, detail, false);
		}
		private static Set<Path> absolute(Set<Path> paths) {
			return paths.stream().map(p -> p.toAbsolutePath().normalize()).collect(java.util.stream.Collectors.toUnmodifiableSet());
		}
		Set<Path> possible() {
			Set<Path> all = new LinkedHashSet<>(providers); all.addAll(uncertainProviders); return all;
		}
		/** This selection meets the rule with proof. */
		boolean provedBy(Set<Path> selected) {
			if (!selected.contains(consumer)) return true;
			return excludes ? !intersects(selected, providers) && !intersects(selected, uncertainProviders) : intersects(selected, providers);
		}
		/** This selection certainly breaks the rule (whether or not it is hard). */
		boolean brokenBy(Set<Path> selected) {
			if (!selected.contains(consumer)) return false;
			return excludes ? intersects(selected, providers) : !intersects(selected, possible());
		}
	}
	/**
	 * {@code unsatisfied} are contracts the selection breaks although another combination could meet them.
	 * {@code unavoidable} are contracts no installed combination can meet at all: not a choice this selector made.
	 * The two override maps (keyed by spelling key) are pins not honoured because they conflict with another pin
	 * or the bundling structure, and pins naming an ecosystem that has no usable candidate for the id.
	 */
	public record Result(Status status, Set<Path> selected, List<Rule> unsatisfied, List<Rule> uncertain, long visited,
			List<Rule> unavoidable, Map<String, Ecosystem> refusedOverrides, Map<String, Ecosystem> impossibleOverrides) {
		public Result(Status status, Set<Path> selected, List<Rule> unsatisfied, List<Rule> uncertain, long visited) {
			this(status, selected, unsatisfied, uncertain, visited, List.of(), Map.of(), Map.of());
		}
	}

	private final List<DuplicateModArbiter.Claim> claims;
	private final List<Rule> rules;
	private final Map<String, List<DuplicateModArbiter.Claim>> domains = new TreeMap<>();
	private final Map<Path, DuplicateModArbiter.Claim> byPath = new LinkedHashMap<>();
	private final Map<String, Ecosystem> overrides;
	private final int limit;
	private long visited;
	private boolean exhausted;
	private Set<Path> solution;

	private JointCandidateSelector(List<DuplicateModArbiter.Claim> claims, List<Rule> rules,
			List<Ecosystem> preference, Map<String, Ecosystem> overrides, int limit) {
		this.claims = claims; this.rules = rules; this.limit = Math.max(1, limit);
		Map<String, Ecosystem> pins = new LinkedHashMap<>();
		overrides.forEach((id, family) -> pins.put(key(id), family)); this.overrides = Map.copyOf(pins);
		Comparator<DuplicateModArbiter.Claim> order = Comparator
				.comparingInt((DuplicateModArbiter.Claim c) -> preference.contains(c.ecosystem()) ? preference.indexOf(c.ecosystem()) : preference.size())
				.thenComparing(c -> path(c).toString());
		for (DuplicateModArbiter.Claim claim : claims) {
			byPath.put(path(claim), claim);
			for (String id : claim.modIds()) domains.computeIfAbsent(key(id), ignored -> new ArrayList<>()).add(claim);
		}
		for (List<DuplicateModArbiter.Claim> domain : domains.values()) domain.sort(order);
	}

	/** Ecosystem preference chooses only among satisfying combinations; it never relaxes a hard clause. */
	public static Result solve(List<DuplicateModArbiter.Claim> claims, List<Rule> rules, List<Ecosystem> preference,
			Map<String, Ecosystem> overrides, int maxNodes) {
		// First only PROVED providers count wherever some build provably meets a contract and another only might;
		// the preference then chooses among builds shown to satisfy it. Only if that has no answer do unproved
		// providers count, so an unproved build is preferred less, never rejected.
		List<Rule> proved = rules.stream().map(r -> r.hard() && !r.excludes() && !r.providers().isEmpty() && !r.uncertainProviders().isEmpty()
				? new Rule(r.id(), r.consumer(), r.providers(), Set.of(), true, r.detail()) : r).toList();
		JointCandidateSelector search = new JointCandidateSelector(claims, proved, preference, overrides, maxNodes);
		search.walk(new LinkedHashMap<>(), new LinkedHashSet<>());
		long visited = search.visited;
		if (search.solution == null && !proved.equals(rules)) {
			search = new JointCandidateSelector(claims, rules, preference, overrides, maxNodes);
			search.walk(new LinkedHashMap<>(), new LinkedHashSet<>());
			visited += search.visited;
		}
		Status status = search.solution != null ? Status.SOLVED : search.exhausted ? Status.SEARCH_LIMIT : Status.UNSATISFIABLE;
		Set<Path> selected = search.solution;
		if (selected == null) {
			// The fallback is a runtime choice, NOT a satisfying assignment. Keep explicit pins and expose every
			// violated clause to the existing player confirmation policy.
			JointCandidateSelector identities = new JointCandidateSelector(claims, List.of(), preference, overrides, maxNodes);
			identities.walk(new LinkedHashMap<>(), new LinkedHashSet<>());
			selected = identities.solution != null ? identities.solution : identities.pinnedFallback();
		}
		List<Rule> unmet = new ArrayList<>(), uncertain = new ArrayList<>();
		for (Rule rule : rules) {
			if (rule.provedBy(selected)) continue;
			if (rule.hard() && rule.brokenBy(selected)) unmet.add(rule);
			else uncertain.add(rule);
		}
		if (status == Status.SOLVED && uncertain.stream().anyMatch(Rule::hard)) status = Status.UNPROVED;
		return new Result(status, Set.copyOf(selected), List.copyOf(unmet), List.copyOf(uncertain), visited);
	}

	private void walk(Map<String, Path> owners, Set<Path> selected) {
		if (solution != null || exhausted) return;
		if (++visited > limit) { exhausted = true; return; }
		String next = domains.keySet().stream().filter(id -> !owners.containsKey(id)).findFirst().orElse(null);
		if (next == null) {
			if (feasible(owners, selected)) solution = Set.copyOf(selected);
			return;
		}
		for (DuplicateModArbiter.Claim candidate : domains.get(next)) {
			if (!compatible(candidate, owners)) continue;
			Map<String, Path> expanded = new LinkedHashMap<>(owners);
			for (String id : candidate.modIds()) expanded.put(key(id), path(candidate));
			Set<Path> chosen = new LinkedHashSet<>(selected); chosen.add(path(candidate));
			if (feasible(expanded, chosen)) walk(expanded, chosen);
			if (solution != null || exhausted) return;
		}
	}

	private boolean feasible(Map<String, Path> owners, Set<Path> selected) {
		for (Rule rule : rules) {
			if (rule.excludes()) {
				// Selections only grow along a branch, so a proved excluded build next to its consumer is final.
				if (rule.hard() && rule.brokenBy(selected)) return false;
				continue;
			}
			if (!rule.hard() || !selected.contains(rule.consumer()) || intersects(selected, rule.possible())) continue;
			boolean couldStillProvide = rule.possible().stream().map(byPath::get).filter(Objects::nonNull)
					.anyMatch(candidate -> compatible(candidate, owners));
			if (!couldStillProvide) return false;
		}
		return true;
	}

	private boolean compatible(DuplicateModArbiter.Claim candidate, Map<String, Path> owners) {
		for (String id : candidate.modIds()) {
			String key = key(id);
			Ecosystem pin = overrides.get(key);
			if (pin != null && pin != candidate.ecosystem()) return false;
			Path existing = owners.get(key);
			if (existing != null && !existing.equals(path(candidate))) return false;
		}
		return true;
	}

	/** Contradictory multi-id pins cannot all be honoured atomically: retain the requested jars and report UNSAT. */
	private Set<Path> pinnedFallback() {
		Set<Path> selected = new LinkedHashSet<>();
		for (var entry : domains.entrySet()) {
			Ecosystem pin = overrides.get(entry.getKey());
			if (pin == null) continue;
			entry.getValue().stream().filter(c -> c.ecosystem() == pin).findFirst().ifPresent(c -> selected.add(path(c)));
		}
		Set<String> covered = new HashSet<>();
		for (DuplicateModArbiter.Claim claim : claims) if (selected.contains(path(claim))) for (String id : claim.modIds()) covered.add(key(id));
		for (var entry : domains.entrySet()) {
			if (covered.contains(entry.getKey())) continue;
			DuplicateModArbiter.Claim claim = entry.getValue().getFirst(); selected.add(path(claim));
			for (String id : claim.modIds()) covered.add(key(id));
		}
		return selected;
	}

	private static boolean intersects(Set<Path> selected, Set<Path> providers) {
		for (Path path : providers) if (selected.contains(path)) return true;
		return false;
	}
	static Path path(DuplicateModArbiter.Claim claim) { return claim.jar().toAbsolutePath().normalize(); }
	static String key(String id) { return ModPresence.spellingKey(id); }
}
