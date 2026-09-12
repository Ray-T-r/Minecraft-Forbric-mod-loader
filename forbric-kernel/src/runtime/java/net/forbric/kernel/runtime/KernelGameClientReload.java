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
import java.util.Locale;
import java.util.function.Consumer;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;

/**
 * Collects traditional MinecraftForge's client reload listeners and hands them to NeoForge's sorted graph.
 *
 * <p>The two families register client reload listeners through different events, and only NeoForge's survives on
 * the merged base. A Forge-family mod's listener would otherwise never be registered at all, so its resources
 * are never reloaded and its assets silently stay missing.
 *
 * <h2>The scratch manager</h2>
 *
 * <p>MinecraftForge's event has no accessor for what was registered to it — the listeners go straight into the
 * {@code ReloadableResourceManager} it was constructed with. So one is constructed purely as a capture buffer,
 * the Forge event is posted against it, and whatever landed inside is read back out and re-registered on
 * NeoForge's event. The manager is never used to reload anything; its constructor seeds an empty list, which is
 * why it is safe to read immediately.
 *
 * <p>Each listener gets a synthetic {@code Identifier} because NeoForge's graph is sorted by id and requires
 * one. The id is derived from the listener's own class name, reduced to the characters an {@code Identifier}
 * path allows, plus its index — so two listeners of the same class do not collide.
 */
public final class KernelGameClientReload {
	private KernelGameClientReload() {
	}

	/**
	 * @param modBus the NeoForge MOD bus, handed over untyped from the boot side. This event is a mod-bus event,
	 *               not a game-bus one, which is why it is a separate pass from the other four bridges.
	 */
	public static void install(Object modBus) {
		Consumer<AddClientReloadListenersEvent> bridge = event -> {
			try {
				// The capture buffer. Reading getListeners() rather than the private field it returns: javap -c
				// shows the accessor is `getfield listeners; areturn`, the same bytes without the private name.
				ReloadableResourceManager scratch = new ReloadableResourceManager(PackType.CLIENT_RESOURCES);
				RegisterClientReloadListenersEvent.BUS.post(new RegisterClientReloadListenersEvent(scratch));

				List<PreparableReloadListener> listeners = scratch.getListeners();
				int n = 0;
				for (PreparableReloadListener listener : listeners) {
					event.addListener(Identifier.fromNamespaceAndPath("forbric",
							"forge/" + sanitisePath(listener.getClass().getName()) + "_" + n), listener);
					n++;
				}

				if (n > 0) {
					ForbricLog.info("[Forbric/EventMux] bridged %d Forge client reload listener(s) into "
							+ "NeoForge's sorted graph", n);
				} else {
					ForbricLog.debug("[Forbric/EventMux] no Forge mod registered a client reload listener");
				}
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/EventMux] could not bridge Forge client reload listeners",
						Reflect.unwrap(t));
			}
		};

		// Four-argument overload with LOWEST, as everywhere in this package.
		((IEventBus) modBus).addListener(EventPriority.LOWEST, false, AddClientReloadListenersEvent.class, bridge);
	}

	/** A class name reduced to the {@code [a-z0-9._/-]} an {@code Identifier} path allows. */
	private static String sanitisePath(String className) {
		StringBuilder out = new StringBuilder(className.length());
		for (char c : className.toLowerCase(Locale.ROOT).toCharArray()) {
			out.append(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '/' || c == '-' ? c : '_');
		}
		return out.toString();
	}
}
