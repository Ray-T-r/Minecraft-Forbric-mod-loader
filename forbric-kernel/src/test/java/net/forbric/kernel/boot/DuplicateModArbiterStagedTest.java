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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** The Alias javadoc's measurement, pinned on the staged pair: Jade's Fabric build carries 20 classes its NeoForge build lacks, and 8 the other way. */
class DuplicateModArbiterStagedTest {
	@Test
	void theStagedJadePairReproducesTheJavadocsNumbers() throws Exception {
		Path mods = Path.of(System.getProperty("user.dir"), "run", "client-merged-pack", "mods").normalize();
		assumeTrue(Files.isDirectory(mods), "staged pack absent");
		Path fabric = null, neo = null;
		try (Stream<Path> list = Files.list(mods)) {
			for (Path p : list.toList()) {
				String name = p.getFileName().toString();
				if (name.contains("Jade") && name.contains("Fabric")) fabric = p;
				if (name.contains("Jade") && name.contains("NeoForge")) neo = p;
			}
		}
		assumeTrue(fabric != null && neo != null, "both Jade builds are not staged");
		List<String> fabricOnly = DuplicateModArbiter.loserOnlyClasses(fabric, neo);
		List<String> neoOnly = DuplicateModArbiter.loserOnlyClasses(neo, fabric);
		assertEquals(20, fabricOnly.size(), "Fabric-only: " + fabricOnly);
		assertEquals(8, neoOnly.size(), "NeoForge-only: " + neoOnly);
	}
}
