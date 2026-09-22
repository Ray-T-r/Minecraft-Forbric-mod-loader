/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.nio.file.Path;
import java.util.*;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModPresence;

/** Bounded exact-cover search over whole jars, with conditional dependency and symbol clauses. */
public final class JointCandidateSelector {
	public enum Status { SOLVED, UNPROVED, UNSATISFIABLE, SEARCH_LIMIT }
	/** If consumer is selected, at least one provider must be selected. Uncertain providers are never proof. */
	public record Rule(String id, Path consumer, Set<Path> providers, Set<Path> uncertainProviders,
			boolean hard, String detail) {
		public Rule {
			consumer = consumer.toAbsolutePath().normalize();
			providers = absolute(providers); uncertainProviders = absolute(uncertainProviders);
		}
		private static Set<Path> absolute(Set<Path> paths) {
			return paths.stream().map(p -> p.toAbsolutePath().normalize()).collect(java.util.stream.Collectors.toUnmodifiableSet());
		}
		Set<Path> possible() {
			Set<Path> all = new LinkedHashSet<>(providers); all.addAll(uncertainProviders); return all;
		}
	}
	public record Result(Status status, Set<Path> selected, List<Rule> unsatisfied, List<Rule> uncertain, long visited) { }

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
		JointCandidateSelector search = new JointCandidateSelector(claims, rules, preference, overrides, maxNodes);
		search.walk(new LinkedHashMap<>(), new LinkedHashSet<>());
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
			if (!selected.contains(rule.consumer()) || intersects(selected, rule.providers())) continue;
			if (rule.hard() && !intersects(selected, rule.uncertainProviders())) unmet.add(rule);
			else uncertain.add(rule);
		}
		if (status == Status.SOLVED && uncertain.stream().anyMatch(Rule::hard)) status = Status.UNPROVED;
		return new Result(status, Set.copyOf(selected), List.copyOf(unmet), List.copyOf(uncertain), search.visited);
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
