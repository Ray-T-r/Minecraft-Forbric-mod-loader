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

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

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
 * <p>Scope, stated honestly: this serves MOD jars only. The two runtime carriers ({@code neoforge-runtime.jar},
 * {@code forge-runtime-interop.jar}) also hold real data — {@code data/c/tags}, {@code data/neoforge/damage_type},
 * their own recipes and advancements — and the merged base carries only {@code data/minecraft/*}, so the loaders'
 * OWN datapacks are missing too. Serving them is a separate change: the two carriers hold competing versions of the
 * same-id recipes (NeoForge forked Forge), so it needs a tri-ecosystem answer, and it would move the hardcoded
 * {@code Loaded 1585 recipes} that three gates use as their "datapacks loaded at all" canary.
 */
public final class KernelDataPacks {
	/** {@code off} restores the unserved behaviour — i.e. puts the silent absence back. */
	static final String PROPERTY = "forbric.modDataPacks";

	private KernelDataPacks() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(String.valueOf(System.getProperty(PROPERTY, "on")).trim());
	}

	/**
	 * Adds a {@code RepositorySource} serving each Forge-family jar in {@code jars} that carries {@code data/}.
	 * Best-effort: a failure costs that mod's datapack, never the boot.
	 *
	 * @param packType the caller's {@code PackType}, passed through rather than re-resolved — the caller has already
	 *                 established it is {@code SERVER_DATA}
	 */
	public static void addTo(Object packRepository, Object packType, ClassLoader cl, List<Path> jars) {
		if (packRepository == null || packType == null || jars == null || jars.isEmpty()) return;
		if (!enabled()) {
			ForbricLog.warn("[Forbric/DataPacks] mod datapacks DISABLED (-D%s=off) — every Forge-family mod's "
					+ "recipes, tags and datapack-registry content will be missing", PROPERTY);
			return;
		}
		try {
			List<Path> serve = forgeFamilyJarsWithData(jars);
			if (serve.isEmpty()) {
				ForbricLog.debug("[Forbric/DataPacks] no Forge-family jar carries data/ — nothing to serve");
				return;
			}

			List<Object> packs = new ArrayList<>();
			List<String> ids = new ArrayList<>();
			for (Path jar : serve) {
				String id = "forbric/data/" + stripExtension(jar.getFileName().toString());
				Object pack = buildPack(cl, id, jar, packType);
				if (pack != null) {
					packs.add(pack);
					ids.add(id);
				}
			}
			if (packs.isEmpty()) return;

			Object source = buildSource(cl, packs, ids);
			Class<?> repoCls = Class.forName("net.minecraft.server.packs.repository.PackRepository", false, cl);
			Class<?> sourceCls = Class.forName("net.minecraft.server.packs.repository.RepositorySource", false, cl);
			repoCls.getMethod("addPackFinder", sourceCls).invoke(packRepository, source);
			ForbricLog.info("[Forbric/DataPacks] served %d mod datapack(s) to the server PackRepository — a "
					+ "Forge-family mod's own data/ is invisible otherwise, because ModList.modFiles is empty: %s",
					ids.size(), ids);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/DataPacks] could not serve mod datapacks (Forge-family mods' recipes, tags and "
					+ "worldgen data will be missing)", KernelBusSupport.unwrap(t));
		}
	}

	/**
	 * The jars whose {@code data/} this path owns: claimed by NeoForge or traditional Forge, and carrying data.
	 *
	 * <p>Fabric-claimed jars are excluded on purpose — fabric-api's resource loader already serves those from
	 * Fabric's own mod list, and serving them twice would append every tag entry a second time. A jar claimed by
	 * nobody (a plain library) has no mod identity and is left alone, as it is on a genuine loader.
	 */
	static List<Path> forgeFamilyJarsWithData(List<Path> jars) {
		List<Path> serve = new ArrayList<>();
		for (Path jar : jars) {
			MultiLoaderArbiter.Ecosystem owner = MultiLoaderArbiter.ownerOf(jar);
			if (owner != MultiLoaderArbiter.Ecosystem.NEOFORGE
					&& owner != MultiLoaderArbiter.Ecosystem.MINECRAFTFORGE) {
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

	/**
	 * One {@code Pack} over {@code jar}, built by NeoForge's own {@code readWithOptionalMeta}.
	 *
	 * <p>The selection config is NeoForge's own {@code MOD_PACK_SELECTION_CONFIG} shape —
	 * {@code (required=false, TOP, fixed=false)}. Not required, because that is how a genuine instance treats a mod
	 * pack: the server auto-enables a pack it has not seen before ("Found new data pack …, loading it
	 * automatically"), and forcing it would take away the operator's ability to turn a mod's data off. TOP, so mod
	 * data overrides vanilla's and a user datapack added later still overrides the mod's.
	 */
	private static Object buildPack(ClassLoader cl, String id, Path jar, Object packType) {
		try {
			Class<?> loaderCls = Class.forName("net.neoforged.neoforge.resource.ResourcePackLoader", false, cl);
			Class<?> locCls = Class.forName("net.minecraft.server.packs.PackLocationInfo", false, cl);
			Class<?> suppCls = Class.forName("net.minecraft.server.packs.repository.Pack$ResourcesSupplier", false, cl);
			Class<?> typeCls = Class.forName("net.minecraft.server.packs.PackType", false, cl);
			Class<?> selCls = Class.forName("net.minecraft.server.packs.PackSelectionConfig", false, cl);
			Class<?> posCls = Class.forName("net.minecraft.server.packs.repository.Pack$Position", false, cl);
			Class<?> srcCls = Class.forName("net.minecraft.server.packs.repository.PackSource", false, cl);
			Class<?> componentCls = Class.forName("net.minecraft.network.chat.Component", false, cl);
			Class<?> fileSuppCls =
					Class.forName("net.minecraft.server.packs.FilePackResources$FileResourcesSupplier", false, cl);

			Object title = componentCls.getMethod("literal", String.class).invoke(null, id);
			Object resources = fileSuppCls.getConstructor(Path.class).newInstance(jar);
			Object location = locCls.getConstructor(String.class, componentCls, srcCls, Optional.class)
					.newInstance(id, title, srcCls.getField("BUILT_IN").get(null), Optional.empty());
			Object selection = selCls.getConstructor(boolean.class, posCls, boolean.class)
					.newInstance(false, posCls.getField("TOP").get(null), false);

			Method read = loaderCls.getMethod("readWithOptionalMeta", locCls, suppCls, typeCls, selCls);
			return read.invoke(null, location, resources, packType, selection);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/DataPacks] could not build a datapack over %s — that mod's data/ will be "
					+ "missing: %s", jar.getFileName(), String.valueOf(KernelBusSupport.unwrap(t)));
			return null;
		}
	}

	/** A {@code RepositorySource} proxy whose {@code loadPacks(Consumer)} emits our packs. */
	private static Object buildSource(ClassLoader cl, List<Object> packs, List<String> ids) throws Exception {
		Class<?> sourceCls = Class.forName("net.minecraft.server.packs.repository.RepositorySource", false, cl);
		return Proxy.newProxyInstance(cl, new Class<?>[] {sourceCls}, (proxy, method, args) -> switch (method.getName()) {
			case "loadPacks" -> {
				if (args != null && args.length == 1 && args[0] instanceof Consumer<?> consumer) {
					@SuppressWarnings("unchecked")
					Consumer<Object> sink = (Consumer<Object>) consumer;
					for (Object pack : packs) sink.accept(pack);
				}
				yield null;
			}
			case "toString" -> "ForbricKernelModDataPackSource" + ids;
			case "hashCode" -> System.identityHashCode(proxy);
			case "equals" -> proxy == (args == null ? null : args[0]);
			default -> null;
		});
	}
}
