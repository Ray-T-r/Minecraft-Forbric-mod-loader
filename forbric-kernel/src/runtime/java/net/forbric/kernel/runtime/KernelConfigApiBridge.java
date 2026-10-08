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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.gui.screens.Screen;

import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.fml.event.config.ModConfigEvent;

/**
 * Connects the mod-ID form of the published config API to the carrier's container form.
 * Configuration events are delivered to discoverable protocol extensions.
 */
public final class KernelConfigApiBridge {

	private KernelConfigApiBridge() {
	}

	private static final Map<String, ModContainer> CONTAINERS = new ConcurrentHashMap<>();

	static ModContainer containerFor(String modId) {
		return CONTAINERS.computeIfAbsent(modId, id -> {
			// markerType and allowPerPhasePost mirror the mod buses the kernel builds elsewhere: ModConfigEvent is
			// an IModBusEvent, and a bus that does not carry that marker is not the bus these events belong on.
			// No start() call — BusBuilder only starts a bus shut down if asked with startShutdown().
			IEventBus bus = BusBuilder.builder()
					.markerType(IModBusEvent.class)
					.allowPerPhasePost()
					.build();
			ModContainer container = (ModContainer) KernelContainers.container(id, bus, null);
			forwardConfigEvents(bus);
			return container;
		});
	}

	private static void forwardConfigEvents(IEventBus bus) {
        var protocols = net.forbric.api.ProtocolExtensions.forLoader(KernelConfigApiBridge.class.getClassLoader());
        bus.addListener(EventPriority.NORMAL, false, ModConfigEvent.Loading.class,
            event -> protocols.configEvent(net.forbric.api.ProtocolExtension.ConfigEvent.LOADING, event.getConfig()));
        bus.addListener(EventPriority.NORMAL, false, ModConfigEvent.Reloading.class,
            event -> protocols.configEvent(net.forbric.api.ProtocolExtension.ConfigEvent.RELOADING, event.getConfig()));
        bus.addListener(EventPriority.NORMAL, false, ModConfigEvent.Unloading.class,
            event -> protocols.configEvent(net.forbric.api.ProtocolExtension.ConfigEvent.UNLOADING, event.getConfig()));
    }

	/**
	 * The mod-ID-keyed 3-arg registration the porting layer compiled against.
	 *
	 * <p>Registered with the carrier, then opened as the port opens it — every type but SERVER, right here
	 * ({@link KernelConfigLoad#openAtRegistration}): a Fabric mod reads its config in the same {@code onInitialize}
	 * that registers it. The kernel's early pass skips what is already loaded, so nothing is opened twice (a second
	 * open warns and installs a second file watcher, and every later edit fires the reload twice).
	 */
	public static ModConfig registerConfig(ConfigTracker tracker, ModConfig.Type type, IConfigSpec spec,
			String modId) {
		ModConfig config = tracker.registerConfig(type, spec, containerFor(modId));
		KernelConfigLoad.openAtRegistration(config);
		return config;
	}

	/** The 4-arg form, with the mod's own file name. */
	public static ModConfig registerConfig(ConfigTracker tracker, ModConfig.Type type, IConfigSpec spec,
			String modId, String fileName) {
		ModConfig config = tracker.registerConfig(type, spec, containerFor(modId), fileName);
		KernelConfigLoad.openAtRegistration(config);
		return config;
	}

	/**
	 * The mod-ID-keyed config screen the porting layer's consumers compiled against.
	 *
	 * <p>Same skew, one class over and one step further out: the port ships its own
	 * {@code ConfigurationScreen} whose constructor takes a mod ID where real NeoForge's takes a
	 * {@code ModContainer}, and mods written for the port name that constructor THEMSELVES. A config consumer hands
	 * {@code ConfigurationScreen::new} to the port's screen-factory registry, so the mismatch is not even a call —
	 * it is a method handle in an {@code invokedynamic}, resolved when the lambda's call site links, which is why
	 * it surfaced as a {@code NoSuchMethodError} from a line that constructs nothing.
	 *
	 * <p>Returns {@code Screen} rather than {@code ConfigurationScreen} so it matches the instantiated type of
	 * that lambda exactly; the factory's functional interface produces a {@code Screen}.
	 */
	public static Screen configurationScreen(String modId, Screen parent) {
		return new net.neoforged.neoforge.client.gui.ConfigurationScreen(containerFor(modId), parent);
	}
}
