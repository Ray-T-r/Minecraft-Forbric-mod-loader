/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import net.forbric.api.Ecosystem;

/**
 * Where a creative tab goes in the merged game's one tab order, by the native rule of the ecosystem that owns it.
 *
 * <p>The merged screen pages NeoForge's sorted tab list, ten categories to a page. NeoForge sorts it topologically: a
 * tab that declares no ordering is chained after the tab registered before it, so a mod's unordered tabs land wherever
 * that chain puts them — on the probe's twelve, between vanilla's {@code redstone_blocks} and
 * {@code tools_and_utilities}, splitting vanilla's ten across three pages. That is NeoForge's rule, and a NeoForge mod's
 * tab keeps it. A Fabric mod's tab never sees it natively: the Fabric platform's pager keeps every vanilla tab on the
 * first page and pages every other tab after them, ordered by id (namespace, then path), ten to a page. So the tabs whose
 * namespace belongs to a Fabric mod, and that declare no NeoForge ordering of their own, are taken out of NeoForge's
 * chain and put after it in that id order; everything else stays exactly where NeoForge put it. In a game whose only
 * modded tabs are Fabric mods', the pages are then the Fabric pages, tab for tab.
 *
 * <p>The owner is found by code, never by a mod's name: the ecosystem whose jar defines the code the tab was built with
 * (its icon supplier, its item generator), and only where that answers nothing, the ecosystem of the mod its namespace
 * belongs to. Vanilla's tabs and tabs no single ecosystem owns keep their place.
 */
public final class EcosystemTabPlacement {
	public static final String PROPERTY = "forbric.creativeTabEcosystemOrder";

	private EcosystemTabPlacement() {
	}

	/**
	 * {@code sorted} with the Fabric-owned, self-unordered tabs moved after the rest in id order.
	 *
	 * @param namespace the tab's registry namespace, null when it has no key
	 * @param path      the tab's registry path
	 * @param ordered   whether the tab declares an ordering of its own (NeoForge's tabsBefore / tabsAfter)
	 * @param owner     the ecosystem that owns the tab, null for none or more than one
	 */
	public static <T> List<T> place(List<T> sorted, Function<T, String> namespace, Function<T, String> path,
			Predicate<T> ordered, Function<T, Ecosystem> owner) {
		if (sorted == null || "off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return sorted;
		List<T> kept = new ArrayList<>(sorted.size()), fabric = new ArrayList<>();
		for (T tab : sorted) {
			String space = namespace.apply(tab);
			boolean fabricOwned = space != null && !"minecraft".equals(space) && !ordered.test(tab) && owner.apply(tab) == Ecosystem.FABRIC;
			(fabricOwned ? fabric : kept).add(tab);
		}
		if (fabric.isEmpty()) return sorted;
		fabric.sort(Comparator.comparing(namespace).thenComparing(path));
		kept.addAll(fabric);
		return kept;
	}
}
