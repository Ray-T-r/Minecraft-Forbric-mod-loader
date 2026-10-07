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

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
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
 * Config events use the optional library's public v5 event API; private dispatch implementations are irrelevant.
 */
public final class KernelConfigApiBridge {

	private KernelConfigApiBridge() {
	}

	private static final Map<String, ModContainer> CONTAINERS = new ConcurrentHashMap<>();

	/** The optional public config event protocol. */
	private static final String EVENTS_API = "fuzs.forgeconfigapiport.fabric.api.v5.ModConfigEvents";

	/** One line per boot, on the first event that actually lands, rather than one per mod per config. */
	private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();

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

	/**
	 * Forwards this container's config events to the porting layer's dispatcher.
	 *
	 * <p>The port ships its own {@code ConfigTracker} to call these three methods; under Forbric the carrier's
	 * class wins that name and the port's never loads, so nothing called them. Reflection, because the porting
	 * layer is a MOD — it may not be installed, and the game side must not link against it.
	 *
	 * <p>Failure is per-event and logged once: a mod whose config callback throws must not take the config load
	 * down with it.
	 */
	private static void forwardConfigEvents(IEventBus bus) {
		Class<?> api;
		try { api = Class.forName(EVENTS_API, false, KernelConfigApiBridge.class.getClassLoader()); }
		catch (ClassNotFoundException absent) { return; }
		forward(bus, api, ModConfigEvent.Loading.class, "loading", "Loading", "onModConfigLoading");
		forward(bus, api, ModConfigEvent.Reloading.class, "reloading", "Reloading", "onModConfigReloading");
		forward(bus, api, ModConfigEvent.Unloading.class, "unloading", "Unloading", "onModConfigUnloading");
	}

	private static <E extends ModConfigEvent> void forward(IEventBus bus, Class<?> api, Class<E> event,
			String accessor, String callbackType, String callbackMethod) {
		Method eventFor, invoker, sink;
		try {
			eventFor = api.getMethod(accessor, String.class);
			invoker = Class.forName("net.fabricmc.fabric.api.event.Event", false, api.getClassLoader()).getMethod("invoker");
			sink = Class.forName(EVENTS_API + "$" + callbackType, false, api.getClassLoader()).getMethod(callbackMethod, ModConfig.class);
		} catch (ReflectiveOperationException incompatible) {
			ForbricLog.warn("[Forbric/ConfigApi] public config event contract is unavailable", incompatible); return;
		}
		AtomicBoolean warned = new AtomicBoolean();
		bus.addListener(EventPriority.NORMAL, false, event, e -> {
			try {
				ModConfig config = e.getConfig(); Object declared = eventFor.invoke(null, config.getModId());
				sink.invoke(invoker.invoke(declared), config);
				if (ANNOUNCED.compareAndSet(false, true)) ForbricLog.info("[Forbric/ConfigApi] config events reach their declared public callbacks");
			} catch (Throwable failure) {
				if (warned.compareAndSet(false, true)) ForbricLog.warn("[Forbric/ConfigApi] could not deliver " + callbackMethod, Reflect.unwrap(failure));
			}
		});
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
