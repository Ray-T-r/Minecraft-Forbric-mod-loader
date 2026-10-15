/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.json.JsonFormat;

/**
 * A mixin config read the way Mixin reads it. Mixin parses a config with Gson's {@code fromJson}, which reads leniently,
 * so a comment is part of the format a mod may ship: Essential's {@code mixins.essential.json} carries its
 * multi-version preprocessor's {@code //#if MC>=11700} lines, and Mixin applies every mixin in it. The kernel read the
 * same bytes with a strict JSON parser, failed on the first {@code /}, and every reader of the config here gave up in
 * silence: no ecosystem was noted for its mixins (so nothing that reads the native game of the mod's own platform could
 * answer for them — the lambda shim, the call-occurrence alignment, the retargets), no fit judgment, no plugin-added
 * mixin judged late, no original {@code required} remembered.
 *
 * <p>The comments Gson's lenient reader skips — {@code //} and {@code #} to the end of the line, and {@code /* ... *}{@code /}
 * — are blanked outside of strings (single- or double-quoted, with escapes) before the strict parse; line breaks stay,
 * so a parse error still names the right line. Nothing else of the lenient grammar is taken on: a config that needs more
 * than its comments forgiven is read as before, by Mixin only.
 */
public final class MixinConfigJson {
	private MixinConfigJson() {
	}

	/** The config in {@code json} (UTF-8), its comments skipped; throws as the JSON parser does when it is not JSON. */
	public static UnmodifiableConfig parse(byte[] json) {
		return JsonFormat.fancyInstance().createParser().parse(new StringReader(withoutComments(new String(json, StandardCharsets.UTF_8))));
	}

	/** {@link #parse(byte[])} for a reader. */
	public static UnmodifiableConfig parse(Reader reader) throws IOException {
		StringBuilder text = new StringBuilder();
		char[] buffer = new char[8192];
		for (int read; (read = reader.read(buffer)) >= 0;) text.append(buffer, 0, read);
		return JsonFormat.fancyInstance().createParser().parse(new StringReader(withoutComments(text.toString())));
	}

	/** {@code json} with every comment outside a string replaced by spaces, its line breaks kept. */
	static String withoutComments(String json) {
		StringBuilder out = new StringBuilder(json.length());
		char quote = 0;
		for (int i = 0; i < json.length(); i++) {
			char c = json.charAt(i);
			if (quote != 0) {
				out.append(c);
				if (c == '\\' && i + 1 < json.length()) out.append(json.charAt(++i));
				else if (c == quote) quote = 0;
				continue;
			}
			if (c == '"' || c == '\'') {
				quote = c;
				out.append(c);
			} else if (c == '#' || (c == '/' && i + 1 < json.length() && json.charAt(i + 1) == '/')) {
				while (i < json.length() && json.charAt(i) != '\n' && json.charAt(i) != '\r') {
					out.append(' ');
					i++;
				}
				if (i < json.length()) out.append(json.charAt(i));
			} else if (c == '/' && i + 1 < json.length() && json.charAt(i + 1) == '*') {
				int end = json.indexOf("*/", i + 2);
				int stop = end < 0 ? json.length() : end + 2;
				for (; i < stop; i++) out.append(json.charAt(i) == '\n' || json.charAt(i) == '\r' ? json.charAt(i) : ' ');
				i--;
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}
}
