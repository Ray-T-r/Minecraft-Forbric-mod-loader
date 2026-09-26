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

import net.forbric.kernel.metadata.forge.FmlConfigElements;
import net.forbric.kernel.metadata.forge.ForgeModEntry;
import net.forbric.kernel.metadata.forge.ForgeModsToml;
import net.forbric.kernel.metadata.forge.ModsTomlParser;

/**
 * A jar's {@code META-INF/mods.toml}, as what a traditional-Forge mod reads back through
 * {@code IModFileInfo.getConfig()} and {@code IModInfo.getConfig()}: the file's top level and each {@code [[mods]]}
 * table.
 *
 * <p>Parsed the way MinecraftForge parses it, by night-config through the kernel's one {@code mods.toml} parser, so
 * every value keeps its TOML type and a table stays a {@code Config} (which {@link KernelForgeConfigurable} answers as
 * MinecraftForge's {@code ImmutableMap}). This used to be a line-by-line reading of {@code key = "value"} lines, which
 * answered strings only: a boolean, a number, a list or a table read as undeclared, and so did a string with an
 * escaped quote in it — wthit's second mod, {@code waila}, describes itself as
 * {@code "Actually WTHIT but with \"waila\" as it's id lmao"}, and that line matched nothing.
 *
 * <p>{@code -Dforbric.fileConfigElements=off} reads the strings-only way again, and so does a file night-config
 * cannot parse, which reads no worse than it did.
 */
final class KernelForgeModsToml {
	static final String PATH = "META-INF/mods.toml";
	private static final Pattern STRING_LINE = Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*\"([^\"]*)\"\\s*(#.*)?$");
	private static final Pattern TABLE = Pattern.compile("^\\s*\\[\\[?\\s*([A-Za-z0-9_.-]+)\\s*\\]?\\]\\s*$");

	/** The file's top level: shallow, with a table left as night-config's {@code Config}. */
	final Map<String, Object> top;
	/** Each {@code [[mods]]} table, in file order. */
	final List<Map<String, Object>> mods;
	/** Whether the values are night-config's own (true) or the strings-only reading (false). */
	final boolean typed;

	private KernelForgeModsToml(Map<String, Object> top, List<Map<String, Object>> mods, boolean typed) {
		this.top = top;
		this.mods = mods;
		this.typed = typed;
	}

	static KernelForgeModsToml empty() {
		return new KernelForgeModsToml(Map.of(), List.of(), FmlConfigElements.enabled());
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
		if (FmlConfigElements.enabled()) {
			try {
				ForgeModsToml toml = ModsTomlParser.parse(text);
				List<Map<String, Object>> mods = new ArrayList<>();
				for (ForgeModEntry mod : toml.getMods()) mods.add(mod.getConfigElements());
				return new KernelForgeModsToml(toml.getConfigElements(), List.copyOf(mods), true);
			} catch (RuntimeException malformed) {
				// Fall through: the strings-only reading tolerated what night-config refuses, and must stay no worse.
			}
		}
		return strings(text);
	}

	/** The reading before night-config: {@code key = "value"} lines only. */
	private static KernelForgeModsToml strings(String text) {
		Map<String, Object> top = new LinkedHashMap<>();
		List<Map<String, Object>> mods = new ArrayList<>();
		Map<String, Object> current = top;
		for (String line : text.split("\\R")) {
			Matcher table = TABLE.matcher(line);
			if (table.matches()) {
				if ("mods".equals(table.group(1)) && line.contains("[[")) {
					current = new LinkedHashMap<>();
					mods.add(current);
				} else {
					current = new LinkedHashMap<>(); // some other table: read, never reported
				}
				continue;
			}
			Matcher kv = STRING_LINE.matcher(line);
			if (kv.matches()) current.put(kv.group(1), kv.group(2));
		}
		return new KernelForgeModsToml(top, mods, false);
	}

	/** The {@code [[mods]]} table declaring {@code modId}, or an empty map. */
	Map<String, Object> mod(String modId) {
		for (Map<String, Object> table : mods) if (modId.equals(table.get("modId"))) return table;
		return Map.of();
	}

	/** The file's {@code license}, or {@code ""} — MinecraftForge's own default, and what the Mods screen writes. */
	String license() {
		return top.get("license") instanceof String license ? license : "";
	}
}
