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

import net.forbric.kernel.boot.KernelDataPacks;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.WorldDataConfiguration;

/**
 * Renames, in a world's own pack lists, the NeoForge carrier's datapack from the id an installed Forbric 0.4 or
 * earlier gave it to the id it has now.
 *
 * <p>An installed profile hands the kernel the carrier under its library file name, and the id used to be built from
 * that name: {@code neoforge-runtime-26.2.jar} became {@code forbric/carrier/2-neoforge-runtime-26.2}, and every world
 * an installed Forbric wrote recorded that id in {@code level.dat}. The carrier's library is now versioned by its
 * NeoForge build ({@code neoforge-runtime-26.2.0.93.jar}), so an id built from the name would change under every old
 * world: vanilla logs "Missing data pack" for the old id, finds the new one as an unknown pack and enables it at the END
 * of the list — above every mod pack, so NeoForge's carrier would override the mods it is meant to sit beneath. The id
 * is now the same whatever the jar is called ({@link KernelDataPacks#carrierPackId}), and this puts that id where the
 * old one stood, so the world keeps its order and the user sees nothing.
 *
 * <p>Called from the head of {@code MinecraftServer.configurePackRepository} (CarrierPackIdMigrationInjector), the one
 * place both a dedicated server and a singleplayer world hand the saved pack selection to the repository. The world
 * saves the configuration this returns, so the rename is written once and the next load finds nothing to do.
 */
public final class KernelCarrierPackIds {
	private KernelCarrierPackIds() {
	}

	public static WorldDataConfiguration migrate(WorldDataConfiguration config) {
		if (config == null) return null;
		try {
			DataPackConfig packs = config.dataPacks();
			List<String> enabled = KernelDataPacks.migrateCarrierIds(packs.getEnabled());
			List<String> disabled = KernelDataPacks.migrateCarrierIds(packs.getDisabled());
			if (enabled == null && disabled == null) return config;
			ForbricLog.info("[Forbric/DataPacks] this world named the NeoForge carrier's data pack by an installed file "
					+ "name; renamed it in place to %s (enabled %s -> %s%s)", KernelDataPacks.NEO_CARRIER_ID,
					packs.getEnabled(), enabled == null ? packs.getEnabled() : enabled,
					disabled == null ? "" : ", disabled " + packs.getDisabled() + " -> " + disabled);
			return new WorldDataConfiguration(new DataPackConfig(enabled == null ? packs.getEnabled() : enabled,
					disabled == null ? packs.getDisabled() : disabled), config.enabledFeatures());
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/DataPacks] could not rename an old NeoForge carrier pack id in this world's pack list; "
					+ "it loads with the list as saved");
			return config;
		}
	}
}
