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
import java.util.Collection;
import java.util.List;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;

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
	 *
	 * <p>{@code carriers} are the ecosystem runtime jars among {@code jars}: each is its ecosystem's own mod file
	 * rather than a mod anybody arbitrates, which changes how its reader is chosen (see
	 * {@link #readsItsMetadataTheVanillaWay}).
	 */
	public static void addTo(Object packRepository, ClassLoader cl, List<Path> jars, Collection<Path> carriers) {
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

			// Decided once per jar: both the child pass and the flat fallback below build from the same answer.
			List<Boolean> vanillaReader = new ArrayList<>();
			int readByVanilla = 0;
			for (Path jar : packJars) {
				boolean vanilla = readsItsMetadataTheVanillaWay(jar, carriers != null && carriers.contains(jar));
				vanillaReader.add(vanilla);
				if (vanilla) readByVanilla++;
			}

			List<Object> packs = new ArrayList<>();
			List<String> ids = new ArrayList<>();
			for (int i = 0; i < packJars.size(); i++) {
				Path jar = packJars.get(i);
				String id = "forbric/" + stripExtension(jar.getFileName().toString());
				Object pack = buildPack(cl, id, jar, true, vanillaReader.get(i));
				if (pack != null) {
					packs.add(pack);
					ids.add(id);
				}
			}
			if (packs.isEmpty()) return;

			// One VISIBLE parent holding the mod packs as hidden children, which is the shape a genuine instance
			// has. Each mod pack used to be served on its own as required + fixed + TOP: applied always, listed
			// never, and above everything — so a player's OWN resource pack could not override a mod's texture,
			// at all, by any means. The parent is required (the assets still always apply) but NOT fixed, so it
			// can be dragged below a pack the player added.
			//
			// Its id is the kernel's own. The NeoForge carrier already publishes a "mod_resources" parent of its
			// own — empty, because its mod-file list is not what the kernel loads from — and two packs sharing an
			// id silently collapse into one inside PackRepository's map.
			Object parent = buildParentPack(cl, packs);
			List<Object> source;
			// What the source EMITS and what the log REPORTS are different lists on the parent path: one pack goes
			// to the repository, and `ids` stays the children it carries, which is what a reader needs to see.
			String describedAs;
			if (parent != null) {
				source = List.of(parent);
				describedAs = "ForbricKernelClientPackSource" + List.of(PARENT_ID);
			} else {
				// No parent, so nothing carries "required" for the set — and the children were built without it.
				// Serving them as they stand would apply NONE of them. Rebuild them the old way instead: each one
				// required and fixed, which costs the player the ability to override a mod texture but costs them
				// no textures.
				packs = new ArrayList<>();
				ids = new ArrayList<>();
				for (int i = 0; i < packJars.size(); i++) {
					Path jar = packJars.get(i);
					String id = "forbric/" + stripExtension(jar.getFileName().toString());
					Object pack = buildPack(cl, id, jar, false, vanillaReader.get(i));
					if (pack != null) {
						packs.add(pack);
						ids.add(id);
					}
				}
				source = packs;
				describedAs = "ForbricKernelClientPackSource" + ids;
			}
			gameSide(cl).getMethod("addSource", Object.class, List.class, String.class)
					.invoke(null, packRepository, source, describedAs);
			ForbricLog.info("[Forbric/ClientPacks] served %d ecosystem asset pack(s) to the client PackRepository "
					+ "(forced-compatible, %d read through vanilla's Pack.readPackMetadata), %d of them declaring "
					+ "overlays: %s", ids.size(), readByVanilla, withOverlays(cl, packs), ids);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/ClientPacks] could not serve ecosystem assets to the client PackRepository "
					+ "(ecosystem shaders/textures will be missing)", Reflect.unwrap(t));
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

	/** One hidden {@code Pack} over {@code jar}; the shape it is given is written down game-side. */
	private static Object buildPack(ClassLoader cl, String id, Path jar, boolean asChild, boolean vanillaReader)
			throws Exception {
		return gameSide(cl).getMethod("buildPack", String.class, Path.class, boolean.class, boolean.class)
				.invoke(null, id, jar, asChild, vanillaReader);
	}

	/** {@code -Dforbric.vanillaPackMetadata=off} reads every served jar's metadata with NeoForge's reader, as before. */
	static final String VANILLA_READER = "forbric.vanillaPackMetadata";

	/**
	 * Whether {@code jar}'s {@code pack.mcmeta} is read by vanilla's {@code Pack.readPackMetadata} rather than by
	 * NeoForge's {@code ResourcePackLoader.readWithOptionalMeta}.
	 *
	 * <p>The reader is part of the contract, because mods hook it. MinecraftForge builds each mod's pack through
	 * {@code Pack.readPackMetadata} (native {@code ResourcePackLoader.findPacks}), and so does fabric-api's resource
	 * loader; NeoForge's reader builds the {@code Pack$Metadata} itself and never calls it. fusion (a MinecraftForge
	 * mod here) mounts a pack's {@code fusion-overrides} folder as an OVERLAY with a {@code @ModifyArg} on the
	 * metadata constructor inside {@code readPackMetadata} — so while every jar went through NeoForge's reader,
	 * Rechiseled Anti-Blocks' 24 connected-texture models were never even read, and nothing said so.
	 *
	 * <p>So: vanilla's reader for every jar NeoForge does not own, which is what that jar's own loader would have
	 * used; NeoForge's for NeoForge's, which is what NeoForge would have used. Only a jar that ships a
	 * {@code pack.mcmeta} can be read by vanilla at all — without one it returns null and logs "Missing metadata",
	 * where NeoForge's reader supplies a default — so those stay on NeoForge's. Game side, a vanilla read that
	 * yields nothing falls back to NeoForge's reader, and then to the synthesised metadata.
	 *
	 * <p>A {@code carrier} is an ecosystem runtime jar, and what it is comes from its own manifest, never from
	 * {@link MultiLoaderArbiter}: nobody arbitrates a carrier, and each one also ships a {@code fabric.mod.json}, so
	 * asking the arbiter scanned the 4 MB MinecraftForge carrier for {@code @Mod} classes on the render thread
	 * inside {@code Minecraft.<init>} and logged that it was "loading it as FORGE only, suppressing [FABRIC]" —
	 * while nothing was being loaded or suppressed. NeoForge's carrier is read by NeoForge's reader, as NeoForge
	 * reads it; MinecraftForge's by vanilla's, as MinecraftForge reads it.
	 */
	static boolean readsItsMetadataTheVanillaWay(Path jar, boolean carrier) {
		if ("off".equalsIgnoreCase(System.getProperty(VANILLA_READER, "on"))) return false;
		// The cheap question first: without a pack.mcmeta there is nothing to route, and no owner to ask about.
		if (!declaresPackMetadata(jar)) return false;
		Ecosystem owner = carrier ? carrierEcosystem(jar) : MultiLoaderArbiter.ownerOf(jar);
		return owner != Ecosystem.NEOFORGE;
	}

	/** The ecosystem a runtime carrier is: NeoForge's declares {@code neoforge.mods.toml}, MinecraftForge's does not. */
	private static Ecosystem carrierEcosystem(Path jar) {
		return MultiLoaderArbiter.declaredBy(jar).contains(Ecosystem.NEOFORGE) ? Ecosystem.NEOFORGE : Ecosystem.FORGE;
	}

	private static boolean declaresPackMetadata(Path jar) {
		if (jar == null || !Files.isRegularFile(jar)) return false;
		try (FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
			for (Path root : fs.getRootDirectories()) {
				if (Files.isRegularFile(root.resolve("pack.mcmeta"))) return true;
			}
		} catch (Throwable ignored) {
			// unreadable — NeoForge's reader, which is what it had before
		}
		return false;
	}

	/**
	 * The game-side half: every {@code Pack}, the parent, the overlay count and the {@code RepositorySource}.
	 *
	 * <p>What stays above this line is policy that names no game type — which jars carry assets, what each pack
	 * is called, and the decision to fall back to flat packs when the parent cannot be built.
	 */
	private static Class<?> gameSide(ClassLoader cl) throws ClassNotFoundException {
		return Class.forName("net.forbric.kernel.runtime.KernelClientPackSource", true, cl);
	}

	/** The kernel's own parent pack id. Deliberately not the carrier's, which already exists and would collide. */
	static final String PARENT_ID = "forbric/mod_resources";

	/**
	 * One visible, movable pack holding every mod's assets as hidden children, or null if it cannot be built.
	 *
	 * <p>This is the shape a genuine instance has, and the reason it matters is what the flat version did: each
	 * mod pack was served required, fixed and pinned to the top, so it was applied always, listed never, and
	 * above every pack the player had. A player could not override a mod's texture by any means — the pack they
	 * added sat below all seventy of them and there was no way to move it.
	 *
	 * <p>Required stays on the parent, so the assets still always apply and a player cannot accidentally turn
	 * their mods' textures off. Fixed does not, so the one row CAN be dragged below a pack they added, which is
	 * the whole point.
	 *
	 * <p>Null rather than a throw: the caller then serves the packs flat, exactly as before, which is worse for
	 * the player but loses no assets.
	 */
	private static Object buildParentPack(ClassLoader cl, List<Object> children) {
		try {
			return gameSide(cl).getMethod("buildParentPack", String.class, List.class)
					.invoke(null, PARENT_ID, children);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/ClientPacks] could not build the parent asset pack; serving each mod's assets "
					+ "on its own instead, which means a player's own resource pack cannot override a mod texture",
					Reflect.unwrap(t));
			return null;
		}
	}

	/**
	 * How many of the built packs declare overlays.
	 *
	 * <p>The number is the evidence, and it is why this is counted rather than assumed: the kernel used to
	 * synthesise each pack's metadata with an EMPTY overlay list, so this would have been zero however many mods
	 * declared them. An overlay is how a mod ships one set of assets per game version, so losing them means a mod
	 * quietly serving the wrong textures — or none.
	 */
	private static int withOverlays(ClassLoader cl, List<Object> packs) {
		try {
			return (int) gameSide(cl).getMethod("withOverlays", List.class).invoke(null, packs);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/ClientPacks] could not count declared overlays: %s",
					String.valueOf(Reflect.unwrap(t)));
			return 0;
		}
	}
}
