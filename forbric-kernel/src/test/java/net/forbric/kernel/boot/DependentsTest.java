package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.UnifiedDependency;

/**
 * The half of a library failure the load report never showed.
 *
 * <p>gate-m24-brokenmod asserts that one mod's failure does not spread, and its canary has no dependants — so
 * the assertion has never been asked the question a real pack asks.
 */
class DependentsTest {

	@Test void theModsThatRequireItAreNamed() {
		List<DiscoveredMod> all = List.of(
				lib("balm"),
				requiring("waystones", "Waystones", "balm"),
				requiring("cookingforblockheads", "Cooking for Blockheads", "balm"),
				lib("unrelated"));
		assertEquals(List.of("Cooking for Blockheads", "Waystones"), Dependents.of("balm", all),
				"display names, sorted, because this list is read by a player");
	}

	@Test void anOptionalDependencyIsNotSomeoneThisTakesDown() {
		List<DiscoveredMod> all = List.of(
				lib("jei"),
				new DiscoveredMod(Ecosystem.NEOFORGE, "nice-to-have", "1.0", "Nice To Have",
						List.of(new UnifiedDependency("jei", "*", false)), List.of(), null, "n.jar"));
		assertTrue(Dependents.of("jei", all).isEmpty(),
				"a mod that says jei is optional keeps working without it, and naming it would be a false alarm");
	}

	@Test void aRequirementWrittenInTheOtherEcosystemsSpellingStillCounts() {
		// The libraries most likely to be shipped for both ecosystems are exactly the ones whose id is spelled
		// two ways, so a string comparison reports no dependants for the worst case.
		List<DiscoveredMod> all = List.of(
				lib("cloth-config"),
				requiring("someneomod", "Some Neo Mod", "cloth_config"));
		assertEquals(List.of("Some Neo Mod"), Dependents.of("cloth-config", all));
	}

	@Test void aModIsNotItsOwnDependentAndIsNamedOnlyOnce() {
		// A universal jar is discovered once per ecosystem; naming it twice reads like two broken mods.
		List<DiscoveredMod> all = List.of(
				lib("balm"),
				requiring("twin", "Twin", "balm"),
				new DiscoveredMod(Ecosystem.FABRIC, "twin", "1.0", "Twin",
						List.of(new UnifiedDependency("balm", "*", true)), List.of(), null, "twin-fabric.jar"),
				new DiscoveredMod(Ecosystem.NEOFORGE, "balm", "1.0", "Balm",
						List.of(new UnifiedDependency("balm", "*", true)), List.of(), null, "balm.jar"));
		assertEquals(List.of("Twin"), Dependents.of("balm", all));
	}

	@Test void nothingIsNamedForAModNobodyDependsOn() {
		assertTrue(Dependents.of("lonely", List.of(lib("lonely"), lib("other"))).isEmpty());
		assertTrue(Dependents.of(null, List.of(lib("x"))).isEmpty());
		assertTrue(Dependents.of("x", null).isEmpty());
	}

	private static DiscoveredMod lib(String id) {
		return new DiscoveredMod(Ecosystem.NEOFORGE, id, "1.0", id, List.of(), List.of(), null, id + ".jar");
	}

	private static DiscoveredMod requiring(String id, String name, String needs) {
		return new DiscoveredMod(Ecosystem.NEOFORGE, id, "1.0", name,
				List.of(new UnifiedDependency(needs, "*", true)), List.of(), null, id + ".jar");
	}
}
