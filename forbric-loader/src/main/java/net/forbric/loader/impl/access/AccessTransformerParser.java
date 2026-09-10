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

package net.forbric.loader.impl.access;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import net.forbric.loader.impl.mapping.ForbricMappings;

/**
 * Clean-room reader for a Forge/NeoForge Access Transformer {@code .cfg} file.
 *
 * <p>Each non-comment line is {@code <modifier> <class> [<member>] [# comment]}, where the modifier is one
 * of {@code public}/{@code protected}/{@code private}/{@code default}, optionally suffixed {@code -f}
 * (strip {@code final}) or {@code +f} (add {@code final}); the member is a field name, a
 * {@code name(desc)ret} method, or the wildcards {@code *} (all fields) / {@code *()} (all methods); an
 * absent member targets the class itself. Names are Mojmap (named); {@link #parseAndRemap} translates them
 * to the runtime (intermediary) namespace via {@link ForbricMappings}.
 */
public final class AccessTransformerParser {
	private AccessTransformerParser() {
	}

	/** Parses cfg lines into directives in the <em>named</em> (Mojmap) namespace (internal class names). */
	public static List<AtDirective> parse(Reader cfg) throws IOException {
		List<AtDirective> directives = new ArrayList<>();

		try (BufferedReader reader = new BufferedReader(cfg)) {
			String line;
			while ((line = reader.readLine()) != null) {
				AtDirective directive = parseLine(line);
				if (directive != null) directives.add(directive);
			}
		}

		return directives;
	}

	/** Parses cfg lines and remaps every class/member/descriptor from named to intermediary. */
	public static List<AtDirective> parseAndRemap(Reader cfg, ForbricMappings mappings) throws IOException {
		List<AtDirective> named = parse(cfg);
		List<AtDirective> remapped = new ArrayList<>(named.size());

		for (AtDirective d : named) {
			remapped.add(remap(d, mappings));
		}

		return remapped;
	}

	private static AtDirective parseLine(String rawLine) {
		String line = stripComment(rawLine).trim();
		if (line.isEmpty()) return null;

		String[] tokens = line.split("\\s+");
		String modifier = tokens[0];

		AtDirective.FinalOp finalOp = AtDirective.FinalOp.LEAVE;
		String accessKeyword = modifier;
		if (modifier.endsWith("-f")) {
			finalOp = AtDirective.FinalOp.STRIP;
			accessKeyword = modifier.substring(0, modifier.length() - 2);
		} else if (modifier.endsWith("+f")) {
			finalOp = AtDirective.FinalOp.MAKE;
			accessKeyword = modifier.substring(0, modifier.length() - 2);
		}

		AtAccess access = AtAccess.parse(accessKeyword);

		if (tokens.length < 2) {
			throw new IllegalArgumentException("access transformer line missing target class: " + rawLine);
		}

		String className = tokens[1].replace('.', '/');

		if (tokens.length < 3) {
			return new AtDirective(className, null, null, false, access, finalOp);
		}

		String member = tokens[2];

		if (AtDirective.ALL_FIELDS.equals(member)) {
			return new AtDirective(className, AtDirective.ALL_FIELDS, null, false, access, finalOp);
		}

		if (AtDirective.ALL_METHODS.equals(member)) {
			return new AtDirective(className, AtDirective.ALL_METHODS, null, true, access, finalOp);
		}

		int paren = member.indexOf('(');
		if (paren >= 0) {
			String name = member.substring(0, paren);
			String desc = member.substring(paren);
			return new AtDirective(className, name, desc, true, access, finalOp);
		}

		return new AtDirective(className, member, null, false, access, finalOp);
	}

	private static AtDirective remap(AtDirective d, ForbricMappings mappings) {
		String owner = d.className;
		String mappedOwner = mappings.mapClass(owner);

		if (d.isClass() || d.isAllFields() || d.isAllMethods()) {
			return new AtDirective(mappedOwner, d.memberName, d.memberDesc, d.method, d.access, d.finalOp);
		}

		if (d.method) {
			String mappedName = mappings.mapMethod(owner, d.memberName, d.memberDesc);
			String mappedDesc = remapMethodDescriptor(d.memberDesc, mappings);
			return new AtDirective(mappedOwner, mappedName, mappedDesc, true, d.access, d.finalOp);
		}

		String mappedName = mappings.mapField(owner, d.memberName, d.memberDesc);
		return new AtDirective(mappedOwner, mappedName, null, false, d.access, d.finalOp);
	}

	/** Remaps the object-type references inside a method descriptor from named to intermediary internal names. */
	static String remapMethodDescriptor(String desc, ForbricMappings mappings) {
		if (desc == null) return null;

		StringBuilder out = new StringBuilder(desc.length());

		for (int i = 0; i < desc.length(); i++) {
			char c = desc.charAt(i);
			out.append(c);

			if (c == 'L') {
				int end = desc.indexOf(';', i);
				if (end < 0) { // malformed; copy the remainder verbatim
					out.append(desc, i + 1, desc.length());
					break;
				}

				String internal = desc.substring(i + 1, end);
				out.append(mappings.mapClass(internal)).append(';');
				i = end;
			}
		}

		return out.toString();
	}

	private static String stripComment(String line) {
		int hash = line.indexOf('#');
		return hash < 0 ? line : line.substring(0, hash);
	}
}
