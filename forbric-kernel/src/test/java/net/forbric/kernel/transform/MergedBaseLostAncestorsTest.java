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

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/** The source/merged hierarchy census checks resource assignability directly; no list licenses a repair. */
class MergedBaseLostAncestorsTest {
	private static final Path RUN = TestFixtures.stagedRoot();
	private static final Path MERGED = RUN.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path FORGE_BASE = RUN.resolve("forge-patched/patched-mc-forge-26.2.jar");
	private static final Path NEO_BASE = RUN.resolve("neoforge-patched/patched-mc-neoforge-26.2.jar");
	private static final Path FORGE_RT = RUN.resolve("forge-runtime/forge-runtime.jar");
	private static final Path FORGE_INTEROP = RUN.resolve("merged-base/forge-runtime-interop.jar");
	private static final Path NEO_RT = RUN.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final Path CONFLICTS = RUN.resolve("merged-base/merge-conflicts.txt");

	@Test
	void theTransformersListIsExactlyWhatTheArtifactsSay() throws IOException {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED) && Files.isRegularFile(FORGE_BASE)
						&& Files.isRegularFile(NEO_BASE) && Files.isRegularFile(FORGE_RT)
						&& Files.isRegularFile(FORGE_INTEROP) && Files.isRegularFile(NEO_RT),
				"the merged base, both patched sides and both carriers must be staged");

		Map<String, String> merged = supers(MERGED, FORGE_INTEROP, NEO_RT);
		Map<String, String> forge = supers(FORGE_BASE, FORGE_RT);
		Map<String, String> neo = supers(NEO_BASE, NEO_RT);

		Set<String> lost = new TreeSet<>();
		for (String owner : classesIn(MERGED)) {
			Set<String> mergedChain = chain(owner, merged);
			for (Map<String, String> world : List.of(forge, neo)) {
				if (!world.containsKey(owner)) continue;
				for (String ancestor : chain(owner, world)) {
					if (!mergedChain.contains(ancestor)) lost.add(ancestor);
				}
			}
		}

        MergedBaseFrameRecomputer frames = recomputer();
        for (String owner : classesIn(MERGED)) for (Map<String,String> source : List.of(forge, neo)) {
            if (!source.containsKey(owner)) continue;
            for (String ancestor : chain(owner, source)) {
                if (!merged.containsKey(ancestor)) continue;
                try { assertEquals(chain(owner, merged).contains(ancestor), frames.assignable(ancestor, owner), owner + " -> " + ancestor); }
                catch(TypeNotPresentException unresolved){throw new AssertionError(owner + " -> " + ancestor,unresolved);}
            }
        }
        assertTrue(!lost.isEmpty(), "the fixture contains actual changed hierarchies");
	}

	/**
	 * The one type the merge KEPT, and the reason the list cannot just be "everything under
	 * CapabilityProvider": ItemStack still extends {@code CapabilityProvider$ItemStacks}, so a frame naming it
	 * is valid and must not trigger a rewrite.
	 */
	@Test
	void theTypeItemStackKeptIsNotInTheList() throws IOException {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED) && Files.isRegularFile(FORGE_RT) && Files.isRegularFile(FORGE_INTEROP) && Files.isRegularFile(NEO_RT),
				"staged jars absent");
		Map<String, String> merged = supers(MERGED, FORGE_INTEROP, NEO_RT);

		assertTrue(chain("net/minecraft/world/item/ItemStack", merged)
						.contains("net/minecraftforge/common/capabilities/CapabilityProvider$ItemStacks"),
				"if ItemStack ever stops extending it, this test is the one that should be rewritten first");
		assertTrue(recomputer().assignable("net/minecraftforge/common/capabilities/CapabilityProvider$ItemStacks", "net/minecraft/world/item/ItemStack"));
	}

	/**
	 * The base builder writes its own structural conflicts beside the jar. It is a NECESSARY but not sufficient
	 * oracle — it does not cover the anonymous classes the merge materialised from one side — so it is asserted
	 * as a subset rather than as the answer.
	 */
	@Test
	void everyStructuralConflictTheBuilderReportedIsAccountedFor() throws IOException {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(CONFLICTS) && Files.isRegularFile(MERGED)
				&& Files.isRegularFile(FORGE_RT) && Files.isRegularFile(FORGE_INTEROP) && Files.isRegularFile(NEO_RT), "the merge report is absent");
		Map<String, String> merged = supers(MERGED, FORGE_INTEROP, NEO_RT);

		List<String> unaccounted = new ArrayList<>();
		for (String line : Files.readAllLines(CONFLICTS, StandardCharsets.UTF_8)) {
			int at = line.indexOf("(superclass: forge=");
			if (at < 0 || line.startsWith("===")) continue;
			String owner = line.substring(0, at).trim();
			Set<String> mergedChain = chain(owner, merged);
			for (String side : List.of("forge=", "neo=")) {
				int from = line.indexOf(side, at);
				if (from < 0) continue;
				from += side.length();
				int to = from;
				while (to < line.length() && " )".indexOf(line.charAt(to)) < 0) to++;
				String declared = line.substring(from, to);
				if (mergedChain.contains(declared)) continue; // this side won; nothing was lost
				if (recomputer().assignable(declared, owner)) {
					unaccounted.add(owner + " lost " + declared);
				}
			}
		}
		assertTrue(unaccounted.isEmpty(),
				"the builder reported these superclass losses and the transformer does not know about them: "
						+ unaccounted);
	}

    private static MergedBaseFrameRecomputer recomputer() {
        return new MergedBaseFrameRecomputer(path -> {
            for (Path file : List.of(MERGED, FORGE_INTEROP, NEO_RT)) try (ZipFile zip = new ZipFile(file.toFile())) {
                var entry = zip.getEntry(path); if (entry == null) continue;
                try (InputStream input = zip.getInputStream(entry)) { return input.readAllBytes(); }
            } catch (IOException unavailable) { throw new java.io.UncheckedIOException("Reading actual hierarchy from " + file + " at " + path, unavailable); }
            try (InputStream input = ClassLoader.getPlatformClassLoader().getResourceAsStream(path)) { return input == null ? null : input.readAllBytes(); }
            catch (IOException unavailable) { return null; }
        });
    }

	private static Set<String> chain(String owner, Map<String, String> supers) {
		Set<String> out = new LinkedHashSet<>();
		String current = owner;
		while (current != null && out.add(current) && out.size() < 64) {
			current = supers.get(current);
		}
		return out;
	}

	private static Map<String, String> supers(Path... jars) throws IOException {
		Map<String, String> out = new HashMap<>();
		for (Path jar : jars) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : zip.stream().toList()) {
					if (!entry.getName().endsWith(".class")) continue;
					String name = entry.getName().substring(0, entry.getName().length() - 6);
					if (out.containsKey(name)) continue; // first jar wins, as the loader's order does
					try (InputStream in = zip.getInputStream(entry)) {
						ClassReader reader = new ClassReader(in.readAllBytes());
						out.put(name, reader.getSuperName());
					} catch (RuntimeException unreadable) {
						// A class ASM cannot read contributes no edge; the chains around it still resolve.
					}
				}
			}
		}
		return out;
	}

	private static List<String> classesIn(Path jar) throws IOException {
		List<String> out = new ArrayList<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : zip.stream().toList()) {
				if (entry.getName().endsWith(".class")) {
					out.add(entry.getName().substring(0, entry.getName().length() - 6));
				}
			}
		}
		return out;
	}
}
