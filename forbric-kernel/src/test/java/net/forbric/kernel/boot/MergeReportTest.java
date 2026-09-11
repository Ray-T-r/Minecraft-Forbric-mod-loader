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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the merge report — the file a player actually reads when a mod seems to be missing.
 *
 * <p>It had no test, and it is the kind of code that cannot fail loudly: every path is best-effort by design, so a
 * report that named the WRONG jar as the one running would be written, logged as success, and believed. Its whole
 * job is to tell a person something they have no other way to find out, which means a wrong report is worse than
 * no report — it sends them to change the one thing that was already right.
 */
class MergeReportTest {
	@Test
	void theRunningCopyAndTheSupersededOneAreNotSwapped(@TempDir Path dir) throws Exception {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		Path fabricSodium = fabricJar(mods, "sodium-fabric.jar", "sodium", "0.6.0");
		Path neoSodium = neoJar(mods, "sodium-neoforge.jar", "sodium", "0.6.0");

		MergeReport.write(dir, mods, decision(neoSodium, Map.of("sodium", neoSodium)));

		String report = report(dir);
		assertEquals(List.of("sodium-neoforge.jar"), after(report, "using:"),
				"the winner the arbiter chose must be the one shown as running:\n" + report);
		assertEquals(List.of("sodium-fabric.jar"), after(report, "not used:"),
				"the other copy must be shown as not running, and the winner must not ALSO appear there — a report "
						+ "that lists a jar as both is worse than one that lists neither:\n" + report);
		assertTrue(report.contains(DuplicateModArbiter.OVERRIDE_FILE),
				"a report that does not say how to change the decision explains nothing actionable");
		assertTrue(fabricSodium.getFileName().toString().endsWith(".jar"));
	}

	/**
	 * Who depends on the duplicated mod is the part a player cannot work out for themselves, and it has to look
	 * across ecosystems: the mod that needs the duplicated one is frequently on the OTHER side.
	 */
	@Test
	void dependentsAreFoundAcrossEcosystems(@TempDir Path dir) throws Exception {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		Path fabricSodium = fabricJar(mods, "sodium-fabric.jar", "sodium", "0.6.0");
		neoJar(mods, "sodium-neoforge.jar", "sodium", "0.6.0");
		neoJar(mods, "neo-dependent.jar", "shaderthing", "1.0.0", "sodium");
		fabricJar(mods, "fabric-dependent.jar", "iris", "1.8.0", "sodium");

		MergeReport.write(dir, mods, decision(fabricSodium, Map.of("sodium", fabricSodium)));

		String report = report(dir);
		String dependents = lineAfter(report, "depended on by:");
		assertTrue(dependents.contains("shaderthing"), "the NeoForge dependent is missing:\n" + report);
		assertTrue(dependents.contains("iris"), "the Fabric dependent is missing:\n" + report);
	}

	@Test
	void anInstanceWithNoDuplicatesGetsNoFile(@TempDir Path dir) throws Exception {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		fabricJar(mods, "solo.jar", "solo", "1.0.0");

		MergeReport.write(dir, mods, decision(null, Map.of()));

		assertFalse(Files.exists(dir.resolve(".forbric-kernel").resolve("merge-report.txt")),
				"one modpack has nothing to explain, and a file that appears anyway trains people to ignore it");
	}

	/**
	 * The report is the one place in the tree whose language follows the system's, so the switch is real behaviour
	 * and not decoration — and the override template it points at has to switch with it, or the file tells a
	 * Chinese reader to look at instructions they cannot follow.
	 */
	@Test
	void theTextFollowsTheSystemLanguage(@TempDir Path dir) throws Exception {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		Path winner = neoJar(mods, "sodium-neoforge.jar", "sodium", "0.6.0");
		fabricJar(mods, "sodium-fabric.jar", "sodium", "0.6.0");

		Locale original = Locale.getDefault();
		try {
			Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
			MergeReport.write(dir, mods, decision(winner, Map.of("sodium", winner)));
			String zh = report(dir);
			assertTrue(zh.contains("Forbric 合并报告"), zh);
			assertTrue(String.join("\n", MergeReport.overrideTemplateHeader()).contains("每个 mod 只会启用一份"));

			Locale.setDefault(Locale.ENGLISH);
			MergeReport.write(dir, mods, decision(winner, Map.of("sodium", winner)));
			String en = report(dir);
			assertTrue(en.contains("Forbric merge report"), en);
			assertTrue(String.join("\n", MergeReport.overrideTemplateHeader()).contains("installed twice"));
		} finally {
			Locale.setDefault(original);
		}
	}

	// --- helpers -----------------------------------------------------------------------------------------------

	private static DuplicateModArbiter.Decision decision(Path winner, Map<String, Path> owners) {
		return new DuplicateModArbiter.Decision(winner == null ? Set.of() : Set.of(), owners, List.of());
	}

	private static String report(Path dir) throws IOException {
		return Files.readString(dir.resolve(".forbric-kernel").resolve("merge-report.txt"), StandardCharsets.UTF_8);
	}

	/** Every jar name that appears after {@code marker}, in report order. */
	private static List<String> after(String report, String marker) {
		List<String> found = new ArrayList<>();
		for (String line : report.split("\n")) {
			int at = line.indexOf(marker);
			if (at >= 0) found.add(line.substring(at + marker.length()).trim().replaceAll("\\s*\\(.*\\)$", ""));
		}
		return found;
	}

	/** The remainder of the first line containing {@code marker} — where the report puts the jar name. */
	private static String lineAfter(String report, String marker) {
		List<String> found = after(report, marker);
		return found.isEmpty() ? "" : found.get(0);
	}

	private static Path fabricJar(Path mods, String name, String id, String version, String... depends)
			throws IOException {
		StringBuilder json = new StringBuilder("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\""
				+ version + "\"");
		if (depends.length > 0) {
			json.append(",\"depends\":{");
			for (int i = 0; i < depends.length; i++) {
				if (i > 0) json.append(',');
				json.append('"').append(depends[i]).append("\":\"*\"");
			}
			json.append('}');
		}
		json.append('}');
		return jar(mods, name, "fabric.mod.json", json.toString());
	}

	private static Path neoJar(Path mods, String name, String id, String version, String... depends)
			throws IOException {
		StringBuilder toml = new StringBuilder("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n"
				+ "[[mods]]\nmodId=\"" + id + "\"\nversion=\"" + version + "\"\n");
		for (String dep : depends) {
			toml.append("[[dependencies.").append(id).append("]]\nmodId=\"").append(dep)
					.append("\"\nmandatory=true\nversionRange=\"[1,)\"\n");
		}
		return jar(mods, name, "META-INF/neoforge.mods.toml", toml.toString());
	}

	private static Path jar(Path mods, String name, String entry, String content) throws IOException {
		Path jar = mods.resolve(name);
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new ZipEntry(entry));
			zip.write(content.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return jar;
	}
}
