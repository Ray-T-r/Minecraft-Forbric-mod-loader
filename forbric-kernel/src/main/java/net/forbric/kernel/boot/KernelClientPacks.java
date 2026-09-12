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

import java.lang.reflect.Constructor;
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
 * Serves the ecosystem jars' CLIENT ASSETS (shaders, textures, models, lang) to the real client
 * {@code PackRepository}.
 *
 * <p>Why: NeoForge's own {@code ResourcePackLoader.findResourcePacks} (its {@code mod_resources} source) is ORPHANED
 * on the merged base — nothing calls it, because the vanilla/Forge pack-repository construction won that merge. So
 * every ecosystem asset is unreachable. That is invisible until something actually needs one: NeoForge's
 * {@code NeoForgeRenderPipelines} registers pipelines whose shaders live at {@code assets/neoforge/shaders/*}, and
 * without them {@code ShaderManager.apply} throws "Failed to load required shader programs" and the client dies before
 * the title screen. Forge-family mod jars have the same problem for their own textures/models.
 *
 * <p>Fix: add one kernel {@code RepositorySource} carrying a distinct pack per jar, straight onto the real repo, at
 * {@code ClientModLoader.setupModResourcePacks(PackRepository)} — the vanilla-woven call inside
 * {@code Minecraft.<init>} made for exactly this, which the kernel redirects here (it used to be neutered). That is
 * before the client's first resource reload, which is the only timing that matters.
 *
 * <p>Compatibility is FORCED to {@code COMPATIBLE}: these jars carry boilerplate/ancient {@code pack.mcmeta}
 * {@code pack_format}s that MC 26.2 would otherwise reject (both genuine loaders force this too). All game types are
 * reached reflectively — the kernel's boot side has no compile-time Minecraft dependency.
 */
public final class KernelClientPacks {
	private KernelClientPacks() {
	}

	/**
	 * Adds a {@code RepositorySource} serving each of {@code jars} that actually carries client resources.
	 * Best-effort: a failure costs assets (missing textures/shaders), never the boot.
	 */
	public static void addTo(Object packRepository, ClassLoader cl, List<Path> jars) {
		if (packRepository == null || jars == null || jars.isEmpty()) return;
		try {
			List<Path> packJars = new ArrayList<>();
			for (Path jar : jars) {
				if (carriesClientAssets(jar)) packJars.add(jar);
			}
			if (packJars.isEmpty()) {
				ForbricLog.debug("[Forbric/ClientPacks] no ecosystem jar carries client resources — nothing to serve");
				return;
			}

			List<Object> packs = new ArrayList<>();
			List<String> ids = new ArrayList<>();
			for (Path jar : packJars) {
				String id = "forbric/" + stripExtension(jar.getFileName().toString());
				Object pack = buildPack(cl, id, jar);
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
			ForbricLog.info("[Forbric/ClientPacks] served %d ecosystem asset pack(s) to the client PackRepository "
					+ "(forced-compatible): %s", ids.size(), ids);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/ClientPacks] could not serve ecosystem assets to the client PackRepository "
					+ "(ecosystem shaders/textures will be missing)", KernelBusSupport.unwrap(t));
		}
	}

	/**
	 * Jars that carry client resources: a {@code pack.mcmeta} OR an {@code assets/} directory.
	 *
	 * <p>This used to require {@code pack.mcmeta}, on the reasoning that a jar without one "would be rejected or
	 * logged as broken". That is not true of this code path — {@link #buildPack} SYNTHESISES the
	 * {@code Pack$Metadata} (title, forced-COMPATIBLE, no feature flags) and never reads the jar's own. The gate was
	 * therefore dropping jars that would have served perfectly, and both genuine loaders serve every mod jar as a
	 * pack whether or not it declares one.
	 *
	 * <p>What it cost, measured on the Odyssey pack: Sodium's real payload lives in a JiJ nested jar with 57 asset
	 * entries and no {@code pack.mcmeta}, so its terrain shaders were never served —
	 * {@code Couldn't find source for VERTEX shader (sodium:blocks/block_layer_opaque)}, then
	 * {@code Pipeline contains invalid shader program} the first frame a chunk drew. Iris (38 entries), MoreCulling
	 * (24), Sound Physics (10) and Lithium (5) were silently missing their assets for the same reason.
	 *
	 * <p>A jar with neither is still skipped — it has nothing to serve.
	 */
	private static boolean carriesClientAssets(Path jar) {
		if (jar == null || !Files.isRegularFile(jar)) return false;
		try (FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
			for (Path root : fs.getRootDirectories()) {
				if (Files.exists(root.resolve("pack.mcmeta"))) return true;
				if (Files.isDirectory(root.resolve("assets"))) return true;
			}
		} catch (Throwable ignored) {
			// unreadable / not a zip — nothing to serve
		}
		return false;
	}

	private static String stripExtension(String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	/** {@code Pack(PackLocationInfo, FileResourcesSupplier, Metadata(forced COMPATIBLE), PackSelectionConfig)}. */
	private static Object buildPack(ClassLoader cl, String id, Path jar) throws Exception {
		Class<?> packCls = Class.forName("net.minecraft.server.packs.repository.Pack", false, cl);
		Class<?> metaCls = Class.forName("net.minecraft.server.packs.repository.Pack$Metadata", false, cl);
		Class<?> suppCls = Class.forName("net.minecraft.server.packs.repository.Pack$ResourcesSupplier", false, cl);
		Class<?> posCls = Class.forName("net.minecraft.server.packs.repository.Pack$Position", false, cl);
		Class<?> locCls = Class.forName("net.minecraft.server.packs.PackLocationInfo", false, cl);
		Class<?> selCls = Class.forName("net.minecraft.server.packs.PackSelectionConfig", false, cl);
		Class<?> srcCls = Class.forName("net.minecraft.server.packs.repository.PackSource", false, cl);
		Class<?> compatCls = Class.forName("net.minecraft.server.packs.repository.PackCompatibility", false, cl);
		Class<?> fileSuppCls =
				Class.forName("net.minecraft.server.packs.FilePackResources$FileResourcesSupplier", false, cl);
		Class<?> flagsCls = Class.forName("net.minecraft.world.flag.FeatureFlagSet", false, cl);
		Class<?> componentCls = Class.forName("net.minecraft.network.chat.Component", false, cl);

		Method literal = componentCls.getMethod("literal", String.class);
		Object title = literal.invoke(null, id);

		Object resources = fileSuppCls.getConstructor(Path.class).newInstance(jar);
		Object location = locCls.getConstructor(String.class, componentCls, srcCls, Optional.class)
				.newInstance(id, title, srcCls.getField("BUILT_IN").get(null), Optional.empty());
		Object metadata = metaCls.getConstructor(componentCls, compatCls, flagsCls, List.class)
				.newInstance(title, compatCls.getField("COMPATIBLE").get(null), flagsCls.getMethod("of").invoke(null),
						List.of());
		// required=true + TOP + fixed: ecosystem assets must always be on, above user packs, and not user-removable.
		Object selection = selCls.getConstructor(boolean.class, posCls, boolean.class)
				.newInstance(true, posCls.getField("TOP").get(null), true);

		Constructor<?> packCtor = packCls.getConstructor(locCls, suppCls, metaCls, selCls);
		return packCtor.newInstance(location, resources, metadata, selection);
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
			case "toString" -> "ForbricKernelClientPackSource" + ids;
			case "hashCode" -> System.identityHashCode(proxy);
			case "equals" -> proxy == (args == null ? null : args[0]);
			default -> null;
		});
	}
}
