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

	private KernelModMetadata() {
	}

	/** The mod's real display name, or its id when discovery has none. Never null, never blank. */
	static String displayNameOf(String modId) {
		DiscoveredMod mod = lookup(modId);
		String name = mod == null ? null : mod.getDisplayName();
		return usable(name) ? name : modId;
	}

	/**
	 * The mod's real version, or {@code "0.0"}.
	 *
	 * <p>An unresolved placeholder counts as no version. A jar whose metadata says {@code ${file.jarVersion}}
	 * expects its loader to substitute the jar's own manifest version; printing the placeholder itself into the
	 * Mods screen would be worse than admitting the version is unknown.
	 */
	static String versionOf(String modId) {
		DiscoveredMod mod = lookup(modId);
		String version = mod == null ? null : mod.getVersion();
		if (!usable(version) || version.contains("${")) return UNKNOWN_VERSION;
		return version;
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
