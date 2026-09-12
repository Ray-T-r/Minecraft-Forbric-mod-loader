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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.boot.DuplicateModArbiter.Claim;
import net.forbric.kernel.boot.DuplicateModArbiter.Decision;

/**
 * Covers {@link DuplicateModArbiter}'s decision rules, driven through the pure {@code arbitrate(List<Claim>)} so no
 * filesystem or real jars are involved. The scanning half (which parser reads which manifest, the {@code
 * environment} filter, composing with {@link MultiLoaderArbiter}) is exercised by the real packs, not here.
 */
class DuplicateModArbiterTest {

	@BeforeEach
	@AfterEach
	void clearState() {
		DuplicateModArbiter.reset();
		MultiLoaderArbiter.reset();
		System.clearProperty(DuplicateModArbiter.SWITCH);
		System.clearProperty(DuplicateModArbiter.OWNER_OVERRIDE);
		System.clearProperty("forbric.multiLoaderPreference");
		System.clearProperty("forbric.dupeIdPreference");
	}

	private static Claim claim(String jar, Ecosystem eco, String... ids) {
		return new Claim(Path.of(jar), eco, List.of(ids));
	}

	@Test
	void distinctIdsAreNeverSuppressed() {
		// THE pass-through assertion: no existing gate stages the same mod id twice, so if this holds the whole
		// arbitration is a provable no-op on all of them.
		Decision d = DuplicateModArbiter.arbitrate(List.of(
				claim("/mods/a.jar", Ecosystem.FABRIC, "alpha"),
				claim("/mods/b.jar", Ecosystem.NEOFORGE, "beta"),
				claim("/mods/c.jar", Ecosystem.FORGE, "gamma")));

		assertTrue(d.suppressedJars().isEmpty());
		assertTrue(d.ownerByModId().isEmpty());
	}

	@Test
	void aFabricNeoforgePairResolvesByTheGlobalPreference() {
		System.setProperty("forbric.multiLoaderPreference", "fabric,neoforge,minecraftforge");

		Decision d = DuplicateModArbiter.arbitrate(List.of(
				claim("/mods/sodium-fabric.jar", Ecosystem.FABRIC, "sodium"),
				claim("/mods/sodium-neoforge.jar", Ecosystem.NEOFORGE, "sodium")));

		assertTrue(d.suppressed(Path.of("/mods/sodium-neoforge.jar")), "the NeoForge copy must lose");
		assertFalse(d.suppressed(Path.of("/mods/sodium-fabric.jar")));
		assertEquals(Path.of("/mods/sodium-fabric.jar").toAbsolutePath(), d.ownerByModId().get("sodium"));
	}

	@Test
	void flippingThePreferenceFlipsTheWinner() {
		System.setProperty("forbric.multiLoaderPreference", "neoforge,minecraftforge,fabric");

		Decision d = DuplicateModArbiter.arbitrate(List.of(
				claim("/mods/sodium-fabric.jar", Ecosystem.FABRIC, "sodium"),
				claim("/mods/sodium-neoforge.jar", Ecosystem.NEOFORGE, "sodium")));

		assertTrue(d.suppressed(Path.of("/mods/sodium-fabric.jar")));
	}

	@Test
	void aPerModOverrideBeatsThePreference() {
		System.setProperty("forbric.multiLoaderPreference", "fabric,neoforge,minecraftforge");
		System.setProperty(DuplicateModArbiter.OWNER_OVERRIDE, "lithostitched=neoforge");

		Decision d = DuplicateModArbiter.arbitrate(List.of(
				claim("/mods/litho-fabric.jar", Ecosystem.FABRIC, "lithostitched"),
				claim("/mods/litho-neoforge.jar", Ecosystem.NEOFORGE, "lithostitched"),
				claim("/mods/sodium-fabric.jar", Ecosystem.FABRIC, "sodium"),
				claim("/mods/sodium-neoforge.jar", Ecosystem.NEOFORGE, "sodium")));

		assertTrue(d.suppressed(Path.of("/mods/litho-fabric.jar")), "the override must win for lithostitched");
		assertTrue(d.suppressed(Path.of("/mods/sodium-neoforge.jar")), "sodium still follows the preference");
	}

	@Test
	void anOverrideNamingAnEcosystemWithNoClaimFallsBackRatherThanUnloadingTheMod() {
		System.setProperty("forbric.multiLoaderPreference", "fabric,neoforge,minecraftforge");
		System.setProperty(DuplicateModArbiter.OWNER_OVERRIDE, "sodium=minecraftforge");

		Decision d = DuplicateModArbiter.arbitrate(List.of(
				claim("/mods/sodium-fabric.jar", Ecosystem.FABRIC, "sodium"),
				claim("/mods/sodium-neoforge.jar", Ecosystem.NEOFORGE, "sodium")));

		// A typo must never leave the mod loaded by nobody: exactly one jar survives, chosen by preference.
		assertEquals(1, d.suppressedJars().size());
		assertTrue(d.suppressed(Path.of("/mods/sodium-neoforge.jar")));
	}

	@Test
	void anUnknownEcosystemInTheOverrideIsIgnored() {
		System.setProperty("forbric.multiLoaderPreference", "fabric,neoforge,minecraftforge");
		System.setProperty(DuplicateModArbiter.OWNER_OVERRIDE, "sodium=quilt");

		Decision d = DuplicateModArbiter.arbitrate(List.of(
				claim("/mods/sodium-fabric.jar", Ecosystem.FABRIC, "sodium"),
				claim("/mods/sodium-neoforge.jar", Ecosystem.NEOFORGE, "sodium")));

		assertTrue(d.suppressed(Path.of("/mods/sodium-neoforge.jar")));
	}

	@Test
	void partialOverlapSuppressesNothing() {
		// The bundling jar declares foo AND foo_compat; only foo collides. Suppressing it would delete foo_compat,
		// which nothing else provides.
		System.setProperty("forbric.multiLoaderPreference", "fabric,neoforge,minecraftforge");

		Decision d = DuplicateModArbiter.arbitrate(List.of(
				claim("/mods/foo-fabric.jar", Ecosystem.FABRIC, "foo"),
				claim("/mods/foo-bundle-neoforge.jar", Ecosystem.NEOFORGE, "foo", "foo_compat")));

		assertTrue(d.suppressedJars().isEmpty(), "a partially-overlapping jar must survive");
		assertEquals(Path.of("/mods/foo-fabric.jar").toAbsolutePath(), d.ownerByModId().get("foo"));
	}

	@Test
	void aFullySubsumedBundleIsSuppressed() {
		// The mirror of the case above: every id the bundle declares is also claimed by winners, so nothing is
		// orphaned and it can go.
		System.setProperty("forbric.multiLoaderPreference", "fabric,neoforge,minecraftforge");

		Decision d = DuplicateModArbiter.arbitrate(List.of(
				claim("/mods/foo-fabric.jar", Ecosystem.FABRIC, "foo"),
				claim("/mods/compat-fabric.jar", Ecosystem.FABRIC, "foo_compat"),
				claim("/mods/foo-bundle-neoforge.jar", Ecosystem.NEOFORGE, "foo", "foo_compat")));

		assertTrue(d.suppressed(Path.of("/mods/foo-bundle-neoforge.jar")));
		assertEquals(1, d.suppressedJars().size());
	}

	@Test
	void twoJarsOfTheSameEcosystemKeepTheFirstByPath() {
		Decision d = DuplicateModArbiter.arbitrate(List.of(
				claim("/mods/architectury-21.0.2.jar", Ecosystem.NEOFORGE, "architectury"),
				claim("/mods/architectury-21.0.6.jar", Ecosystem.NEOFORGE, "architectury")));

		// Deterministic, not version-aware — the documented behaviour, and -Dforbric.modOwner cannot break a
		// same-ecosystem tie either. Version-picking is out of scope; the log names both.
		assertTrue(d.suppressed(Path.of("/mods/architectury-21.0.6.jar")));
		assertFalse(d.suppressed(Path.of("/mods/architectury-21.0.2.jar")));
	}

	@Test
	void threeWayContestLeavesExactlyOneSurvivor() {
		System.setProperty("forbric.multiLoaderPreference", "minecraftforge,fabric,neoforge");

		Decision d = DuplicateModArbiter.arbitrate(List.of(
				claim("/mods/x-fabric.jar", Ecosystem.FABRIC, "x"),
				claim("/mods/x-neoforge.jar", Ecosystem.NEOFORGE, "x"),
				claim("/mods/x-forge.jar", Ecosystem.FORGE, "x")));

		assertEquals(2, d.suppressedJars().size());
		assertFalse(d.suppressed(Path.of("/mods/x-forge.jar")));
	}

	@Test
	void theOffSwitchDisablesArbitrationEntirely() {
		System.setProperty(DuplicateModArbiter.SWITCH, "off");

		// The off switch is checked in the scanning entry point, which is what a boot calls.
		Decision d = DuplicateModArbiter.arbitrate(Path.of("/nonexistent/mods"), null);

		assertTrue(d.suppressedJars().isEmpty());
	}

	@Test
	void aDedicatedDupePreferenceOverridesTheSharedOne() {
		// The two arbitrations answer different questions and must be separable: the shared knob still governs
		// per-jar multiloader ownership while cross-jar ties resolve the other way. Merging the two real packs is
		// exactly this case — Fabric wins duplicate ids, but the NeoForge pack's universal jars stay NeoForge.
		System.setProperty("forbric.multiLoaderPreference", "neoforge,minecraftforge,fabric");
		System.setProperty("forbric.dupeIdPreference", "fabric,neoforge,minecraftforge");

		Decision d = DuplicateModArbiter.arbitrate(List.of(
				claim("/mods/sodium-fabric.jar", Ecosystem.FABRIC, "sodium"),
				claim("/mods/sodium-neoforge.jar", Ecosystem.NEOFORGE, "sodium")));

		assertTrue(d.suppressed(Path.of("/mods/sodium-neoforge.jar")), "the dedicated knob must win");
	}

	@Test
	void theDupePreferenceFallsBackToTheSharedOneWhenUnset() {
		System.setProperty("forbric.multiLoaderPreference", "fabric,neoforge,minecraftforge");

		Decision d = DuplicateModArbiter.arbitrate(List.of(
				claim("/mods/sodium-fabric.jar", Ecosystem.FABRIC, "sodium"),
				claim("/mods/sodium-neoforge.jar", Ecosystem.NEOFORGE, "sodium")));

		assertTrue(d.suppressed(Path.of("/mods/sodium-neoforge.jar")));
	}

	@Test
	void theLosingEcosystemGetsAPresenceAlias() {
		// The A/B/C case: C ships a Fabric jar and a NeoForge jar, A is Fabric-only and B is NeoForge-only, both
		// depend on C. Only one C jar survives — but the two builds are 98–100% the same classes, so B still links.
		// What B loses is C's IDENTITY on its side, and that is what the alias restores.
		System.setProperty("forbric.dupeIdPreference", "fabric,neoforge,minecraftforge");

		Decision d = DuplicateModArbiter.arbitrate(List.of(
				new Claim(Path.of("/mods/c-fabric.jar"), Ecosystem.FABRIC, List.of("c"), Map.of("c", "1.2.3")),
				new Claim(Path.of("/mods/c-neoforge.jar"), Ecosystem.NEOFORGE, List.of("c"), Map.of("c", "1.2.3"))));

		assertEquals(1, d.aliases().size());
		assertEquals(new DuplicateModArbiter.Alias("c", Ecosystem.NEOFORGE, "1.2.3"), d.aliases().get(0));
		assertEquals(1, d.aliasesFor(Ecosystem.NEOFORGE).size());
		assertTrue(d.aliasesFor(Ecosystem.FABRIC).isEmpty(), "the winning side needs no alias");
	}

	@Test
	void anAliasCarriesTheWinnersVersionNotTheLosers() {
		// A dependency range is checked against whatever the alias reports, so it must describe the jar actually
		// present — reporting the suppressed jar's version would answer for code that is not there.
		System.setProperty("forbric.dupeIdPreference", "fabric,neoforge,minecraftforge");

		Decision d = DuplicateModArbiter.arbitrate(List.of(
				new Claim(Path.of("/mods/c-fabric.jar"), Ecosystem.FABRIC, List.of("c"), Map.of("c", "2.0.0")),
				new Claim(Path.of("/mods/c-neoforge.jar"), Ecosystem.NEOFORGE, List.of("c"), Map.of("c", "1.0.0"))));

		assertEquals("2.0.0", d.aliases().get(0).version());
	}

	@Test
	void aThreeWayContestAliasesBothLosingEcosystems() {
		System.setProperty("forbric.dupeIdPreference", "minecraftforge,fabric,neoforge");

		Decision d = DuplicateModArbiter.arbitrate(List.of(
				claim("/mods/x-fabric.jar", Ecosystem.FABRIC, "x"),
				claim("/mods/x-neoforge.jar", Ecosystem.NEOFORGE, "x"),
				claim("/mods/x-forge.jar", Ecosystem.FORGE, "x")));

		assertEquals(2, d.aliases().size());
		assertEquals(1, d.aliasesFor(Ecosystem.FABRIC).size());
		assertEquals(1, d.aliasesFor(Ecosystem.NEOFORGE).size());
	}

	@Test
	void aSameEcosystemTieNeedsNoAlias() {
		// Both jars are NeoForge, so nothing lost its identity — the surviving jar already provides it.
		Decision d = DuplicateModArbiter.arbitrate(List.of(
				claim("/mods/architectury-21.0.2.jar", Ecosystem.NEOFORGE, "architectury"),
				claim("/mods/architectury-21.0.6.jar", Ecosystem.NEOFORGE, "architectury")));

		assertTrue(d.aliases().isEmpty());
	}

	@Test
	void aMissingModsDirectoryIsNotAnError() {
		Decision d = DuplicateModArbiter.arbitrate(Path.of("/nonexistent/mods"), null);

		assertTrue(d.suppressedJars().isEmpty());
		assertFalse(d.suppressed(Path.of("/nonexistent/mods/anything.jar")));
	}

	// ---------------------------------------------------------------- universal jars

	@Test
	void aUniversalJarHandsItsLosingIdentityBackEvenWithNoDuplicatesAtAll() {
		// The common case: nothing is contested, so the cross-jar pass has nothing to say — but the file still
		// declared two loaders and is loaded as one, and the other side has to be able to answer isModLoaded.
		Decision decision = DuplicateModArbiter.arbitrate(
				List.of(new Claim(Path.of("iris-neoforge.jar"), Ecosystem.NEOFORGE, List.of("iris"))),
				List.of(new DuplicateModArbiter.Alias("iris", Ecosystem.FABRIC, "1.11.2")));

		assertTrue(decision.suppressedJars().isEmpty(), "a universal jar is never suppressed — it is one file");
		assertEquals(List.of("iris"), aliasIds(decision, Ecosystem.FABRIC));
	}

	@Test
	void aUniversalJarsAliasUsesTheLosingManifestsOwnId() {
		// JourneyMap declares journeymap to NeoForge and journeymap-wrongloader to Fabric, the latter a deliberate
		// marker so stock Fabric ignores the file. Aliasing the WINNER's id into Fabric would answer a question
		// nobody asked and leave the real one unanswered.
		Decision decision = DuplicateModArbiter.arbitrate(
				List.of(new Claim(Path.of("journeymap-neoforge.jar"), Ecosystem.NEOFORGE, List.of("journeymap"))),
				List.of(new DuplicateModArbiter.Alias("journeymap-wrongloader", Ecosystem.FABRIC, "6.0.1")));

		assertEquals(List.of("journeymap-wrongloader"), aliasIds(decision, Ecosystem.FABRIC));
	}

	@Test
	void universalAliasesSurviveAlongsideCrossJarOnes() {
		Decision decision = DuplicateModArbiter.arbitrate(
				List.of(new Claim(Path.of("sodium-fabric.jar"), Ecosystem.FABRIC, List.of("sodium")),
						new Claim(Path.of("sodium-neoforge.jar"), Ecosystem.NEOFORGE, List.of("sodium"))),
				List.of(new DuplicateModArbiter.Alias("iris", Ecosystem.FABRIC, "1.11.2")));

		// Two aliases in total: iris from the universal jar, and sodium handed back to whichever side lost it.
		assertEquals(2, decision.aliases().size());
		assertTrue(aliasIds(decision, Ecosystem.FABRIC).contains("iris"),
				"the universal alias must not be lost when a cross-jar duplicate also exists");
		assertEquals(1, aliasIds(decision, Ecosystem.FABRIC).size() + aliasIds(decision, Ecosystem.NEOFORGE).size()
				- 1, "exactly one side loses sodium");
	}

	private static List<String> aliasIds(Decision decision, Ecosystem ecosystem) {
		return decision.aliasesFor(ecosystem).stream().map(DuplicateModArbiter.Alias::modId).sorted().toList();
	}
}
