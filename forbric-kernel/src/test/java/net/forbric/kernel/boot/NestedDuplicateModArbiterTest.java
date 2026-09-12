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
 * The nested pass, driven through its pure half.
 *
 * <p>The defect it exists for: each loader deduplicates only within its own family — {@code KernelFabricLoader
 * .register} keeps the first Fabric id, {@code KernelModLoader} the first {@code @Mod} — so a library nested by a
 * Fabric mod AND by a MinecraftForge mod was constructed once per ecosystem. Xaero's {@code xaerolib} is the
 * worked example, and the second construction threw on a duplicate config channel only AFTER
 * {@code XaeroLib.<init>} had overwritten {@code INSTANCE} with the half-built object.
 *
 * <p>What it must NOT do is the larger change it looks like: withdrawing same-family nested duplicates. Those are
 * the ordinary shape of JarJar and both loaders already handle them. Doing it anyway took NeoForge Sodium off the
 * classpath — its real mod jar is nested inside a wrapper declaring the same id — and its {@code ServiceLoader}
 * lookup then failed with "Failed to load service for
 * net.caffeinemc.mods.sodium.client.services.PlatformRuntimeInformation". {@link
 * #sameFamilyNestedDuplicatesAreLeftAlone()} is that measurement.
 */
class NestedDuplicateModArbiterTest {
	private static final String FORGE_NESTED = "/g/.forbric-kernel/jarjar/xaerolib-forge.jar";
	private static final String FABRIC_NESTED = "/g/.forbric-kernel/jij/xaerominimap/xaerolib-fabric.jar";

	@BeforeEach
	@AfterEach
	void clearState() {
		DuplicateModArbiter.reset();
		MultiLoaderArbiter.reset();
		System.clearProperty(DuplicateModArbiter.SWITCH);
		System.clearProperty(DuplicateModArbiter.OWNER_OVERRIDE);
		System.clearProperty("forbric.nestedDupePreference");
		System.clearProperty("forbric.dupeIdPreference");
		System.clearProperty("forbric.multiLoaderPreference");
	}

	private static Claim claim(String jar, Ecosystem eco, String id, String version) {
		return new Claim(Path.of(jar), eco, List.of(id), Map.of(id, version));
	}

	private static Decision nested(List<Claim> topLevel, List<Claim> nestedClaims) {
		return DuplicateModArbiter.arbitrateNested(Decision.none(), topLevel, nestedClaims);
	}

	@Test
	void twoFamiliesNestingTheSameIdIsArbitratedAndFabricWins() {
		Decision d = nested(List.of(), List.of(
				claim(FORGE_NESTED, Ecosystem.FORGE, "xaerolib", "1.7.3"),
				claim(FABRIC_NESTED, Ecosystem.FABRIC, "xaerolib", "1.7.3")));

		assertEquals(1, d.suppressedJars().size(), "exactly one copy may load");
		// Measured, not preferred on principle: the MinecraftForge half sets XaeroLib.client from
		// FMLClientSetupEvent, and the Fabric minimap's first-tick hook then read it as null. See
		// DuplicateModArbiter.nestedPreference().
		assertTrue(d.suppressed(Path.of(FORGE_NESTED)), "the Fabric copy is the one that is ready in time");
		assertEquals(Path.of(FABRIC_NESTED).toAbsolutePath(), d.ownerByModId().get("xaerolib"));
	}

	@Test
	void theLosingFamilyStillGetsThePresenceAlias() {
		Decision d = nested(List.of(), List.of(
				claim(FORGE_NESTED, Ecosystem.FORGE, "xaerolib", "1.7.3"),
				claim(FABRIC_NESTED, Ecosystem.FABRIC, "xaerolib", "1.7.3")));

		assertEquals(1, d.aliasesFor(Ecosystem.FORGE).size(),
				"the library really is here — a Forge mod's isLoaded(\"xaerolib\") must still answer yes");
		assertEquals("1.7.3", d.aliasesFor(Ecosystem.FORGE).get(0).version());
	}

	@Test
	void theAliasFallsBackToWhicheverCopyDeclaredAVersion() {
		Decision d = nested(List.of(), List.of(
				new Claim(Path.of(FORGE_NESTED), Ecosystem.FORGE, List.of("xaerolib"), Map.of("xaerolib", "1.7.3")),
				new Claim(Path.of(FABRIC_NESTED), Ecosystem.FABRIC, List.of("xaerolib"))));

		// A mod comparing the aliased version against a range is better served by the loser's real number than by
		// versionOf's "0" placeholder.
		assertEquals("1.7.3", d.aliasesFor(Ecosystem.FORGE).get(0).version());
	}

	@Test
	void sameFamilyNestedDuplicatesAreLeftAlone() {
		// The ordinary shape of JarJar: one library nested by several mods of the same family, and a mod whose real
		// jar is nested inside its own wrapper. Both loaders already keep the first and ignore the rest, WITHOUT
		// taking anything off the classpath — and taking it off is what broke NeoForge Sodium's ServiceLoader.
		Decision d = nested(List.of(), List.of(
				claim("/g/.forbric-kernel/jij/a/fabric-api-base.jar", Ecosystem.FABRIC, "fabric-api-base", "2.0.4"),
				claim("/g/.forbric-kernel/jij/b/fabric-api-base.jar", Ecosystem.FABRIC, "fabric-api-base", "2.0.4"),
				claim("/g/.forbric-kernel/jarjar/sodium-mod.jar", Ecosystem.NEOFORGE, "sodium", "0.9.2")));

		assertEquals(Decision.none().suppressedJars(), d.suppressedJars(), "nothing may be withdrawn");
	}

	@Test
	void aNestedCopyNeverOutvotesTheJarTheUserInstalled() {
		// The preference would take xaerolib from the nested Fabric copy, but the top-level jar's discovery has
		// already run by the time the nested pass sees it — deciding against it would describe a load that did not
		// happen.
		Claim installed = claim("/g/mods/xaerolib-neoforge.jar", Ecosystem.NEOFORGE, "xaerolib", "1.7.3");
		Decision d = nested(List.of(installed),
				List.of(claim(FABRIC_NESTED, Ecosystem.FABRIC, "xaerolib", "1.7.3")));

		assertFalse(d.suppressed(Path.of("/g/mods/xaerolib-neoforge.jar")), "the installed jar must survive");
		assertTrue(d.suppressed(Path.of(FABRIC_NESTED)), "the nested copy is the one that loses");
	}

	@Test
	void aNestedJarIsKeptWhenItAlsoCarriesSomethingNothingElseProvides() {
		// The subset rule, as in the top-level pass: withdrawing this jar because 'xaerolib' collided would leave
		// 'xaerolib_extra' loaded by nobody.
		Claim bundle = new Claim(Path.of(FORGE_NESTED), Ecosystem.FORGE, List.of("xaerolib", "xaerolib_extra"),
				Map.of("xaerolib", "1.7.3", "xaerolib_extra", "1.0"));
		Decision d = nested(List.of(),
				List.of(bundle, claim(FABRIC_NESTED, Ecosystem.FABRIC, "xaerolib", "1.7.3")));

		assertFalse(d.suppressed(Path.of(FORGE_NESTED)), "a partial overlap must suppress nothing");
	}

	@Test
	void theParentOfALosingNestedJarIsNeverTouched() {
		Claim parent = claim("/g/mods/xaeroworldmap-forge.jar", Ecosystem.FORGE, "xaeroworldmap", "1.46.0");
		Decision d = nested(List.of(parent), List.of(
				claim(FORGE_NESTED, Ecosystem.FORGE, "xaerolib", "1.7.3"),
				claim(FABRIC_NESTED, Ecosystem.FABRIC, "xaerolib", "1.7.3")));

		assertEquals(1, d.suppressedJars().size());
		assertFalse(d.suppressed(Path.of("/g/mods/xaeroworldmap-forge.jar")),
				"losing a nested library must not cost the mod that nested it");
	}

	@Test
	void theOwnerOverrideStillWins() {
		System.setProperty(DuplicateModArbiter.OWNER_OVERRIDE, "xaerolib=forge");
		Decision d = nested(List.of(), List.of(
				claim(FORGE_NESTED, Ecosystem.FORGE, "xaerolib", "1.7.3"),
				claim(FABRIC_NESTED, Ecosystem.FABRIC, "xaerolib", "1.7.3")));

		assertTrue(d.suppressed(Path.of(FABRIC_NESTED)), "-Dforbric.modOwner must reach a nested jar too");
	}

	@Test
	void thePreferenceKnobReplacesTheOrder() {
		System.setProperty("forbric.nestedDupePreference", "forge,fabric");
		Decision d = nested(List.of(), List.of(
				claim(FORGE_NESTED, Ecosystem.FORGE, "xaerolib", "1.7.3"),
				claim(FABRIC_NESTED, Ecosystem.FABRIC, "xaerolib", "1.7.3")));

		assertTrue(d.suppressed(Path.of(FABRIC_NESTED)));
	}

	@Test
	void aSingleClaimantIsNeverSuppressed() {
		// The invariant that keeps every existing gate green: no gate stages one mod id twice across ecosystems,
		// so the whole pass is a provable no-op on all of them.
		Decision d = nested(List.of(claim("/g/mods/parent.jar", Ecosystem.FABRIC, "parent", "1")),
				List.of(claim(FABRIC_NESTED, Ecosystem.FABRIC, "xaerolib", "1.7.3")));

		assertEquals(Decision.none().suppressedJars(), d.suppressedJars());
	}
}
