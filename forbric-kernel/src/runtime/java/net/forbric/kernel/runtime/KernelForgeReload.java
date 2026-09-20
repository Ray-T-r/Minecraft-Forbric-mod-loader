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

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraftforge.event.ForgeEventFactory;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.resource.ListenerKey;

/**
 * The server twin of {@link KernelGameClientReload}: posts MinecraftForge's {@code AddReloadListenerEvent} from
 * the merged server reload, through the carrier's own {@code ForgeEventFactory}.
 *
 * <p>The merged {@code ReloadableServerResources.loadResources} calls only NeoForge's
 * {@code EventHooks.onResourceReload}; a constant-pool scan of the merged base finds ZERO references to Forge's
 * event. So every traditional-Forge mod that registers a JSON data loader the documented way — from
 * {@code AddReloadListenerEvent} — had its listener registered on a bus nobody posted to, and its custom data
 * folder loaded nothing, silently. The merged constructor's single call site is redirected here with the same
 * descriptor; this method calls NeoForge's hook first (so its list, context injection and ordering are exactly
 * what they were), then hands that list to the carrier's real {@code ForgeEventFactory.onResourceReload}, which
 * fires the Forge bus and appends what Forge listeners added. Forge's own order is the same: its listeners come
 * after the game's. The registry lookup handed to Forge is the same object NeoForge's path uses
 * ({@code getRegistryLookup()} returns the {@code lookupWithUpdatedTags} the resources were built with).
 *
 * <p>{@code -Dforbric.forgeReloadListeners=off} returns NeoForge's list unchanged — the redirect itself is inert
 * — and a throwing Forge listener costs this reload every Forge listener (the carrier fires them in one post),
 * which is logged rather than allowed to abort the reload.
 */
public final class KernelForgeReload {
	public static final String PROPERTY = "forbric.forgeReloadListeners";

	private KernelForgeReload() {
	}

	/** Same descriptor as the NeoForge hook it stands in front of, so the merged call site's stack is untouched. */
	public static List<PreparableReloadListener> onResourceReload(ReloadableServerResources resources,
			RegistryAccess registries, Map<ListenerKey<?>, PreparableReloadListener> retained) {
		List<PreparableReloadListener> neo = EventHooks.onResourceReload(resources, registries, retained);
		if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) {
			ForbricLog.debug("[Forbric/EventMux] -D%s=off — MinecraftForge's AddReloadListenerEvent is not posted "
					+ "for this reload", PROPERTY);
			return neo;
		}
		try {
			// The carrier's own code: constructs the event, fires AddReloadListenerEvent.BUS, and returns a fresh
			// list of NeoForge's listeners followed by whatever Forge listeners added.
			List<PreparableReloadListener> all = ForgeEventFactory.onResourceReload(resources,
					resources.getRegistryLookup(), neo);
			int added = all.size() - neo.size();
			if (added > 0) {
				ForbricLog.info("[Forbric/EventMux] bridged %d MinecraftForge server reload listener(s) into this "
						+ "reload — the merged base posts only NeoForge's AddServerReloadListenersEvent, so a "
						+ "traditional-Forge mod's JSON data loaders were never registered", added);
			} else {
				ForbricLog.debug("[Forbric/EventMux] MinecraftForge's AddReloadListenerEvent was posted; no Forge "
						+ "listener added anything to this reload");
			}
			return all;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/EventMux] could not post MinecraftForge's AddReloadListenerEvent — every "
					+ "Forge reload listener is absent from this reload", Reflect.unwrap(t));
			return neo;
		}
	}
}
