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

package net.forbric.kernel.boot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarFile;

import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.util.ForbricLog;

/**
 * Decides which ONE ecosystem loads a multi-loader mod jar.
 *
 * <p>A "universal" mod jar ships a manifest per loader — {@code fabric.mod.json}, {@code META-INF/mods.toml} AND
 * {@code META-INF/neoforge.mods.toml} — plus one glue class per family (a {@code net.minecraftforge} {@code @Mod},
 * a {@code net.neoforged} {@code @Mod}, a Fabric entrypoint). On a normal instance exactly one loader is running,
 * so exactly one of those is claimed. On Forbric ALL THREE are running, so without arbitration the same mod is
 * initialised three times: its content registers three times, its listeners fire three times. Observed on real
 * mods — FallingTree and collective were each claimed by Fabric + MinecraftForge + NeoForge simultaneously.
 *
 * <p>Discovery deliberately reports every manifest truthfully ("which family actually loads is a boot-time
 * policy" — {@link ForbricModDiscoverer}); this class is that policy. It picks by a documented preference order,
 * logs the choice and what it suppressed, and is overridable with
 * {@code -Dforbric.multiLoaderPreference=neoforge,minecraftforge,fabric}.
 *
 * <p>The default order prefers a Forge-family claim over Fabric because the Forge/NeoForge baselines are ALWAYS
 * present on the merged base, whereas a jar's Fabric side typically declares a dependency on fabric-api that the
 * user may not have installed; and NeoForge before traditional Forge because NeoForge won most of the byte-merge,
 * so its glue's hooks are the most likely to be intact.
 */
public final class MultiLoaderArbiter {
	/** Which loader family claims a jar. */
	public enum Ecosystem {
		NEOFORGE, MINECRAFTFORGE, FABRIC
	}

	private static final List<Ecosystem> DEFAULT_PREFERENCE =
			List.of(Ecosystem.NEOFORGE, Ecosystem.MINECRAFTFORGE, Ecosystem.FABRIC);

	/** jar path -> the ecosystem that owns it. Computed once per jar; discovery order is stable. */
	private static final Map<String, Ecosystem> OWNERS = new LinkedHashMap<>();

	private MultiLoaderArbiter() {
	}

	/** Forgets every decision — for tests, and so a re-launch in one process re-arbitrates. */
	public static synchronized void reset() {
		OWNERS.clear();
	}

	/**
	 * The ecosystem that owns {@code jar}. A jar declaring exactly one family is owned by it (the common case, no
	 * log); a jar declaring several is arbitrated by preference and logged ONCE, naming what was suppressed.
	 * Returns {@code null} when the jar declares no loader manifest at all (a plain library — nobody claims it, and
	 * every path should treat it as unowned rather than as "not mine").
	 */
	public static synchronized Ecosystem ownerOf(Path jar) {
		String key = jar.toAbsolutePath().toString();
		if (OWNERS.containsKey(key)) return OWNERS.get(key);

		List<Ecosystem> declared = declaredBy(jar);
		Ecosystem owner = null;
		if (declared.size() == 1) {
			owner = declared.get(0);
		} else if (declared.size() > 1) {
			for (Ecosystem candidate : preference()) {
				if (declared.contains(candidate)) {
					owner = candidate;
					break;
				}
			}
			if (owner == null) owner = declared.get(0); // preference listed none of them — stay deterministic
			List<Ecosystem> suppressed = new ArrayList<>(declared);
			suppressed.remove(owner);
			ForbricLog.info("[Forbric/MultiLoader] %s declares %d loaders — loading it as %s only, suppressing %s "
					+ "(a universal jar would otherwise initialise once per live ecosystem)",
					jar.getFileName(), declared.size(), owner, suppressed);
		}

		OWNERS.put(key, owner);
		return owner;
	}

	/** True when {@code jar} is claimed by another family, so {@code mine} must skip it. Unowned jars are never skipped. */
	public static boolean suppressedFor(Path jar, Ecosystem mine) {
		Ecosystem owner = ownerOf(jar);
		return owner != null && owner != mine;
	}

	/** Which loader manifests the jar actually carries, in preference-independent (stable) order. */
	private static List<Ecosystem> declaredBy(Path jar) {
		List<Ecosystem> declared = new ArrayList<>();
		try (JarFile zip = new JarFile(jar.toFile())) {
			if (zip.getEntry(ForbricModDiscoverer.NEOFORGE_MANIFEST) != null) declared.add(Ecosystem.NEOFORGE);
			if (zip.getEntry(ForbricModDiscoverer.FORGE_MANIFEST) != null) declared.add(Ecosystem.MINECRAFTFORGE);
			if (zip.getEntry(ForbricModDiscoverer.FABRIC_MANIFEST) != null) declared.add(Ecosystem.FABRIC);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/MultiLoader] could not read %s: %s", jar.getFileName(), String.valueOf(t));
		}
		return declared;
	}

	/**
	 * {@code -Dforbric.multiLoaderPreference} (csv of ecosystem names), else the documented default.
	 *
	 * <p>Package-visible so {@link DuplicateModArbiter} resolves cross-jar ties by the SAME order — one knob for
	 * both arbitrations, which is the only way "prefer Fabric on this instance" can mean one thing.
	 */
	static List<Ecosystem> preference() {
		String csv = System.getProperty("forbric.multiLoaderPreference");
		if (csv == null || csv.isBlank()) return DEFAULT_PREFERENCE;

		List<Ecosystem> order = new ArrayList<>();
		for (String raw : csv.split(",")) {
			try {
				order.add(Ecosystem.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException unknown) {
				ForbricLog.warn("[Forbric/MultiLoader] ignoring unknown ecosystem '%s' in "
						+ "-Dforbric.multiLoaderPreference", raw.trim());
			}
		}
		return order.isEmpty() ? DEFAULT_PREFERENCE : order;
	}
}
