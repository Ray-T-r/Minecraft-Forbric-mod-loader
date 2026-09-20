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

package net.forbric.kernel.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The string keys of a jar's {@code META-INF/mods.toml}: the top-level ones and each {@code [[mods]]} table's.
 *
 * <p>Enough for what a traditional-Forge mod reads back through {@code IModFileInfo.getConfig()} — wthit asks
 * for {@code issueTrackerURL} in its static initialiser and dies when it is absent. Only {@code key = "value"}
 * lines are read; multi-line strings and non-string values are left out, which reads as "not declared".
 */
final class KernelForgeModsToml {
	static final String PATH = "META-INF/mods.toml";
	private static final Pattern STRING_LINE = Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*\"([^\"]*)\"\\s*(#.*)?$");
	private static final Pattern TABLE = Pattern.compile("^\\s*\\[\\[?\\s*([A-Za-z0-9_.-]+)\\s*\\]?\\]\\s*$");

	final Map<String, String> top = new LinkedHashMap<>();
	final List<Map<String, String>> mods = new ArrayList<>();

	static KernelForgeModsToml empty() {
		return new KernelForgeModsToml();
	}

	/** The parsed file, or {@link #empty()} when {@code jar} is null, unreadable or has no mods.toml. */
	static KernelForgeModsToml read(Path jar) {
		if (jar == null) return empty();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(PATH);
			if (entry == null) return empty();
			return parse(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
		} catch (Throwable unreadable) {
			return empty();
		}
	}

	static KernelForgeModsToml parse(String text) {
		KernelForgeModsToml out = new KernelForgeModsToml();
		Map<String, String> current = out.top;
		for (String line : text.split("\\R")) {
			Matcher table = TABLE.matcher(line);
			if (table.matches()) {
				if ("mods".equals(table.group(1)) && line.contains("[[")) {
					current = new LinkedHashMap<>();
					out.mods.add(current);
				} else {
					current = new LinkedHashMap<>(); // some other table: read, never reported
				}
				continue;
			}
			Matcher kv = STRING_LINE.matcher(line);
			if (kv.matches()) current.put(kv.group(1), kv.group(2));
		}
		return out;
	}

	/** The {@code [[mods]]} table declaring {@code modId}, or an empty map. */
	Map<String, String> mod(String modId) {
		for (Map<String, String> table : mods) if (modId.equals(table.get("modId"))) return table;
		return Map.of();
	}
}
