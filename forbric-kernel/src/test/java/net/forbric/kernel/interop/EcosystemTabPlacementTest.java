/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.Test;

/**
 * Tabs placed by their owner's native rule, on tabs with names of their own: twelve unordered tabs of a Fabric-owned
 * namespace that NeoForge's registration chain dropped between the base game's tabs go after everything else in id
 * order; a NeoForge-owned tab, a Fabric-owned tab that declares its own ordering and unowned ones stay where NeoForge put
 * them.
 */
class EcosystemTabPlacementTest {
	record Tab(String namespace, String path, boolean ordered) {
		@Override public String toString() {
			return namespace + ":" + path;
		}
	}

	private static final Map<String, Ecosystem> OWNERS = Map.of("loomcraft", Ecosystem.FABRIC, "quarry", Ecosystem.FABRIC,
			"anvilworks", Ecosystem.NEOFORGE);

	private static List<Tab> place(List<Tab> sorted) {
		return EcosystemTabPlacement.place(sorted, Tab::namespace, Tab::path, Tab::ordered, tab -> OWNERS.get(tab.namespace()));
	}

	private static List<Tab> base(String... paths) {
		List<Tab> tabs = new ArrayList<>();
		for (String path : paths) tabs.add(new Tab("minecraft", path, true));
		return tabs;
	}

	@Test void aFabricOwnersUnorderedTabsArePagedAfterTheBaseGameInIdOrder() {
		List<Tab> neoChain = new ArrayList<>(base("a1", "a2", "a3", "a4", "a5"));
		for (int i = 0; i < 12; i++) neoChain.add(new Tab("loomcraft", "bolt" + i, false));
		neoChain.addAll(base("b1", "b2", "b3", "b4", "b5"));
		List<Tab> placed = place(neoChain);
		assertEquals("[minecraft:a1, minecraft:a2, minecraft:a3, minecraft:a4, minecraft:a5, minecraft:b1, minecraft:b2, minecraft:b3, "
				+ "minecraft:b4, minecraft:b5, loomcraft:bolt0, loomcraft:bolt1, loomcraft:bolt10, loomcraft:bolt11, loomcraft:bolt2, "
				+ "loomcraft:bolt3, loomcraft:bolt4, loomcraft:bolt5, loomcraft:bolt6, loomcraft:bolt7, loomcraft:bolt8, loomcraft:bolt9]",
				placed.toString());
		// Pages of ten: the base game's ten on the first, as the Fabric pager pages them.
		assertEquals(base("a1", "a2", "a3", "a4", "a5", "b1", "b2", "b3", "b4", "b5"), placed.subList(0, 10));
	}

	@Test void otherOwnersAndSelfOrderedTabsKeepNeoForgesPlace() {
		Tab neo = new Tab("anvilworks", "hammer", false), selfOrdered = new Tab("quarry", "pick", true), unowned = new Tab("stray", "x", false);
		List<Tab> neoChain = new ArrayList<>(base("a1", "a2"));
		neoChain.add(new Tab("quarry", "z", false));
		neoChain.add(neo);
		neoChain.add(selfOrdered);
		neoChain.add(unowned);
		neoChain.add(new Tab("loomcraft", "c", false));
		neoChain.addAll(base("b1"));
		assertEquals("[minecraft:a1, minecraft:a2, anvilworks:hammer, quarry:pick, stray:x, minecraft:b1, loomcraft:c, quarry:z]",
				place(neoChain).toString());
	}

	@Test void withNothingToMoveTheSameListComesBackAndTheSwitchLeavesNeoForgesOrder() {
		List<Tab> noFabric = base("a1", "a2");
		assertSame(noFabric, place(noFabric));
		List<Tab> chain = new ArrayList<>(base("a1"));
		chain.add(new Tab("loomcraft", "bolt", false));
		chain.addAll(base("b1"));
		System.setProperty(EcosystemTabPlacement.PROPERTY, "off");
		try {
			assertSame(chain, place(chain));
		} finally {
			System.clearProperty(EcosystemTabPlacement.PROPERTY);
		}
	}
}
