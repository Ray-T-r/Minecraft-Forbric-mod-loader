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

package net.forbric.kernel.runtime;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.flag.FeatureFlagSet;
import net.neoforged.neoforge.resource.EmptyPackResources;
import net.neoforged.neoforge.resource.ResourcePackLoader;

/**
 * The game side of serving an ecosystem jar's {@code assets/} to the CLIENT resource repository.
 *
 * <p>{@code KernelClientPacks} keeps the policy — which jars carry client assets, what each pack is called, and
 * the decision to fall back to flat packs when the parent cannot be built. What lives here is the part that
 * could only be spelled reflectively: thirteen Minecraft types and a {@code RepositorySource} that used to be a
 * name-dispatching {@code Proxy} with a {@code default -> null} arm.
 */
public final class KernelClientPackSource {
	/** Ids of the packs whose metadata vanilla's reader supplied — what the boot side's served line counts. */
	private static final Set<String> READ_BY_VANILLA = ConcurrentHashMap.newKeySet();
	/** Ids whose fall-back from vanilla's reader was already said: the flat pass and a second call rebuild them. */
	private static final Set<String> FELL_BACK = ConcurrentHashMap.newKeySet();

	private KernelClientPackSource() {
	}

	/**
	 * One hidden {@code Pack} over {@code jar}.
	 *
	 * <p>As a CHILD: not required and not fixed on its own, because the parent carries both for the whole set.
	 * Standalone (only when the parent could not be built): required and fixed, so the assets still apply.
	 *
	 * <p>HIDDEN either way. {@code isHidden} gates LISTING only, never application — {@code getAvailableIds} and
	 * {@code getSelectedIds} filter on it, while {@code openAllSelected} and {@code getSelectedPacks} do not, and
	 * {@code rebuildSelected} re-inserts every {@code isRequired()} pack regardless. So {@code required} is what
	 * keeps them applied and {@code hidden} is what keeps them out of the screen; neither substitutes for the
	 * other. A mod's own assets are not a resource pack the player chose to add, and they were appearing as
	 * seventy rows nobody could turn off.
	 *
	 * <p>{@code vanillaReader} is the boot side's answer to which reader this jar's own loader would have used;
	 * see {@code KernelClientPacks.readsItsMetadataTheVanillaWay}.
	 */
	public static Object buildPack(String id, Path jar, boolean asChild, boolean vanillaReader) {
		Component title = Component.literal(id);
		Pack.ResourcesSupplier resources = new FilePackResources.FileResourcesSupplier(jar);
		PackLocationInfo location =
				new PackLocationInfo(id, title, PackSource.BUILT_IN, Optional.empty());
		PackSelectionConfig selection = new PackSelectionConfig(!asChild, Pack.Position.TOP, !asChild);
		return readPack(location, resources, selection, vanillaReader).hidden();
	}

	/** The readers, in order, over one pack's resources: {@link #buildPack}'s body once the jar is opened. */
	private static Pack readPack(PackLocationInfo location, Pack.ResourcesSupplier resources,
			PackSelectionConfig selection, boolean vanillaReader) {
		// Vanilla's reader for a jar NeoForge does not own: it is the one MinecraftForge and fabric-api build their
		// mods' packs with, and so the one their mods hook (fusion mounts its overrides folder as an overlay there).
		Pack pack = vanillaReader ? readThroughVanilla(location, resources, selection) : null;
		if (vanillaReader) countVanillaRead(location.id(), pack != null);
		// NeoForge's own reader otherwise: it opens the jar's real pack.mcmeta and builds the Metadata from it,
		// which is where a pack's OVERLAYS live. The kernel synthesised that record with an empty overlay list, so
		// a Forge-family mod declaring overlays — the mechanism a mod uses to ship one set of assets per game
		// version — had them dropped without a word. It fills in the feature flags too.
		if (pack == null) pack = readWithTheJarsOwnMeta(location, resources, selection);
		if (pack == null) {
			// Synthesised: worse but not broken — the mod keeps its assets and loses only its overlays.
			Pack.Metadata metadata = new Pack.Metadata(
					location.title(), PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of());
			pack = new Pack(location, resources, metadata, selection);
		}
		return pack;
	}

	/**
	 * Keeps the count of what vanilla's reader actually read, and says so when a jar routed to it was served
	 * through NeoForge's reader instead. That fall-back keeps the jar's assets, but whatever its loader's mods hook
	 * on vanilla's reader did not run for it — fusion's overlay, for one — and a count of the ROUTING would have
	 * reported it as read.
	 */
	private static void countVanillaRead(String id, boolean read) {
		if (read) {
			READ_BY_VANILLA.add(id);
			return;
		}
		READ_BY_VANILLA.remove(id);
		if (FELL_BACK.add(id)) {
			ForbricLog.info("[Forbric/ClientPacks] vanilla's Pack.readPackMetadata read nothing from %s (vanilla logs "
					+ "why) — serving it through NeoForge's reader, so a hook its loader's mods put on vanilla's reader "
					+ "(fusion mounts its overlays there) did not run for it", id);
		}
	}

	/** How many of {@code packs} vanilla's reader built the metadata of. */
	public static int readByVanilla(List<Object> packs) {
		int read = 0;
		for (Object pack : packs) {
			if (READ_BY_VANILLA.contains(((Pack) pack).getId())) read++;
		}
		return read;
	}

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
	 * <p>Null rather than a throw: the caller then serves the packs flat, which is worse for the player but
	 * loses no assets.
	 */
	public static Object buildParentPack(String parentId, List<Object> children) {
		try {
			Component title = Component.literal("Mod Resources");

			// The pack format of the running game, so the parent never reads as out of date for its own version.
			PackFormat packFormat = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
			PackMetadataSection metadata =
					new PackMetadataSection(title, new InclusiveRange<>(packFormat));

			PackLocationInfo location =
					new PackLocationInfo(parentId, title, PackSource.DEFAULT, Optional.empty());
			// required, TOP, NOT fixed — see this method's javadoc for why each of the three is what it is.
			PackSelectionConfig selection = new PackSelectionConfig(true, Pack.Position.TOP, false);

			Pack parent = Pack.readMetaAndCreate(location,
					new EmptyPackResources.EmptyResourcesSupplier(metadata),
					PackType.CLIENT_RESOURCES, selection);
			if (parent == null) return null;

			List<Pack> kids = new ArrayList<>();
			for (Object child : children) kids.add((Pack) child);
			return parent.withChildren(List.copyOf(kids));
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
	 *
	 * <p>{@code Pack.metadata} is private and there is no accessor, so this one field stays a string. The record
	 * it holds is typed, so {@code overlays()} is checked.
	 */
	public static int withOverlays(List<Object> packs) {
		int declaring = 0;
		try {
			Field metadata = Pack.class.getDeclaredField("metadata");
			metadata.setAccessible(true);
			for (Object pack : packs) {
				if (metadata.get(pack) instanceof Pack.Metadata meta && !meta.overlays().isEmpty()) declaring++;
			}
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/ClientPacks] could not count declared overlays: %s",
					String.valueOf(Reflect.unwrap(t)));
		}
		return declaring;
	}

	/** Adds a source emitting {@code packs} to {@code packRepository}. */
	public static void addSource(Object packRepository, List<Object> packs, String describedAs) {
		List<Object> emit = List.copyOf(packs);
		((PackRepository) packRepository).addPackFinder(new RepositorySource() {
			@Override
			public void loadPacks(Consumer<Pack> sink) {
				for (Object pack : emit) sink.accept((Pack) pack);
			}

			@Override
			public String toString() {
				return describedAs;
			}
		});
	}

	/**
	 * A {@code Pack} whose metadata vanilla's {@code Pack.readPackMetadata} read, or null when it read none.
	 *
	 * <p>Called for the hooks, not for the parse: the parse is the same one NeoForge's reader does, but a guest
	 * {@code @ModifyArg} on the {@code Pack$Metadata} constructor INSIDE {@code readPackMetadata} only runs when
	 * that method does. fusion's {@code PackMixin} adds a pack's {@code fusion-overrides} folder to the overlay
	 * list there, which is how Rechiseled Anti-Blocks' connected-texture models are mounted at all.
	 *
	 * <p>What it returns is rebuilt as {@code COMPATIBLE}, keeping everything else it read — the overlays a hook
	 * added included. {@code readPackMetadata} judges compatibility against this game's pack format, and a mod
	 * jar's {@code pack_format} is routinely a version or two behind; serving mod assets forced compatible is the
	 * kernel's policy for these packs ({@code KernelClientPacks}), and the synthesised fallback does the same.
	 *
	 * <p>Null — no metadata section, or an unreadable one — sends the caller to NeoForge's reader, and from there
	 * to the synthesised metadata, exactly the path every jar took before.
	 *
	 * <p>Vanilla's reader is stricter than NeoForge's, and what it says about a pack it reads is its own, in its
	 * own log lines — the lines this jar's own loader prints for it too. A {@code pack} section vanilla's primary
	 * codec rejects (fusion-1.3.15a-forge declares a multi-version range from format 4; vanilla's floor for one is
	 * 15) gets its WARN "Error reading pack metadata, attempting fallback type" with a stack trace, and the
	 * fallback type then reads it. A section that will not parse at all ends in its outer catch, "Failed to read
	 * pack … metadata", and null — and here that jar is still served, through NeoForge's reader, which tolerates a
	 * broken {@code overlays} with a warning of its own. A namespaced section ({@code neoforge:overlays},
	 * {@code fabric:overlays}) never gets that far: {@code PackMetadataFailSoftInjector} treats one that will not
	 * parse as absent. So the residue is a Forge or Fabric mod jar with a malformed BARE {@code overlays}: its
	 * assets stay served, but the line gate-m9-client asserts absent appears, as it would on that jar's own loader.
	 * The only gate-pack jar with a bare {@code overlays} is Stellarity, which NeoForge owns. The flat fallback
	 * pass, which runs only when the parent pack cannot be built, reads each jar a second time and so repeats
	 * vanilla's lines once.
	 */
	private static Pack readThroughVanilla(
			PackLocationInfo location, Pack.ResourcesSupplier resources, PackSelectionConfig selection) {
		try {
			PackFormat current = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
			Pack.Metadata read = Pack.readPackMetadata(location, resources, current, PackType.CLIENT_RESOURCES);
			if (read == null) return null;
			Pack.Metadata compatible = new Pack.Metadata(read.description(), PackCompatibility.COMPATIBLE,
					read.requestedFeatures(), read.overlays(), read.isHidden());
			return new Pack(location, resources, compatible, selection);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/ClientPacks] vanilla's pack reader could not read %s, using NeoForge's: %s",
					location.id(), String.valueOf(Reflect.unwrap(t)));
			return null;
		}
	}

	/**
	 * A {@code Pack} built from the jar's own {@code pack.mcmeta}, or null when that is not possible.
	 *
	 * <p>{@code ResourcePackLoader.readWithOptionalMeta} is NeoForge's, and it is what a genuine instance uses
	 * for exactly these packs: it reads the metadata section, falls back to a default when the file is absent,
	 * forces {@code COMPATIBLE} so a pack built for another game version is still served, and carries across the
	 * two things the kernel's own synthesis could not — the declared overlays and the feature flags.
	 */
	private static Pack readWithTheJarsOwnMeta(
			PackLocationInfo location, Pack.ResourcesSupplier resources, PackSelectionConfig selection) {
		try {
			return ResourcePackLoader.readWithOptionalMeta(
					location, resources, PackType.CLIENT_RESOURCES, selection);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/ClientPacks] could not read a pack's own metadata, synthesising it "
					+ "(its overlays will not apply): %s", String.valueOf(Reflect.unwrap(t)));
			return null;
		}
	}
}
