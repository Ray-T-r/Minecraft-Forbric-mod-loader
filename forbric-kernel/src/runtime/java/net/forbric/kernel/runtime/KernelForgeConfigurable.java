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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.google.common.collect.ImmutableMap;

import net.forbric.kernel.metadata.forge.FmlConfigElements;
import net.minecraftforge.forgespi.language.IConfigurable;

/**
 * MinecraftForge's {@code IConfigurable} over one table of a jar's {@code mods.toml} — the file's top level or one
 * {@code [[mods]]} entry — answering as MinecraftForge's own {@code NightConfigWrapper} does.
 *
 * <p>That is ({@code fmlloader} 26.2-65.0.x): each path element a literal key, a scalar or a list as itself, a missing
 * path as empty, and a table as an {@code ImmutableMap} of its entries, not descending further
 * ({@link FmlConfigElements#minecraftForge}). {@code getConfigList} answers a wrapper per table of an array of tables,
 * which is how the file's {@code [[mods]]} entries are reached. wthit reads {@code issueTrackerURL} through here in a
 * static initialiser, and every {@code @Mod} in its jar died when there was nothing here at all.
 *
 * <p>Over the strings-only reading ({@code -Dforbric.fileConfigElements=off}) it answers as it did before: one key,
 * the string, and {@code [[mods]]} as the one list.
 */
final class KernelForgeConfigurable implements IConfigurable {
	private final Map<String, Object> values;
	private final List<Map<String, Object>> mods;
	private final boolean typed;

	/** The file's top level, whose {@code getConfigList("mods")} is its {@code [[mods]]} entries. */
	static KernelForgeConfigurable file(KernelForgeModsToml toml) {
		return new KernelForgeConfigurable(toml.top, toml.mods, toml.typed);
	}

	/** One mod's {@code [[mods]]} entry. */
	static KernelForgeConfigurable mod(KernelForgeModsToml toml, String modId) {
		return new KernelForgeConfigurable(toml.mod(modId), List.of(), toml.typed);
	}

	private KernelForgeConfigurable(Map<String, Object> values, List<Map<String, Object>> mods, boolean typed) {
		this.values = values;
		this.mods = mods;
		this.typed = typed;
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> Optional<T> getConfigElement(String... key) {
		if (!typed) {
			if (key == null || key.length != 1) return Optional.empty();
			return (Optional<T>) Optional.ofNullable(values.get(key[0]));
		}
		return (Optional<T>) FmlConfigElements.minecraftForge(values, ImmutableMap::copyOf, key);
	}

	@Override
	public List<? extends IConfigurable> getConfigList(String... key) {
		if (!typed) {
			if (key == null || key.length != 1 || !"mods".equals(key[0])) return List.of();
			List<IConfigurable> out = new ArrayList<>();
			for (Map<String, Object> table : mods) out.add(new KernelForgeConfigurable(table, List.of(), false));
			return out;
		}
		// Natively a path that is not an array of tables throws InvalidModFileException; answering nothing is
		// not modelled beyond that, and no reader asks.
		if (!(FmlConfigElements.at(values, key) instanceof Collection<?> tables)) return List.of();
		List<IConfigurable> out = new ArrayList<>();
		for (Object table : tables) {
			if (table instanceof UnmodifiableConfig config) {
				out.add(new KernelForgeConfigurable(config.valueMap(), List.of(), true));
			} else if (table instanceof Map<?, ?> flattened) {
				@SuppressWarnings("unchecked")
				Map<String, Object> entries = (Map<String, Object>) flattened;
				out.add(new KernelForgeConfigurable(entries, List.of(), true));
			}
		}
		return out;
	}

	@Override
	public String toString() {
		return "KernelForgeConfigurable" + values.keySet();
	}
}
