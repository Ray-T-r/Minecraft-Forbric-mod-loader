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

package net.forbric.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Covers the one ecosystem vocabulary, and the two places where its spellings deliberately differ. */
class EcosystemTest {
	/**
	 * {@code name()} is an ABI, not a label: {@code run/diff-oracle.sh} hardcodes FABRIC/FORGE/NEOFORGE as an
	 * INDEPENDENT ground truth in python, and {@code boot/Main} writes {@code getEcosystem().name()} into the scan
	 * JSON the oracle compares against. Renaming a constant makes the oracle disagree with the kernel about every
	 * mod at once, and the diff would read as a discovery bug.
	 */
	@Test
	void theConstantNamesAreWhatTheDifferentialOracleCompilesAgainst() {
		assertEquals("FABRIC", Ecosystem.FABRIC.name());
		assertEquals("NEOFORGE", Ecosystem.NEOFORGE.name());
		assertEquals(2, Ecosystem.values().length, "MinecraftForge is no ecosystem Forbric loads");
	}

	/**
	 * MinecraftForge, in either spelling a player or a launch profile ever used, is no ecosystem: {@code parse} reads it
	 * as none, and {@link Ecosystem#namesMinecraftForge} tells it apart, so the knobs and the player's override file say
	 * "no longer supported" rather than "unknown". The player's spelling still reads back as that name.
	 */
	@Test
	void minecraftForgeInEitherSpellingIsNoEcosystemButIsRecognised() {
		assertEquals("neoforge", Ecosystem.NEOFORGE.configId());
		assertEquals("fabric", Ecosystem.FABRIC.configId());

		for (String raw : List.of("minecraftforge", "forge", "  MinecraftForge ", "FORGE")) {
			assertNull(Ecosystem.parse(raw), raw);
			assertTrue(Ecosystem.namesMinecraftForge(raw), raw);
		}
		for (String raw : List.of("neoforge", "fabric", "quilt", "")) assertFalse(Ecosystem.namesMinecraftForge(raw), raw);
		assertFalse(Ecosystem.namesMinecraftForge(null));
		assertEquals(Ecosystem.NEOFORGE, Ecosystem.parse(" NeoForge "));
	}

	@Test
	void anUnrecognisedSpellingIsNullRatherThanAGuess() {
		assertNull(Ecosystem.parse("quilt"));
		assertNull(Ecosystem.parse(null));
		assertNull(Ecosystem.parse(""));
	}

	/**
	 * The family split is what decides whether a mod is driven by the Forge-family lifecycle at all, and the two
	 * Forge families are on the same side of it however different their packages are.
	 */
	@Test
	void bothForgeFamiliesAreOneFamilyAndFabricIsNot() {
		assertTrue(Ecosystem.NEOFORGE.isForgeFamily());
		assertFalse(Ecosystem.FABRIC.isForgeFamily());
	}

	@Test
	void everyConstantHasAFamilyIdAConfigIdAndADisplayName() {
		for (Ecosystem ecosystem : Ecosystem.values()) {
			assertFalse(ecosystem.familyId().isBlank(), ecosystem.name());
			assertFalse(ecosystem.configId().isBlank(), ecosystem.name());
			assertFalse(ecosystem.displayName().isBlank(), ecosystem.name());
			assertEquals(ecosystem, Ecosystem.parse(ecosystem.configId()), "its own config id must round-trip");
			assertEquals(ecosystem, Ecosystem.parse(ecosystem.familyId()), "and so must its family id");
		}
	}
}
