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

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModPresence;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;

/**
 * Serves a Forge-family mod jar's own {@code data/} — its recipes, tags, loot tables, advancements and datapack
 * registry content — to the SERVER datapack {@code PackRepository}.
 *
 * <p>Why this has to exist at all. A genuine loader turns every mod jar into a pack: NeoForge's
 * {@code ResourcePackLoader.findResourcePacks()} walks {@code ModList.get().getModFiles()} and builds one
 * {@code Pack} per mod file, for both {@code PackType}s. The kernel publishes its mods into {@code ModList} through
 * {@code setLoadedMods}, which fills {@code mods}/{@code sortedContainers}/{@code indexedMods} but deliberately
 * leaves {@code modFiles} EMPTY — so that walk finds nothing and not one Forge-family mod's data is ever read.
 * Fabric mods are unaffected: fabric-api's own resource loader serves them from Fabric's mod list, which is why the
 * gap stayed invisible for so long — every gate that checked datapack content happened to use Fabric builds.
 *
 * <p>What it actually costs, measured. lithostitched ships the same {@code data/} tree in its Fabric and its
 * NeoForge build — byte-identical listings. Booted at the same seed with the same fabric-api, the FABRIC build
 * reaches {@code Done}; the NEOFORGE build dies generating the first ruined portal, because lithostitched's own
 * mixin redirects vanilla's template selection into {@code TemplateLists.getRandom}, which does
 * {@code registry.get(RUINED_PORTAL_STANDARD).get()} on a {@code lithostitched:template_list} registry that exists
 * (the kernel declares it correctly) and is EMPTY (nothing ever read the JSON that fills it). Same mod, same data,
 * same seed — only the manifest differs. That is the shape of this bug: not a crash in the loader, a silent absence
 * that surfaces as the mod's own code failing somewhere unrelated.
 *
 * <p>Metadata is read through NeoForge's own {@code ResourcePackLoader.readWithOptionalMeta} rather than
 * synthesised, which is the difference from {@link KernelClientPacks}: that path invents a forced-COMPATIBLE
 * {@code Pack$Metadata} and never opens the jar's {@code pack.mcmeta}, which is fine for assets but would throw away
 * a mod's root-pack {@code overlays} — the very sections {@code PackOverlayMutabilityInjector} exists to keep
 * working. {@code readWithOptionalMeta} reads the real metadata with an unlimited supported-format range and
 * tolerates a jar with no {@code pack.mcmeta} at all, which is exactly the genuine loader's behaviour.
 *
 * <p>The two runtime carriers ({@code neoforge-runtime.jar}, {@code forge-runtime-interop.jar}) are served the
 * same way, and they are not an afterthought: the merged base carries only {@code data/minecraft/*}, so without
 * them the whole {@code c:} convention-tag skeleton — 513 tag files, the thing every cross-mod recipe is written
 * against — simply does not exist, and neither does {@code neoforge:damage_type} or either loader's data maps.
 *
 * <p>Ordering between the two was the part that needed an answer, and the measurement gave one. Of the 746 data
 * files both carriers ship, 640 are byte-identical; the 106 that differ are 57 tags, 30 loot tables and 19 recipes.
 * Tags are additive — not one carrier tag file sets {@code "replace": true} — so for those, serving both IS the
 * answer and the union is what a genuine tri-loader instance would have. The other 49 are last-wins, and NeoForge
 * takes them: the merged base IS NeoForge with MinecraftForge spliced in, and NeoForge's versions are written
 * against its own registered ingredient types ({@code neoforge:difference} and friends), which resolve here.
 *
 * <p>What this does NOT do is add recipes. Every one of the carriers' 208 and 394 recipe files overrides a vanilla
 * recipe id — none is new — so the {@code Loaded 1585 recipes} count three gates use as their canary does not move.
 * What changes is those recipes' CONTENT: NeoForge rewrites vanilla's ingredients to be tag-based (a bundle takes
 * {@code #c:leathers} rather than {@code minecraft:leather}), which is precisely what lets a mod's own leather work
 * in a vanilla recipe. Getting 1585 and getting the right 1585 are different claims; this one is about the second.
 */
public final class KernelDataPacks {
	/** {@code off} restores the unserved behaviour — i.e. puts the silent absence back. */
	static final String PROPERTY = "forbric.modDataPacks";

	/** The carriers' own data, switched separately: the two halves fail in visibly different ways. */
	static final String LOADER_PROPERTY = "forbric.loaderDataPacks";

	private KernelDataPacks() {
	}

	static boolean enabled() {
		return isOn(PROPERTY);
	}

	static boolean loaderDataEnabled() {
		return isOn(LOADER_PROPERTY);
	}

	private static boolean isOn(String property) {
		return !"off".equalsIgnoreCase(String.valueOf(System.getProperty(property, "on")).trim());
	}

	/**
	 * Adds a {@code RepositorySource} serving each Forge-family jar in {@code jars} that carries {@code data/}.
	 * Best-effort: a failure costs that mod's datapack, never the boot.
	 *
	 * @param packType    the caller's {@code PackType}, passed through rather than re-resolved — the caller has
	 *                    already established it is {@code SERVER_DATA}
	 * @param runtimeJars the ecosystem carriers, served BELOW the mod packs so a mod still overrides its loader
	 */
	public static void addTo(Object packRepository, Object packType, ClassLoader cl, List<Path> jars,
			List<Path> runtimeJars) {
		if (packRepository == null || packType == null) return;

		// PRIORITY IS THE ID STRING, not the order we emit in. PackRepository.discoverAvailable drains each source
		// into a TreeMap before merging it, so every pack from one source is re-sorted alphabetically by id and the
		// order this method hands them over is thrown away. (Measured: emitted forge, neoforge, lithostitched; the
		// world came back with neoforge-runtime ABOVE the mod, because "n" > "l".) So the stack is spelled into the
		// ids instead: "forbric/carrier/…" sorts before "forbric/data/…" because c < d, and the rank digit orders
		// the carriers among themselves. Result, lowest first: MinecraftForge, NeoForge — which therefore takes the
		// 49 last-wins files the two disagree on — then the mods, which override both. That is the stack a genuine
		// instance has, and it is now a property of the names, which cannot be reordered out from under it.
		List<Object> packs = new ArrayList<>();
		List<String> ids = new ArrayList<>();
		int loaderPacks = collect(cl, packType, carriersWithData(runtimeJars), packs, ids,
				KernelDataPacks::carrierPackId);
		Set<String> takenModIds = new java.util.LinkedHashSet<>();
		int modPacks = collect(cl, packType, forgeFamilyJarsWithData(jars), packs, ids,
				jar -> modPackId(jar, KernelDataPacks::firstModIdIn, takenModIds));
		if (packs.isEmpty()) {
			ForbricLog.debug("[Forbric/DataPacks] nothing carries data/ — no datapack to serve");
			return;
		}

		try {
			gameSide(cl).getMethod("addSource", Object.class, List.class, String.class)
					.invoke(null, packRepository, packs, "ForbricKernelModDataPackSource" + ids);
			ForbricLog.info("[Forbric/DataPacks] served %d datapack(s) to the server PackRepository — %d loader "
					+ "carrier(s) (the c: convention tags live only here) below %d mod pack(s) (a Forge-family "
					+ "mod's own data/ is invisible otherwise, because ModList.modFiles is empty): %s",
					ids.size(), loaderPacks, modPacks, ids);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/DataPacks] could not serve datapacks (the c: convention tags and every "
					+ "Forge-family mod's recipes, tags and worldgen data will be missing)",
					Reflect.unwrap(t));
		}
	}

	/** Builds a pack per jar, appending to {@code packs}/{@code ids}. Returns how many were built. */
	private static int collect(ClassLoader cl, Object packType, List<Path> jars, List<Object> packs,
			List<String> ids, Function<Path, String> idOf) {
		int built = 0;
		for (Path jar : jars) {
			String id = idOf.apply(jar);
			Object pack = buildPack(cl, id, jar, packType);
			if (pack != null) {
				packs.add(pack);
				ids.add(id);
				built++;
			}
		}
		return built;
	}

	/**
	 * The ecosystem carriers that carry data, lowest priority first.
	 *
	 * <p>Which carrier is which is read off the jar rather than off the caller's argument order: a NeoForge carrier
	 * has a {@code data/neoforge/} namespace, a traditional-Forge one has {@code data/forge/}. That keeps the rule
	 * where it can be checked instead of in an unwritten convention about how the launcher lists its jars.
	 */
	static List<Path> carriersWithData(List<Path> runtimeJars) {
		if (runtimeJars == null || runtimeJars.isEmpty()) return List.of();
		if (!loaderDataEnabled()) {
			ForbricLog.warn("[Forbric/DataPacks] loader datapacks DISABLED (-D%s=off) — the c: convention tags, "
					+ "the loaders' data maps and their tag-based rewrites of vanilla recipes will be missing",
					LOADER_PROPERTY);
			return List.of();
		}
		List<Path> serve = new ArrayList<>();
		for (Path jar : runtimeJars) {
			if (carriesData(jar)) serve.add(jar);
		}
		serve.sort(Comparator.comparingInt(KernelDataPacks::carrierRank));
		return serve;
	}

	/**
	 * A carrier's pack id. The {@code carrier} segment puts every carrier below every mod pack (c &lt; d), and the
	 * rank digit orders the carriers against each other — both by string comparison, which is the only ordering
	 * {@link #addTo} actually gets to control.
	 */
	static String carrierPackId(Path jar) {
		return "forbric/carrier/" + carrierRank(jar) + "-" + stripExtension(jar.getFileName().toString());
	}

	/**
	 * A mod's pack id. Sorts after every carrier id, so a mod overrides its loader.
	 *
	 * <p><b>Derived from the mod's ID, not its file name.</b> A world records which datapacks it has enabled, by
	 * id, in its save. With the file name in the id, updating or renaming a mod changed that id — so every world
	 * created before the update came back reporting a datapack it no longer has and one it has never seen, which
	 * is the "Experimental Settings / Create Backup" dialog, on every old world, after every mod update. A mod's
	 * id is the one name about it that does not change.
	 *
	 * <p>Falls back to the file name when the id is unknown or already taken. Two jars answering to one id would
	 * silently collapse into one pack — {@code PackRepository.discoverAvailable} drains each source into a map
	 * keyed by id — and losing a mod's data outright is far worse than an id that moves when the file is renamed.
	 */
	static String modPackId(Path jar, Function<Path, String> modIdOf, Set<String> taken) {
		String modId = modIdOf == null ? null : modIdOf.apply(jar);
		if (modId != null && !modId.isBlank() && taken.add("forbric/data/" + modId)) {
			return "forbric/data/" + modId;
		}
		return "forbric/data/" + stripExtension(jar.getFileName().toString());
	}

	/**
	 * The id of the first mod discovery found in {@code jar}, or null.
	 *
	 * <p>A jar may declare several mods; the first is used, and it is the same first every launch because the
	 * registry preserves discovery order. This reads the registry rather than re-parsing the jar, so it costs a
	 * map lookup.
	 */
	static String firstModIdIn(Path jar) {
		if (jar == null) return null;
		String source = jar.toString();
		for (DiscoveredMod mod : ModPresence.forgeFamilyMods()) {
			if (source.equals(mod.getSource())) return mod.getId();
		}
		return null;
	}

	/** Higher rank = later in the sorted order = wins a last-wins collision. */
	static int carrierRank(Path jar) {
		if (hasNamespace(jar, "neoforge")) return 2;
		if (hasNamespace(jar, "forge")) return 1;
		return 0;
	}

	private static boolean hasNamespace(Path jar, String namespace) {
		try (FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
			for (Path root : fs.getRootDirectories()) {
				if (Files.isDirectory(root.resolve("data").resolve(namespace))) return true;
			}
		} catch (Throwable unreadable) {
			// not a zip, or no permission — rank it lowest
		}
		return false;
	}

	/**
	 * The jars whose {@code data/} this path owns: claimed by NeoForge or traditional Forge, and carrying data.
	 *
	 * <p>Fabric-claimed jars are excluded on purpose — fabric-api's resource loader already serves those from
	 * Fabric's own mod list, and serving them twice would append every tag entry a second time. A jar claimed by
	 * nobody (a plain library) has no mod identity and is left alone, as it is on a genuine loader.
	 */
	static List<Path> forgeFamilyJarsWithData(List<Path> jars) {
		if (jars == null || jars.isEmpty()) return List.of();
		if (!enabled()) {
			ForbricLog.warn("[Forbric/DataPacks] mod datapacks DISABLED (-D%s=off) — every Forge-family mod's "
					+ "recipes, tags and datapack-registry content will be missing", PROPERTY);
			return List.of();
		}
		List<Path> serve = new ArrayList<>();
		for (Path jar : jars) {
			Ecosystem owner = MultiLoaderArbiter.ownerOf(jar);
			if (owner != Ecosystem.NEOFORGE
					&& owner != Ecosystem.FORGE) {
				continue;
			}
			if (carriesData(jar)) serve.add(jar);
		}
		return serve;
	}

	/** True when {@code jar} has a {@code data/} directory — the only thing a datapack source can serve. */
	static boolean carriesData(Path jar) {
		if (jar == null || !Files.isRegularFile(jar)) return false;
		try (FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
			for (Path root : fs.getRootDirectories()) {
				if (Files.isDirectory(root.resolve("data"))) return true;
			}
		} catch (Throwable unreadable) {
			// not a zip, or no permission — nothing to serve
		}
		return false;
	}

	static String stripExtension(String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	/** One {@code Pack} over {@code jar}; the shape it is given is written down where it is built, game-side. */
	private static Object buildPack(ClassLoader cl, String id, Path jar, Object packType) {
		try {
			return gameSide(cl).getMethod("buildPack", String.class, Path.class, Object.class)
					.invoke(null, id, jar, packType);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/DataPacks] could not build a datapack over %s — that mod's data/ will be "
					+ "missing: %s", jar.getFileName(), String.valueOf(Reflect.unwrap(t)));
			return null;
		}
	}

	/**
	 * The game-side half: building a {@code Pack} and handing a {@code RepositorySource} over.
	 *
	 * <p>Everything above this line is policy that names no game type — which jars carry data, who owns them,
	 * what each pack is called and in what order they stack — and is tested as such.
	 */
	private static Class<?> gameSide(ClassLoader cl) throws ClassNotFoundException {
		return Class.forName("net.forbric.kernel.runtime.KernelDataPackSource", true, cl);
	}
}
