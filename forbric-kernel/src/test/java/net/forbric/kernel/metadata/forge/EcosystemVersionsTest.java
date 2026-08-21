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

package net.forbric.kernel.metadata.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EcosystemVersionsTest {
	@TempDir
	Path dir;

	@BeforeEach
	@AfterEach
	void forget() {
		EcosystemVersions.reset();
	}

	@Test
	void aCarriersOwnManifestIsWhereTheVersionComesFrom() throws Exception {
		EcosystemVersions.record(List.of(carrier("neoforge-runtime.jar", "META-INF/neoforge.mods.toml",
				"neoforge", "26.2.0.7-beta")));
		assertEquals("26.2.0.7-beta", EcosystemVersions.provided("neoforge"));
		assertNull(EcosystemVersions.provided("forge"));
	}

	/**
	 * The MinecraftForge carrier ships {@code version="${global.forgeVersion}"} — a placeholder its build never
	 * substituted. Recording it made the audit accuse an honest mod of requiring a newer Forge than we provide.
	 * An unresolved placeholder is an ABSENT version, not a low one.
	 */
	@Test
	void anUnresolvedPlaceholderIsNotAVersion() throws Exception {
		EcosystemVersions.record(List.of(carrier("forge-runtime.jar", "META-INF/mods.toml",
				"forge", "${global.forgeVersion}")));
		assertNull(EcosystemVersions.provided("forge"),
				"declining to judge beats judging confidently on a placeholder");
	}

	@Test
	void aBlankVersionIsAlsoDeclined() throws Exception {
		EcosystemVersions.record(List.of(carrier("neoforge-runtime.jar", "META-INF/neoforge.mods.toml",
				"neoforge", "   ")));
		assertNull(EcosystemVersions.provided("neoforge"));
	}

	@Test
	void onlyEcosystemIdsAreRecorded() throws Exception {
		EcosystemVersions.record(List.of(carrier("somemod.jar", "META-INF/neoforge.mods.toml",
				"justamod", "1.2.3")));
		assertNull(EcosystemVersions.provided("justamod"));
	}

	@Test
	void nothingToReadIsNotAnError() {
		EcosystemVersions.record(null);
		EcosystemVersions.record(List.of());
		EcosystemVersions.record(List.of(dir.resolve("absent.jar")));
		assertNull(EcosystemVersions.provided("neoforge"));
		// audit must be equally unbothered
		EcosystemVersions.audit(null, "nowhere");
	}

	/** The first carrier to claim an ecosystem owns it; a second must not silently redefine what we provide. */
	@Test
	void theFirstCarrierToClaimAnEcosystemWins() throws Exception {
		EcosystemVersions.record(List.of(
				carrier("a.jar", "META-INF/neoforge.mods.toml", "neoforge", "26.2.0.7-beta"),
				carrier("b.jar", "META-INF/neoforge.mods.toml", "neoforge", "1.0.0")));
		assertEquals("26.2.0.7-beta", EcosystemVersions.provided("neoforge"));
	}

	private Path carrier(String name, String manifestPath, String modId, String version) throws Exception {
		Path jar = dir.resolve(name);
		String toml = """
				modLoader="javafml"
				loaderVersion="[3,]"
				license="LGPL v2.1"

				[[mods]]
				    modId="%s"
				    version="%s"
				""".formatted(modId, version);
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new ZipEntry(manifestPath));
			OutputStream out = zip;
			out.write(toml.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return jar;
	}
}
