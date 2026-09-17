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

package net.forbric.kernel.classloading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;

/**
 * Covers which loader a guest class is allowed to see.
 *
 * <p>The two Forge families were grouped under one constant, so a NeoForge-only mod probing for TRADITIONAL
 * MinecraftForge's loader class was told it exists. On a real NeoForge instance it does not, and that probe is
 * exactly how a mod decides which family's branch to take — so the grouping sent it down the other family's.
 */
class LoaderProbePolicyTest {
	private static final String FORGE_MARKER = ForeignType.FML_LOADER.binary(Ecosystem.FORGE);
	private static final String NEO_MARKER = ForeignType.FML_LOADER.binary(Ecosystem.NEOFORGE);

	@Test
	void theTwoForgeFamiliesHaveDifferentMarkerClasses() {
		// The premise of the whole fix. If a future carrier ever made these the same string, grouping them would
		// be right again and this test is where that shows up.
		assertTrue(!FORGE_MARKER.equals(NEO_MARKER), FORGE_MARKER + " and " + NEO_MARKER + " must differ");
		assertTrue(LoaderProbePolicy.isProbe(FORGE_MARKER));
		assertTrue(LoaderProbePolicy.isProbe(NEO_MARKER));
	}

	@Test
	void eachEcosystemMapsToItsOwnFamily() {
		assertEquals(LoaderProbePolicy.Family.FABRIC, LoaderProbePolicy.familyOf(Ecosystem.FABRIC));
		assertEquals(LoaderProbePolicy.Family.FORGE, LoaderProbePolicy.familyOf(Ecosystem.FORGE));
		assertEquals(LoaderProbePolicy.Family.NEOFORGE, LoaderProbePolicy.familyOf(Ecosystem.NEOFORGE));
		assertNull(LoaderProbePolicy.familyOf(null), "an unowned jar has no family and probes as before");
	}

	@Test
	void aNeoForgeClassIsToldTraditionalForgeIsAbsent() {
		assumeTrue(LoaderProbePolicy.enabled(), "probe policy disabled by system property");

		assertThrows(ClassNotFoundException.class, () -> LoaderProbePolicy.forName(
				FORGE_MARKER, false, LoaderProbePolicyTest.class.getClassLoader(),
				LoaderProbePolicy.Family.NEOFORGE.name()),
				"this is the probe the grouping used to answer yes to");
	}

	@Test
	void aTraditionalForgeClassIsToldNeoForgeIsAbsent() {
		assumeTrue(LoaderProbePolicy.enabled(), "probe policy disabled by system property");

		assertThrows(ClassNotFoundException.class, () -> LoaderProbePolicy.forName(
				NEO_MARKER, false, LoaderProbePolicyTest.class.getClassLoader(),
				LoaderProbePolicy.Family.FORGE.name()));
	}

	@Test
	void aFabricClassIsToldBothForgeLoadersAreAbsent() {
		assumeTrue(LoaderProbePolicy.enabled(), "probe policy disabled by system property");
		ClassLoader here = LoaderProbePolicyTest.class.getClassLoader();
		String fabric = LoaderProbePolicy.Family.FABRIC.name();

		assertThrows(ClassNotFoundException.class,
				() -> LoaderProbePolicy.forName(FORGE_MARKER, false, here, fabric));
		assertThrows(ClassNotFoundException.class,
				() -> LoaderProbePolicy.forName(NEO_MARKER, false, here, fabric));
	}

	@Test
	void aClassThatIsNotAProbeIsNeverHidden() {
		// The policy covers the canonical markers only. Anything else must resolve normally, or a mod that
		// genuinely links against a foreign type degrades into a link error instead of working.
		assertTrue(!LoaderProbePolicy.isProbe("net.neoforged.fml.ModList"));
		assertTrue(!LoaderProbePolicy.isProbe("java.lang.String"));

		assertEquals(String.class, assertDoesNotThrowClass(
				"java.lang.String", LoaderProbePolicy.Family.FORGE.name()));
	}

	@Test
	void anUnownedJarSeesEveryProbeAsBefore() {
		// A universal jar carrying more than one manifest has no family; its probes are how it works out which
		// half of itself to run, and hiding either answer would break it. Its asking family is null.
		assertThrows(ClassNotFoundException.class, () -> LoaderProbePolicy.forName(
				"net.this.does.not.exist", false, LoaderProbePolicyTest.class.getClassLoader(), null),
				"a genuinely absent class still reports absent");
	}

	private static Class<?> assertDoesNotThrowClass(String name, String askingFamily) {
		try {
			return LoaderProbePolicy.forName(name, false, LoaderProbePolicyTest.class.getClassLoader(), askingFamily);
		} catch (ClassNotFoundException e) {
			throw new AssertionError(name + " must resolve normally", e);
		}
	}
}
