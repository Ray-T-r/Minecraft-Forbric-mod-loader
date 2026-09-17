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

package net.forbric.api;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.forbric.kernel.util.ForbricLog;

/**
 * What each ecosystem's mods are, as seen from the OTHER ecosystems.
 *
 * <p>On a normal instance "is mod X installed" has one answer because there is one loader. Here there are three,
 * and each keeps its own list: NeoForge's {@code LoadingModList} and {@code ModList}, MinecraftForge's
 * {@code ModList}, and {@code FabricLoader}'s containers. A mod that asks its own loader therefore learns only
 * about mods from its own family — so a NeoForge mod running next to a Fabric Sodium is told Sodium is absent.
 *
 * <p><b>Why that is not cosmetic.</b> The overwhelming use of that question is a COMPATIBILITY BRANCH: "if Sodium
 * is here, render through Sodium's pipeline; otherwise render through vanilla's". Answer it wrongly and the mod
 * takes the vanilla branch while Sodium really has replaced the pipeline, so its work goes somewhere nothing
 * draws. Nothing throws, nothing logs, and the feature is simply invisible — Physics Mod's block debris and
 * ragdolls were exactly this: present, loaded, mixins applied, drawing into a path Sodium no longer runs.
 *
 * <p>This registry is the single answer all three lists are seeded from or fall back to. It is PRESENCE ONLY:
 * declaring a Fabric mod here does not give it a NeoForge container, an event bus or a config, and does not put
 * it in either family's <em>loading</em> list (which is what a multiplayer handshake announces). It states the
 * one thing that is unambiguously true — that mod is running in this instance, at this version.
 *
 * <p>Publishing is done by whoever computed a list, once, during boot; readers are the seeders, the Fabric
 * registration, and the {@code isLoaded} fallback the transform chain injects. Everything degrades to "not
 * present", which is the pre-registry behaviour, if a publish never happens.
 *
 * <h2>Why this lives in {@code net.forbric.api}</h2>
 *
 * <p>It is the first domain where the three compatibility layers align to a Forbric-owned answer rather than to
 * each other, so it is the first one to move out of {@code net.forbric.kernel.boot}. The move is what makes that
 * real rather than nominal: {@code ForeignModPresenceInjector} rewrites both families' {@code ModList.isLoaded}
 * to call {@code net/forbric/api/ModPresence.isLoaded}, so the bytecode crossing from game code into the kernel
 * now lands on an API type instead of a boot-side internal.
 *
 * <p><b>Presence only, still.</b> {@code getModContainerById} is deliberately left alone. There genuinely is no
 * NeoForge container for a Fabric mod, and inventing one would hand a caller a container with no event bus and no
 * config where it expects a real one. The hub answers the question that has one true answer, and declines the one
 * that does not — which is the shape every later domain should copy.
 */
public final class ModPresence {
	/**
	 * Escape hatch: {@code -Dforbric.crossEcosystemPresence=off} restores the pre-fix behaviour, where every
	 * ecosystem could only see its own mods. It exists so a gate can run the same instance both ways — a
	 * compatibility branch that is supposed to flip has to be shown flipping — and so a pack that somehow prefers
	 * the old answer has a switch rather than a downgrade.
	 */
	static final String SWITCH = "forbric.crossEcosystemPresence";

	private static volatile List<DiscoveredMod> forgeFamily = List.of();
	private static volatile List<DiscoveredMod> fabric = List.of();
	private static volatile Set<String> ids = Set.of();
	private static volatile Map<String, DiscoveredMod> byId = Map.of();

	private ModPresence() {
	}

	/** Records the Forge-family (MinecraftForge + NeoForge) mods this boot loaded. */
	public static void publishForgeFamily(List<DiscoveredMod> mods) {
		forgeFamily = usable(mods);
		reindex();
	}

	/** Records the Fabric mods this boot loaded, nested ones included. */
	public static void publishFabric(List<DiscoveredMod> mods) {
		fabric = usable(mods);
		reindex();
	}

	/**
	 * A defensive copy with unusable entries dropped.
	 *
	 * <p>{@code List.copyOf} was here, and it rejects a null element by throwing — which made {@link #add}'s
	 * null guard unreachable and turned one bad entry into a failed publish. Both callers publish inside a
	 * {@code catch (Throwable)} that degrades to "no presence at all", so the cost of a single null would have
	 * been every cross-ecosystem answer reverting to "not installed": the exact silence this registry exists to
	 * end, arriving through the code meant to prevent it.
	 */
	private static List<DiscoveredMod> usable(List<DiscoveredMod> mods) {
		if (mods == null || mods.isEmpty()) return List.of();

		List<DiscoveredMod> copy = new java.util.ArrayList<>(mods.size());
		for (DiscoveredMod mod : mods) {
			if (mod != null) copy.add(mod);
		}
		return List.copyOf(copy);
	}

	/** The Forge-family mods, for the Fabric side's presence registrations. Empty before the publish. */
	public static List<DiscoveredMod> forgeFamilyMods() {
		return enabled() ? forgeFamily : List.of();
	}

	/** The Fabric mods, for the Forge-family seeders. Empty before the publish. */
	public static List<DiscoveredMod> fabricMods() {
		return enabled() ? fabric : List.of();
	}

	/**
	 * Whether a mod with this id is running in this instance, in ANY ecosystem.
	 *
	 * <p>Called from game code: the transform chain rewrites both families' {@code ModList.isLoaded} to OR their
	 * own answer with this one. Never throws — a presence check that can fail is worse than one that says no.
	 */
	public static boolean isLoaded(String id) {
		try {
			return id != null && enabled() && ids.contains(id);
		} catch (Throwable t) {
			return false;
		}
	}

	/** One line naming what each ecosystem contributed, for the boot log. */
	static String summary() {
		return String.format("%d Forge-family + %d Fabric mod(s) now visible to every ecosystem's \"is X loaded\"",
				forgeFamily.size(), fabric.size());
	}

	private static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	/**
	 * Everything discovery learned about a loaded mod — version, display name, dependencies — or null.
	 *
	 * <p>Read from game code, which is why it is here and not somewhere boot-side: the SPI objects the kernel
	 * hands NeoForge used to answer "0.0" for every mod's version and the mod id for its display name, because
	 * they were built from an id and a jar path and nothing else. The Mods screen showed a list of ids at version
	 * 0.0, and a mod comparing another mod's version got a number that is below everything.
	 *
	 * <p>Deliberately NOT gated by {@link #SWITCH}. That switch answers a cross-ecosystem question — should a
	 * Fabric mod be able to see a Forge mod — and a mod's own version is not that question. Turning the switch
	 * off must not put "0.0" back.
	 *
	 * <p>Indexed by id and by alias, like {@link #ids}: whoever asks holds one name for the mod and does not know
	 * which of the two it is.
	 */
	public static DiscoveredMod metadata(String id) {
		try {
			return id == null ? null : byId.get(id);
		} catch (Throwable t) {
			return null;
		}
	}

	private static void reindex() {
		Set<String> merged = new LinkedHashSet<>();
		for (DiscoveredMod mod : forgeFamily) add(merged, mod);
		for (DiscoveredMod mod : fabric) add(merged, mod);
		ids = Set.copyOf(merged);

		// First publish wins, so a presence alias cannot overwrite the real mod's own metadata.
		Map<String, DiscoveredMod> index = new LinkedHashMap<>();
		for (DiscoveredMod mod : forgeFamily) index(index, mod);
		for (DiscoveredMod mod : fabric) index(index, mod);
		byId = Map.copyOf(index);

		ForbricLog.debug("[Forbric/Presence] %s", summary());
	}

	private static void index(Map<String, DiscoveredMod> into, DiscoveredMod mod) {
		if (mod == null) return;
		if (mod.getId() != null && !mod.getId().isBlank()) into.putIfAbsent(mod.getId(), mod);
		for (String alias : mod.getAliases()) {
			if (alias != null && !alias.isBlank()) into.putIfAbsent(alias, mod);
		}
	}

	/**
	 * Indexes a mod under its id AND every alias it declares.
	 *
	 * <p>The aliases are not a nicety: LibJF's modules are all {@code "id":"libjf-base"} with
	 * {@code "provides":["libjf_base"]}, and {@code libjf_base} is the id every dependent names. Indexing only
	 * the id answers "is libjf_base loaded" with a confident no while it is running — which is the one failure
	 * shape this registry exists to prevent.
	 */
	private static void add(Set<String> into, DiscoveredMod mod) {
		if (mod == null) return;
		if (mod.getId() != null && !mod.getId().isBlank()) into.add(mod.getId());
		for (String alias : mod.getAliases()) {
			if (alias != null && !alias.isBlank()) into.add(alias);
		}
	}
}
