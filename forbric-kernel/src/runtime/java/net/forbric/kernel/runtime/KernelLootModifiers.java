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
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import net.forbric.kernel.util.ForbricLog;

/**
 * The resource-manager view both families' loot-modifier managers scan {@code loot_modifiers/} through: every
 * {@code loot_modifiers/global_loot_modifiers.json} — MinecraftForge's legacy list file — is hidden from the
 * directory listing and nothing else changes. MinecraftForge still reads its own index by name on the ORIGINAL
 * manager (the transformer wraps only the argument of its {@code super.prepare}); NeoForge, which has no
 * list-file concept, no longer logs {@code Couldn't parse data file} for each one.
 *
 * <p>{@code -Dforbric.lootModifierIndex=off}, read per call: the original manager is handed back and both ERROR
 * lines return.
 */
public final class KernelLootModifiers {
	public static final String PROPERTY = "forbric.lootModifierIndex";
	static final String INDEX_SUFFIX = "/global_loot_modifiers.json";
	static final String DIRECTORY = "loot_modifiers";

	private KernelLootModifiers() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	public static ResourceManager withoutTheLegacyIndex(ResourceManager manager) {
		if (!enabled() || manager == null || manager instanceof HidingIndex) return manager;
		return new HidingIndex(manager);
	}

	/** Whether {@code id} is a legacy index file — {@code <ns>:loot_modifiers/global_loot_modifiers.json}. */
	static boolean isLegacyIndex(Identifier id) {
		String path = id.getPath();
		return path.endsWith(INDEX_SUFFIX) && path.startsWith(DIRECTORY + "/");
	}

	/** Delegates everything; the two listings additionally hide the index and say what they hid. */
	record HidingIndex(ResourceManager delegate) implements ResourceManager {
		@Override
		public Set<String> getNamespaces() {
			return delegate.getNamespaces();
		}

		@Override
		public List<Resource> getResourceStack(Identifier id) {
			return delegate.getResourceStack(id);
		}

		@Override
		public Map<Identifier, Resource> listResources(String prefix, Predicate<Identifier> filter) {
			List<Identifier> hidden = new ArrayList<>();
			Map<Identifier, Resource> kept = delegate.listResources(prefix, hiding(filter, hidden));
			report(kept.size(), hidden);
			return kept;
		}

		@Override
		public Map<Identifier, List<Resource>> listResourceStacks(String prefix, Predicate<Identifier> filter) {
			List<Identifier> hidden = new ArrayList<>();
			Map<Identifier, List<Resource>> kept = delegate.listResourceStacks(prefix, hiding(filter, hidden));
			report(kept.size(), hidden);
			return kept;
		}

		@Override
		public Stream<PackResources> listPacks() {
			return delegate.listPacks();
		}

		@Override
		public Optional<Resource> getResource(Identifier id) {
			return delegate.getResource(id);
		}

		private static Predicate<Identifier> hiding(Predicate<Identifier> filter, List<Identifier> hidden) {
			return id -> {
				if (isLegacyIndex(id)) {
					hidden.add(id);
					return false;
				}
				return filter.test(id);
			};
		}

		private static void report(int kept, List<Identifier> hidden) {
			ForbricLog.info("[Forbric/Loot] loot-modifier directory scan: %d file(s) kept, %d legacy index file(s) hidden %s — "
					+ "NeoForge's manager has no list-file concept and logged 'Couldn't parse data file' for each; "
					+ "MinecraftForge still reads its own index by name", kept, hidden.size(), hidden);
		}
	}
}
