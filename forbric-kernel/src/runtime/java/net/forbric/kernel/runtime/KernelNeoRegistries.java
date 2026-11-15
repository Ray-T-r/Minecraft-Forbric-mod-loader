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
import java.lang.reflect.Method;
import java.util.List;

import net.forbric.kernel.boot.KernelModLoader;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;
import net.neoforged.bus.api.IEventBus;
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

		int delivered = 0;
		ClassLoader cl = KernelNeoRegistries.class.getClassLoader();
		for (Object bus : buses) {
			// The bus's mod active while it takes the event: RegistryManager.postNewRegistryEvent posts it through
			// ModLoader.postEventWrapContainerInModOrder, so natively a listener's ModLoadingContext names its own mod.
			// Here it said "minecraft".
			Object container = containerOf(bus);
			if (container != null) KernelModLoader.setNeoActiveContainer(cl, container);
			try {
				((IEventBus) bus).post(event);
				delivered++;
			} catch (Throwable perBus) {
				Caught.warn(perBus, "[Forbric/Lifecycle] a NeoForge mod's NewRegistryEvent listener failed — that "
						+ "mod's custom registries are skipped, the rest still register");
			} finally {
				if (container != null) KernelModLoader.setNeoActiveContainer(cl, null);
			}
		}

		// Separate from the delivery above: mods have already been notified by this point, so a fill() failure must
		// not be reported as "could not post" — the earliest-phase setup mods hang off this event has happened
		// either way.
		try {
			Method fill = NewRegistryEvent.class.getDeclaredMethod("fill");
			fill.setAccessible(true);
			fill.invoke(event);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Lifecycle] NewRegistryEvent.fill failed — mod-declared custom registries "
					+ "may be missing (delivery to the mod buses itself succeeded)");
		}
		if (!"off".equalsIgnoreCase(System.getProperty("forbric.modifyRegistriesEvent", "on"))) {
			Constructor<ModifyRegistriesEvent> modifyCtor = ModifyRegistriesEvent.class.getDeclaredConstructor();
			modifyCtor.setAccessible(true);
			ModifyRegistriesEvent modify = modifyCtor.newInstance();
			for (Object bus : buses) {
				try { ((IEventBus) bus).post(modify); }
				catch (Throwable failed) {
					Caught.warn(failed, "[Forbric/Lifecycle] a NeoForge ModifyRegistriesEvent listener failed");
				}
			}
			ForbricLog.info("[Forbric/Lifecycle] posted ModifyRegistriesEvent after registry creation, before content registration");
		}
		return delivered;
	}

	/** The container of the published NeoForge mod whose bus {@code bus} is, or null. */
	private static Object containerOf(Object bus) {
		for (KernelModLoader.NeoIdentity identity : KernelModLoader.publishedNeoMods().values()) {
			if (identity.bus() == bus) return identity.container();
		}
		return null;
	}
}
