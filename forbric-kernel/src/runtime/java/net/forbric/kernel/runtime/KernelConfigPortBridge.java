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

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;

/**
 * Registers a Fabric mod's config with the REAL {@code ConfigTracker}, from a caller that expects an older shape.
 *
 * <p>ForgeConfigAPIPort exists so Fabric mods can use NeoForge's config API, and it ships its own
 * {@code net.neoforged.fml.config.ConfigTracker} to provide it. Under Forbric that package is {@code ALWAYS_GAME},
 * so the carrier's class wins and the port's never loads — and the port's compiled call sites then ask for
 * {@code registerConfig(ModConfig$Type, IConfigSpec, String, String)}, keyed by mod ID, which real NeoForge
 * 26.2.0.88 does not have. It takes a {@code ModContainer}. The result is a {@code NoSuchMethodError} raised not
 * by the port but by whichever mod first registered a config through it, which is how a ShoulderSurfing crash in
 * {@code Minecraft.<init>} came to be a config-API bug.
 *
 * <p>So the ID gets a container. Not a published one: it is identity for the tracker and nothing else. The
 * tracker keys its per-mod lock by {@code container.getModId()} and posts config events through
 * {@code container.acceptEvent}, and that is the whole surface used here.
 *
 * <p><b>The bus is null, deliberately.</b> {@code ModContainer.acceptEvent} is final and begins by returning when
 * its bus is null, so null is a legal and silent "this Fabric mod has no NeoForge mod bus". Handing it the
 * NeoForge BASELINE bus instead would fan a Fabric mod's {@code ModConfigEvent.Loading} out to NeoForge's own
 * listeners under a foreign mod ID.
 *
 * <p>One container per ID, cached: a fresh one per call would give each of a mod's configs a different event sink
 * and a different lock.
 */
public final class KernelConfigPortBridge {

	private KernelConfigPortBridge() {
	}

	private static final Map<String, ModContainer> CONTAINERS = new ConcurrentHashMap<>();

	private static ModContainer containerFor(String modId) {
		return CONTAINERS.computeIfAbsent(modId, id -> (ModContainer) KernelContainers.container(id, null, null));
	}

	/**
	 * The mod-ID-keyed 3-arg registration the porting layer compiled against.
	 *
	 * <p>Registration only. The carrier opens a STARTUP config here and leaves the rest to the lifecycle, and that
	 * is kept: a config registered from a Fabric entrypoint that runs before the kernel's early-config pass would
	 * otherwise be opened twice, and the carrier's second open warns and installs a second file watcher, so every
	 * later edit fires the reload twice. {@code KernelLifecycle} opens what is still unopened, once, afterwards.
	 */
	public static ModConfig registerConfig(ConfigTracker tracker, ModConfig.Type type, IConfigSpec spec,
			String modId) {
		return tracker.registerConfig(type, spec, containerFor(modId));
	}

	/** The 4-arg form, with the mod's own file name. */
	public static ModConfig registerConfig(ConfigTracker tracker, ModConfig.Type type, IConfigSpec spec,
			String modId, String fileName) {
		return tracker.registerConfig(type, spec, containerFor(modId), fileName);
	}
}
