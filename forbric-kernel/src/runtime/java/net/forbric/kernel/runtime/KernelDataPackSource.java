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

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.neoforged.neoforge.resource.ResourcePackLoader;

/**
 * The game side of serving a Forge-family mod jar's own {@code data/} to the server datapack repository.
 *
 * <p>{@code KernelDataPacks} keeps the policy — which jars carry data, who owns them, what each pack is called
 * and in what order they stack. None of that names a game type and all of it has tests. What lives here is the
 * part that could only be spelled reflectively: building a {@code Pack} and handing a {@code RepositorySource}
 * to the repository.
 *
 * <h2>What being typed changed</h2>
 *
 * <p>The source was a {@code Proxy} dispatching on method NAME with {@code default -> null}. That is a shape
 * that cannot fail loudly: a method the interface gains, or one whose name moves, is answered with null and
 * nobody hears about it. {@code RepositorySource} declares exactly one method today, and the difference is that
 * javac now says so — if it ever declares two, this stops compiling instead of quietly answering null to the
 * second.
 *
 * <p>Metadata is read through NeoForge's own {@code readWithOptionalMeta} rather than synthesised. That path
 * reads the jar's real {@code pack.mcmeta} with an unlimited supported-format range and tolerates a jar with
 * none, which is the genuine loader's behaviour — and, unlike a synthesised {@code Pack.Metadata}, it keeps a
 * mod's root-pack {@code overlays}.
 */
public final class KernelDataPackSource {
	private KernelDataPackSource() {
	}

	/**
	 * One {@code Pack} over {@code jar}, or null when it cannot be built — that mod's data is then missing, which
	 * is worth a line but never worth failing the boot.
	 *
	 * <p>The selection config is NeoForge's own {@code MOD_PACK_SELECTION_CONFIG} shape:
	 * {@code (required=false, TOP, fixed=false)}. Not required, because that is how a genuine instance treats a
	 * mod pack — the server auto-enables one it has not seen ("Found new data pack …, loading it automatically")
	 * and forcing it would take away the operator's ability to turn a mod's data off. TOP, so mod data overrides
	 * vanilla's while a user datapack added later still overrides the mod's.
	 */
	public static Object buildPack(String id, Path jar, Object packType) {
		try {
			PackLocationInfo location = new PackLocationInfo(
					id, Component.literal(id), PackSource.BUILT_IN, Optional.empty());
			PackSelectionConfig selection = new PackSelectionConfig(false, Pack.Position.TOP, false);
			return ResourcePackLoader.readWithOptionalMeta(location,
					new FilePackResources.FileResourcesSupplier(jar), (PackType) packType, selection);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/DataPacks] could not build a datapack over %s — that mod's data/ will be "
					+ "missing: %s", jar.getFileName(), String.valueOf(Reflect.unwrap(t)));
			return null;
		}
	}

	/**
	 * Adds a source emitting {@code packs} to {@code packRepository}.
	 *
	 * <p>The caller's emission order is NOT the priority. {@code PackRepository.discoverAvailable} drains each
	 * source into a {@code TreeMap} before merging it, so every pack from one source is re-sorted alphabetically
	 * by id and whatever order arrives here is thrown away. The stack is spelled into the ids instead, boot-side.
	 */
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
}
