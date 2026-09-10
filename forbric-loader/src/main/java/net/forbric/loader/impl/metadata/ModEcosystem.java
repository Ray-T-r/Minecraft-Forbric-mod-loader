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

package net.forbric.loader.impl.metadata;

/** Which loader a discovered mod belongs to. */
public enum ModEcosystem {
	/** A Fabric mod, declared by {@code fabric.mod.json}. */
	FABRIC,
	/** A traditional MinecraftForge mod, declared by {@code META-INF/mods.toml}. */
	FORGE,
	/** A NeoForge mod, declared by {@code META-INF/neoforge.mods.toml}. */
	NEOFORGE;

	/** Whether this is a Forge-family ecosystem (traditional MinecraftForge or NeoForge). */
	public boolean isForgeFamily() {
		return this != FABRIC;
	}

	/** The lowercase id used in wrapped-mod metadata ({@code forbric:ecosystem}) and {@code -Dforbric.forgeFamily}. */
	public String familyId() {
		return name().toLowerCase();
	}
}
