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
		assertEquals("FORGE", Ecosystem.FORGE.name());
		assertEquals("NEOFORGE", Ecosystem.NEOFORGE.name());
	}

	/**
	 * The config id is NOT the constant name for traditional Forge. The player-facing override file has always
	 * spelled it {@code minecraftforge}, and that file is the player's — the kernel renamed its constant, not
	 * their config. Reading it with {@code valueOf} instead of {@link Ecosystem#parse} is a bug that has already
	 * happened once.
	 */
	@Test
	void theConfigIdKeepsThePlayersSpellingForTraditionalForge() {
		assertEquals("minecraftforge", Ecosystem.FORGE.configId());
		assertEquals("neoforge", Ecosystem.NEOFORGE.configId());
		assertEquals("fabric", Ecosystem.FABRIC.configId());

		assertEquals(Ecosystem.FORGE, Ecosystem.parse("minecraftforge"));
		assertEquals(Ecosystem.FORGE, Ecosystem.parse("forge"));
		assertEquals(Ecosystem.FORGE, Ecosystem.parse("  MinecraftForge "));
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
		assertTrue(Ecosystem.FORGE.isForgeFamily());
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
