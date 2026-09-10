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

/**
 * Wires traditional-MinecraftForge mods' CLIENT ASSETS (textures, shaders, models, lang) into the REAL client
 * {@code PackRepository} on the merged client base.
 *
 * <p>Why this exists: on the merged client NeoForge won the client mod-loading merge, so NeoForge serves its own
 * (and Fabric's) mod resources, but {@link net.forbric.loader.impl.forge.runtime.ForbricClientDualLifecycle}
 * deliberately runs traditional-Forge's {@code ClientModLoader.begin(...)} against a THROWAWAY pack repo to keep
 * Forge's resource integration (a colliding {@code mod_resources}, an {@code AddPackFindersEvent}, Forge reload
 * listeners) from disturbing NeoForge's real packs. That leaves Forge mods' own assets unserved — e.g. physicsmod
 * registers render pipelines whose shaders {@code physicsmod:core/*} live only in its jar; unfound, the pipelines
 * fail to compile, the initial resource reload throws, and the client drops all packs (black screen).
 *
 * <p>Fix: add a dedicated Forbric {@code RepositorySource} (a distinct pack id per Forge mod, so nothing collides
 * with NeoForge's {@code mod_resources}) directly onto the real repo, before the client's first reload. Each Forge
 * mod jar is served as a {@code FilePackResources} with compatibility FORCED to {@code COMPATIBLE} (Forge mod jars
 * carry ancient/boilerplate {@code pack.mcmeta pack_format}s that MC 26.2 would otherwise reject — Forge's own
 * loader forces this too via {@code displayTest = IGNORE_ALL_VERSION}). All MC/Forge types are reached by
 * reflection (Forbric has no compile-time Minecraft/Forge dependency).
 */
public final class ForbricForgeClientPacks {
	private ForbricForgeClientPacks() {
	}

	/**
	 * Add a {@code RepositorySource} serving every traditional-Forge content mod's assets to {@code realPackRepo}.
	 * Best-effort: reflective failures are the caller's to log; the client boots without Forge textures either way.
	 *
	 * @param realPackRepo the live {@code net.minecraft.server.packs.repository.PackRepository} of the client
	 * @param cl           the shared Knot class loader (merged game + both runtimes)
	 */
	public static void addForgeModAssetsTo(Object realPackRepo, ClassLoader cl) throws Exception {
		ForbricForgeKnownPacks.addClientResourcePacksTo(realPackRepo, cl);
	}
}
