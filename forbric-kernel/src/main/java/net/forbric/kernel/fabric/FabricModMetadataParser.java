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

package net.forbric.kernel.fabric;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.json.JsonFormat;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.Person;

import net.forbric.kernel.fabric.KernelMetadataSupport.MapContactInformation;
import net.forbric.kernel.fabric.KernelMetadataSupport.SimpleModDependency;
import net.forbric.kernel.fabric.KernelMetadataSupport.SimplePerson;
import net.forbric.kernel.fabric.KernelModMetadata.EntrypointDecl;
import net.forbric.kernel.fabric.KernelModMetadata.MixinConfigDecl;
import net.forbric.kernel.util.ForbricLog;

/**
 * Parses a {@code fabric.mod.json} (schema v1) into the kernel's {@link KernelModMetadata}.
 *
 * <p>This is the full loader-grade reader, superseding {@code metadata.fabric.FabricModJsonReader} (which reads
 * only the subset the unified discovery pass needs). It additionally understands the declarations the kernel has
 * to act on: {@code entrypoints} (including custom keys and the {@code {adapter, value}} object form),
 * {@code jars} (JiJ), {@code mixins} (with per-side {@code environment}), {@code accessWidener}, and the
 * {@code custom} block mods read back through {@code ModMetadata#getCustomValue}.
 *
 * <p>Unparseable optional fields degrade to their empty value with a warning rather than failing the mod: a mod
 * with a malformed {@code contact} block should still load.
 */
public final class FabricModMetadataParser {
	private FabricModMetadataParser() {
	}

	public static KernelModMetadata read(InputStream in) {
		return read(new InputStreamReader(in, StandardCharsets.UTF_8));
	}

	public static KernelModMetadata read(Reader reader) {
		UnmodifiableConfig json = JsonFormat.fancyInstance().createParser().parse(reader);

		String id = string(json, "id");
		if (id == null || id.isEmpty()) throw new IllegalArgumentException("fabric.mod.json has no 'id'");

		String rawVersion = string(json, "version");
		Version version = parseVersion(id, rawVersion);

		return new KernelModMetadata(
				orDefault(string(json, "type"), "fabric"),
				id,
				stringList(json, "provides"),
				version,
				environment(string(json, "environment")),
				dependencies(json),
				string(json, "name"),
				string(json, "description"),
				people(json, "authors"),
				people(json, "contributors"),
				contact(sub(json, "contact")),
				license(json),
				icons(json),
				customValues(json),
				entrypoints(json),
				mixins(json),
				string(json, "accessWidener"),
				nestedJars(json),
				languageAdapters(json));
	}

	/** {@code "languageAdapters": { "<name>": "the.LanguageAdapter" }} — declared by one mod, named by others. */
	private static Map<String, String> languageAdapters(UnmodifiableConfig json) {
		Map<String, String> result = new LinkedHashMap<>();
		UnmodifiableConfig block = sub(json, "languageAdapters");
		if (block == null) return result;

		for (UnmodifiableConfig.Entry entry : block.entrySet()) {
			Object value = entry.getValue();
			if (value instanceof String className && !className.isBlank()) {
				result.put(entry.getKey(), className);
			}
		}
		return result;
	}

	private static Version parseVersion(String id, String raw) {
		if (raw == null || raw.isEmpty()) {
			ForbricLog.warn("[Forbric/Fabric] mod '%s' declares no version; treating it as 0.0.0", id);
			raw = "0.0.0";
		}

		try {
			return KernelVersion.parse(raw);
		} catch (VersionParsingException e) {
			// KernelVersion.parse falls back to a string version, so this is unreachable for any non-blank input;
			// keep the branch honest rather than swallowing a future parser change.
			throw new IllegalArgumentException("mod '" + id + "' has an unparseable version '" + raw + "'", e);
		}
	}

	/** {@code "entrypoints": { "<key>": [ "cls" | {"adapter": "...", "value": "..."} ] }} */
	private static Map<String, List<EntrypointDecl>> entrypoints(UnmodifiableConfig json) {
		Map<String, List<EntrypointDecl>> result = new LinkedHashMap<>();
		UnmodifiableConfig block = sub(json, "entrypoints");
		if (block == null) return result;

		for (UnmodifiableConfig.Entry entry : block.entrySet()) {
			Object value = entry.getValue();
			if (!(value instanceof List)) continue;

			List<EntrypointDecl> decls = new ArrayList<>();

			for (Object element : (List<?>) value) {
				if (element instanceof String) {
					decls.add(new EntrypointDecl("default", (String) element));
				} else if (element instanceof UnmodifiableConfig) {
					UnmodifiableConfig obj = (UnmodifiableConfig) element;
					String v = string(obj, "value");
					if (v != null) decls.add(new EntrypointDecl(orDefault(string(obj, "adapter"), "default"), v));
				}
			}

			if (!decls.isEmpty()) result.put(entry.getKey(), Collections.unmodifiableList(decls));
		}

		return result;
	}

	/** {@code "mixins": [ "a.mixins.json", {"config": "b.mixins.json", "environment": "client"} ]} */
	private static List<MixinConfigDecl> mixins(UnmodifiableConfig json) {
		List<MixinConfigDecl> configs = new ArrayList<>();
		Object value = json.get(Collections.singletonList("mixins"));
		if (!(value instanceof List)) return configs;

		for (Object element : (List<?>) value) {
			if (element instanceof String) {
				configs.add(new MixinConfigDecl((String) element, ModEnvironment.UNIVERSAL));
			} else if (element instanceof UnmodifiableConfig) {
				UnmodifiableConfig obj = (UnmodifiableConfig) element;
				String config = string(obj, "config");
				if (config != null) configs.add(new MixinConfigDecl(config, environment(string(obj, "environment"))));
			}
		}

		return configs;
	}

	/** {@code "jars": [ {"file": "META-INF/jars/x.jar"} ]} */
	private static List<String> nestedJars(UnmodifiableConfig json) {
		List<String> jars = new ArrayList<>();
		Object value = json.get(Collections.singletonList("jars"));
		if (!(value instanceof List)) return jars;

		for (Object element : (List<?>) value) {
			if (element instanceof UnmodifiableConfig) {
				String file = string((UnmodifiableConfig) element, "file");
				if (file != null) jars.add(file);
			} else if (element instanceof String) {
				jars.add((String) element);
			}
		}

		return jars;
	}

	private static List<ModDependency> dependencies(UnmodifiableConfig json) {
		List<ModDependency> deps = new ArrayList<>();

		for (ModDependency.Kind kind : ModDependency.Kind.values()) {
			UnmodifiableConfig block = sub(json, kind.getKey());
			if (block == null) continue;

			for (UnmodifiableConfig.Entry entry : block.entrySet()) {
				deps.add(new SimpleModDependency(kind, entry.getKey(), constraints(entry.getValue())));
			}
		}

		return deps;
	}

	/** A depends value is a single predicate string or an array of them (OR-joined). */
	private static List<String> constraints(Object value) {
		if (value instanceof String) return List.of((String) value);

		if (value instanceof List) {
			List<String> parts = new ArrayList<>();

			for (Object o : (List<?>) value) {
				if (o != null) parts.add(o.toString());
			}

			if (!parts.isEmpty()) return parts;
		}

		return List.of("*");
	}

	/** {@code "authors": [ "Name" | {"name": "Name", "contact": {…}} ]} */
	private static List<Person> people(UnmodifiableConfig json, String key) {
		List<Person> people = new ArrayList<>();
		Object value = json.get(Collections.singletonList(key));
		if (!(value instanceof List)) return people;

		for (Object element : (List<?>) value) {
			if (element instanceof String) {
				people.add(new SimplePerson((String) element, ContactInformation.EMPTY));
			} else if (element instanceof UnmodifiableConfig) {
				UnmodifiableConfig obj = (UnmodifiableConfig) element;
				String name = string(obj, "name");
				if (name != null) people.add(new SimplePerson(name, contact(sub(obj, "contact"))));
			}
		}

		return people;
	}

	private static ContactInformation contact(UnmodifiableConfig block) {
		if (block == null) return ContactInformation.EMPTY;

		Map<String, String> map = new LinkedHashMap<>();

		for (UnmodifiableConfig.Entry entry : block.entrySet()) {
			Object value = entry.getValue();
			if (value != null) map.put(entry.getKey(), value.toString());
		}

		return map.isEmpty() ? ContactInformation.EMPTY : new MapContactInformation(map);
	}

	/** {@code "license"} is a string or an array of strings. */
	private static List<String> license(UnmodifiableConfig json) {
		Object value = json.get(Collections.singletonList("license"));
		if (value instanceof String) return List.of((String) value);

		return stringList(json, "license");
	}

	/** {@code "icon"} is a path, or an object keyed by pixel size. */
	private static Map<Integer, String> icons(UnmodifiableConfig json) {
		Map<Integer, String> icons = new LinkedHashMap<>();
		Object value = json.get(Collections.singletonList("icon"));

		if (value instanceof String) {
			// An unsized icon answers every getIconPath(size) query, so record it at size 0 — smaller than any
			// real request, which makes the "largest available" fallback select it.
			icons.put(0, (String) value);
		} else if (value instanceof UnmodifiableConfig) {
			for (UnmodifiableConfig.Entry entry : ((UnmodifiableConfig) value).entrySet()) {
				try {
					icons.put(Integer.parseInt(entry.getKey()), String.valueOf((Object) entry.getValue()));
				} catch (NumberFormatException ignored) {
					// A non-numeric icon key is not a size; skip it rather than fail the mod.
				}
			}
		}

		return icons;
	}

	private static Map<String, CustomValue> customValues(UnmodifiableConfig json) {
		Map<String, CustomValue> values = new LinkedHashMap<>();
		UnmodifiableConfig block = sub(json, "custom");
		if (block == null) return values;

		for (UnmodifiableConfig.Entry entry : block.entrySet()) {
			values.put(entry.getKey(), KernelCustomValue.of(entry.getValue()));
		}

		return values;
	}

	private static ModEnvironment environment(String value) {
		if (value == null || value.isEmpty() || value.equals("*")) return ModEnvironment.UNIVERSAL;
		if (value.equals("client")) return ModEnvironment.CLIENT;
		if (value.equals("server")) return ModEnvironment.SERVER;

		return ModEnvironment.UNIVERSAL;
	}

	private static List<String> stringList(UnmodifiableConfig config, String key) {
		Object value = config.get(Collections.singletonList(key));
		if (!(value instanceof List)) return new ArrayList<>();

		List<String> out = new ArrayList<>();

		for (Object o : (List<?>) value) {
			if (o != null) out.add(o.toString());
		}

		return out;
	}

	private static String string(UnmodifiableConfig config, String key) {
		Object value = config.get(Collections.singletonList(key));
		return value == null ? null : value.toString();
	}

	private static UnmodifiableConfig sub(UnmodifiableConfig config, String key) {
		Object value = config.get(Collections.singletonList(key));
		return value instanceof UnmodifiableConfig ? (UnmodifiableConfig) value : null;
	}

	private static String orDefault(String value, String fallback) {
		return value == null || value.isEmpty() ? fallback : value;
	}
}
