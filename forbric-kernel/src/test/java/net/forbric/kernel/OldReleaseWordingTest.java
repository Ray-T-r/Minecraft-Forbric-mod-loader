/*
 * Copyright 2026 The Forbric Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package net.forbric.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Nothing in the kernel or the installer says that the Forbric which carried MinecraftForge was "0.4 or earlier".
 *
 * <p>The owner chose 0.4.x as the first release line without MinecraftForge (2026-11-17). The merged base (NeoForge's
 * and MinecraftForge's patches in one jar) and the MinecraftForge carrier came from every release up to v0.3.1-beta2 and
 * from the 0.4.0-dev builds before the removal, so "Forbric 0.4 or earlier", "Forbric &le; 0.4" or "as Forbric 0.4 did"
 * would tell a 0.4.x player that their own version is the old one -- worst in the old-install refusal, which a 0.4.x
 * player only ever sees about an older install. Those texts now say "0.3.x or earlier" (with the 0.4.0-dev builds named
 * where a player reads it); this keeps a new one from coming back, in a refusal, a log line or a comment.
 *
 * <p>Read as text, with a line break and the comment marker or string concatenation after it joined to one space, so
 * a phrase split across two lines of a comment or two string literals is still one phrase. Scanned: the kernel's
 * {@code src/main}, {@code src/runtime} and {@code src/test} Java, its gate and helper scripts ({@code run/*.sh},
 * {@code run/compat} Python and shell, not the dated records under {@code run/compat/reports}, which describe what was
 * true when they were written), and the installer's Java. This file spells the phrases, so it is not scanned.
 */
class OldReleaseWordingTest {
	/** The phrases, each naming 0.4 as the old, MinecraftForge-carrying line. */
	static final List<Pattern> OLD_CLAIMS = List.of(
			Pattern.compile("Forbric\\s+0\\.4(?![.\\d])\\s+(?:and|or)\\s+(?:earlier|older)"),
			Pattern.compile("Forbric\\s+(?:&le;|<=|\u2264)\\s*0\\.4(?![.\\d])"),
			Pattern.compile("(?:as|like)\\s+Forbric\\s+0\\.4(?![.\\d])\\s+did"),
			Pattern.compile("0\\.4(?![.\\d])\\s*(?:\u53ca|\u6216)(?:\u66f4\u65e9|\u4ee5\u524d|\u4e4b\u524d)"));

	/**
	 * What splits one phrase in source: the end of one string literal, a {@code +} and the start of the next (joined to
	 * nothing, the space is inside the literals), or a line break with the indentation and comment marker after it
	 * (joined to one space).
	 */
	private static final Pattern JOIN = Pattern.compile("\"\\s*\\+\\s*\"|[ \\t]*\\r?\\n[ \\t]*(?://|\\*|#)?[ \\t]*");

	private static final Path KERNEL = Path.of(".");
	private static final Path INSTALLER = Path.of("../forbric-kernel-installer");

	@Test
	void noTextCallsTheMinecraftForgeLineForbric04OrEarlier() throws IOException {
		List<Path> files = scanned();
		assertTrue(files.size() > 1000, "the scan found only " + files.size() + " files: wrong working directory?");
		List<String> found = new ArrayList<>();
		for (Path file : files) found.addAll(claims(file, Files.readString(file, StandardCharsets.UTF_8)));
		assertEquals(List.of(), found, "texts that call the MinecraftForge-carrying Forbric \"0.4 or earlier\"; since 0.4.x "
				+ "is the first release without MinecraftForge, say \"0.3.x or earlier\" (and, where a player reads it, "
				+ "\"or a 0.4.0-dev build that still had MinecraftForge\")");
	}

	/** The checker itself: every phrase it must catch, split as source splits them, and the true ones it must not. */
	@Test
	void theCheckCatchesEachPhraseAndOnlyThose() {
		for (String old : List.of(
				"\"this game was installed by Forbric 0.4 or earlier, still carries MinecraftForge \"",
				"\"does not apply to an install made by Forbric 0.4 or \"\n\t\t\t\t\t+ \"earlier: its game carries\"",
				"\"the merged game base Forbric 0.4 or \" + \"earlier built\"",
				"// the merged base of Forbric\n\t\t// 0.4 and earlier, MinecraftForge's half",
				" * hard-references invisible. (The merged base of Forbric 0.4\n * and earlier named the old owners",
				" * {@code MergedBaseAnonymousDriftTest}; the merged base of Forbric &le; 0.4 gave the same",
				"# it is the id a world written by Forbric <= 0.4 already holds",
				"\"skipped (-D%s=off keeps it, as Forbric 0.4 did)\"",
				"\"\u5b83\u662f Forbric 0.4 \u6216\u66f4\u65e9\u7248\u672c\u751f\u6210\u7684\"")) {
			assertEquals(1, claims(Path.of("sample"), old).size(), "not caught: " + old);
		}
		for (String fine : List.of(
				"\"this game was installed by Forbric 0.3.x or earlier (or a 0.4.0-dev build that still had MinecraftForge)\"",
				"// the merged base of Forbric 0.3.x and earlier",
				"\"as Forbric 0.3.x did\"", "Fabric Loader's pre-0.4 entry point", "C2ME 0.4.x", "sized(0.4F, 0.6F)",
				"Forbric 0.4.0 is the first release without MinecraftForge", "0.4.0 \u4e4b\u524d\u7684 Forbric")) {
			assertEquals(List.of(), claims(Path.of("sample"), fine), "a true statement was flagged: " + fine);
		}
	}

	/** Each old claim in {@code text}, as {@code file:line: phrase}. */
	static List<String> claims(Path file, String text) {
		StringBuilder joined = new StringBuilder(text.length());
		List<Integer> origin = new ArrayList<>(text.length());
		Matcher join = JOIN.matcher(text);
		int at = 0;
		while (join.find()) {
			for (int i = at; i < join.start(); i++) {
				joined.append(text.charAt(i));
				origin.add(i);
			}
			if (text.charAt(join.start()) != '"') {
				joined.append(' ');
				origin.add(join.start());
			}
			at = join.end();
		}
		for (int i = at; i < text.length(); i++) {
			joined.append(text.charAt(i));
			origin.add(i);
		}
		List<String> found = new ArrayList<>();
		for (Pattern claim : OLD_CLAIMS) {
			Matcher m = claim.matcher(joined);
			while (m.find()) {
				int start = origin.get(m.start());
				long line = text.substring(0, start).chars().filter(c -> c == '\n').count() + 1;
				found.add(file + ":" + line + ": " + m.group());
			}
		}
		return found;
	}

	private static List<Path> scanned() throws IOException {
		List<Path> files = new ArrayList<>();
		for (Path root : List.of(KERNEL.resolve("src/main/java"), KERNEL.resolve("src/runtime/java"),
				KERNEL.resolve("src/test/java"), INSTALLER.resolve("src/main/java"), INSTALLER.resolve("src/test/java"))) {
			assertTrue(Files.isDirectory(root), "missing source root " + root.toAbsolutePath().normalize());
			try (Stream<Path> walk = Files.walk(root)) {
				walk.filter(p -> p.toString().endsWith(".java"))
						.filter(p -> !p.getFileName().toString().equals("OldReleaseWordingTest.java"))
						.forEach(files::add);
			}
		}
		for (Path dir : List.of(KERNEL.resolve("run"), KERNEL.resolve("run/compat"), KERNEL.resolve("run/compat/mac"),
				KERNEL.resolve("run/compat/win"))) {
			if (!Files.isDirectory(dir)) continue;
			try (Stream<Path> list = Files.list(dir)) {
				list.filter(Files::isRegularFile)
						.filter(p -> p.toString().endsWith(".sh") || p.toString().endsWith(".py"))
						.forEach(files::add);
			}
		}
		return files;
	}
}
