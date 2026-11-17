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
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

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
 * <p>The runtime carrier ({@code neoforge-runtime.jar}) is served the same way, and it is not an afterthought: the
 * game base carries only {@code data/minecraft/*}, so without it the whole {@code c:} convention-tag skeleton — 513
 * tag files, the thing every cross-mod recipe is written against — simply does not exist, and neither does
 * {@code neoforge:damage_type} or NeoForge's data maps.
 *
 * <p>Forbric 0.3.x and earlier also served a MinecraftForge carrier ({@code forge-runtime-interop.jar}), and ordering
 * the two was measured then: of the 746 data files both carriers shipped, 640 were byte-identical; of the 106 that
 * differed, the tags were additive and the 49 last-wins files went to NeoForge's, whose versions are written against
 * its own registered ingredient types ({@code neoforge:difference} and friends). The rank digit in a carrier's pack id
 * is what ordered them.
 *
 * <p>What this does NOT do is add recipes. Every one of the carrier's recipe files overrides a vanilla
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
		// the carriers among themselves. Result, lowest first: the carrier (NeoForge's), then the mods, which override
		// it. That is the stack a genuine instance has, and it is now a property of the names, which cannot be
		// reordered out from under it.
		List<Object> packs = new ArrayList<>();
		List<String> ids = new ArrayList<>();
		Set<String> takenCarrierIds = new java.util.LinkedHashSet<>();
		int loaderPacks = collect(cl, packType, carriersWithData(runtimeJars), packs, ids,
				jar -> carrierPackId(jar, takenCarrierIds), KernelDataPacks::carrierKnownPack);
		Set<String> takenModIds = new java.util.LinkedHashSet<>();
		int modPacks = collect(cl, packType, forgeFamilyJarsWithData(jars), packs, ids,
				jar -> modPackId(jar, KernelDataPacks::firstModIdIn, takenModIds), KernelDataPacks::modKnownPack);
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
			Caught.warn(t, "[Forbric/DataPacks] could not serve datapacks (the c: convention tags and every "
					+ "Forge-family mod's recipes, tags and worldgen data will be missing)");
		}
	}

	/** Builds a pack per jar, appending to {@code packs}/{@code ids}. Returns how many were built. */
	private static int collect(ClassLoader cl, Object packType, List<Path> jars, List<Object> packs,
			List<String> ids, Function<Path, String> idOf, Function<Path, List<String>> knownPackOf) {
		int built = 0;
		for (Path jar : jars) {
			String id = idOf.apply(jar);
			Object pack = buildPack(cl, id, jar, packType, knownPackOf.apply(jar));
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
	 *
	 * <p><b>NeoForge's carrier is {@link #NEO_CARRIER_ID} whatever its jar is called</b> (D43). The id used to end in
	 * the jar's file name, which is {@code neoforge-runtime.jar} on a staged tree but the library file name on an
	 * installed profile: {@code neoforge-runtime-26.2.jar} up to v0.3.1-beta2 and in the 0.4.0-dev builds
	 * that still had MinecraftForge, {@code neoforge-runtime-26.2.0.93.jar} since the carrier is versioned by its NeoForge
	 * build (4da13f7f). So every world an installed Forbric had written named a pack
	 * the next install no longer served, and vanilla enabled the "new" one at the end of the list, above every mod
	 * pack. Keyed on rank and family, the id no longer moves with the file; {@link #migrateCarrierIds} renames what old
	 * worlds recorded. A carrier that is not NeoForge's has no family to name and keeps its file name.
	 */
	static String carrierPackId(Path jar) {
		int rank = carrierRank(jar);
		if (rank == 2) return NEO_CARRIER_ID;
		return "forbric/carrier/" + rank + "-" + stripExtension(jar.getFileName().toString());
	}

	/**
	 * {@link #carrierPackId(Path)}, unless an earlier carrier of this boot already has that id: then the jar's file-name
	 * id, said out loud. Since the NeoForge carrier's id stopped naming its file (D43), a second jar with NeoForge's data
	 * would get the same id, and two packs under one id collapse into one in the pack repository without a word. One
	 * NeoForge carrier is all a launch passes today; this keeps a second from vanishing if that ever changes.
	 */
	static String carrierPackId(Path jar, Set<String> taken) {
		String id = carrierPackId(jar);
		if (taken.add(id)) return id;
		String byName = "forbric/carrier/" + carrierRank(jar) + "-" + stripExtension(jar.getFileName().toString());
		ForbricLog.warn("[Forbric/DataPacks] %s would share the carrier pack id %s with an earlier carrier; served as %s "
				+ "instead (a world records that id, so it moves if the file is renamed)", jar.getFileName(), id, byName);
		taken.add(byName);
		return byName;
	}

	/** The NeoForge carrier's datapack id: rank 2 (D2), family NeoForge (D43). Worlds record it in {@code level.dat}. */
	public static final String NEO_CARRIER_ID = "forbric/carrier/2-neoforge-runtime";

	/**
	 * {@code ids} — a world's enabled or disabled pack list — with every id an earlier install gave the
	 * NeoForge carrier ({@link #NEO_CARRIER_ID} + {@code "-"} + the jar's version, e.g.
	 * {@code forbric/carrier/2-neoforge-runtime-26.2}) renamed to {@link #NEO_CARRIER_ID} where it stands, so the
	 * world keeps the order it was saved with. Should a list end up naming the carrier twice (an old id and the current
	 * one), the first keeps its place and the later one goes. Returns null when nothing needed renaming.
	 *
	 * <p>MinecraftForge's carrier ids ({@code forbric/carrier/1-forge-runtime-interop…}) are left alone: nothing serves
	 * that pack any more, and vanilla's one "Missing data pack" line per upgraded world is the honest answer.
	 */
	public static List<String> migrateCarrierIds(List<String> ids) {
		if (ids == null || ids.isEmpty()) return null;
		boolean renamed = false;
		List<String> out = new ArrayList<>(ids.size());
		for (String id : ids) {
			String now = id != null && id.startsWith(NEO_CARRIER_ID + "-") ? NEO_CARRIER_ID : id;
			renamed |= !java.util.Objects.equals(now, id);
			if (NEO_CARRIER_ID.equals(now) && out.contains(NEO_CARRIER_ID)) {
				renamed = true;
				continue;
			}
			out.add(now);
		}
		return renamed ? out : null;
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
	 * The known pack the genuine platform declares for a jar's data pack: {@code [namespace, id, version]}, or null
	 * where it declares none.
	 *
	 * <p>A registry entry's lifecycle follows its pack: an entry loaded from a pack that is not a known pack is
	 * {@code Lifecycle.experimental()}, and one such entry makes the whole world's lifecycle experimental — which is
	 * the "Experimental Settings / Make a Backup" screen before every world load. NeoForge declares every mod file's pack
	 * a known pack — {@code KnownPack("neoforge", "mod/" + its mod ids, their versions)}, NeoForge itself included, whose
	 * {@code data/neoforge/damage_type} entry is one — so on a genuine instance that entry is stable and nothing is asked.
	 * MinecraftForge declares none. The kernel serves these jars itself, so it declares what their own platform would:
	 * NeoForge's form for a jar NeoForge owns, nothing for one MinecraftForge owns.
	 */
	static List<String> knownPack(Ecosystem owner, List<String> modIds, List<String> versions) {
		if (owner != Ecosystem.NEOFORGE || modIds == null || modIds.isEmpty()) return null;
		return List.of("neoforge", "mod/" + String.join(",", modIds), String.join(",", versions));
	}

	/** {@link #knownPack} for a Forge-family mod jar, from the mods discovery found in it, in discovery order. */
	static List<String> modKnownPack(Path jar) {
		if (jar == null) return null;
		List<String> ids = new ArrayList<>(), versions = new ArrayList<>();
		String source = jar.toString();
		for (DiscoveredMod mod : ModPresence.forgeFamilyMods()) {
			if (!source.equals(mod.getSource())) continue;
			ids.add(mod.getId());
			versions.add(String.valueOf(mod.getVersion()));
		}
		// NeoForge's form for a jar NeoForge owns; no other claim declares one.
		return MultiLoaderArbiter.claimOf(jar).ownedBy(Ecosystem.NEOFORGE) ? knownPack(Ecosystem.NEOFORGE, ids, versions)
				: null;
	}

	/**
	 * {@link #knownPack} for a carrier: NeoForge's carrier is NeoForge's own mod file, read from its
	 * {@code neoforge.mods.toml} as NeoForge reads it; MinecraftForge's declares none.
	 */
	static List<String> carrierKnownPack(Path jar) {
		if (carrierRank(jar) != 2) return null;
		try (FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
			Path toml = fs.getPath("META-INF", "neoforge.mods.toml");
			if (!Files.isRegularFile(toml)) return null;
			String jarVersion = null;
			Path manifest = fs.getPath("META-INF", "MANIFEST.MF");
			if (Files.isRegularFile(manifest)) {
				try (var in = Files.newInputStream(manifest)) {
					jarVersion = new java.util.jar.Manifest(in).getMainAttributes().getValue("Implementation-Version");
				}
			}
			List<String> ids = new ArrayList<>(), versions = new ArrayList<>();
			try (var in = Files.newInputStream(toml)) {
				for (var mod : net.forbric.kernel.metadata.forge.ModsTomlParser.parse(in).getMods()) {
					ids.add(mod.getModId());
					versions.add(net.forbric.kernel.metadata.forge.ModsTomlParser.resolveVersion(mod.getVersion(), jarVersion));
				}
			}
			return knownPack(Ecosystem.NEOFORGE, ids, versions);
		} catch (Throwable unreadable) {
			Caught.warn(unreadable, "[Forbric/DataPacks] could not read the NeoForge mods.toml of carrier %s; it declares no known pack", jar.getFileName());
			return null;
		}
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

	/**
	 * Higher rank = later in the sorted order = wins a last-wins collision. NeoForge's carrier keeps rank 2, the number
	 * worlds have recorded in {@code level.dat} since MinecraftForge's carrier (rank 1) was beside it (D2); a carrier
	 * without NeoForge's data ranks 0. The rank is read off the jar, never off its name, and so is the id it gives.
	 */
	static int carrierRank(Path jar) {
		if (hasNamespace(jar, "neoforge")) return 2;
		return 0;
	}

	private static boolean hasNamespace(Path jar, String namespace) {
		try (FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
			for (Path root : fs.getRootDirectories()) {
				if (Files.isDirectory(root.resolve("data").resolve(namespace))) return true;
			}
		} catch (Throwable unreadable) {
			// not a zip, or no permission — rank it lowest
			Caught.warn(unreadable, "[Forbric/DataPacks] could not read %s to rank it as a carrier; ranked lowest",
					jar.getFileName());
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
			// Owned by NeoForge: a Fabric jar is served by fabric-api and a library has no mod identity. No jar is owned
			// by MinecraftForge any more. A refused jar never gets here in a boot — discovery yields no mod of one in
			// mods/, and KernelBoot takes every refused nested jar out of the mod jars before they are handed on — so
			// the refused arm is reached only by a test that calls this directly, and is kept so its verdict holds
			// while the refusal is spelled as a claim instead of an owner of FORGE. A refused jar serves no data
			// (interop-misc); the arm goes with the FORGE spelling.
			JarClaim claim = MultiLoaderArbiter.claimOf(jar);
			if (!claim.ownedBy(Ecosystem.NEOFORGE) && !claim.refused()) continue;
			if (!carriesData(jar)) continue;
			List<String> failed = failedModsOf(jar);
			if (failed != null) {
				ForbricLog.warn("[Forbric/DataPacks] not serving %s's data/ — %s did not finish loading, and its data would name "
						+ "content it never registered, which fails the whole registry load", jar.getFileName(), failed);
				continue;
			}
			serve.add(jar);
		}
		return serve;
	}

	/** The mod ids of {@code jar} when the catalogue has rows for it and EVERY one is FAILED; else null. */
	static List<String> failedModsOf(Path jar) {
		String name = jar.getFileName().toString();
		List<String> ids = new ArrayList<>();
		for (net.forbric.api.ModCatalog.Entry e : net.forbric.api.ModCatalog.everything()) {
			if (!name.equals(e.jar())) continue;
			if (e.status() != net.forbric.api.ModCatalog.Status.FAILED) return null;
			ids.add(e.modId());
		}
		return ids.isEmpty() ? null : ids;
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
			Caught.warn(unreadable, "[Forbric/DataPacks] could not read %s to look for data/; its data/ is not served",
					jar.getFileName());
		}
		return false;
	}

	static String stripExtension(String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	/** One {@code Pack} over {@code jar}; the shape it is given is written down where it is built, game-side. */
	private static Object buildPack(ClassLoader cl, String id, Path jar, Object packType, List<String> knownPack) {
		try {
			return gameSide(cl).getMethod("buildPack", String.class, Path.class, Object.class, String.class, String.class, String.class)
					.invoke(null, id, jar, packType, knownPack == null ? null : knownPack.get(0),
							knownPack == null ? null : knownPack.get(1), knownPack == null ? null : knownPack.get(2));
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/DataPacks] could not build a datapack over %s — that mod's data/ will be "
					+ "missing", jar.getFileName());
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
