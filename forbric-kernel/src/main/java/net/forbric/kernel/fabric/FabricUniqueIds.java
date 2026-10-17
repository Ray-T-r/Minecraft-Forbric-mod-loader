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
import net.forbric.kernel.util.Caught;

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
 * shared id load ({@code DuplicateModArbiter} runs arbitration again where another build of what was left out could
 * take its place, telling this what earlier rounds outvoted). Builds of one mod are never rivals here, also when they
 * share an alias rather than their id (every
 * {@code mixinextras-fabric} copy provides {@code com_github_llamalad7_mixinextras}): that is arbitration's.
 *
 * <p>What takes part: Fabric Loader's own index, the Fabric jars in {@code mods/} and the nested Fabric mods it reaches
 * itself (a Fabric mod's {@code jars}) that {@code -Dforbric.nestedRequirements=off:<id>} does not exempt. Their hard
 * dependencies are Fabric's, met by what is in that index, so they shape the choice as on Fabric. Everything else is
 * outside it: a Forge-family mod, a Fabric mod a JarJar declaration or a Forge-family walk brought in, an exempted one,
 * a library without {@code fabric.mod.json}. Such a jar loads whenever a parent of it loads, goes with its parents,
 * never decides a rivalry, and never meets a dependency for Fabric's choice; its own needs, which Fabric Loader never
 * sees, are met only after that choice is made, by keeping a would-be loser back.
 *
 * <p>Where the kernel stays more lenient than native:
 * <ul>
 * <li>Jars in {@code mods/} all load. Native drops one that no id of its own singles out when a rival takes its ids,
 *     and refuses to start when two forced jars collide.</li>
 * <li>A nested mod with a hard dependency nothing that loads meets is switched off by native, and with it what it
 *     bundles and what only it satisfies. Here native's choice is taken with that mod off, and then the mod, and what
 *     goes with it, comes back wherever it takes nothing native loads: it yields every id it shares, and still loads
 *     where nothing contests it ({@code DependencyAudit} reports the dependency).</li>
 * <li>A Fabric jar in {@code mods/}, or a mod outside Fabric's index, that needs a would-be loser (by its id or one it
 *     provides, or a mod nested in it), with nothing else that loads meeting the need, keeps it back with a WARN,
 *     together with what bundles it on the way to a parent that loads and what it brings with it (its nested mods,
 *     what needs it); the winner loads too. For a jar in {@code mods/} native drops the winning jar in {@code mods/}
 *     instead if no id of its own forces it, and refuses to start otherwise; a need from outside Fabric's index native
 *     never sees. A nested Fabric mod Fabric judges keeps nothing back: like native, it goes itself. The build kept
 *     back is the one arbitration chose for its id; another installed build that would not need keeping back is not
 *     tried.</li>
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
		return resolve(loading, requiredElsewhere, List.of());
	}

	/**
	 * As above, with {@code outvoted}: Fabric mods that an earlier decision already left out for losing a rivalry. A
	 * judged mod whose hard dependency only they meet cannot load, as when they were weighed with it.
	 */
	public static <K> Outcome<K> resolve(List<NestedFabricRequirements.Candidate<K>> loading,
			List<NestedFabricRequirements.Requirement> requiredElsewhere, List<NestedFabricRequirements.Candidate<K>> outvoted) {
		Outcome<K> none = new Outcome<>(Map.of(), Map.of(), Map.of());
		if (!NestedFabricRequirements.enabled()) return none;
		List<Rivals<K>> rivals = rivals(loading);
		if (rivals.isEmpty() && outvoted.isEmpty()) return none;

		Map<K, Integer> variable = new HashMap<>();
		for (var candidate : loading) variable.put(candidate.key(), variable.size() + 1);
		Map<K, NestedFabricRequirements.Candidate<K>> byKey = new HashMap<>();
		for (var candidate : loading) byKey.put(candidate.key(), candidate);
		List<NestedFabricRequirements.Candidate<K>> order = priorityOrder(loading);
		List<NestedFabricRequirements.Candidate<K>> judged = new ArrayList<>();
		for (var candidate : order) if (judgeable(candidate)) judged.add(candidate);

		// Fabric's own hard dependencies, met by what is in its index: a judged mod's (HARD_DEP), and a Fabric jar in
		// mods/ (forced, so a need). Everything else's needs Fabric never sees; they are met after its choice.
		List<Need<K>> hard = new ArrayList<>(), forced = new ArrayList<>(), outside = new ArrayList<>();
		Set<K> unmet = new HashSet<>();
		Map<K, NestedFabricRequirements.Requirement> metOnlyByOutvoted = new LinkedHashMap<>();
		for (var candidate : loading) {
			boolean inIndex = takesPart(candidate);
			for (var requirement : candidate.requires()) {
				List<NestedFabricRequirements.Candidate<K>> providers = providers(loading, requirement);
				if (providers == null) continue;
				if (!inIndex) {
					if (!providers.isEmpty()) outside.add(new Need<>(candidate, requirement, providers));
					continue;
				}
				List<NestedFabricRequirements.Candidate<K>> fabric = providers.stream().filter(FabricUniqueIds::takesPart).toList();
				if (!fabric.isEmpty()) {
					(candidate.root() ? forced : hard).add(new Need<>(candidate, requirement, fabric));
				} else if (judgeable(candidate)) {
					List<NestedFabricRequirements.Candidate<K>> out = providers(outvoted, requirement);
					if (out != null && out.stream().anyMatch(FabricUniqueIds::takesPart)) metOnlyByOutvoted.putIfAbsent(candidate.key(), requirement);
					else unmet.add(candidate.key());
				}
			}
		}
		for (var requirement : requiredElsewhere) {
			List<NestedFabricRequirements.Candidate<K>> providers = providers(loading, requirement);
			if (providers != null && !providers.isEmpty()) outside.add(new Need<>(null, requirement, providers));
		}
		// Nothing contested now, and nothing needs only what an earlier decision outvoted: nothing to leave out.
		if (rivals.isEmpty() && metOnlyByOutvoted.isEmpty()) return none;

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
			for (K key : metOnlyByOutvoted.keySet()) clause(solver, -variable.get(key));
			for (var need : forcedLiterals.entrySet()) {
				List<Integer> met = new ArrayList<>(List.of(-need.getValue()));
				for (var provider : need.getKey().providers()) met.add(variable.get(provider.key()));
				clause(solver, met);
			}
			for (var pair : rivalLiterals.entrySet()) {
				clause(solver, -pair.getValue(), -variable.get(pair.getKey().a().key()), -variable.get(pair.getKey().b().key()));
			}
		} catch (ContradictionException structure) {
			Caught.warn(structure, "[Forbric/JiJ] one mod per id: the mods that load contradict their own structure; nothing "
					+ "left out");
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
			assumptions = weigh(solver, assumptions, judged, self -> !loadsNatively.contains(self), variable, model);
			if (!satisfiable(solver, assumptions, model)) return none;
			// Last, the needs Fabric never sees: one nothing that loads meets keeps a would-be loser back, with what
			// bundles it on the way to a parent that loads, their rivalries relaxed; everything that loads stays, and
			// what the kept mods bring with them (their nested mods, what needs them) comes in, in Fabric's order.
			for (var need : outside) {
				boolean[] now = model[0];
				if (need.by() != null && !now[variable.get(need.by().key())]) continue;
				if (need.providers().stream().anyMatch(p -> now[variable.get(p.key())])) continue;
				List<NestedFabricRequirements.Candidate<K>> providers = new ArrayList<>(need.providers());
				providers.sort(Comparator.comparingInt(p -> order.contains(p) ? order.indexOf(p) : Integer.MAX_VALUE));
				for (var provider : providers) {
					Set<K> chain = ancestors(provider, byKey);
					chain.add(provider.key());
					VecInt attempt = new VecInt();
					for (int literal : needsKept) attempt.push(literal);
					Set<Integer> released = new HashSet<>();
					for (var pair : rivalLiterals.entrySet()) {
						if (relaxed.contains(pair.getValue())) continue;
						if (chain.contains(pair.getKey().a().key()) || chain.contains(pair.getKey().b().key())) released.add(pair.getValue());
						else attempt.push(pair.getValue());
					}
					for (var candidate : loading) if (now[variable.get(candidate.key())]) attempt.push(variable.get(candidate.key()));
					attempt.push(variable.get(provider.key()));
					if (!satisfiable(solver, attempt, model)) continue;
					relaxed.addAll(released);
					String text = (need.by() == null ? need.requirement().by() : need.by().id()) + " requires "
							+ need.requirement().id() + " " + need.requirement().range() + ", and nothing else that loads meets that";
					keptFor.putIfAbsent(provider.key(), text);
					for (K key : chain) if (!key.equals(provider.key())) keptFor.putIfAbsent(key, "it bundles " + provider.id() + " (" + text + ")");
					assumptions = weigh(solver, attempt, judged, self -> !now[self], variable, model);
					break;
				}
			}
			if (!satisfiable(solver, assumptions, model)) return none;
		} catch (TimeoutException bounded) {
			Caught.warn(bounded, "[Forbric/JiJ] one mod per id: the search reached its bound; every mod that shares an id loads");
			return none;
		}

		boolean[] loads = model[0];
		Map<K, String> leftOut = new LinkedHashMap<>();
		Map<K, Lost> lost = new LinkedHashMap<>();
		for (var candidate : loading) {
			if (loads[variable.get(candidate.key())]) continue;
			boolean anyParent = false, parentLoads = false;
			for (K parent : candidate.parents()) {
				if (!variable.containsKey(parent)) continue;
				anyParent = true;
				parentLoads |= loads[variable.get(parent)];
			}
			if (anyParent && !parentLoads) {
				leftOut.put(candidate.key(), "only mods left out bundle it");
				continue;
			}
			Rivals<K> beaten = null;
			NestedFabricRequirements.Candidate<K> winner = null;
			for (var pair : rivalLiterals.entrySet()) {
				if (relaxed.contains(pair.getValue())) continue;
				var other = pair.getKey().a() == candidate ? pair.getKey().b() : pair.getKey().b() == candidate ? pair.getKey().a() : null;
				if (other == null || !loads[variable.get(other.key())]) continue;
				// A jar in mods/ that won names the rivalry first: it loads whatever else is chosen.
				if (beaten == null || other.root() && !winner.root()) {
					beaten = pair.getKey();
					winner = other;
				}
			}
			if (beaten != null) {
				leftOut.put(candidate.key(), rivalReason(beaten.id(), winner));
				lost.put(candidate.key(), new Lost(beaten.id(), winner.root()));
				continue;
			}
			leftOut.put(candidate.key(), dependencyReason(candidate, hard, metOnlyByOutvoted, variable, loads));
		}
		Map<K, Rivals<K>> keptAgainst = new LinkedHashMap<>();
		Map<K, NestedFabricRequirements.Candidate<K>> keptWinner = new HashMap<>();
		for (var pair : rivalLiterals.entrySet()) {
			if (!relaxed.contains(pair.getValue())) continue;
			Rivals<K> rivalry = pair.getKey();
			if (!loads[variable.get(rivalry.a().key())] || !loads[variable.get(rivalry.b().key())]) continue;
			// The one native would leave out: what a need kept, else a judged mod against a jar in mods/, else the later.
			var kept = keptFor.containsKey(rivalry.a().key()) ? rivalry.a() : keptFor.containsKey(rivalry.b().key()) ? rivalry.b()
					: !judgeable(rivalry.a()) ? rivalry.b() : !judgeable(rivalry.b()) ? rivalry.a()
					: order.indexOf(rivalry.a()) > order.indexOf(rivalry.b()) ? rivalry.a() : rivalry.b();
			var other = kept == rivalry.a() ? rivalry.b() : rivalry.a();
			var named = keptWinner.get(kept.key());
			if (named == null || other.root() && !named.root()) {
				keptAgainst.put(kept.key(), rivalry);
				keptWinner.put(kept.key(), other);
			}
		}
		Map<K, NestedFabricRequirements.KeptBack> keptBack = new LinkedHashMap<>();
		for (var entry : keptAgainst.entrySet()) {
			K kept = entry.getKey();
			String because = keptFor.containsKey(kept) ? keptFor.get(kept) : because(byKey.get(kept), forced, byKey, variable, loads);
			keptBack.put(kept, new NestedFabricRequirements.KeptBack(rivalReason(entry.getValue().id(), keptWinner.get(kept)), because));
		}
		return new Outcome<>(leftOut, keptBack, lost);
	}

	/** Tries each of {@code candidates} that {@code open} lets through, in order: loaded where it can be, else left out. */
	private static <K> VecInt weigh(ISolver solver, VecInt assumptions, List<NestedFabricRequirements.Candidate<K>> candidates,
			java.util.function.IntPredicate open, Map<K, Integer> variable, boolean[][] model) throws TimeoutException {
		VecInt decided = copy(assumptions);
		for (var candidate : candidates) {
			int self = variable.get(candidate.key());
			if (!open.test(self)) continue;
			VecInt attempt = copy(decided);
			attempt.push(self);
			if (satisfiable(solver, attempt, model)) decided = attempt;
			else decided.push(-self);
		}
		return decided;
	}

	/** Every candidate {@code candidate} is nested in, through any chain of parents among the candidates. */
	private static <K> Set<K> ancestors(NestedFabricRequirements.Candidate<K> candidate,
			Map<K, NestedFabricRequirements.Candidate<K>> byKey) {
		Set<K> seen = new LinkedHashSet<>();
		java.util.Deque<NestedFabricRequirements.Candidate<K>> work = new java.util.ArrayDeque<>(List.of(candidate));
		while (!work.isEmpty()) {
			for (K parent : work.remove().parents()) {
				var above = byKey.get(parent);
				if (above != null && seen.add(parent)) work.add(above);
			}
		}
		return seen;
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
			Caught.warn(inconsistent, "[Forbric/JiJ] one mod per id: the nesting order is not a consistent order; mods are "
					+ "sorted by root, id and version instead");
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

	/** Why a judged mod whose parent loads and that lost no rivalry does not load: a dependency only left-out mods meet. */
	private static <K> String dependencyReason(NestedFabricRequirements.Candidate<K> candidate, List<Need<K>> hard,
			Map<K, NestedFabricRequirements.Requirement> metOnlyByOutvoted, Map<K, Integer> variable, boolean[] loads) {
		for (var need : hard) {
			if (need.by() != candidate) continue;
			if (need.providers().stream().noneMatch(p -> loads[variable.get(p.key())])) return leftOutBuilds(need.requirement());
		}
		var requirement = metOnlyByOutvoted.get(candidate.key());
		if (requirement != null) return leftOutBuilds(requirement);
		return "Fabric Loader's resolution leaves it out";
	}

	private static String leftOutBuilds(NestedFabricRequirements.Requirement requirement) {
		return "it requires " + requirement.id() + " " + requirement.range() + ", and every installed build of that is left out";
	}

	private static String rivalReason(String id, NestedFabricRequirements.Candidate<?> winner) {
		return "it answers to '" + id + "', as " + winner.fabric().getId() + " " + version(winner)
				+ (winner.root() ? " in mods/" : "") + " does, and Fabric Loader loads one mod per id, provides included";
	}

	/**
	 * The need that kept {@code kept} in: a forced jar's, met by {@code kept} or a mod nested in it, and by nothing else
	 * that loads.
	 */
	private static <K> String because(NestedFabricRequirements.Candidate<K> kept, List<Need<K>> needs,
			Map<K, NestedFabricRequirements.Candidate<K>> byKey, Map<K, Integer> variable, boolean[] loads) {
		for (var need : needs) {
			for (var provider : need.providers()) {
				if (provider != kept && !ancestors(provider, byKey).contains(kept.key())) continue;
				boolean other = need.providers().stream().anyMatch(p -> p != provider && loads[variable.get(p.key())]
						&& p != kept && !ancestors(p, byKey).contains(kept.key()));
				if (other) continue;
				String text = (need.by() == null ? need.requirement().by() : need.by().id()) + " requires "
						+ need.requirement().id() + " " + need.requirement().range() + ", and nothing else that loads meets that";
				return provider == kept ? text : "it bundles " + provider.id() + " (" + text + ")";
			}
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
