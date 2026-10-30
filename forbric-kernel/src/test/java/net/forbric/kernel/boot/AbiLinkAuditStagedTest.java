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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;

/**
 * The false-positive pin: over the staged 97-jar client pack, resolved against both carriers, the merged base and
 * the pack itself, the audit finds NOTHING. A real pack has CustomSkinLoader naming fml/loading and fml/relauncher,
 * universal jars naming both families, Fabric ports shipping net.minecraftforge.* for their dependants — every one
 * of those is a shape a naive audit cries wolf on.
 */
class AbiLinkAuditStagedTest {
	@Test
	void theStagedClientPackHasNoDanglingForgeFamilyReference() throws Exception {
		Path mods = Path.of(System.getProperty("user.dir"), "run", "client-merged-pack", "mods").normalize();
		Path run = TestFixtures.stagedRoot().normalize();
		List<Path> against = List.of(run.resolve(TestFixtures.FORGE_CARRIER_JAR), run.resolve(TestFixtures.NEO_CARRIER_JAR),
				run.resolve(TestFixtures.BASE_JAR));
		TestFixtures.requireFiles(Fixture.STAGED, "staged pack or carriers absent", against.toArray(Path[]::new));
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isDirectory(mods), "staged pack or carriers absent");
		List<Path> jars = new ArrayList<>();
		try (Stream<Path> list = Files.list(mods)) {
			list.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(jars::add);
		}
		// The pack is whatever this machine keeps in run/, not a pinned set: a small one is a different fixture.
		TestFixtures.require(Fixture.THIRD_PARTY, jars.size() >= 50, "the staged pack is not the real one");
		List<Path> universe = new ArrayList<>(against);
		universe.addAll(jars);
		List<AbiLinkAudit.Finding> findings = AbiLinkAudit.audit(jars, AbiLinkAudit.classesOf(universe));
		assertEquals(List.of(), findings, "measured 0 over the staged pack; a finding here is a false positive to scope out, "
				+ "or a real mod compiled against another Forge that has just been staged");
	}
}
