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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.forbric.api.ModCatalog;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.fml.loading.FMLPaths;

/**
 * The game side of loading NeoForge's configs: the early pass, and the late pass that catches what the early one
 * could not have seen.
 *
 * <p>WHICH types each pass covers, and when each runs, is policy — it stays boot-side in
 * {@code KernelLifecycle}, where {@code lateConfigTypes} has a test and its javadoc records why SERVER is absent
 * from both lists. This is only the doing.
 *
 * <h2>The one member that stays reflective</h2>
 *
 * <p>{@code ConfigTracker.openConfig} is package-private static, and it is the only entry point that opens ONE
 * config. {@code loadConfigs} is public and is the wrong call for the late pass: it re-opens every config of the
 * type, and the carrier's second open warns and installs a SECOND file watcher, so every later edit of that file
 * fires the reload twice.
 */
public final class KernelConfigLoad {
	private KernelConfigLoad() {
	}

	/**
	 * Loads each named config type from the global config directory.
	 *
	 * <p>STARTUP must not be named here. {@code ConfigTracker.registerConfig} opens a STARTUP config EAGERLY at
	 * registration, so naming it asks the carrier to open every one a SECOND time — which it does, warning
	 * "Opening a config that was already loaded" and firing {@code ModConfigEvent.Loading} again. The late pass
	 * still covers STARTUP, and it opens only what has no loaded config yet.
	 *
	 * <p>Missing files are fine: NeoForge writes defaults. Best-effort per type, so one unusable type does not
	 * cost the others.
	 */
	public static void loadEarly(List<String> types) {
		try {
			Path configDir = FMLPaths.CONFIGDIR.get();
			Method openConfig = openConfig();
			int opened = 0, all = 0;
			for (String t : types) {
				// The carrier's loadConfigs(type, dir) is exactly this forEach over configSets.get(type) — spelled
				// out so ONE config that will not open costs only its own mod a row, not every config after it.
				Set<ModConfig> configs = ModConfigs.getConfigSet(ModConfig.Type.valueOf(t));
				if (configs == null) continue;
				for (ModConfig config : List.copyOf(configs)) {
					all++;
					if (open(openConfig, config, configDir, t, "early")) opened++;
				}
			}
			ForbricLog.info("[Forbric/Lifecycle] loaded NeoForge configs (%s) from %s — opened %d of %d config(s)",
					String.join("+", types), configDir, opened, all);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not load NeoForge configs", Reflect.unwrap(t));
		}
	}

	/** The one package-private static entry point that opens ONE config, on both passes. */
	private static Method openConfig() throws ReflectiveOperationException {
		Method openConfig = ConfigTracker.class.getDeclaredMethod("openConfig", ModConfig.class, Path.class, Path.class);
		openConfig.setAccessible(true);
		return openConfig;
	}

	/** Opens one config; a failure marks its mod DEGRADED and answers false. */
	private static boolean open(Method openConfig, ModConfig config, Path configDir, String type, String pass) {
		try {
			openConfig.invoke(null, config, configDir, null);
			return true;
		} catch (Throwable failed) {
			ForbricLog.warn("[Forbric/Lifecycle] could not open " + config.getModId() + "'s " + type + " config ("
					+ pass + " pass)", Reflect.unwrap(failed));
			ModCatalog.mark(config.getModId(), ModCatalog.Status.DEGRADED, "its " + type + " config could not be opened");
			return false;
		}
	}

	/**
	 * Opens every config of the named types that has no loaded config yet.
	 *
	 * <p>The early pass happens once, before mod content registration. A Fabric mod registering a config from a
	 * CLIENT entrypoint is therefore too late for it, and nothing else opens a non-STARTUP config — the carrier's
	 * {@code registerConfig} eagerly opens STARTUP only. The mod then reads a config that was registered and
	 * never loaded, and what it gets is not an empty config but "Cannot get config value before config is
	 * loaded", thrown from wherever it first asked. ShoulderSurfing asks from a mixin in {@code Minecraft.<init>}.
	 *
	 * <p>General on purpose: it fixes any late registrar, not the one that exposed it, and it cannot double-open
	 * because it opens only what has no loaded config yet.
	 *
	 * @return {@code modid:TYPE} for each config opened
	 */
	public static List<String> openLate(List<String> types) {
		List<String> opened = new ArrayList<>();
		try {
			Path configDir = FMLPaths.CONFIGDIR.get();
			Method openConfig = openConfig();

			for (String t : types) {
				Set<ModConfig> configs = ModConfigs.getConfigSet(ModConfig.Type.valueOf(t));
				if (configs == null) continue;
				for (ModConfig config : List.copyOf(configs)) {
					if (config.getLoadedConfig() != null) continue;
					if (open(openConfig, config, configDir, t, "late")) opened.add(config.getModId() + ":" + t);
				}
			}
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not open late-registered NeoForge configs",
					Reflect.unwrap(t));
		}
		return opened;
	}
}
