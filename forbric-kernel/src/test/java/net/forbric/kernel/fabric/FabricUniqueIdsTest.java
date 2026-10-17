package net.forbric.kernel.fabric;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The port of Fabric Loader 0.19.3's {@code ModPrioSorter} order and its mod-version comparison, which decide who wins
 * a shared id (FabricUniqueIds). Whole-instance shapes are in {@code NestedFabricRequirementsTest}.
 */
class FabricUniqueIdsTest {
	/**
	 * Roots first, then by id; then a mod moves ahead of the earlier ones it overlaps and has the higher version against,
	 * but no further than the first of those: aaa, which overlaps nothing, keeps its place.
	 */
	@Test void theOrderIsModPrioSortersSortThenItsOverlapMoves() {
		var root = candidate("zz-root", "1", true, List.of(), "");
		var m1 = candidate("m1", "1.0.0", false, List.of("zz-root"), ",\"provides\":[\"x\"]");
		var m2 = candidate("m2", "3.0.0", false, List.of("zz-root"), ",\"provides\":[\"x\",\"y\"]");
		var m3 = candidate("m3", "5.0.0", false, List.of("zz-root"), ",\"provides\":[\"y\"]");
		var alone = candidate("aaa", "1", false, List.of("zz-root"), "");
		assertEquals(List.of("zz-root", "aaa", "m3", "m2", "m1"),
				FabricUniqueIds.priorityOrder(List.of(m1, m2, m3, alone, root)).stream().map(c -> c.id()).toList());
	}

	/** Equal versions do not move: the id order stands. A mod never moves ahead of another copy of its own id. */
	@Test void anOverlapWithoutAHigherVersionKeepsTheIdOrder() {
		var x = candidate("x", "1", false, List.of(), "");
		var y = candidate("y", "1", false, List.of(), ",\"provides\":[\"x\"]");
		assertEquals(List.of("x", "y"), FabricUniqueIds.priorityOrder(List.of(y, x)).stream().map(c -> c.id()).toList());
		var newer = candidate("y", "2", false, List.of(), ",\"provides\":[\"x\"]");
		assertEquals(List.of("y", "x"), FabricUniqueIds.priorityOrder(List.of(x, newer)).stream().map(c -> c.id()).toList());
	}

	/** Mod versions are parsed without wildcards: "1.x" is a string version and compares as a string, as Fabric does. */
	@Test void aWildcardModVersionComparesAsAString() {
		assertEquals(Integer.signum("1.x".compareTo("1.5")), Integer.signum(FabricUniqueIds.compareVersions("1.x", "1.5")));
		assertTrue(FabricUniqueIds.compareVersions("1.10.0", "1.9.0") > 0, "semantic versions compare numerically");
		assertTrue(FabricUniqueIds.compareVersions("2.0.0-beta.1", "2.0.0") < 0, "a pre-release sorts below its release");
	}

	/** Nothing contested, nothing decided: the common case returns before any solving. */
	@Test void withoutASharedIdNothingIsLeftOut() {
		var root = candidate("host", "1", true, List.of(), "");
		var lib = candidate("lib", "1", false, List.of("host"), "");
		assertFalse(FabricUniqueIds.contested(List.of(root, lib)));
		assertTrue(FabricUniqueIds.resolve(List.of(root, lib), List.of()).leftOut().isEmpty());
	}

	private static NestedFabricRequirements.Candidate<String> candidate(String id, String version, boolean root,
			List<String> parents, String extra) {
		var metadata = FabricModMetadataParser.read(new StringReader("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\""
				+ version + "\"" + extra + "}"));
		Map<String, String> provides = new java.util.HashMap<>();
		provides.put(net.forbric.api.ModPresence.spellingKey(id), version);
		for (String alias : metadata.getProvides()) provides.putIfAbsent(net.forbric.api.ModPresence.spellingKey(alias), version);
		return new NestedFabricRequirements.Candidate<>(id, id, root, !root, provides, parents, metadata,
				NestedFabricRequirements.requirementsOf(metadata));
	}
}
