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

import java.util.Map;
import net.forbric.api.DiscoveredMod;
import net.forbric.api.ModPresence;

/**
 * What discovery learned about a mod, in the plainest form the two {@code IModInfo} implementations can share.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Both implementations are built from a mod id and a jar path and nothing else, so both answered
 * {@code getVersion()} with the placeholder "0.0" and {@code getDisplayName()} with the mod id. The kernel had
 * the real values the whole time — discovery parses them out of every mod's own metadata file — they were simply
 * never carried across to the objects NeoForge hands to mods. So the Mods screen listed a column of ids at
 * version 0.0, and a mod comparing another mod's version against a requirement got a number below every
 * requirement.
 *
 * <p>Strings, not version objects. The two Forge families each have their own {@code ArtifactVersion}, and a
 * helper that returned one would force a cross-family type on the other; each caller builds its own family's.
 */
final class KernelModMetadata {
	/** The conventional unknown-version placeholder, and what both implementations used to answer always. */
	static final String UNKNOWN_VERSION = "0.0";

	/**
	 * {@code -Dforbric.declaredModMetadata=off} describes every kernel-built mod info from {@link ModPresence}
	 * alone again, which has never heard of a mod that arrived inside another mod's jar.
	 */
	static final String DECLARED_SWITCH = "forbric.declaredModMetadata";

	private KernelModMetadata() {
	}

	/**
	 * What to describe {@code modId} with: the entry its OWN jar declared when the caller holds one, else what
	 * discovery published.
	 *
	 * <p>{@link ModPresence} is published from the Forge-family jars directly in {@code mods/} plus the Fabric list,
	 * so a NeoForge mod that came out of another mod's jar-in-jar — all twelve LibJF modules, commonnetworking inside
	 * Fake Players — was a mod it had never heard of, and every answer here was the placeholder. That cost more than
	 * a "0.0" on the Mods screen: LibJF finds its configs through {@code getModProperties()}, and against an empty
	 * table LibJF Translate's {@code libjf:config} entry point, which native NeoForge registers, never registered.
	 *
	 * <p>The declared entry wins because it is the more specific answer: for a jar in {@code mods/} it is the very
	 * object discovery published, and for a nested one it is the only answer there is.
	 */
	static DiscoveredMod resolve(String modId, DiscoveredMod declared) {
		if (declared != null && modId != null && modId.equals(declared.getId())
				&& !"off".equalsIgnoreCase(System.getProperty(DECLARED_SWITCH, "on"))) {
			return declared;
		}
		return lookup(modId);
	}

	/** The mod's real display name, or its id when discovery has none. Never null, never blank. */
	static String displayNameOf(String modId) {
		return displayNameOf(modId, null);
	}

	/** As {@link #displayNameOf(String)}, preferring what the mod's own jar declared. See {@link #resolve}. */
	static String displayNameOf(String modId, DiscoveredMod declared) {
		DiscoveredMod mod = resolve(modId, declared);
		String name = mod == null ? null : mod.getDisplayName();
		return usable(name) ? name : modId;
	}

	/**
	 * The mod's {@code [modproperties.<id>]} table, or an empty map.
	 *
	 * <p>Not loader data: it is how a mod addresses ANOTHER mod, and the reader is whoever looks. Sodium reads
	 * {@code sodium:config_api_user} out of it to find the class that builds that mod's page in Video Settings;
	 * Jade reads {@code jade}. Every kernel-built {@code IModInfo} used to answer this with an empty map, so iris
	 * declared its Sodium config entry point correctly in its own {@code neoforge.mods.toml} and its options page
	 * did not exist — the declaration was parsed by nobody and the table reached no one.
	 */
	static Map<String, Object> propertiesOf(String modId) {
		return propertiesOf(modId, null);
	}

	/** As {@link #propertiesOf(String)}, preferring what the mod's own jar declared. See {@link #resolve}. */
	static Map<String, Object> propertiesOf(String modId, DiscoveredMod declared) {
		DiscoveredMod mod = resolve(modId, declared);
		return mod == null ? Map.of() : mod.getModProperties();
	}

	/**
	 * The mod's real version, or {@code "0.0"}.
	 *
	 * <p>An unresolved placeholder counts as no version. A jar whose metadata says {@code ${file.jarVersion}}
	 * expects its loader to substitute the jar's own manifest version; printing the placeholder itself into the
	 * Mods screen would be worse than admitting the version is unknown.
	 */
	static String versionOf(String modId) {
		return versionOf(modId, null);
	}

	/** As {@link #versionOf(String)}, preferring what the mod's own jar declared. See {@link #resolve}. */
	static String versionOf(String modId, DiscoveredMod declared) {
		DiscoveredMod mod = resolve(modId, declared);
		String version = mod == null ? null : mod.getVersion();
		if (!usable(version) || version.contains("${")) return UNKNOWN_VERSION;
		return version;
	}

	/** The jar a discovered mod came from, or null when unknown or not a file on disk. */
	static java.nio.file.Path jarOf(String modId) {
		DiscoveredMod mod = lookup(modId);
		if (mod == null || mod.getSource() == null) return null;
		try {
			java.nio.file.Path path = java.nio.file.Path.of(String.valueOf(mod.getSource()));
			return java.nio.file.Files.isRegularFile(path) ? path : null;
		} catch (RuntimeException notAPath) {
			return null;
		}
	}

	private static DiscoveredMod lookup(String modId) {
		try {
			return ModPresence.metadata(modId);
		} catch (Throwable t) {
			// Metadata is a nicety; every caller here is on a path that must not fail because of it.
			return null;
		}
	}

	private static boolean usable(String value) {
		return value != null && !value.isBlank();
	}
}
