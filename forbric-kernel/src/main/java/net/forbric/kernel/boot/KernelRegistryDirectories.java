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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModPresence;
import net.forbric.kernel.util.ForbricLog;

/**
 * The answer {@code Registries.registryDirPath} gives for a registry, decided by the ecosystem that owns it.
 *
 * <p>Vanilla's body is the registry's path and nothing else, so a mod registry {@code wover:wover/world_preset_info}
 * would read {@code data/<ns>/wover/world_preset_info/}. All three loaders put the registry's namespace in front of
 * that — but in different places. NeoForge and MinecraftForge patched the BODY ({@code CommonHooks.prefixNamespace},
 * or the same test inline). Fabric left the body vanilla and adds the namespace afterwards: fabric-registry-sync's
 * {@code RegistriesMixin} is a {@code @ModifyReturnValue} on {@code elementsDirPath} and {@code tagsDirPath}. For
 * a plain mod the three agree on {@code data/<ns>/<registry ns>/<registry path>/}.
 *
 * <p>They stop agreeing the moment another mixin stands between the body and Fabric's modifier, and WorldWeaver
 * (wover) does exactly that. Its {@code RegistryDataLoaderMixinEarly}, at priority 200, injects at the RETURN of
 * {@code elementsDirPath}, cancellable, and for each of its own six datapack registries hands back the value the
 * body returned. Being applied first, its early return comes before fabric's modifier and skips it, so on native
 * Fabric those registries read the vanilla directory — which is where wover ships them:
 * {@code data/wover/wover/world_preset_info/normal.json} is element {@code wover:normal} of
 * {@code wover:wover/world_preset_info}, and {@code data/minecraft/wover/worldgen/biome_data/} holds the ten
 * vanilla Nether and End biomes' placement data.
 *
 * <p>The merged base took NeoForge's body. The value wover handed back was therefore already prefixed,
 * {@code wover/wover/world_preset_info} — {@code data/<ns>/wover/wover/world_preset_info/}, a directory nothing
 * ships — and the two of its registries that have data loaded EMPTY without a word: the sweep pack's content
 * census counted 5+3 preset entries and 10 biome entries on native Fabric and none here. Wover's world types lost
 * their settings, its Nether and End generators lost where each vanilla biome belongs, and both fell back to
 * defaults in silence. Its tags were never affected: its mixin leaves {@code tagsDirPath} to fabric's modifier.
 *
 * <p>So the body answers as the owner's loader would: vanilla's path for a registry whose namespace belongs to a
 * Fabric mod, whatever the merged body said for everything else. Nothing changes for a plain Fabric registry,
 * because fabric's modifier still runs after this and puts the namespace back exactly as it does natively — the
 * only registries whose directory moves are those a mod intercepted the way wover does, and they move to where
 * that mod put its data. {@code componentsDirPath} (datagen only) follows too; Fabric never prefixed it. That
 * leans on fabric-registry-sync's {@code RegistriesMixin} applying here as it does natively; if the kernel ever
 * left it out, Fabric registries would read unprefixed, as on a native Fabric instance without fabric-api — and the
 * load report would name that mixin.
 *
 * <p>Ownership is the namespace's: {@link ModPresence#soleEcosystem}. A namespace no mod claims — or that mods of
 * two ecosystems claim — keeps the merged answer, which is what every registry had before this existed. Called from
 * game code on resource-reload threads, so it never throws: a failed decision is the merged answer.
 */
public final class KernelRegistryDirectories {
	/** Namespaces already named in the log — one line per mod, not one per registry per reload. */
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

	private KernelRegistryDirectories() {
	}

	/**
	 * What {@code Registries.registryDirPath} returns for the registry {@code namespace:path}.
	 *
	 * @param merged    what the merged body computed (NeoForge's prefixed directory)
	 * @param namespace the registry id's namespace
	 * @param path      the registry id's path — vanilla's whole answer
	 */
	public static String registryDirPath(String merged, String namespace, String path) {
		try {
			if (namespace == null || path == null || "minecraft".equals(namespace)) return merged;
			if (ModPresence.soleEcosystem(namespace) != Ecosystem.FABRIC) return merged;
			if (!path.equals(merged) && REPORTED.add(namespace)) {
				ForbricLog.info("[Forbric/Registries] registries in the Fabric namespace %s (first: %s:%s) get "
						+ "vanilla's data directory from Registries' body, as on native Fabric; fabric-registry-sync "
						+ "adds the namespace after it unless the mod's own mixin keeps it off", namespace, namespace,
						path);
			}
			return path;
		} catch (Throwable t) {
			return merged;
		}
	}

	/** Forgets which namespaces were logged — for tests. */
	static void resetForTests() {
		REPORTED.clear();
	}
}
