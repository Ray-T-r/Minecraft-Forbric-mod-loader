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
import java.util.Optional;

import net.neoforged.neoforgespi.language.IConfigurable;

/**
 * The {@code IConfigurable} the kernel's synthetic mods report: "this mod declares nothing".
 *
 * <p>Genuine NeoForge backs this with the parsed {@code neoforge.mods.toml} section. The kernel constructs its
 * containers directly and has no such section, but the value may not be null — {@code ModListScreen.updateCache}
 * dereferences it from the screen's TICK, i.e. on every frame the Mods list is open, so a null crashed the
 * client the moment that list was shown again (which is what closing a mod's config screen with Done does).
 *
 * <p>Both answers mean "every lookup finds nothing", which is the truthful answer here rather than a placeholder.
 */
public final class KernelConfigurable implements IConfigurable {
	private final String modId;

	public KernelConfigurable(String modId) {
		this.modId = modId;
	}

	@Override
	public <T> Optional<T> getConfigElement(String... key) {
		return Optional.empty();
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
