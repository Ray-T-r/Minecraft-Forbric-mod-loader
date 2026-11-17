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
 * <p>Read as text, with a line break and the comment marker after it joined to one space, and the seams between string
 * literals joined to nothing (Java's {@code " + "}, adjacent literals as Python and shell write them, and the
 * {@code ", "} between {@code String.join} or list arguments), so a phrase split across two lines of a comment or two
 * string literals is still one phrase. Case, a {@code v} before the version, "versions"/"版本", hyphens and a
 * parenthesis between the words do not hide it either. Not read: a phrase assembled at run time from variables, or one
 * that neither names "Forbric" nor uses the Chinese "0.4 及/或更早" form. Scanned: the kernel's
 * {@code src/main}, {@code src/runtime} and {@code src/test} Java, its gate and helper scripts ({@code run/*.sh},
 * {@code run/compat} Python and shell, not the dated records under {@code run/compat/reports}, which describe what was
 * true when they were written), and the installer's Java. This file spells the phrases, so it is not scanned.
 */
class OldReleaseWordingTest {
	/**
	 * The version the phrases name: 0.4, 0.4.x or 0.4.0, with or without a {@code v}, but not a longer version (0.4.1,
	 * 0.4F; a full stop ending the sentence is fine) and not a pre-release build of it (0.4.0-dev names exactly the builds that did still carry MinecraftForge).
	 */
	private static final String V04 = "v?0\\.4(?:\\.x|\\.0)?(?!\\.?\\w)(?!-(?:dev|alpha|beta|rc|pre|snapshot)\\b)";

	/** Between two words of a phrase: spaces, hyphens, an opening parenthesis, or nothing when literals were joined. */
	private static final String GAP = "[\\s\\-(]*";

	/** The phrases, each naming 0.4 as the old, MinecraftForge-carrying line. */
	static final List<Pattern> OLD_CLAIMS = List.of(
			Pattern.compile("(?i)Forbric[\\s-]+(?:versions?[\\s-]+|\u7248\u672c\\s*)?" + V04 + GAP + "(?:and|or)" + GAP
					+ "(?:earlier|older|before|below|lower|prior)"),
			Pattern.compile("(?i)Forbric[\\s-]+(?:versions?[\\s-]+)?(?:up[\\s-]+to|through)[\\s-]+" + V04),
			Pattern.compile("(?i)Forbric[\\s-]*(?:&le;|<=|\u2264)\\s*" + V04),
			Pattern.compile("(?i)(?:as|like)\\s+Forbric\\s+" + V04 + "\\s+did"),
			Pattern.compile(V04 + "\\s*(?:\u7248\u672c)?\\s*(?:\u53ca|\u6216)\\s*(?:\u66f4\u65e9|\u4ee5\u524d|\u4e4b\u524d)"));

	/**
	 * What splits one phrase in source: the end of one string literal and the start of the next, in double or single
	 * quotes, with a {@code +} or only white space (line breaks included) between them -- joined to nothing, the space
	 * is inside the literals -- or with a {@code ,} between them ({@code String.join} or list arguments, joined to one
	 * space); and a line break with the indentation and comment marker after it (joined to one space).
	 */
	private static final Pattern JOIN = Pattern.compile(
			"\"\\s*[+,]?\\s*\"|'\\s*[+,]?\\s*'|[ \\t]*\\r?\\n[ \\t]*(?://|\\*|#)?[ \\t]*");

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
				"\"\u5b83\u662f Forbric 0.4 \u6216\u66f4\u65e9\u7248\u672c\u751f\u6210\u7684\"",
				// The variants the first version of this check missed (review of lane VER, F2).
				"// installed by Forbric v0.4 or earlier", "installed by forbric 0.4 or earlier",
				"FORBRIC 0.4 AND OLDER", "the base of Forbric 0.4 and before", "the merged base of Forbric up to 0.4.",
				"Forbric versions 0.4 and earlier", "Forbric version 0.4 or older", "Forbric through 0.4.x",
				"\"Forbric 0.4 \u7248\u672c\u53ca\u66f4\u65e9\"", "Forbric 0.4 (and earlier)", "Forbric-0.4-or-earlier",
				"Forbric 0.4.x or earlier", "Forbric 0.4.0 and below", "Forbric \u2264 v0.4",
				"String.join(\" \", \"installed by Forbric 0.4\", \"or earlier\")",
				"List.of(\"installed by Forbric 0.4 or \", \"earlier\")",
				"msg = ('installed by Forbric 0.4 or '\n        'earlier')",
				"msg = (\"installed by Forbric 0.4 or \"\n        \"earlier\")",
				"echo 'installed by Forbric 0.4 or' 'earlier'",
				"// as forbric v0.4 did")) {
			assertEquals(1, claims(Path.of("sample"), old).size(), "not caught: " + old);
		}
		for (String fine : List.of(
				"\"this game was installed by Forbric 0.3.x or earlier (or a 0.4.0-dev build that still had MinecraftForge)\"",
				"// the merged base of Forbric 0.3.x and earlier",
				"\"as Forbric 0.3.x did\"", "Fabric Loader's pre-0.4 entry point", "C2ME 0.4.x", "sized(0.4F, 0.6F)",
				"Forbric 0.4.0 is the first release without MinecraftForge", "0.4.0 \u4e4b\u524d\u7684 Forbric",
				"Forbric 0.4 and later", "Forbric 0.4.x or newer", "C2ME 0.4 or earlier",
				"forbric-v0.4.0-dev", "Forbric 0.3.x or earlier (or a 0.4.0-dev build)", "\"Forbric 0.4\", \"is new\"",
				"Forbric 0.4 \u4ee5\u540e", "Forbric 0.4 \u53ca\u4ee5\u540e")) {
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
			char first = text.charAt(join.start());
			if ((first != '"' && first != '\'') || join.group().indexOf(',') >= 0) {
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
