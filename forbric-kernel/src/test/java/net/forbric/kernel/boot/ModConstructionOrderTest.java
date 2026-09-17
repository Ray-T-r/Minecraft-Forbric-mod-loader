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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.UnifiedDependency;

/**
 * Covers {@link ModConstructionOrder} — which mod runs first.
 *
 * <p>It was jar file name, alphabetically, which is not an order at all. A mod whose jar sorts before a library
 * it requires ran first and called that library before it had initialised; the error comes out of the library,
 * blamed on the library.
 */
class ModConstructionOrderTest {

	@AfterEach
	void clearSwitch() {
		System.clearProperty(ModConstructionOrder.SWITCH);
	}

	private static DiscoveredMod mod(String id, UnifiedDependency... deps) {
		return new DiscoveredMod(Ecosystem.NEOFORGE, id, "1.0.0", id, List.of(deps), List.of(), null, id + ".jar");
	}

	private static UnifiedDependency requires(String id) {
		return new UnifiedDependency(id, "*", true);
	}

	private static UnifiedDependency optional(String id) {
		return new UnifiedDependency(id, "*", false);
	}

	private static UnifiedDependency ordered(String id, UnifiedDependency.Ordering ordering) {
		return new UnifiedDependency(id, "*", false, ordering, UnifiedDependency.SideScope.BOTH);
	}

	@Test
	void aRequiredLibraryComesFirstEvenWhenItsNameSortsLast() {
		// The failure shape exactly: "architectury" needs "zzz-lib", and the alphabetical order runs it first.
		List<String> order = ModConstructionOrder.of(List.of(
				mod("architectury", requires("zzzlib")),
				mod("zzzlib")));

		assertEquals(List.of("zzzlib", "architectury"), order);
	}

	@Test
	void anExplicitAfterIsHonouredWithoutBeingARequirement() {
		// A mod can ask to load after another without depending on it — that is what the ordering key is for, and
		// it was read out of the metadata and then thrown away.
		List<String> order = ModConstructionOrder.of(List.of(
				mod("aaa", ordered("bbb", UnifiedDependency.Ordering.AFTER)),
				mod("bbb")));

		assertEquals(List.of("bbb", "aaa"), order);
	}

	@Test
	void anExplicitBeforePushesTheOtherModBack() {
		List<String> order = ModConstructionOrder.of(List.of(
				mod("zzz", ordered("aaa", UnifiedDependency.Ordering.BEFORE)),
				mod("aaa")));

		assertEquals(List.of("zzz", "aaa"), order);
	}

	@Test
	void anOptionalDependencyThatStatesNoOrderImposesNone() {
		// Deliberate, and the narrower reading. An optional dependency says "you may not be here"; a mod that
		// also needs the other one to have RUN says so with the ordering key, and that key is honoured above. A
		// required dependency is different — a mod cannot function without one, so it is ordered behind it.
		List<String> order = ModConstructionOrder.of(List.of(
				mod("aaa", optional("zzz")),
				mod("zzz")));

		assertEquals(List.of("aaa", "zzz"), order, "an optional dependency alone must not reorder anything");
	}

	@Test
	void anOptionalDependencyWithAnOrderingIsHonoured() {
		List<String> order = ModConstructionOrder.of(List.of(
				mod("aaa", ordered("zzz", UnifiedDependency.Ordering.AFTER)),
				mod("zzz")));

		assertEquals(List.of("zzz", "aaa"), order);
	}

	@Test
	void aRequirementOnSomethingNotInstalledIsIgnored() {
		// The dependency audit reports a missing requirement. Inventing an edge to a node that is not there would
		// be a cycle waiting to happen, and would drop the mod out of the order entirely.
		List<String> order = ModConstructionOrder.of(List.of(
				mod("solo", requires("never-installed"))));

		assertEquals(List.of("solo"), order);
	}

	@Test
	void aDependencyNamedByAnAliasStillOrders() {
		// Every LibJF module is named through an alias: the jar's id is libjf-base and dependents name libjf_base.
		DiscoveredMod library = mod("libjf-base").withAliases(List.of("libjf_base"));

		List<String> order = ModConstructionOrder.of(List.of(
				mod("aaa-consumer", requires("libjf_base")),
				library));

		assertEquals(List.of("libjf-base", "aaa-consumer"), order,
				"the alias has to resolve to the mod that provides it, or the library is ordered after its user");
	}

	@Test
	void anAliasIsNotAnExtraEntry() {
		List<String> order = ModConstructionOrder.of(List.of(
				mod("libjf-base").withAliases(List.of("libjf_base", "libjf"))));

		assertEquals(List.of("libjf-base"), order, "an alias is a name for a node, not another node");
	}

	@Test
	void independentModsKeepTheOrderTheyCameIn() {
		// A stable tie-break is what makes an order-dependent bug reproducible instead of intermittent.
		List<String> order = ModConstructionOrder.of(List.of(mod("aaa"), mod("bbb"), mod("ccc")));

		assertEquals(List.of("aaa", "bbb", "ccc"), order);
	}

	@Test
	void aDependentDoesNotDragItselfForward() {
		// zzz requires aaa, and everything else is independent: only zzz moves, and only behind aaa.
		List<String> order = ModConstructionOrder.of(List.of(
				mod("aaa"), mod("bbb"), mod("ccc"), mod("zzz", requires("aaa"))));

		assertEquals(List.of("aaa", "bbb", "ccc", "zzz"), order);
	}

	@Test
	void aCycleKeepsEveryModAndSaysSo() {
		// Two mods each requiring the other cannot be ordered. Dropping one would be far worse than running them
		// in an arbitrary order, and both have to still be there.
		List<String> order = ModConstructionOrder.of(List.of(
				mod("aaa", requires("bbb")),
				mod("bbb", requires("aaa")),
				mod("ccc")));

		assertEquals(3, order.size());
		assertTrue(order.containsAll(List.of("aaa", "bbb", "ccc")));
	}

	@Test
	void theEscapeHatchRestoresTheFileNameOrder() {
		System.setProperty(ModConstructionOrder.SWITCH, "name");

		assertEquals(List.of("architectury", "zzzlib"), ModConstructionOrder.of(List.of(
				mod("architectury", requires("zzzlib")),
				mod("zzzlib"))));
	}

	@Test
	void theEscapeHatchStopsTheSortToo() {
		// It did not, and that was worse than useless: with ordering off the sort had nothing to sort by, but it
		// still moved every item whose id the order does not name to the end. The list came out different, so the
		// caller reported "now in dependency order" while producing file-name order — a log line that was the
		// opposite of the truth.
		System.setProperty(ModConstructionOrder.SWITCH, "name");
		List<String> items = List.of("zzzlib:Main", "architectury:Main", "unknown:Main");

		assertEquals(items, ModConstructionOrder.sort(
				items, s -> s.split(":")[0], List.of("architectury", "zzzlib")));
	}

	@Test
	void sortingAppliesTheOrderToWhateverCarriesTheId() {
		Function<String, String> id = s -> s.split(":")[0];

		List<String> sorted = ModConstructionOrder.sort(
				List.of("architectury:Main", "zzzlib:Main"), id, List.of("zzzlib", "architectury"));

		assertEquals(List.of("zzzlib:Main", "architectury:Main"), sorted);
	}

	@Test
	void twoClassesOfOneModKeepTheirRelativeOrder() {
		// A jar may declare several entry classes under one id; the order is a property of the MOD, so they must
		// not be shuffled against each other.
		Function<String, String> id = s -> s.split(":")[0];

		List<String> sorted = ModConstructionOrder.sort(
				List.of("balm:Common", "balm:Client", "zzzlib:Main"), id, List.of("zzzlib", "balm"));

		assertEquals(List.of("zzzlib:Main", "balm:Common", "balm:Client"), sorted);
	}

	@Test
	void anUnknownIdIsKeptAndSortsLast() {
		// Something discovery never saw must not vanish, and must not be run ahead of a library it might need.
		Function<String, String> id = s -> s.split(":")[0];

		List<String> sorted = ModConstructionOrder.sort(
				List.of("mystery:Main", "zzzlib:Main"), id, List.of("zzzlib"));

		assertEquals(List.of("zzzlib:Main", "mystery:Main"), sorted);
	}

	@Test
	void aNullIdIsKeptRatherThanThrowing() {
		List<String> sorted = ModConstructionOrder.sort(
				java.util.Arrays.asList("known", null), s -> s, List.of("known"));

		assertEquals(2, sorted.size());
		assertEquals("known", sorted.get(0));
	}
}
