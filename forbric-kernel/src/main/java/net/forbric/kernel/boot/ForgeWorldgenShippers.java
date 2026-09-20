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
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.util.ForbricLog;

/**
 * Which installed mods ship MinecraftForge biome or structure modifier files — so that when the bridge is off
 * ({@code -Dforbric.forgeWorldgen=off}) each of them is NAMED as losing the feature rather than the world going
 * silently vanilla.
 */
public final class ForgeWorldgenShippers {
	private static final Pattern MODIFIER = Pattern.compile("^data/([^/]+)/forge/(biome_modifier|structure_modifier)/.+\\.json$");

	private ForgeWorldgenShippers() {
	}

	/** One jar that ships modifier files: the namespaces its files live under, and how many. */
	public record Shipper(String jar, Set<String> namespaces, int files) {
	}

	/** Pure: the jars among {@code jars} that ship Forge modifier files, in the given order. */
	public static List<Shipper> scan(List<Path> jars) {
		List<Shipper> shippers = new ArrayList<>();
		for (Path jar : jars) {
			Set<String> namespaces = new LinkedHashSet<>();
			int files = 0;
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				Enumeration<? extends ZipEntry> entries = zip.entries();
				while (entries.hasMoreElements()) {
					Matcher match = MODIFIER.matcher(entries.nextElement().getName());
					if (!match.matches()) continue;
					namespaces.add(match.group(1));
					files++;
				}
			} catch (Exception unreadable) {
				continue;
			}
			if (files > 0) shippers.add(new Shipper(jar.getFileName().toString(), namespaces, files));
		}
		return shippers;
	}

	/**
	 * Reports the shippers, and when the bridge is off, marks each one DEGRADED. Attribution goes through the
	 * catalog by jar name; a namespace with no catalog row of its own is tried as a mod id, and
	 * {@link ModCatalog#mark} drops what it does not know rather than inventing a row.
	 */
	public static void report(List<Path> jars, boolean bridgeEnabled) {
		List<Shipper> shippers = scan(jars);
		if (shippers.isEmpty()) return;
		Map<String, String> byJar = new LinkedHashMap<>();
		for (ModCatalog.Entry entry : ModCatalog.everything()) {
			if (entry.ecosystem() == Ecosystem.FORGE && entry.jar() != null) byJar.putIfAbsent(entry.jar(), entry.modId());
		}
		List<String> named = new ArrayList<>();
		for (Shipper shipper : shippers) {
			named.add(shipper.jar() + " (" + shipper.files() + " file(s) under " + shipper.namespaces() + ")");
		}
		if (bridgeEnabled) {
			ForbricLog.info("[Forbric/Worldgen] %d MinecraftForge mod jar(s) ship forge biome/structure modifier files: %s",
					shippers.size(), named);
			return;
		}
		ForbricLog.warn("[Forbric/Worldgen] -Dforbric.forgeWorldgen=off — %d MinecraftForge mod jar(s) ship biome/structure "
				+ "modifiers that will NOT apply: %s", shippers.size(), named);
		for (Shipper shipper : shippers) {
			Set<String> ids = new LinkedHashSet<>();
			String owner = byJar.get(shipper.jar());
			if (owner != null) ids.add(owner);
			ids.addAll(shipper.namespaces());
			for (String id : ids) {
				ModCatalog.mark(id, ModCatalog.Status.DEGRADED,
						"-Dforbric.forgeWorldgen=off: its forge biome/structure modifier files are not applied");
			}
		}
	}
}
