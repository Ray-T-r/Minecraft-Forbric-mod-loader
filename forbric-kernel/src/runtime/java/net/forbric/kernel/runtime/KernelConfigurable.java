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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.forbric.kernel.metadata.forge.FmlConfigElements;
import net.neoforged.neoforgespi.language.IConfigurable;

/**
 * The {@code IConfigurable} the kernel's NeoForge mod infos report: one table of the mod's {@code neoforge.mods.toml}
 * — its {@code [[mods]]} entry for {@code KernelModInfo}, the file's top level for {@code KernelModFileInfo} — or
 * "declares nothing".
 *
 * <p>Genuine NeoForge backs this with the parsed {@code neoforge.mods.toml} section. The value may not be null —
 * {@code ModListScreen.updateCache} dereferences it from the screen's TICK, i.e. on every frame the Mods list is open,
 * so a null crashed the client the moment that list was shown again (which is what closing a mod's config screen with
 * Done does).
 *
 * <p>It answered nothing at all for a long time, which was not the truth: the kernel had parsed the table. Not
 * Enough Crashes reads {@code authors} from here and {@code issueTrackerURL} from the file's, for every mod it lists
 * when it attributes a crash; Puzzles Lib reads {@code authors}, {@code credits} and {@code displayURL}. A lookup
 * answers as NeoForge's {@code NightConfigWrapper} does ({@link FmlConfigElements#neoForge}): a scalar or a list as
 * itself, a table as its {@code valueMap()}, each path element a literal key. {@code getConfigList} still answers
 * nothing; no reader of it was found.
 */
public final class KernelConfigurable implements IConfigurable {
	private final String modId;
	private final Map<String, Object> elements;

	/** "This mod declares nothing". */
	public KernelConfigurable(String modId) {
		this(modId, Map.of());
	}

	/** @param elements one table of the mod's {@code neoforge.mods.toml}, shallow, as FML leaves it; never null */
	public KernelConfigurable(String modId, Map<String, Object> elements) {
		this.modId = modId;
		this.elements = elements == null ? Map.of() : elements;
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> Optional<T> getConfigElement(String... key) {
		return (Optional<T>) FmlConfigElements.neoForge(elements, key);
	}

	@Override
	public List<? extends IConfigurable> getConfigList(String... key) {
		return List.of();
	}

	@Override
	public String toString() {
		return "KernelModConfig[" + modId + "]";
	}
}
