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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraftforge.forgespi.language.IConfigurable;

/** MinecraftForge's {@code IConfigurable} over one mods.toml table's string keys, with {@code [[mods]]} as its one list. */
final class KernelForgeConfigurable implements IConfigurable {
	private final Map<String, String> values;
	private final List<Map<String, String>> mods;

	KernelForgeConfigurable(Map<String, String> values, List<Map<String, String>> mods) {
		this.values = values;
		this.mods = mods;
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> Optional<T> getConfigElement(String... key) {
		if (key == null || key.length != 1) return Optional.empty();
		return (Optional<T>) Optional.ofNullable(values.get(key[0]));
	}

	@Override
	public List<? extends IConfigurable> getConfigList(String... key) {
		if (key == null || key.length != 1 || !"mods".equals(key[0])) return List.of();
		List<IConfigurable> out = new ArrayList<>();
		for (Map<String, String> table : mods) out.add(new KernelForgeConfigurable(table, List.of()));
		return out;
	}

	@Override
	public String toString() {
		return "KernelForgeConfigurable" + values.keySet();
	}
}
