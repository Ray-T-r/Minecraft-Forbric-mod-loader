/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.boot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.UnifiedDependency;
import net.forbric.kernel.util.ForbricLog;

/**
 * The order mods are constructed and initialised in.
 *
 * <h2>What it replaces</h2>
 *
 * <p>Jar file name, alphabetically. That is what discovery hands out and what every downstream pass kept: mods
 * were constructed in it, Fabric mods were registered and their entry points invoked in it. It is not an order,
 * it is an accident of what the files are called — so a mod whose jar sorts before a library it needs ran first
 * and called that library's API before the library had initialised. What comes back is an error inside the
 * library, attributed to the library, on a line that has nothing to do with the real cause.
 *
 * <p>Both real loaders sort by dependency: NeoForge and Forge topologically sort by requirements plus the
 * explicit {@code ordering} key, and Fabric resolves by id and then walks the dependency graph.
 *
 * <h2>The rules, and why ties are broken the way they are</h2>
 *
 * <p>A mod comes after everything it requires, and after everything it declares it loads AFTER. A mod declaring
 * it loads BEFORE another comes first. A requirement naming a mod that is not installed contributes nothing —
 * it is either optional or already reported by the dependency audit, and inventing an edge to a missing node
 * would be a cycle waiting to happen.
 *
 * <p>Ties keep the order they came in, which is the alphabetical one. That is deliberate: a stable tie-break
 * means the same pack constructs in the same order every launch, so a bug that depends on order is reproducible
 * rather than intermittent.
 *
 * <p>A cycle cannot be ordered, and the honest thing is to say so rather than pick a winner silently. The mods
 * in it keep their incoming order and are named in a warning, because a cycle between two mods is a fact about
 * those mods that their authors need to hear.
 *
 * <p>Escape hatch: {@code -Dforbric.modOrder=name} restores the file-name order for a pack that somehow needs it.
 */
public final class ModConstructionOrder {
	static final String SWITCH = "forbric.modOrder";

	private ModConstructionOrder() {
	}

	/** Whether dependency ordering is on. */
	public static boolean enabled() {
		return !"name".equalsIgnoreCase(System.getProperty(SWITCH, "dependency"));
	}

	/**
	 * Mod ids in construction order.
	 *
	 * <p>Every installed mod appears exactly once, ids and {@code provides} aliases alike resolving to the mod
	 * that owns them. The returned list holds real ids only; an alias is a name for a node, not a node.
	 */
	public static List<String> of(Collection<DiscoveredMod> mods) {
		Map<String, DiscoveredMod> byName = new LinkedHashMap<>();
		List<String> ids = new ArrayList<>();

		for (DiscoveredMod mod : mods) {
			if (mod == null || mod.getId() == null || mod.getId().isBlank()) continue;
			if (byName.putIfAbsent(mod.getId(), mod) == null) ids.add(mod.getId());
			for (String alias : mod.getAliases()) {
				if (alias != null && !alias.isBlank()) byName.putIfAbsent(alias, mod);
			}
		}

		if (!enabled()) return ids;

		// after.get(x) = the mods that must come after x.
		Map<String, Set<String>> after = new LinkedHashMap<>();
		Map<String, Integer> incoming = new LinkedHashMap<>();
		for (String id : ids) {
			after.put(id, new LinkedHashSet<>());
			incoming.put(id, 0);
		}

		for (String id : ids) {
			for (UnifiedDependency dep : byName.get(id).getDependencies()) {
				DiscoveredMod target = dep == null ? null : byName.get(dep.getModId());
				// Not installed: nothing to order against. The dependency audit is what reports a missing one.
				if (target == null || target.getId().equals(id)) continue;

				if (dep.getOrdering() == UnifiedDependency.Ordering.BEFORE) {
					link(after, incoming, id, target.getId());
				} else if (dep.getOrdering() == UnifiedDependency.Ordering.AFTER || dep.isMandatory()) {
					link(after, incoming, target.getId(), id);
				}
			}
		}

		List<String> ordered = new ArrayList<>(ids.size());
		Deque<String> ready = new ArrayDeque<>();
		// Seeded in incoming order, and drained from the FRONT, so a mod with nothing blocking it keeps its
		// alphabetical place rather than being reordered by graph shape.
		for (String id : ids) {
			if (incoming.get(id) == 0) ready.addLast(id);
		}

		while (!ready.isEmpty()) {
			String id = ready.pollFirst();
			ordered.add(id);
			for (String next : after.get(id)) {
				if (incoming.merge(next, -1, Integer::sum) == 0) ready.addLast(next);
			}
		}

		if (ordered.size() != ids.size()) {
			List<String> cycle = new ArrayList<>();
			for (String id : ids) {
				if (!ordered.contains(id)) {
					cycle.add(id);
					ordered.add(id);
				}
			}
			ForbricLog.warn("[Forbric/Order] %d mod(s) declare a dependency cycle and cannot be ordered, so they "
					+ "keep the order they were found in — whichever of them initialises first may call the other "
					+ "before it is ready: %s", cycle.size(), cycle);
		}
		return ordered;
	}

	/**
	 * Sorts {@code items} into {@code order}, keeping anything not named in it where it was.
	 *
	 * <p>Something with no id, or an id discovery never saw, must not vanish and must not be shuffled to one end:
	 * it goes after the last ordered item that preceded it, which for a mod nobody depends on is exactly where it
	 * already was.
	 */
	public static <T> List<T> sort(List<T> items, java.util.function.Function<T, String> idOf, List<String> order) {
		// The escape hatch has to actually escape. Without this the sort still ran — it just had nothing to sort
		// BY — and moved every item whose id the order does not name to the end, which is a reordering of its own
		// and made the caller report "dependency order" while producing file-name order.
		if (!enabled()) return items;

		Map<String, Integer> rank = new LinkedHashMap<>();
		for (int i = 0; i < order.size(); i++) rank.putIfAbsent(order.get(i), i);

		List<T> sorted = new ArrayList<>(items);
		// A stable sort, so two items of equal rank — including two @Mod classes of the SAME mod — keep their
		// relative order.
		sorted.sort((a, b) -> Integer.compare(rankOf(rank, idOf.apply(a)), rankOf(rank, idOf.apply(b))));
		return sorted;
	}

	private static int rankOf(Map<String, Integer> rank, String id) {
		Integer at = id == null ? null : rank.get(id);
		// Unknown ids sort last, together, in their original order: there is nothing to say about where they
		// belong, and putting them first would let one run before a library it may well need.
		return at == null ? Integer.MAX_VALUE : at;
	}

	private static void link(Map<String, Set<String>> after, Map<String, Integer> incoming, String first,
			String then) {
		if (after.get(first).add(then)) incoming.merge(then, 1, Integer::sum);
	}
}
