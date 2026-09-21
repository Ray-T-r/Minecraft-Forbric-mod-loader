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

package net.forbric.kernel.boot;

import java.util.LinkedHashMap;
import java.util.Map;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;

/**
 * A NeoForge {@code ModContainer} for a mod that is NOT a NeoForge mod, so a Forge-family library it calls has a
 * context to answer with.
 *
 * <p>A multi-loader library ships one build per loader and only one copy of a class can exist, so whichever build
 * the nested-jar arbitration keeps is the build EVERY host gets — including hosts from the other family. See
 * {@link DuplicateModArbiter#nestedPreference()} for why that arbitration cannot simply be made to agree with
 * every host: tr7zw's {@code transition} is nested by a Fabric mod and a NeoForge mod, one host each, same
 * version, and the two builds stub out each other's lifecycle phases.
 *
 * <p>What the kernel CAN fix is the half that is its own: a Fabric mod handed the NeoForge build calls
 * {@code ModLoadingContext.get().getActiveContainer().getEventBus()}, and outside a window the kernel wraps that
 * falls back to NeoForge's {@code "minecraft"} container, whose {@code getEventBus()} is null by design. So
 * EntityCulling's {@code onInitializeClient} did not register a keybind and did not fail cleanly either — it threw
 * {@code NullPointerException: Cannot invoke IEventBus.addListener because ModContainer.getEventBus() is null} out
 * of its first line and lost the whole entrypoint, every mixin it had already applied still in place.
 *
 * <p>This mints one real bus and container per foreign mod id, on demand, so that call answers. A container handed
 * out here is NOT published into {@code ModList}: the mod is not a NeoForge mod, {@code isModLoaded} already
 * answers for it through the presence aliases, and putting it in the list the mod-bus fan-out walks would post
 * every NeoForge lifecycle phase at a mod that never asked for one.
 */
final class KernelForeignShimContext {
	/** The switch that puts back NeoForge's "minecraft" fallback, whose {@code getEventBus()} is null. */
	static final String SWITCH = "forbric.foreignShimContext";
	private static final Map<String, KernelModLoader.NeoIdentity> CONTAINERS = new LinkedHashMap<>();
	private static int served;

	private KernelForeignShimContext() {
	}

	/** The container for {@code modId}, minting one on first use. Null if the NeoForge runtime is not present. */
	static synchronized KernelModLoader.NeoIdentity identityFor(ClassLoader cl, String modId) {
		if ("off".equalsIgnoreCase(System.getProperty(SWITCH, "on"))) return null;
		if (CONTAINERS.containsKey(modId)) return CONTAINERS.get(modId);
		KernelModLoader.NeoIdentity identity = null;
		try {
			Object bus = KernelBusSupport.makeModBus(cl);
			Object container = Class.forName("net.forbric.kernel.runtime.KernelContainers", true, cl)
					.getMethod("container", String.class, Object.class, java.nio.file.Path.class)
					.invoke(null, modId, bus, null);
			identity = new KernelModLoader.NeoIdentity(bus, container);
		} catch (ClassNotFoundException | NoClassDefFoundError absent) {
			ForbricLog.debug("[Forbric/ShimContext] no NeoForge runtime — '%s' gets no container", modId);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/ShimContext] could not build a container for '%s': %s", modId,
					String.valueOf(Reflect.unwrap(t)));
		}
		CONTAINERS.put(modId, identity);
		return identity;
	}

	/**
	 * Runs {@code body} with {@code modId}'s container active, then clears it.
	 *
	 * <p>Clears rather than restores: the kernel only ever sets one of these around a window it owns, and a stale
	 * container is how content gets registered under another mod's namespace.
	 */
	static void with(ClassLoader cl, String modId, Runnable body) {
		KernelModLoader.NeoIdentity identity = identityFor(cl, modId);
		if (identity == null) {
			body.run();
			return;
		}
		KernelModLoader.setNeoActiveContainer(cl, identity.container());
		try {
			body.run();
		} finally {
			KernelModLoader.setNeoActiveContainer(cl, null);
		}
	}

	/** Says how many foreign mods were given one, once, after the window that hands them out has closed. */
	static synchronized void report() {
		int live = handedOut().size();
		if (live == 0 || served == live) return;
		served = live;
		ForbricLog.info("[Forbric/ShimContext] %d non-NeoForge mod(s) ran with a NeoForge ModContainer of their "
				+ "own — a multi-loader library has one build per loader and only one copy of a class can exist, "
				+ "so a Fabric mod can be holding the NeoForge build and asking it to register something "
				+ "(-D%s=off to hand it NeoForge's null-bus \"minecraft\" fallback instead)", live, SWITCH);
	}

	/**
	 * Delivers what those mods registered on their own bus. Called once the CLIENT is far enough along to have
	 * the things those events carry — not from the window that hands the containers out, which is inside
	 * {@code Minecraft.<init>} and reached before {@code Minecraft.options} exists at all.
	 *
	 * <p>This is the half that makes the container worth having. A bus the kernel mints is in nobody's
	 * {@code ModList}, so NeoForge's own fan-out never reaches it: without this a keybind is registered and then
	 * sits on a bus no event will ever arrive at, which looks exactly like working right up to the moment the
	 * player presses the key.
	 */
	static void deliver(ClassLoader cl) {
		java.util.List<Object> buses = new java.util.ArrayList<>();
		for (KernelModLoader.NeoIdentity identity : handedOut().values()) buses.add(identity.bus());
		if (buses.isEmpty()) return;
		try {
			Class.forName("net.forbric.kernel.runtime.KernelForeignShimKeys", true, cl)
					.getMethod("deliver", java.util.Collection.class).invoke(null, buses);
		} catch (ClassNotFoundException | NoClassDefFoundError absent) {
			ForbricLog.debug("[Forbric/ShimContext] no runtime half — nothing to deliver");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/ShimContext] could not deliver what the foreign mods registered",
					Reflect.unwrap(t));
		}
	}

	/** Every container handed out so far, by mod id. */
	static synchronized Map<String, KernelModLoader.NeoIdentity> handedOut() {
		Map<String, KernelModLoader.NeoIdentity> live = new LinkedHashMap<>();
		for (Map.Entry<String, KernelModLoader.NeoIdentity> e : CONTAINERS.entrySet()) {
			if (e.getValue() != null) live.put(e.getKey(), e.getValue());
		}
		return live;
	}

	/** Test seam. */
	static synchronized void reset() {
		CONTAINERS.clear();
		served = 0;
	}
}
