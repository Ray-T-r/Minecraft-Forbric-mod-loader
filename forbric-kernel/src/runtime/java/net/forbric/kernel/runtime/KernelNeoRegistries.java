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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.forbric.kernel.boot.KernelModLoader;
import net.forbric.kernel.util.ForbricLog;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import net.neoforged.neoforge.registries.ModifyRegistriesEvent;

/**
 * The game side of NeoForge's registry-creation phase: {@code NewRegistryEvent}, filled, then
 * {@code ModifyRegistriesEvent}.
 *
 * <p>{@code RegisterEvent} is not posted here any more: NeoForge's own {@code GameData.postRegisterEvents} posts it, so
 * every mixin on that method runs ({@code RegisterEventsSeam}). The boot side keeps what is policy — which buses, and the
 * unfreeze/freeze window all of this runs inside.
 *
 * <h2>What is still reflective</h2>
 *
 * <p>Both event constructors are package-private, which is reflection on either side. {@code NewRegistryEvent.fill} is
 * likewise package-private.
 */
public final class KernelNeoRegistries {
	private KernelNeoRegistries() {
	}

	/**
	 * Posts {@code NewRegistryEvent} to every bus, then fills it — the phase before {@code RegisterEvent} where
	 * mods create their own registries.
	 *
	 * <p>The kernel fired this only on traditional Forge's global bus and drove NeoForge's own handler directly,
	 * so no NeoForge MOD ever received it. Two things break without it: a mod that declares a custom registry
	 * never gets one, and — less obviously — mods use this earliest mod-bus phase for setup that later phases
	 * depend on. WhiteNoise loads its config here (its own spec, outside NeoForge's {@code ConfigTracker}, so no
	 * amount of {@code loadConfigs} substitutes), which is why Mob Champions could read a config value from its
	 * {@code RegisterEvent} listener on genuine NeoForge but threw "Cannot get config value before config is
	 * loaded" here.
	 *
	 * <p>One event instance is posted to every bus and filled once, matching NeoForge, which collects each mod's
	 * registries into a single event and registers them together.
	 *
	 * @return how many buses received it
	 */
	public static int postNewRegistryEvent(List<Object> buses) throws Exception {
		Constructor<NewRegistryEvent> ctor = NewRegistryEvent.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		NewRegistryEvent event = ctor.newInstance();
		Map<Object, ModContainer> containers = containersByBus();

		// Natively RegistryManager.postNewRegistryEvent hands it to every container (ModLoader.postEventWrapContainerInModOrder),
		// and the first listener that throws ends the "Registry initialization" task: the boot stops on it (D87). The kernel
		// used to skip that mod's custom registries and carry on.
		int delivered = 0;
		ClassLoader cl = KernelNeoRegistries.class.getClassLoader();
		for (Object bus : buses) {
			// The bus's mod active while it takes the event: RegistryManager.postNewRegistryEvent posts it through
			// ModLoader.postEventWrapContainerInModOrder, so natively a listener's ModLoadingContext names its own mod.
			// Here it said "minecraft".
			ModContainer container = containers.get(bus);
			if (container != null) KernelModLoader.setNeoActiveContainer(cl, container);
			try {
				post(container, (IEventBus) bus, event);
			} finally {
				if (container != null) KernelModLoader.setNeoActiveContainer(cl, null);
			}
			delivered++;
		}

		// The fill, too, is inside that task natively (a mod declaring a registry twice fails it).
		Method fill = NewRegistryEvent.class.getDeclaredMethod("fill");
		fill.setAccessible(true);
		try {
			fill.invoke(event);
		} catch (InvocationTargetException failed) {
			throw failed.getCause() instanceof Exception e ? e : failed;
		}
		if (!"off".equalsIgnoreCase(System.getProperty("forbric.modifyRegistriesEvent", "on"))) {
			Constructor<ModifyRegistriesEvent> modifyCtor = ModifyRegistriesEvent.class.getDeclaredConstructor();
			modifyCtor.setAccessible(true);
			ModifyRegistriesEvent modify = modifyCtor.newInstance();
			for (Object bus : buses) post(containers.get(bus), (IEventBus) bus, modify);
			ForbricLog.info("[Forbric/Lifecycle] posted ModifyRegistriesEvent after registry creation, before content registration");
		}
		return delivered;
	}

	/**
	 * Hands {@code event} to the mod whose bus {@code bus} is, as NeoForge does: through its container's
	 * {@code ModContainer.acceptEvent}, which turns a listener's throw into a {@code ModLoadingException} naming the mod. A bus
	 * with no container the kernel knows (none should be left) gets the event straight.
	 */
	private static <T extends net.neoforged.bus.api.Event & net.neoforged.fml.event.IModBusEvent> void post(ModContainer container,
			IEventBus bus, T event) {
		if (container != null) container.acceptEvent(event);
		else bus.post(event);
	}

	/** Each published NeoForge mod's container, by its bus. */
	private static Map<Object, ModContainer> containersByBus() {
		Map<Object, ModContainer> byBus = new IdentityHashMap<>();
		for (KernelModLoader.NeoIdentity identity : KernelModLoader.publishedNeoMods().values()) {
			if (identity.bus() != null && identity.container() instanceof ModContainer container) byBus.put(identity.bus(), container);
		}
		return byBus;
	}
}
