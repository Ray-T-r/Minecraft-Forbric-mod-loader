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

package net.forbric.kernel.metadata.forge;

import java.util.Locale;

import net.forbric.kernel.util.ForbricLog;

/**
 * What {@code mods.toml}'s {@code modLoader} field says a mod is written in, and what this kernel does about it.
 *
 * <h2>Why this file exists at all</h2>
 *
 * <p>{@code modLoader} has been parsed, stored and exposed since the metadata layer was written, and read by
 * nothing. The only place the string {@code lowcodefml} appeared in this project was a test fixture. So a mod
 * that is not written in Java was loaded as though it were: the Forge-family path reflects the widest public
 * constructor, finds none on a Kotlin {@code object}, and reports "no public constructor" — a true sentence
 * about a mod that is not broken, with the actual reason sitting unread in its own manifest.
 *
 * <p>The Fabric side has never had this gap. {@code KernelLanguageAdapters} exists precisely because, without
 * it, every Kotlin mod died on "language adapter 'kotlin' is not supported yet" — a message that at least named
 * the problem. This is the Forge-family half of that, starting with naming it.
 *
 * <h2>What is and is not handled</h2>
 *
 * <ul>
 *   <li>{@code javafml} — a Java {@code @Mod} class, constructed by reflection. The default and the normal case.</li>
 *   <li>{@code kotlinforforge} — on genuine Forge the provider reads the Kotlin {@code object}'s
 *       {@code INSTANCE} field instead of calling a constructor. The kernel now does the same when a mod class
 *       has no public constructor, so the common shape works; anything the provider does BEYOND that does not.</li>
 *   <li>{@code lowcodefml} — a manifest-only mod with no mod class at all. Nothing to construct, and nothing
 *       here goes wrong; it is named so that "this pack contains one" is a fact in the log rather than a
 *       surprise later.</li>
 *   <li>anything else — unknown, and said so by name.</li>
 * </ul>
 */
public final class LanguageProviders {

	/** The one this kernel implements outright. */
	public static final String JAVA = "javafml";
	/** Handled as far as the {@code INSTANCE} shape goes. */
	public static final String KOTLIN = "kotlinforforge";
	/** No mod class to construct, so nothing to provide. */
	public static final String LOW_CODE = "lowcodefml";

	private LanguageProviders() {
	}

	/** Normalised, never null: a manifest with no {@code modLoader} is a Java mod. */
	public static String of(ForgeModsToml toml) {
		String declared = toml == null ? null : toml.getModLoader();
		if (declared == null || declared.isBlank()) return JAVA;
		return declared.strip().toLowerCase(Locale.ROOT);
	}

	/** Whether the kernel constructs mods of this language at all. */
	public static boolean isSupported(String provider) {
		return JAVA.equals(provider) || KOTLIN.equals(provider) || LOW_CODE.equals(provider);
	}

	/**
	 * Says, once per manifest, when a mod is not a plain Java one.
	 *
	 * <p>Deliberately a log line and not a {@code DEGRADED} mark: a Kotlin mod whose object shape the kernel can
	 * take does work, and a {@code lowcodefml} data mod has nothing to construct in the first place. Marking
	 * either would be the false accusation this project has already made once, from the other direction.
	 */
	public static void audit(ForgeModsToml toml, String source) {
		String provider = of(toml);
		if (JAVA.equals(provider)) return;

		if (KOTLIN.equals(provider)) {
			ForbricLog.info("[Forbric/Language] %s declares modLoader=%s — the kernel has no Kotlin language "
					+ "provider and will take the mod class's INSTANCE where there is one, which covers a Kotlin "
					+ "`object`; anything the real provider does beyond that is not here", source, provider);
		} else if (LOW_CODE.equals(provider)) {
			ForbricLog.debug("[Forbric/Language] %s declares modLoader=%s — no mod class to construct", source, provider);
		} else {
			ForbricLog.warn("[Forbric/Language] %s declares modLoader=%s, which this kernel does not implement. "
					+ "If it ships a mod class, construction will fail on it and the reason is this line, not "
					+ "whatever the failure says", source, provider);
		}
	}
}
