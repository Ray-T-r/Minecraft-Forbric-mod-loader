/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.fabric;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sat4j.core.VecInt;
import org.sat4j.minisat.SolverFactory;
import org.sat4j.specs.ContradictionException;
import org.sat4j.specs.ISolver;
import org.sat4j.specs.TimeoutException;

import net.forbric.api.ModPresence;
import net.forbric.kernel.mixin.FabricMixinCompat;
import net.forbric.kernel.util.ForbricLog;

/**
 * Fabric Loader's one mod per id, {@code provides} included, among the mods that load.
 *
 * <p>Read off fabric-loader 0.19.3. {@code ModPrioSorter.sort} indexes every candidate under its id and each id it
 * provides, and {@code ModSolver} lets at most one of each index load ({@code UNIQUE_ID}). A jar in {@code mods/} that
 * alone answers to one of its ids is forced ({@code ROOT_FORCELOAD_SINGLE}); every other candidate is weighted
 * {@code -2^(n - priority)}, so the choice is the lexicographic optimum over {@code ModPrioSorter}'s order: each mod in
 * turn loads if it can, given the ones before it. A nested mod also needs a parent that loads
 * ({@code NESTED_REQ_PARENT}) and every mod a provider for each hard dependency ({@code HARD_DEP}); one with a hard
 * dependency nothing installed meets is switched off ({@code HARD_DEP_NO_CANDIDATE}). So next to owo-lib,
 * owo-sentinel (nested in mods that need owo-lib so it can ask for it when it is missing; it {@code provides}
 * {@code owo} and {@code owo-lib}) never loads, and of two nested mods that share an id the one Fabric Loader orders
 * first loads, unless a mod ordered before both needs the other: a mod nested in it, or a mod that depends on it.
 *
 * <p>Applied to the selection, never before it. Cross-jar arbitration ({@code ReachableCandidateSelector}) first picks
 * one build per declared id across ecosystems; only among what it selected does this decide which mods answering to a
 * shared id load ({@code DuplicateModArbiter} runs arbitration again where another build of a loser's id could take
 * its place). Builds of one mod are never rivals here, also when they share an alias rather than their id (every
 * {@code mixinextras-fabric} copy provides {@code com_github_llamalad7_mixinextras}): that is arbitration's.
 *
 * <p>What takes part: Fabric Loader's own index, the Fabric jars in {@code mods/} and the nested Fabric mods it reaches
 * itself (a Fabric mod's {@code jars}) that {@code -Dforbric.nestedRequirements=off:<id>} does not exempt. Their hard
 * dependencies are Fabric's, so they shape the choice as on Fabric. Everything else is outside that index: a
 * Forge-family mod, a Fabric mod a JarJar declaration or a Forge-family walk brought in, an exempted one, a library
 * without {@code fabric.mod.json}. Such a jar loads whenever a parent of it loads, goes with its parents, never decides
 * a rivalry, and its needs, which Fabric Loader never sees, are met only after Fabric's choice is made: by keeping a
 * would-be loser back.
 *
 * <p>Where the kernel stays more lenient than native:
 * <ul>
 * <li>Jars in {@code mods/} all load. Native drops one that no id of its own singles out when a rival takes its ids,
 *     and refuses to start when two forced jars collide.</li>
 * <li>A nested mod with a hard dependency nothing that loads meets is switched off by native, and with it what it
 *     bundles and what only it satisfies. Here native's choice is taken with that mod off, and then the mod, and what
 *     goes with it, comes back wherever it takes nothing native loads: it yields every id it shares, and still loads
 *     where nothing contests it ({@code DependencyAudit} reports the dependency).</li>
 * <li>A mod that needs a would-be loser, by its id or one it provides, with nothing else that loads meeting the need,
 *     keeps it back, with a WARN, and the winner loads too. For a jar in {@code mods/} native drops the winning jar in
 *     {@code mods/} instead if no id of its own forces it, and refuses to start otherwise; for a mod outside Fabric's
 *     index native knows nothing of the need. The build kept back is the one arbitration chose for its id; another
 *     installed build that would not need keeping back is not tried.</li>
 * </ul>
 * Not weighed: {@code recommends} (native tries to meet each before its order) and {@code breaks} between mods, a mod
 * listing its own id or an alias twice (native then never loads it), and {@code minecraft}, {@code java},
 * {@code fabricloader} and the like (see {@link NestedFabricRequirements}). {@code -Dforbric.nestedRequirements=off}
 * turns this off with the rest of Fabric's nested-mod rule.
 */
public final class FabricUniqueIds {
	/** Work bounds, as in cross-jar arbitration: the same pack decides the same on every machine. */
	static final int CONFLICT_BUDGET = 200_000;

	private FabricUniqueIds() {
	}

	/**
	 * {@link #resolve}'s answer: what is left out (with why), what is kept back, and which of the left-out mods lost a
	 * rivalry themselves (rather than going with a loser), with the id they lost and whether a jar in {@code mods/} won it.
	 */
	public record Outcome<K>(Map<K, String> leftOut, Map<K, NestedFabricRequirements.KeptBack> keptBack, Map<K, Lost> lost) {
	}

	/** A rivalry lost: the id, and whether the winner is a jar in {@code mods/} (it loads whatever else is chosen). */
	public record Lost(String id, boolean toRoot) {
	}

	/** Two Fabric mods that load, of different ids, both answering to {@code id}; at least one of them judged. */
	private record Rivals<K>(NestedFabricRequirements.Candidate<K> a, NestedFabricRequirements.Candidate<K> b, String id) {
	}

	/** A need that holds if {@code by} loads (or always, for {@code by == null}), met by any of {@code providers}. */
	private record Need<K>(NestedFabricRequirements.Candidate<K> by, NestedFabricRequirements.Requirement requirement,
			List<NestedFabricRequirements.Candidate<K>> providers) {
	}

	/** Whether any two Fabric mods in {@code loading} contest an id, the only case {@link #resolve} decides. */
	public static <K> boolean contested(List<NestedFabricRequirements.Candidate<K>> loading) {
		return !rivals(loading).isEmpty();
	}

	/** Every id {@code candidate} answers to in Fabric Loader's index: its id and each it provides, or none. */
	public static Set<String> answersTo(NestedFabricRequirements.Candidate<?> candidate) {
		return candidate.fabric() == null || candidate.fabric().getId() == null ? Set.of() : ids(candidate);
	}

	/**
	 * Which of the mods in {@code loading} (every candidate that loads: roots, and the nested ones whose parents load,
	 * one build per declared id) Fabric Loader would leave out for sharing an id, with what then cannot load either; and
	 * which would-be losers a mod keeps back. {@code requiredElsewhere}: requirements of mods that load but are not
	 * candidates, outside Fabric's index. Nothing is left out when nothing is contested, or when the search reaches
	 * its bound.
	 */
	public static <K> Outcome<K> resolve(List<NestedFabricRequirements.Candidate<K>> loading,
			List<NestedFabricRequirements.Requirement> requiredElsewhere) {
		Outcome<K> none = new Outcome<>(Map.of(), Map.of(), Map.of());
		if (!NestedFabricRequirements.enabled()) return none;
		List<Rivals<K>> rivals = rivals(loading);
		if (rivals.isEmpty()) return none;

		Map<K, Integer> variable = new HashMap<>();
		for (var candidate : loading) variable.put(candidate.key(), variable.size() + 1);
		List<NestedFabricRequirements.Candidate<K>> order = priorityOrder(loading);
		List<NestedFabricRequirements.Candidate<K>> judged = new ArrayList<>();
		for (var candidate : order) if (judgeable(candidate)) judged.add(candidate);

		// Fabric's own hard dependencies: a judged mod's (HARD_DEP), and a Fabric jar in mods/ (forced, so a need).
		// Everything else's needs Fabric never sees; they are met after its choice.
		List<Need<K>> hard = new ArrayList<>(), forced = new ArrayList<>(), outside = new ArrayList<>();
		Set<K> unmet = new HashSet<>();
		for (var candidate : loading) {
			for (var requirement : candidate.requires()) {
				List<NestedFabricRequirements.Candidate<K>> providers = providers(loading, requirement);
				if (providers == null) continue;
				if (providers.isEmpty()) {
					if (judgeable(candidate)) unmet.add(candidate.key());
					continue;
				}
				Need<K> need = new Need<>(candidate, requirement, providers);
				if (judgeable(candidate)) hard.add(need);
				else if (candidate.root() && takesPart(candidate)) forced.add(need);
				else outside.add(need);
			}
		}
		for (var requirement : requiredElsewhere) {
			List<NestedFabricRequirements.Candidate<K>> providers = providers(loading, requirement);
			if (providers != null && !providers.isEmpty()) outside.add(new Need<>(null, requirement, providers));
		}

		int next = loading.size();
		Map<Need<K>, Integer> forcedLiterals = new LinkedHashMap<>();
		for (var need : forced) forcedLiterals.put(need, ++next);
		// The rivalry whose loser comes last in Fabric Loader's order goes first, then by its winner, so that where a
		// need keeps only some of them the mod Fabric Loader prefers is the one kept back, whatever the scan order.
		List<Rivals<K>> byLoser = new ArrayList<>(rivals);
		byLoser.sort(Comparator.comparingInt((Rivals<K> r) -> -Math.max(order.indexOf(r.a()), order.indexOf(r.b())))
				.thenComparingInt(r -> -Math.min(order.indexOf(r.a()), order.indexOf(r.b()))));
		Map<Rivals<K>, Integer> rivalLiterals = new LinkedHashMap<>();
		for (var pair : byLoser) rivalLiterals.put(pair, ++next);

		ISolver solver = SolverFactory.newDefault();
		solver.newVar(next);
		solver.setTimeoutOnConflicts(CONFLICT_BUDGET);
		boolean[][] model = new boolean[1][];
		try {
			for (var candidate : loading) {
				int self = variable.get(candidate.key());
				if (candidate.root()) {
					clause(solver, self);
					continue;
				}
				List<Integer> parents = new ArrayList<>(List.of(-self));
				for (K parent : candidate.parents()) {
					if (!variable.containsKey(parent)) continue;
					parents.add(variable.get(parent));
					// Outside Fabric's index a nested jar loads whenever a parent of it does, as the kernel loads it.
					if (!judgeable(candidate)) clause(solver, -variable.get(parent), self);
				}
				// A candidate the caller says loads with no parent among them is taken as given.
				if (parents.size() > 1) clause(solver, parents);
				else if (!judgeable(candidate)) clause(solver, self);
			}
			for (var need : hard) {
				List<Integer> met = new ArrayList<>(List.of(-variable.get(need.by().key())));
				for (var provider : need.providers()) met.add(variable.get(provider.key()));
				clause(solver, met);
			}
			for (var need : forcedLiterals.entrySet()) {
				List<Integer> met = new ArrayList<>(List.of(-need.getValue()));
				for (var provider : need.getKey().providers()) met.add(variable.get(provider.key()));
				clause(solver, met);
			}
			for (var pair : rivalLiterals.entrySet()) {
				clause(solver, -pair.getValue(), -variable.get(pair.getKey().a().key()), -variable.get(pair.getKey().b().key()));
			}
		} catch (ContradictionException structure) {
			ForbricLog.debug("[Forbric/JiJ] one mod per id: the mods that load contradict their own structure (%s); nothing "
					+ "left out", String.valueOf(structure));
			return none;
		}

		Set<Integer> relaxed = new HashSet<>();
		Map<K, String> keptFor = new LinkedHashMap<>();
		VecInt assumptions = new VecInt();
		try {
			if (!satisfiable(solver, assumptions, model)) return none;
			// What the forced jars need, then each rivalry; a rivalry a forced need cannot do without is relaxed.
			Set<Integer> needsKept = new LinkedHashSet<>();
			accept(solver, assumptions, List.copyOf(forcedLiterals.values()), new HashSet<>(), model);
			for (int i = 0; i < assumptions.size(); i++) if (assumptions.get(i) > 0) needsKept.add(assumptions.get(i));
			accept(solver, assumptions, List.copyOf(rivalLiterals.values()), relaxed, model);
			// Native's choice: the judged mods in its order, with the ones whose dependency nothing meets switched off.
			VecInt nativeChoice = copy(assumptions);
			for (var candidate : judged) {
				if (!unmet.contains(candidate.key())) continue;
				VecInt off = copy(nativeChoice);
				off.push(-variable.get(candidate.key()));
				if (satisfiable(solver, off, model)) nativeChoice = off;
			}
			List<Integer> loadsNatively = new ArrayList<>();
			for (var candidate : judged) {
				if (unmet.contains(candidate.key())) continue;
				int self = variable.get(candidate.key());
				VecInt attempt = copy(nativeChoice);
				attempt.push(self);
				if (satisfiable(solver, attempt, model)) {
					nativeChoice = attempt;
					loadsNatively.add(self);
				} else {
					nativeChoice.push(-self);
				}
			}
			// Then everything native leaves out comes back, in the same order, where it takes nothing native loads.
			for (int self : loadsNatively) assumptions.push(self);
			for (var candidate : judged) {
				int self = variable.get(candidate.key());
				if (loadsNatively.contains(self)) continue;
				VecInt attempt = copy(assumptions);
				attempt.push(self);
				if (satisfiable(solver, attempt, model)) assumptions = attempt;
				else assumptions.push(-self);
			}
			if (!satisfiable(solver, assumptions, model)) return none;
			// Last, the needs Fabric never sees: one nothing that loads meets keeps a would-be loser back, its rivalries
			// relaxed, and everything that loads stays.
			for (var need : outside) {
				boolean[] now = model[0];
				if (need.by() != null && !now[variable.get(need.by().key())]) continue;
				if (need.providers().stream().anyMatch(p -> now[variable.get(p.key())])) continue;
				List<NestedFabricRequirements.Candidate<K>> providers = new ArrayList<>(need.providers());
				providers.sort(Comparator.comparingInt(p -> order.contains(p) ? order.indexOf(p) : Integer.MAX_VALUE));
				for (var provider : providers) {
					VecInt attempt = new VecInt();
					for (int literal : needsKept) attempt.push(literal);
					Set<Integer> released = new HashSet<>();
					for (var pair : rivalLiterals.entrySet()) {
						if (relaxed.contains(pair.getValue())) continue;
						if (pair.getKey().a() == provider || pair.getKey().b() == provider) released.add(pair.getValue());
						else attempt.push(pair.getValue());
					}
					for (var candidate : loading) if (now[variable.get(candidate.key())]) attempt.push(variable.get(candidate.key()));
					attempt.push(variable.get(provider.key()));
					if (!satisfiable(solver, attempt, model)) continue;
					relaxed.addAll(released);
					assumptions = attempt;
					// Only what the kept mod brings with it changes: everything else that was out stays out where it can.
					for (var candidate : loading) {
						if (now[variable.get(candidate.key())] || candidate == provider) continue;
						VecInt off = copy(assumptions);
						off.push(-variable.get(candidate.key()));
						if (satisfiable(solver, off, model)) assumptions = off;
					}
					keptFor.putIfAbsent(provider.key(), (need.by() == null ? need.requirement().by() : need.by().id())
							+ " requires " + need.requirement().id() + " " + need.requirement().range()
							+ ", and nothing else that loads meets that");
					break;
				}
			}
			if (!satisfiable(solver, assumptions, model)) return none;
		} catch (TimeoutException bounded) {
			ForbricLog.warn("[Forbric/JiJ] one mod per id: the search reached its bound; every mod that shares an id loads");
			return none;
		}

		boolean[] loads = model[0];
		Map<K, String> leftOut = new LinkedHashMap<>();
		Map<K, Lost> lost = new LinkedHashMap<>();
		for (var candidate : loading) {
			if (loads[variable.get(candidate.key())]) continue;
			leftOut.put(candidate.key(), reason(candidate, rivalLiterals, relaxed, hard, variable, loads));
			for (var pair : rivalLiterals.entrySet()) {
				if (relaxed.contains(pair.getValue())) continue;
				var other = pair.getKey().a() == candidate ? pair.getKey().b() : pair.getKey().b() == candidate ? pair.getKey().a() : null;
				if (other == null || !loads[variable.get(other.key())]) continue;
				lost.putIfAbsent(candidate.key(), new Lost(pair.getKey().id(), other.root()));
			}
		}
		Map<K, NestedFabricRequirements.KeptBack> keptBack = new LinkedHashMap<>();
		for (var pair : rivalLiterals.entrySet()) {
			if (!relaxed.contains(pair.getValue())) continue;
			Rivals<K> rivalry = pair.getKey();
			if (!loads[variable.get(rivalry.a().key())] || !loads[variable.get(rivalry.b().key())]) continue;
			// The one native would leave out: what a need kept, else a judged mod against a jar in mods/, else the later.
			var kept = keptFor.containsKey(rivalry.a().key()) ? rivalry.a() : keptFor.containsKey(rivalry.b().key()) ? rivalry.b()
					: !judgeable(rivalry.a()) ? rivalry.b() : !judgeable(rivalry.b()) ? rivalry.a()
					: order.indexOf(rivalry.a()) > order.indexOf(rivalry.b()) ? rivalry.a() : rivalry.b();
			var other = kept == rivalry.a() ? rivalry.b() : rivalry.a();
			if (keptBack.containsKey(kept.key())) continue;
			String because = keptFor.containsKey(kept.key()) ? keptFor.get(kept.key()) : because(kept, forced, variable, loads);
			keptBack.put(kept.key(), new NestedFabricRequirements.KeptBack(rivalReason(rivalry.id(), other), because));
		}
		return new Outcome<>(leftOut, keptBack, lost);
	}

	/** Every pair of Fabric mods of different ids that answer to one id, both taking part, at least one of them judged. */
	private static <K> List<Rivals<K>> rivals(List<NestedFabricRequirements.Candidate<K>> loading) {
		Map<String, List<NestedFabricRequirements.Candidate<K>>> holders = new LinkedHashMap<>();
		for (var candidate : loading) {
			if (!takesPart(candidate)) continue;
			for (String id : ids(candidate)) holders.computeIfAbsent(id, ignored -> new ArrayList<>()).add(candidate);
		}
		List<Rivals<K>> rivals = new ArrayList<>();
		Set<List<K>> seen = new HashSet<>();
		for (var group : holders.entrySet()) {
			List<NestedFabricRequirements.Candidate<K>> list = group.getValue();
			for (int i = 0; i < list.size(); i++) for (int j = i + 1; j < list.size(); j++) {
				var a = list.get(i);
				var b = list.get(j);
				if (a.fabric().getId().equals(b.fabric().getId()) || !judgeable(a) && !judgeable(b)) continue;
				if (seen.add(List.of(a.key(), b.key()))) rivals.add(new Rivals<>(a, b, group.getKey()));
			}
		}
		return rivals;
	}

	/** In Fabric Loader's index: a Fabric jar in {@code mods/}, or a judged nested Fabric mod. */
	private static boolean takesPart(NestedFabricRequirements.Candidate<?> candidate) {
		return candidate.fabric() != null && candidate.fabric().getId() != null && (candidate.root() || judgeable(candidate));
	}

	/** A nested mod Fabric Loader itself reaches, and that {@code off:<id>} does not keep out of the rule. */
	private static boolean judgeable(NestedFabricRequirements.Candidate<?> candidate) {
		return !candidate.root() && candidate.judged() && candidate.fabric() != null && candidate.fabric().getId() != null
				&& NestedFabricRequirements.judged(candidate.fabric().getId());
	}

	/** A Fabric mod's id and each id it provides, as Fabric Loader indexes them (each once). */
	private static Set<String> ids(NestedFabricRequirements.Candidate<?> candidate) {
		Set<String> ids = new LinkedHashSet<>();
		ids.add(candidate.fabric().getId());
		ids.addAll(candidate.fabric().getProvides());
		return ids;
	}

	/** What meets {@code requirement} among {@code loading}, or {@code null} for an id the platform answers. */
	private static <K> List<NestedFabricRequirements.Candidate<K>> providers(List<NestedFabricRequirements.Candidate<K>> loading,
			NestedFabricRequirements.Requirement requirement) {
		if (requirement.id() == null || NestedFabricRequirements.platform(requirement.id())) return null;
		String wanted = ModPresence.spellingKey(requirement.id());
		List<NestedFabricRequirements.Candidate<K>> providers = new ArrayList<>();
		for (var candidate : loading) {
			String version = candidate.provides().get(wanted);
			if (version != null && requirement.accepts().test(version)) providers.add(candidate);
		}
		return providers;
	}

	/**
	 * Fabric Loader's priority order over the Fabric mods in {@code loading} that take part ({@code ModPrioSorter.sort}):
	 * jars in {@code mods/} first, then by id, then the higher version, then the shallower, then by parent; after which
	 * a mod moves ahead of the ones before it that it shares an id with and has the higher version against.
	 */
	static <K> List<NestedFabricRequirements.Candidate<K>> priorityOrder(List<NestedFabricRequirements.Candidate<K>> loading) {
		List<NestedFabricRequirements.Candidate<K>> mods = new ArrayList<>();
		for (var candidate : loading) if (takesPart(candidate)) mods.add(candidate);
		Map<K, NestedFabricRequirements.Candidate<K>> byKey = new HashMap<>();
		for (var mod : mods) byKey.put(mod.key(), mod);
		Map<K, Integer> depth = depths(mods, byKey);
		Comparator<NestedFabricRequirements.Candidate<K>> order = new Comparator<>() {
			@Override
			public int compare(NestedFabricRequirements.Candidate<K> a, NestedFabricRequirements.Candidate<K> b) {
				if (a.root() != b.root()) return a.root() ? -1 : 1;
				int id = a.fabric().getId().compareTo(b.fabric().getId());
				if (id != 0) return id;
				int version = compareVersions(version(b), version(a));
				if (version != 0) return version;
				int nest = Integer.compare(depth.get(a.key()), depth.get(b.key()));
				if (nest != 0 || a.root()) return nest;
				return compareParents(a, b);
			}

			private int compareParents(NestedFabricRequirements.Candidate<K> a, NestedFabricRequirements.Candidate<K> b) {
				NestedFabricRequirements.Candidate<K> best = null;
				for (K key : a.parents()) {
					var parent = byKey.get(key);
					if (parent != null && (best == null || compare(best, parent) > 0)) best = parent;
				}
				if (best == null) return 0;
				boolean shared = false;
				for (K key : b.parents()) {
					var parent = byKey.get(key);
					if (parent == null) continue;
					if (parent == best) shared = true;
					else if (compare(best, parent) > 0) return 1;
				}
				return shared ? 0 : -1;
			}
		};
		try {
			mods.sort(order);
		} catch (IllegalArgumentException inconsistent) {
			mods.sort(Comparator.comparing((NestedFabricRequirements.Candidate<K> c) -> !c.root())
					.thenComparing(c -> c.fabric().getId())
					.thenComparing((x, y) -> compareVersions(version(y), version(x))));
		}

		Map<String, Integer> holders = new HashMap<>();
		Set<String> provided = new HashSet<>();
		for (var mod : mods) {
			holders.merge(mod.fabric().getId(), 1, Integer::sum);
			for (String alias : mod.fabric().getProvides()) {
				holders.merge(alias, 1, Integer::sum);
				provided.add(alias);
			}
		}
		provided.removeIf(alias -> holders.get(alias) <= 1);
		if (provided.isEmpty()) return mods;
		boolean pastRoots = false;
		int start = 0;
		Set<String> overlapping = new HashSet<>();
		for (int i = 0; i < mods.size(); i++) {
			var mod = mods.get(i);
			String id = mod.fabric().getId();
			if (!pastRoots && !mod.root()) {
				pastRoots = true;
				start = i;
			}
			if (provided.contains(id)) overlapping.add(id);
			for (String alias : mod.fabric().getProvides()) if (provided.contains(alias)) overlapping.add(alias);
			if (overlapping.isEmpty()) continue;
			int moveTo = -1;
			for (int j = i - 1; j >= start; j--) {
				var other = mods.get(j);
				String otherId = other.fabric().getId();
				if (otherId.equals(id)) break;
				if (overlapping.contains(otherId) || !Collections.disjoint(overlapping, other.fabric().getProvides())) {
					int cmp = compareOverlappingIds(mod, other);
					if (cmp < 0) moveTo = j;
					else if (cmp != Integer.MAX_VALUE) break;
				}
			}
			if (moveTo >= 0) {
				mods.remove(i);
				mods.add(moveTo, mod);
			}
			overlapping.clear();
		}
		return mods;
	}

	/** {@code ModPrioSorter.compareOverlappingIds}: negative when {@code a} has the higher version where they overlap. */
	private static int compareOverlappingIds(NestedFabricRequirements.Candidate<?> a, NestedFabricRequirements.Candidate<?> b) {
		int sum = 0;
		boolean matched = false;
		String aId = a.fabric().getId();
		String bId = b.fabric().getId();
		int sign = Integer.signum(compareVersions(version(b), version(a)));
		for (String alias : a.fabric().getProvides()) {
			if (alias.equals(bId)) {
				sum += sign;
				matched = true;
			}
		}
		for (String alias : b.fabric().getProvides()) {
			if (alias.equals(aId)) {
				sum += sign;
				matched = true;
				continue;
			}
			for (String own : a.fabric().getProvides()) {
				if (alias.equals(own)) {
					sum += sign;
					matched = true;
					break;
				}
			}
		}
		return matched ? sum : Integer.MAX_VALUE;
	}

	/**
	 * Fabric Loader's order of two mod versions. A mod version is parsed without wildcards, so one with an {@code x} or
	 * {@code *} component is a {@code StringVersion} and compares as a string, as does anything against one.
	 */
	static int compareVersions(String a, String b) {
		if (wildcard(a) || wildcard(b)) return a.compareTo(b);
		return FabricMixinCompat.compare(a, b);
	}

	private static boolean wildcard(String version) {
		String core = version;
		int cut = core.indexOf('+');
		if (cut >= 0) core = core.substring(0, cut);
		cut = core.indexOf('-');
		if (cut >= 0) core = core.substring(0, cut);
		for (String part : core.split("\\.")) if (part.equals("x") || part.equals("X") || part.equals("*")) return true;
		return false;
	}

	/** How deep each mod is nested below a jar in {@code mods/}, through its shallowest parent ({@code getMinNestLevel}). */
	private static <K> Map<K, Integer> depths(List<NestedFabricRequirements.Candidate<K>> mods,
			Map<K, NestedFabricRequirements.Candidate<K>> byKey) {
		Map<K, Integer> depth = new HashMap<>();
		for (var mod : mods) depth.put(mod.key(), mod.root() ? 0 : Integer.MAX_VALUE / 2);
		for (boolean changed = true; changed;) {
			changed = false;
			for (var mod : mods) {
				if (mod.root()) continue;
				for (K parent : mod.parents()) {
					Integer above = byKey.containsKey(parent) ? depth.get(parent) : Integer.valueOf(0);
					if (above + 1 < depth.get(mod.key())) {
						depth.put(mod.key(), above + 1);
						changed = true;
					}
				}
			}
		}
		return depth;
	}

	private static String version(NestedFabricRequirements.Candidate<?> candidate) {
		return candidate.fabric().getVersion() == null ? "" : candidate.fabric().getVersion().getFriendlyString();
	}

	/** Why {@code candidate} does not load: an id a mod that loads also answers to, its parents, or its dependency. */
	private static <K> String reason(NestedFabricRequirements.Candidate<K> candidate, Map<Rivals<K>, Integer> rivals,
			Set<Integer> relaxed, List<Need<K>> hard, Map<K, Integer> variable, boolean[] loads) {
		for (var pair : rivals.entrySet()) {
			if (relaxed.contains(pair.getValue())) continue;
			Rivals<K> rivalry = pair.getKey();
			var other = rivalry.a() == candidate ? rivalry.b() : rivalry.b() == candidate ? rivalry.a() : null;
			if (other != null && loads[variable.get(other.key())]) return rivalReason(rivalry.id(), other);
		}
		boolean parentLoads = false, anyParent = false;
		for (K parent : candidate.parents()) {
			if (!variable.containsKey(parent)) continue;
			anyParent = true;
			parentLoads |= loads[variable.get(parent)];
		}
		if (anyParent && !parentLoads) return "only mods left out bundle it";
		for (var need : hard) {
			if (need.by() != candidate) continue;
			if (need.providers().stream().noneMatch(p -> loads[variable.get(p.key())])) {
				return "it requires " + need.requirement().id() + " " + need.requirement().range()
						+ ", and every installed build of that is left out";
			}
		}
		return "Fabric Loader's resolution leaves it out";
	}

	private static String rivalReason(String id, NestedFabricRequirements.Candidate<?> winner) {
		return "it answers to '" + id + "', as " + winner.fabric().getId() + " " + version(winner)
				+ (winner.root() ? " in mods/" : "") + " does, and Fabric Loader loads one mod per id, provides included";
	}

	/** The need that kept {@code kept} in: a forced jar's, met by nothing else that loads. */
	private static <K> String because(NestedFabricRequirements.Candidate<K> kept, List<Need<K>> needs, Map<K, Integer> variable,
			boolean[] loads) {
		for (var need : needs) {
			if (!need.providers().contains(kept)) continue;
			if (need.providers().stream().filter(p -> p != kept).anyMatch(p -> loads[variable.get(p.key())])) continue;
			String by = need.by() == null ? need.requirement().by() : need.by().id();
			return by + " requires " + need.requirement().id() + " " + need.requirement().range()
					+ ", and nothing else that loads meets that";
		}
		return "a mod that loads needs it, and nothing else that loads meets that";
	}

	/** Keeps each literal iff it is consistent with everything kept before it; one that is not goes to {@code relaxed}. */
	private static void accept(ISolver solver, VecInt assumptions, List<Integer> literals, Set<Integer> relaxed,
			boolean[][] model) throws TimeoutException {
		if (literals.isEmpty()) return;
		VecInt attempt = copy(assumptions);
		for (int literal : literals) attempt.push(literal);
		if (satisfiable(solver, attempt, model)) {
			for (int literal : literals) assumptions.push(literal);
			return;
		}
		if (literals.size() == 1) {
			relaxed.add(literals.getFirst());
			assumptions.push(-literals.getFirst());
			return;
		}
		int half = literals.size() / 2;
		accept(solver, assumptions, literals.subList(0, half), relaxed, model);
		accept(solver, assumptions, literals.subList(half, literals.size()), relaxed, model);
	}

	private static boolean satisfiable(ISolver solver, VecInt assumptions, boolean[][] model) throws TimeoutException {
		if (!solver.isSatisfiable(assumptions)) return false;
		boolean[] values = new boolean[solver.nVars() + 1];
		for (int literal : solver.model()) if (literal > 0 && literal < values.length) values[literal] = true;
		model[0] = values;
		return true;
	}

	private static VecInt copy(VecInt values) {
		int[] entries = new int[values.size()];
		values.copyTo(entries);
		return new VecInt(entries);
	}

	private static void clause(ISolver solver, int... literals) throws ContradictionException {
		solver.addClause(new VecInt(literals));
	}

	private static void clause(ISolver solver, List<Integer> literals) throws ContradictionException {
		clause(solver, literals.stream().mapToInt(Integer::intValue).toArray());
	}
}
