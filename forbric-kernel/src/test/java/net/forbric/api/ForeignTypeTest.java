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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pins the Forge-family name pairs, and above all the root asymmetry a prefix helper would get wrong. */
class ForeignTypeTest {

	/**
	 * THE reason this is a table and not a rule. NeoForge keeps what descends from FML under {@code net.neoforged.}
	 * and puts the mod-facing game API under {@code net.neoforged.neoforge.}; MinecraftForge has one root for both.
	 * Anything that "swaps the prefix" gets the second group wrong, and a wrong class name here does not throw --
	 * the transform simply never fires, which is the silent-failure shape this project keeps paying for.
	 */
	@Test
	void neoForgeSplitsAcrossTwoRootsAndTheTableKnowsWhich() {
		assertEquals("net.neoforged.fml.ModList", ForeignType.MOD_LIST.binary(Ecosystem.NEOFORGE));
		assertEquals("net.neoforged.neoforge.registries.GameData", ForeignType.GAME_DATA.binary(Ecosystem.NEOFORGE));

		assertEquals("net.minecraftforge.fml.ModList", ForeignType.MOD_LIST.binary(Ecosystem.FORGE));
		assertEquals("net.minecraftforge.registries.GameData", ForeignType.GAME_DATA.binary(Ecosystem.FORGE));

		assertFalse(ForeignType.MOD_LIST.binary(Ecosystem.NEOFORGE).startsWith("net.neoforged.neoforge."),
				"fml.* stays under net.neoforged. -- pushing it down a level is the mistake this test exists for");
	}

	@Test
	void internalNamesAreTheSlashFormAsmWants() {
		assertEquals("net/neoforged/neoforge/client/loading/ClientModLoader",
				ForeignType.CLIENT_MOD_LOADER.internal(Ecosystem.NEOFORGE));
		assertEquals("net/minecraftforge/client/loading/ClientModLoader",
				ForeignType.CLIENT_MOD_LOADER.internal(Ecosystem.FORGE));
	}

	@Test
	void fabricHasNoneOfThese() {
		for (ForeignType t : ForeignType.values()) {
			assertNull(t.binary(Ecosystem.FABRIC), t + " is Forge-family only");
			assertNull(t.internal(Ecosystem.FABRIC), t + " is Forge-family only");
		}
	}

	@Test
	void matchesAnswersForEitherFamilyAndNothingElse() {
		assertTrue(ForeignType.MOD_LIST.matches("net.neoforged.fml.ModList"));
		assertTrue(ForeignType.MOD_LIST.matches("net.minecraftforge.fml.ModList"));
		assertFalse(ForeignType.MOD_LIST.matches("net.neoforged.neoforge.fml.ModList"), "the wrong root is not a match");
		assertFalse(ForeignType.MOD_LIST.matches("net.fabricmc.loader.api.FabricLoader"));
	}

	/** Every row must name both families, or a call site that asks for one gets a null it will not check. */
	@Test
	void everyRowNamesBothForgeFamilies() {
		for (ForeignType type : ForeignType.values()) {
			for (Ecosystem eco : new Ecosystem[] {Ecosystem.FORGE, Ecosystem.NEOFORGE}) {
				String binary = type.binary(eco);
				assertNotNull(binary, type + " has no " + eco + " name");
				assertTrue(binary.startsWith(eco == Ecosystem.FORGE ? "net.minecraftforge." : "net.neoforged."),
						type + " " + eco + " name is in the wrong root: " + binary);
			}
		}
	}
}
