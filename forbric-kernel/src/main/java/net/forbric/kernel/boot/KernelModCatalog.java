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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.json.JsonFormat;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.metadata.forge.ForgeModEntry;
import net.forbric.kernel.metadata.forge.ForgeModsToml;
import net.forbric.kernel.metadata.forge.ModsTomlParser;
import net.forbric.kernel.util.ForbricLog;

/**
 * Fills {@link ModCatalog} from the mods discovery already found, by going back to each jar for the fields a
 * player sees and discovery has no reason to keep.
 *
 * <h2>Why a second read</h2>
 *
 * <p>{@link DiscoveredMod} carries what LOADING needs — id, version, display name, dependencies, mixin configs,
 * access transformers. Description, authors and the logo are not among them, and should not be: discovery runs
 * before Mixin and adding three unused strings to every mod's record would be paying for a screen nobody may
 * open. So the catalogue re-opens the jars once, when the list is first wanted, and asks for exactly the four
 * fields it is missing.
 *
 * <p>Best-effort throughout. A jar whose metadata cannot be re-read still appears, with the name and version
 * discovery already knew — the alternative is a mod vanishing from the list because its description could not be
 * parsed, which would be a worse answer to "what is installed" than a missing sentence.
 */
public final class KernelModCatalog {
	private KernelModCatalog() {
	}

	/**
	 * Builds and publishes the catalogue.
	 *
	 * @param mods every mod of every ecosystem, already arbitrated — the caller is the one place in the boot that
	 *             holds all three families at once
	 */
	public static void publish(List<DiscoveredMod> mods) {
		publish(mods, null);
	}

	/**
	 * @param modsDir the directory a player drops jars into. A mod whose jar is directly in it was INSTALLED; one
	 *                whose jar is anywhere else was extracted out of another jar and is bundled. Null means the
	 *                distinction cannot be drawn, and then everything counts as installed -- which is the old
	 *                behaviour, and wrong in the direction that shows too much rather than too little.
	 */
	public static void publish(List<DiscoveredMod> mods, Path modsDir) {
		if (mods == null || mods.isEmpty()) {
			ModCatalog.publish(List.of());
			return;
		}
		List<ModCatalog.Entry> entries = new ArrayList<>(mods.size());
		// One jar can declare several mods (fabric-api's nested modules, a mods.toml with two [[mods]]), so the
		// per-jar metadata is read once and asked for each id, rather than once per id.
		Map<String, Map<String, Display>> byJar = new LinkedHashMap<>();
		for (DiscoveredMod mod : mods) {
			if (mod == null || mod.getId() == null || mod.getId().isBlank()) continue;
			Map<String, Display> jar = byJar.computeIfAbsent(String.valueOf(mod.getSource()),
					source -> read(source, mod.getEcosystem()));
			Display d = jar.getOrDefault(mod.getId(), Display.EMPTY);
			entries.add(new ModCatalog.Entry(mod.getEcosystem(), mod.getId(),
					d.name.isEmpty() ? mod.getDisplayName() : d.name, mod.getVersion(), d.description, d.authors,
					fileName(mod.getSource()), d.icon, bundledBy(mod.getSource(), modsDir)));
		}
		ModCatalog.publish(entries);
		ForbricLog.info("[Forbric/Catalog] %d installed mod(s) for the unified Mods screen: %d Fabric, %d NeoForge,"
						+ " %d MinecraftForge — plus %d jar(s) they carry inside themselves, which are running and "
						+ "are not what a player means by \"my mods\"",
				ModCatalog.all().size(), ModCatalog.count(Ecosystem.FABRIC), ModCatalog.count(Ecosystem.NEOFORGE),
				ModCatalog.count(Ecosystem.FORGE), ModCatalog.everything().size() - ModCatalog.all().size());
	}

	/**
	 * Which installed jar carries this one, or {@code ""} if a player put it in {@code mods/} themselves.
	 *
	 * <p>The selected candidate graph supplies provenance for content-addressed files. Only selected parent
	 * edges count; multiple possible mod owners remain unknown. Legacy discovery without a matching plan falls
	 * back to the original layout: direct {@code mods/} children are installed, Fabric's old {@code jij/<parent>}
	 * paths retain their parent, and flattened JarJar files remain bundled with an unknown parent.
	 */
	static String bundledBy(String source, Path modsDir) {
		if (source == null || source.isBlank() || modsDir == null) return "";
		Path jar = Path.of(source).toAbsolutePath().normalize();
		NestedCandidatePlan plan = DuplicateModArbiter.currentPlan();
		if (plan != null) {
			var owner = plan.bundledBy(jar);
			if (owner.isPresent()) return owner.get();
		}
		Path parent = jar.getParent();
		if (parent != null && parent.equals(modsDir.toAbsolutePath().normalize())) return "";
		// .forbric-kernel/jij/<parent-mod-id>/<child>.jar
		if (parent != null && parent.getParent() != null
				&& JIJ_DIR.equals(String.valueOf(parent.getParent().getFileName()))) {
			return String.valueOf(parent.getFileName());
		}
		return UNKNOWN_PARENT;
	}

	private static final String JIJ_DIR = "jij";
	/** A bundled jar whose parent the extraction layout does not record. Still bundled; just unattributed. */
	static final String UNKNOWN_PARENT = "?";

	/** The display-only fields, per mod id, that discovery does not keep. */
	record Display(String name, String description, List<String> authors, String icon) {
		static final Display EMPTY = new Display("", "", List.of(), "");
	}

	/** Reads one jar's metadata file. Never throws: an unreadable jar yields no display fields, not no mod. */
	static Map<String, Display> read(String source, Ecosystem ecosystem) {
		Map<String, Display> out = new LinkedHashMap<>();
		if (source == null || source.isBlank() || "null".equals(source)) return out;
		Path jar = Path.of(source);
		if (!Files.isRegularFile(jar)) return out;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			if (ecosystem == Ecosystem.FABRIC) {
				readFabric(zip, out);
			} else {
				readForgeFamily(zip, out);
			}
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Catalog] no display metadata from %s: %s", source, String.valueOf(t));
		}
		return out;
	}

	private static void readFabric(ZipFile zip, Map<String, Display> out) throws Exception {
		ZipEntry entry = zip.getEntry("fabric.mod.json");
		if (entry == null) return;
		Config config;
		try (InputStream in = zip.getInputStream(entry)) {
			// Lenient on purpose: real fabric.mod.json files in the wild carry comments and trailing commas, and
			// a strict parse here would cost the mod its description over punctuation.
			config = JsonFormat.minimalInstance().createParser().parse(
					new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		String id = string(config, "id");
		if (id.isEmpty()) return;
		out.put(id, new Display(string(config, "name"), string(config, "description"), authors(config.get("authors")),
				icon(config.get("icon"))));
	}

	private static void readForgeFamily(ZipFile zip, Map<String, Display> out) throws Exception {
		// neoforge.mods.toml first: a universal jar ships both, and the NeoForge one is the more current of the two.
		for (String name : new String[] {"META-INF/neoforge.mods.toml", "META-INF/mods.toml"}) {
			ZipEntry entry = zip.getEntry(name);
			if (entry == null) continue;
			ForgeModsToml toml;
			try (InputStream in = zip.getInputStream(entry)) {
				toml = ModsTomlParser.parse(in);
			}
			for (ForgeModEntry mod : toml.getMods()) {
				if (mod.getModId() == null) continue;
				out.putIfAbsent(mod.getModId(), new Display(orEmpty(mod.getDisplayName()),
						orEmpty(mod.getDescription()), List.of(), ""));
			}
			if (!out.isEmpty()) return;
		}
	}

	/** {@code authors} is a list of strings OR of objects with a {@code name} — both shapes are in real packs. */
	private static List<String> authors(Object raw) {
		if (!(raw instanceof List<?> list)) return List.of();
		List<String> out = new ArrayList<>();
		for (Object o : list) {
			if (o instanceof String s && !s.isBlank()) out.add(s.trim());
			else if (o instanceof Config c) {
				String name = string(c, "name");
				if (!name.isEmpty()) out.add(name);
			}
		}
		return out;
	}

	/** {@code icon} is a path OR a size-keyed map; the largest declared size is the one worth showing. */
	private static String icon(Object raw) {
		if (raw instanceof String s) return s.trim();
		if (raw instanceof Config c) {
			String best = "";
			int bestSize = -1;
			for (Map.Entry<String, Object> e : c.valueMap().entrySet()) {
				int size;
				try {
					size = Integer.parseInt(e.getKey());
				} catch (NumberFormatException notASize) {
					continue;
				}
				if (size > bestSize && e.getValue() instanceof String path) {
					bestSize = size;
					best = path.trim();
				}
			}
			return best;
		}
		return "";
	}

	private static String string(Config config, String key) {
		Object v = config.get(key);
		return v instanceof String s ? s.trim() : "";
	}

	private static String orEmpty(String s) {
		return s == null ? "" : s.trim();
	}

	private static String fileName(String source) {
		if (source == null || source.isBlank()) return "";
		int slash = Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\'));
		return slash < 0 ? source : source.substring(slash + 1);
	}
}
