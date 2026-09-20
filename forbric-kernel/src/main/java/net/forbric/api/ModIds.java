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

package net.forbric.api;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds the mod a dependency means when the two ecosystems spell the same library's id differently.
 *
 * <p>Cloth Config is the case that found this. The Fabric build declares {@code "id":"cloth-config"}; the
 * MinecraftForge and NeoForge builds declare {@code modId = "cloth_config"}. One project, one author, one set of
 * {@code me.shedaniel.clothconfig2} classes — two spellings, because each ecosystem's id conventions were settled
 * separately and neither side has any reason to declare the other's name. On a single-ecosystem loader that never
 * shows: you install the build for your loader and the id its dependents ask for is the one it declares.
 *
 * <p>On Forbric it shows immediately. A NeoForge mod requiring {@code cloth_config} sits next to the Fabric Cloth
 * Config whose classes it will happily link against at runtime, and every id-keyed lookup says NOT INSTALLED —
 * the dependency dialog accuses the player of a missing mod that is in their mods folder, and
 * {@link net.forbric.kernel.boot.ModConstructionOrder} draws no edge, so the dependent can construct first.
 *
 * <p>The rule is punctuation, and only punctuation: {@code -}, {@code _}, {@code .} and spaces are dropped and the
 * rest is lowercased. It is applied ONLY after the exact id (and every {@code provides} alias) has already missed,
 * and only when exactly ONE installed mod collapses to the same key. Two candidates and it declines, because a
 * silenced real dependency is worse than a spelling this does not know: the dialog's whole value is being
 * believed, and it can only be believed while it never covers something up.
 *
 * <p>Measured before it was trusted: across a 97-jar three-ecosystem pack, 102 distinct declared ids collapse to
 * 102 distinct keys — no two mods collide at all.
 *
 * <p>{@code -Dforbric.crossEcosystemIds=off} restores exact-id matching.
 */
public final class ModIds {
	static final String SWITCH = "forbric.crossEcosystemIds";

	private ModIds() {
	}

	/** Whether the cross-ecosystem spelling fallback is on. */
	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	/**
	 * {@code id} with the punctuation each ecosystem chose for itself removed.
	 *
	 * <p>Not a mod id and never stored as one: two different spellings share a key, so writing one back out would
	 * be inventing a name no mod declares.
	 */
	public static String collapsed(String id) {
		if (id == null) return null;
		StringBuilder out = new StringBuilder(id.length());
		for (int i = 0; i < id.length(); i++) {
			char c = id.charAt(i);
			if (c != '-' && c != '_' && c != '.' && c != ' ') out.append(c);
		}
		return out.toString().toLowerCase(Locale.ROOT);
	}

	/**
	 * The single entry of {@code byExactId} whose key is {@code wanted} under another ecosystem's spelling.
	 *
	 * @param byExactId every id and alias already known, exactly as declared; never modified
	 * @return that entry's value, or {@code null} when the id is spelled the same (so the caller's own exact
	 *         lookup already answered), when nothing collapses to it, or when more than one mod does
	 */
	public static <T> T underAnotherSpelling(String wanted, Map<String, T> byExactId) {
		if (!enabled() || wanted == null || wanted.isBlank() || byExactId == null || byExactId.isEmpty()) return null;

		// The exact spelling is the caller's own answer, not this one's. Returning it here would make a plain hit
		// indistinguishable from a cross-ecosystem one in the log, and the log line is the point.
		if (byExactId.containsKey(wanted)) return null;

		String key = collapsed(wanted);
		if (key.isEmpty()) return null;

		// The same mod reached under two of its own spellings (its id plus a provides alias) is ONE candidate.
		// Counting entries instead of distinct mods would let a mod that declares both spellings itself be the
		// reason it is not found.
		Set<T> candidates = new LinkedHashSet<>();
		for (Map.Entry<String, T> entry : byExactId.entrySet()) {
			if (key.equals(collapsed(entry.getKey()))) candidates.add(entry.getValue());
		}
		return candidates.size() == 1 ? candidates.iterator().next() : null;
	}
}
