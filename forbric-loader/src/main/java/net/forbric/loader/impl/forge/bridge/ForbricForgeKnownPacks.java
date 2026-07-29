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

package net.forbric.loader.impl.forge.bridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.forbric.loader.impl.util.ForbricLog;
import net.forbric.loader.impl.util.ForbricVersionGate;

/**
 * Known-pack-aware client/server pack bridge for wrapped traditional-Forge mods on the tri-in-one merged base.
 */
public final class ForbricForgeKnownPacks {
	private static final String FORGE_RUNTIME_MOD_ID = "forge";
	private static final Set<Object> SERVER_DATA_REPOSITORIES =
			Collections.newSetFromMap(Collections.synchronizedMap(new IdentityHashMap<>()));

	private ForbricForgeKnownPacks() {
	}

	public static void addClientResourcePacksTo(Object packRepository, ClassLoader cl) throws Exception {
		if (!isMergedForgeNeoBase(cl)) return;
		List<ForgePackCandidate> mods = collectForgeContentMods();
		if (mods.isEmpty()) {
			ForbricLog.info("[Forbric/ForgeAssets] no traditional-Forge content mods present — nothing to wire");
			return;
		}

		List<Object> packs = buildPacks(cl, mods, ForbricKnownPackIdentity.CLIENT_RESOURCES);
		if (packs.isEmpty()) return;

		List<String> served = packIds(mods, ForbricKnownPackIdentity.CLIENT_RESOURCES);
		Object source = buildSource(cl, packs, served, "ForbricForgeClientPackSource");
		invokeAddPackFinder(packRepository, cl, source);
		ForbricLog.info("[Forbric/ForgeAssets] wired " + served.size() + " traditional-Forge mod asset pack(s) into the "
				+ "client PackRepository (known-pack aware, forced-compatible, required): " + served);
	}

	public static void addServerDataPacksIfApplicable(Object packRepository, ClassLoader cl) {
		if (packRepository == null || !isMergedForgeNeoBase(cl)) return;
		if (!SERVER_DATA_REPOSITORIES.add(packRepository)) return;
		if (!looksLikeServerDataRepository(packRepository, cl)) return;

		try {
			List<ForgePackCandidate> mods = collectForgeContentMods();
			if (mods.isEmpty()) return;

			List<Object> packs = buildPacks(cl, mods, ForbricKnownPackIdentity.SERVER_DATA);
			if (packs.isEmpty()) return;

			List<String> served = packIds(mods, ForbricKnownPackIdentity.SERVER_DATA);
			Object source = buildSource(cl, packs, served, "ForbricForgeServerDataSource");
			invokeAddPackFinder(packRepository, cl, source);
			ForbricLog.info("[Forbric/ForgeAssets] wired " + served.size()
					+ " traditional-Forge mod data pack(s) into the server PackRepository (known-pack aware): " + served);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/ForgeAssets] wiring Forge-mod server-data packs failed", t);
		}
	}

	private static boolean isMergedForgeNeoBase(ClassLoader cl) {
		try {
			Class.forName("net.minecraftforge.registries.NamespacedWrapper", false, cl);
			Class.forName("net.neoforged.neoforge.network.registration.NetworkRegistry", false, cl);
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static void invokeAddPackFinder(Object packRepository, ClassLoader cl, Object source) throws Exception {
		Class<?> repositorySourceCls = Class.forName("net.minecraft.server.packs.repository.RepositorySource", false, cl);
		packRepository.getClass().getMethod("addPackFinder", repositorySourceCls).invoke(packRepository, source);
	}

	private static Object buildSource(ClassLoader cl, List<Object> packs, List<String> served, String sourceName)
			throws Exception {
		Class<?> repositorySourceCls = Class.forName("net.minecraft.server.packs.repository.RepositorySource", false, cl);
		return Proxy.newProxyInstance(cl, new Class<?>[] { repositorySourceCls }, (proxy, method, args) -> {
			switch (method.getName()) {
				case "loadPacks":
					if (args != null && args.length == 1 && args[0] instanceof Consumer<?>) {
						@SuppressWarnings("unchecked")
						Consumer<Object> consumer = (Consumer<Object>) args[0];
						for (Object pack : packs) consumer.accept(pack);
					}
					return null;
				case "toString":
					return sourceName + served;
				case "hashCode":
					return System.identityHashCode(proxy);
				case "equals":
					return proxy == (args == null ? null : args[0]);
				default:
					return null;
			}
		});
	}

	private static List<Object> buildPacks(ClassLoader cl, List<ForgePackCandidate> mods, String packType) throws Exception {
		Class<?> packCls = Class.forName("net.minecraft.server.packs.repository.Pack", false, cl);
		Class<?> packMetaCls = Class.forName("net.minecraft.server.packs.repository.Pack$Metadata", false, cl);
		Class<?> resourcesSupplierCls = Class.forName("net.minecraft.server.packs.repository.Pack$ResourcesSupplier", false, cl);
		Class<?> positionCls = Class.forName("net.minecraft.server.packs.repository.Pack$Position", false, cl);
		Class<?> packLocationInfoCls = Class.forName("net.minecraft.server.packs.PackLocationInfo", false, cl);
		Class<?> packSelectionConfigCls = Class.forName("net.minecraft.server.packs.PackSelectionConfig", false, cl);
		Class<?> packSourceCls = Class.forName("net.minecraft.server.packs.repository.PackSource", false, cl);
		Class<?> packCompatibilityCls = Class.forName("net.minecraft.server.packs.repository.PackCompatibility", false, cl);
		Class<?> fileSupplierCls = Class.forName("net.minecraft.server.packs.FilePackResources$FileResourcesSupplier", false, cl);
		Class<?> featureFlagSetCls = Class.forName("net.minecraft.world.flag.FeatureFlagSet", false, cl);
		Class<?> componentCls = Class.forName("net.minecraft.network.chat.Component", false, cl);
		Class<?> knownPackCls = Class.forName("net.minecraft.server.packs.repository.KnownPack", false, cl);

		Object packSourceBuiltIn = packSourceCls.getField("BUILT_IN").get(null);
		Object compatibilityCompatible = packCompatibilityCls.getField("COMPATIBLE").get(null);
		Object positionTop = positionCls.getField("TOP").get(null);
		Object emptyFeatures = featureFlagSetCls.getMethod("of").invoke(null);
		Method literal = componentCls.getMethod("literal", String.class);

		Constructor<?> fileSupplierCtor = fileSupplierCls.getConstructor(Path.class);
		Constructor<?> locationCtor =
				packLocationInfoCls.getConstructor(String.class, componentCls, packSourceCls, Optional.class);
		Constructor<?> metaCtor =
				packMetaCls.getConstructor(componentCls, packCompatibilityCls, featureFlagSetCls, List.class);
		Constructor<?> selectionCtor =
				packSelectionConfigCls.getConstructor(boolean.class, positionCls, boolean.class);
		Constructor<?> packCtor = packCls.getConstructor(
				packLocationInfoCls, resourcesSupplierCls, packMetaCls, packSelectionConfigCls);
		Constructor<?> knownPackCtor = knownPackCls.getConstructor(String.class, String.class, String.class);

		List<Object> packs = new ArrayList<>();
		for (ForgePackCandidate mod : mods) {
			ForbricKnownPackIdentity.Descriptor descriptor =
					ForbricKnownPackIdentity.descriptor(mod.ecosystem(), packType, mod.modId(), mod.version());
			Object title = literal.invoke(null, descriptor.titleText());
			Object knownPack = knownPackCtor.newInstance(descriptor.knownPackNamespace(), descriptor.knownPackId(),
					descriptor.version());
			Object location = locationCtor.newInstance(descriptor.locationId(), title, packSourceBuiltIn,
					Optional.of(knownPack));
			Object supplier = fileSupplierCtor.newInstance(mod.jar());
			Object meta = metaCtor.newInstance(title, compatibilityCompatible, emptyFeatures, List.of());
			Object selection = selectionCtor.newInstance(Boolean.TRUE, positionTop, Boolean.FALSE);
			packs.add(packCtor.newInstance(location, supplier, meta, selection));
		}
		return packs;
	}

	private static List<String> packIds(List<ForgePackCandidate> mods, String packType) {
		List<String> ids = new ArrayList<>(mods.size());
		for (ForgePackCandidate mod : mods) {
			ids.add(ForbricKnownPackIdentity.descriptor(mod.ecosystem(), packType, mod.modId(), mod.version()).locationId());
		}
		return ids;
	}

	private static boolean looksLikeServerDataRepository(Object packRepository, ClassLoader cl) {
		try {
			Field sourcesField = findField(packRepository.getClass(), "sources");
			if (sourcesField == null) return false;
			sourcesField.setAccessible(true);
			Object sources = sourcesField.get(packRepository);
			if (!(sources instanceof Iterable<?> iterable)) return false;

			Class<?> folderSourceCls =
					Class.forName("net.minecraft.server.packs.repository.FolderRepositorySource", false, cl);
			Class<?> packSourceCls = Class.forName("net.minecraft.server.packs.repository.PackSource", false, cl);
			Object world = packSourceCls.getField("WORLD").get(null);
			Object server = packSourceCls.getField("SERVER").get(null);
			Field packSourceField = findField(folderSourceCls, "packSource");
			if (packSourceField == null) return false;
			packSourceField.setAccessible(true);

			for (Object source : iterable) {
				if (source == null || !folderSourceCls.isInstance(source)) continue;
				Object packSource = packSourceField.get(source);
				if (packSource == world || packSource == server) return true;
			}
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/ForgeAssets] could not classify PackRepository for server-data known-pack bridge", t);
		}
		return false;
	}

	private static List<ForgePackCandidate> collectForgeContentMods() {
		List<ForgePackCandidate> out = new ArrayList<>();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			String id = mod.getMetadata().getId();
			if (FORGE_RUNTIME_MOD_ID.equals(id) || id.startsWith("forbric")) continue;

			boolean isWrappedForgeMod = mod.getMetadata().containsCustomValue("forbric:forgeClasses")
					|| mod.getMetadata().containsCustomValue("forbric:forgeClass");
			if (!isWrappedForgeMod) continue;

			CustomValue eco = mod.getMetadata().getCustomValue("forbric:ecosystem");
			String ecosystem = eco != null && eco.getType() == CustomValue.CvType.STRING ? eco.getAsString() : "forge";
			if (!"forge".equals(ecosystem)) continue;
			if (ForbricVersionGate.isVersionIncompatible(id)) {
				ForbricLog.info("[Forbric/ForgeAssets] skipping pack bridge for version-incompatible mod '" + id + "'");
				continue;
			}
			if (mod.getOrigin().getKind() != ModOrigin.Kind.PATH) continue;

			Path jar = firstOriginJar(mod);
			if (jar == null) continue;
			Version version = mod.getMetadata().getVersion();
			out.add(new ForgePackCandidate(id, ecosystem, version == null ? "0" : version.getFriendlyString(), jar));
		}
		return out;
	}

	private static Path firstOriginJar(ModContainer mod) {
		Path first = null;
		for (Path path : mod.getOrigin().getPaths()) {
			if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".jar")) continue;
			if (first == null) {
				first = path;
			} else {
				ForbricLog.warn("[Forbric/ForgeAssets] mod '" + mod.getMetadata().getId()
						+ "' exposed multiple jar origins; using only " + first.getFileName()
						+ " for known-pack identity and resource serving");
				break;
			}
		}
		return first;
	}

	private static Field findField(Class<?> c, String name) {
		for (; c != null; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField(name);
			} catch (NoSuchFieldException ignore) {
				// try superclass
			}
		}
		return null;
	}

	private record ForgePackCandidate(String modId, String ecosystem, String version, Path jar) {
	}
}
