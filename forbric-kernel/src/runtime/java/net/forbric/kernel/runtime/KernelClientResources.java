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

import java.lang.reflect.Field;
import java.util.List;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ReloadableResourceManager;

/**
 * Gives the client's {@code ResourceManager} its selected packs BEFORE mod setup runs, so a mod that reads one of
 * its own assets from client setup finds it.
 *
 * <h2>The timing this repairs</h2>
 *
 * <p>{@code Minecraft.<init>} creates an empty {@code ReloadableResourceManager} and does not fill it until
 * {@code createReload(...)}, which is the LAST thing the constructor does — after the kernel's client mod-loading
 * window. Until then {@code getResource} answers empty for everything, vanilla assets included.
 *
 * <p>NeoForge lives with that: its {@code ClientModLoader.finish()} is called from the same place, before the
 * reload, so a NeoForge mod reading a resource in client setup gets nothing there too. <b>MinecraftForge does
 * not.</b> Its {@code ClientModLoader.onResourceReload} is itself a {@code PreparableReloadListener}: mod loading,
 * {@code FMLClientSetupEvent} and the SIDED_SETUP deferred queue all run INSIDE the first reload, by which point
 * the manager holds its packs. The kernel runs one window for both families, so a MinecraftForge mod written
 * against that guarantee met an empty manager.
 *
 * <p>Xaero's World Map is what found it. Its deferred client-setup work calls
 * {@code getResourceManager().getResource(xaeroworldmap:vanilla_states.dat).get()} with no {@code isPresent}
 * check, so the empty Optional became {@code NoSuchElementException} → "Xaero's World Map has crashed!", a report
 * that names the mod and says nothing about when it ran.
 *
 * <h2>What this does, and what it deliberately does not</h2>
 *
 * <p>It installs a {@code MultiPackResourceManager} over {@code packRepository.openAllSelected()} — the same call
 * vanilla makes moments later, from the same repository, after the same {@code reload()} and
 * {@code loadSelectedResourcePacks}. It does NOT run any reload listener: nothing is baked, no model or atlas is
 * built, and vanilla's own reload still does all of that. The packs opened here are this method's own handles, so
 * vanilla closing the manager it replaces closes exactly them.
 *
 * <p>It is a LENIENCY, not a restoration of vanilla behaviour: on genuine NeoForge those reads would still find
 * nothing. Serving the resource cannot break a mod that did not ask for it, and it is what a MinecraftForge mod
 * is entitled to. {@code -Dforbric.clientResourcePreload=off} goes back to the empty manager.
 */
public final class KernelClientResources {
	public static final String PROPERTY = "forbric.clientResourcePreload";

	private KernelClientResources() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * @return the number of packs installed, {@code -1} when switched off, {@code -2} when it could not be done
	 */
	public static int preload() {
		if (!enabled()) return -1;
		try {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null) return -2;
			if (!(minecraft.getResourceManager() instanceof ReloadableResourceManager manager)) return -2;

			List<PackResources> packs = minecraft.getResourcePackRepository().openAllSelected();
			if (packs.isEmpty()) return -2;

			Field resources = ReloadableResourceManager.class.getDeclaredField("resources");
			resources.setAccessible(true);
			Object previous = resources.get(manager);
			resources.set(manager, new MultiPackResourceManager(PackType.CLIENT_RESOURCES, packs));
			// The empty one the constructor made. Closed AFTER the swap so a failure above leaves a live manager.
			if (previous instanceof CloseableResourceManager closeable) closeable.close();
			return packs.size();
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/ClientResources] could not preload the client resource manager: %s",
					String.valueOf(Reflect.unwrap(t)));
			return -2;
		}
	}
}
