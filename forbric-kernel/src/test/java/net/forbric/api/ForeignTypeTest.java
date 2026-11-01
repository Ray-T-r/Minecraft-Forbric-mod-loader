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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

		assertFalse(ForeignType.MOD_LIST.binary(Ecosystem.NEOFORGE).startsWith("net.neoforged.neoforge."),
				"fml.* stays under net.neoforged. -- pushing it down a level is the mistake this test exists for");
	}

	@Test
	void internalNamesAreTheSlashFormAsmWants() {
		assertEquals("net/neoforged/neoforge/client/loading/ClientModLoader",
				ForeignType.CLIENT_MOD_LOADER.internal(Ecosystem.NEOFORGE));
	}

	@Test
	void fabricHasNoneOfThese() {
		for (ForeignType t : ForeignType.values()) {
			assertNull(t.binary(Ecosystem.FABRIC), t + " is Forge-family only");
			assertNull(t.internal(Ecosystem.FABRIC), t + " is Forge-family only");
		}
	}

	/** NeoForge's name and nothing else: MinecraftForge's column went with {@code Ecosystem.FORGE}. */
	@Test
	void matchesAnswersForNeoForgesNameAndNothingElse() {
		assertTrue(ForeignType.MOD_LIST.matches("net.neoforged.fml.ModList"));
		assertFalse(ForeignType.MOD_LIST.matches("net.minecraftforge.fml.ModList"), "MinecraftForge is no ecosystem");
		assertFalse(ForeignType.MOD_LIST.matches("net.neoforged.neoforge.fml.ModList"), "the wrong root is not a match");
		assertFalse(ForeignType.MOD_LIST.matches("net.fabricmc.loader.api.FabricLoader"));
	}

	/** Every row must name NeoForge's class, or a call site that asks for it gets a null it will not check. */
	@Test
	void everyRowNamesNeoForgesClass() {
		for (ForeignType type : ForeignType.values()) {
			String binary = type.binary(Ecosystem.NEOFORGE);
			assertNotNull(binary, type + " has no NeoForge name");
			assertTrue(binary.startsWith("net.neoforged."), type + " NeoForge name is in the wrong root: " + binary);
		}
	}

	/**
	 * The table only helps while the codebase actually uses it, and the first version of it did not: it shipped
	 * with five rows while twenty-one more pairs stayed written out inline, in twelve files that named BOTH
	 * families a few lines apart. Nothing said so. A table that silently falls behind the code is worse than no
	 * table, because it reads like a guarantee.
	 *
	 * <p>So this walks the kernel's own source and fails on any concept written out under both families outside
	 * this enum. It is deliberately about PAIRS, not about every foreign name: a site that only ever names one
	 * family has no second half to forget, and forcing it through a two-column table would be ceremony. The
	 * hazard being guarded is specifically "handled one, forgot the other".
	 *
	 * <p>Two families' names count as the same concept when the SIMPLE name matches, not when the package path
	 * after the root matches. The first version compared paths and therefore saw nothing wrong with three real
	 * pairs: {@code forgespi.language.IModInfo} against {@code neoforgespi.language.IModInfo} (a THIRD NeoForge
	 * root the table did not know about), the same for {@code IConfigurable}, and
	 * {@code network.NetworkRegistry} against {@code network.registration.NetworkRegistry}, which do not even
	 * agree on the sub-package. Matching on the simple name is strictly more sensitive, so it cannot miss what
	 * path-matching caught. It can in principle pair two unrelated classes that happen to share a simple name;
	 * the failure prints both fully-qualified names so that is obvious, and the answer then is an explicit
	 * exclusion with a reason, not a looser rule.
	 */
	@Test
	void noConceptIsStillWrittenOutUnderBothFamiliesOutsideThisEnum() throws Exception {
		Path kernel = Path.of(System.getProperty("user.dir"), "src", "main", "java", "net", "forbric", "kernel");
		assertTrue(Files.isDirectory(kernel), "the kernel sources are part of this checkout: " + kernel);

		Pattern literal = Pattern.compile("\"(net[./](?:minecraftforge|neoforged)[A-Za-z0-9_./$]*)\"");
		Map<String, Map<Boolean, String>> byTail = new TreeMap<>();
		Map<String, Set<String>> where = new TreeMap<>();

		try (Stream<Path> files = Files.walk(kernel)) {
			for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				Matcher m = literal.matcher(codeOnly(Files.readString(f)));
				while (m.find()) {
					String dotted = m.group(1).replace('/', '.');
					// Package PREFIXES are not class names. DelegationPolicy pins "net.neoforged." and
					// CommonNetworkInteropInjector matches on "net/neoforged/"; neither names a concept that
					// could have a forgotten other half, and a two-column table has nothing to offer them.
					if (dotted.endsWith(".") || !Character.isUpperCase(lastSegment(dotted).charAt(0))) continue;
					// MinecraftForge's name or NeoForge's: a concept written under both is a pair, whichever loads.
					boolean minecraftForge = dotted.startsWith("net.minecraftforge.");
					String tail = lastSegment(dotted);
					byTail.computeIfAbsent(tail, k -> new TreeMap<>()).put(minecraftForge, dotted);
					where.computeIfAbsent(tail, k -> new TreeSet<>()).add(f.getFileName().toString());
				}
			}
		}

		List<String> inlinePairs = byTail.entrySet().stream()
				.filter(e -> e.getValue().size() == 2)
				.map(e -> "  " + e.getKey() + ": " + String.join(" / ", e.getValue().values())
						+ "  (" + String.join(", ", where.get(e.getKey())) + ")")
				.toList();

		assertTrue(inlinePairs.isEmpty(),
				"these concepts are written out under BOTH Forge families inline, so a change that updates one "
						+ "half and misses the other compiles and passes every test. Give each a ForeignType row:\n"
						+ String.join("\n", inlinePairs));
	}

	/**
	 * The file with comments blanked out.
	 *
	 * <p>Needed because javadoc legitimately quotes these names — {@code LoaderProbePolicy} shows the
	 * {@code doesClassExist("net.neoforged…")} branch a MOD writes, which is the thing being explained, not the
	 * kernel naming a pair. Scanning prose made this test report its own documentation as a defect.
	 *
	 * <p>A real scan rather than a regex: it has to know it is inside a string literal, or the {@code //} in a
	 * {@code "https://…"} would start a comment and swallow the rest of the line.
	 */
	private static String codeOnly(String java) {
		StringBuilder out = new StringBuilder(java.length());
		boolean inString = false, inChar = false, inLine = false, inBlock = false, escaped = false;
		for (int i = 0; i < java.length(); i++) {
			char c = java.charAt(i);
			char next = i + 1 < java.length() ? java.charAt(i + 1) : '\0';
			if (inLine) {
				if (c == '\n') { inLine = false; out.append(c); }
				continue;
			}
			if (inBlock) {
				if (c == '*' && next == '/') { inBlock = false; i++; }
				else if (c == '\n') out.append(c);
				continue;
			}
			if (inString || inChar) {
				out.append(c);
				if (escaped) escaped = false;
				else if (c == '\\') escaped = true;
				else if (inString && c == '"') inString = false;
				else if (inChar && c == '\'') inChar = false;
				continue;
			}
			if (c == '/' && next == '/') { inLine = true; continue; }
			if (c == '/' && next == '*') { inBlock = true; i++; continue; }
			if (c == '"') inString = true;
			if (c == '\'') inChar = true;
			out.append(c);
		}
		return out.toString();
	}

	private static String lastSegment(String binaryName) {
		int dot = binaryName.lastIndexOf('.');
		String last = dot < 0 ? binaryName : binaryName.substring(dot + 1);
		return last.isEmpty() ? "x" : last;
	}
}
