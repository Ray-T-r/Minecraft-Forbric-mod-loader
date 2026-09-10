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

package net.forbric.loader.impl.metadata.fabric;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.json.JsonFormat;

import net.forbric.loader.impl.metadata.DiscoveredMod;
import net.forbric.loader.impl.metadata.ModEcosystem;
import net.forbric.loader.impl.metadata.UnifiedDependency;

/**
 * Reads a {@code fabric.mod.json} (schema v1) into Forbric's unified {@link DiscoveredMod} model.
 *
 * <p>This is a focused reader covering the fields discovery needs (id, version, name, depends, mixins,
 * accessWidener). In the integrated build (milestone P0) it is replaced by Fabric Loader's own
 * {@code ModMetadataParser}, which Forbric reuses as-is; this keeps the discovery layer testable before
 * the substrate is wired in.
 */
public final class FabricModJsonReader {
	private FabricModJsonReader() {
	}

	public static DiscoveredMod read(InputStream in, String source) {
		return read(new InputStreamReader(in, StandardCharsets.UTF_8), source);
	}

	public static DiscoveredMod read(Reader reader, String source) {
		UnmodifiableConfig json = JsonFormat.fancyInstance().createParser().parse(reader);

		String id = getString(json, "id");
		String version = getString(json, "version");
		String name = getString(json, "name");

		List<UnifiedDependency> deps = new ArrayList<>();
		UnmodifiableConfig depends = getSubConfig(json, "depends");

		if (depends != null) {
			for (UnmodifiableConfig.Entry e : depends.entrySet()) {
				deps.add(new UnifiedDependency(e.getKey(), versionConstraint(e.getValue()), true));
			}
		}

		return new DiscoveredMod(
				ModEcosystem.FABRIC,
				id,
				version,
				name,
				deps,
				readMixins(json),
				getString(json, "accessWidener"),
				source);
	}

	/** A fabric.mod.json depends value is a predicate string or an array of them (OR-joined). */
	private static String versionConstraint(Object value) {
		if (value instanceof String) return (String) value;

		if (value instanceof List) {
			List<String> parts = new ArrayList<>();

			for (Object o : (List<?>) value) {
				if (o != null) parts.add(o.toString());
			}

			if (!parts.isEmpty()) return String.join(" || ", parts);
		}

		return "*";
	}

	/** {@code mixins} is an array whose elements are either a config name string or {@code {"config": "..."}}. */
	private static List<String> readMixins(UnmodifiableConfig json) {
		Object value = json.get(Collections.singletonList("mixins"));
		if (!(value instanceof List)) return Collections.emptyList();

		List<String> configs = new ArrayList<>();

		for (Object element : (List<?>) value) {
			if (element instanceof String) {
				configs.add((String) element);
			} else if (element instanceof UnmodifiableConfig) {
				String config = getString((UnmodifiableConfig) element, "config");
				if (config != null) configs.add(config);
			} else if (element instanceof Map) {
				Object config = ((Map<?, ?>) element).get("config");
				if (config != null) configs.add(config.toString());
			}
		}

		return configs;
	}

	private static String getString(UnmodifiableConfig config, String key) {
		Object value = config.get(Collections.singletonList(key));
		return value == null ? null : value.toString();
	}

	private static UnmodifiableConfig getSubConfig(UnmodifiableConfig config, String key) {
		Object value = config.get(Collections.singletonList(key));
		return value instanceof UnmodifiableConfig ? (UnmodifiableConfig) value : null;
	}
}
