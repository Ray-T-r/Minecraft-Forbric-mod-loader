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
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;

/** Opens genuine MinecraftForge configs one at a time, retaining its reader, events, save and file watcher. */
public final class KernelForgeConfigLoad {
    private KernelForgeConfigLoad() {}

    /** The caller chooses the side's types; their Forge ordering is CLIENT then COMMON. */
    public static void loadEarly(List<String> types) {
        open(types, false);
    }

    /** Returns modid:TYPE only for successful new opens; already loaded data is never opened a second time. */
    public static List<String> openLate(List<String> types) {
        return open(types, true);
    }

    private static List<String> open(List<String> names, boolean late) {
        if ("off".equalsIgnoreCase(System.getProperty("forbric.earlyConfigs", "on"))) return List.of();
        List<ModConfig.Type> types = selectedTypes(names);
        Path directory = FMLPaths.CONFIGDIR.get();
        List<String> opened = new ArrayList<>();
        for (ModConfig.Type type : types) {
            Set<ModConfig> configs = ConfigTracker.configSets().get(type);
            List<ModConfig> snapshot;
            if (configs == null) snapshot = List.of();
            else synchronized (configs) { snapshot = List.copyOf(configs); }
            int applied = 0, alreadyLoaded = 0, failed = 0;
            for (ModConfig config : snapshot) {
                // An overlapping early/late caller must not both observe null and install two watchers.
                synchronized (config) {
                    try {
                        if (config.getConfigData() != null) {
                            alreadyLoaded++;
                            continue;
                        }
                        openOne(config, directory);
                        applied++;
                        opened.add(config.getModId() + ":" + type.name());
                    } catch (Throwable failure) {
                        failed++;
                        Throwable cause = Reflect.unwrap(failure);
                        String detail = "MinecraftForge " + type.name() + " config " + config.getFileName()
                                + " could not be opened: " + cause;
                        ModCatalog.mark(config.getModId(), ModCatalog.Status.DEGRADED, detail);
                        ForbricLog.warn("[Forbric/Lifecycle] " + config.getModId() + ": " + detail, cause);
                    }
                }
            }
            ForbricLog.info("[Forbric/Lifecycle] %s MinecraftForge configs (%s): applied %d, already loaded %d, failed %d from %s",
                    late ? "opened late" : "loaded", type.name(), applied, alreadyLoaded, failed, directory);
        }
        return List.copyOf(opened);
    }

    /**
     * Do not reuse NeoForge's type set or enum conversion: Forge has no STARTUP and SERVER is per-world.
     * Validate the whole request before opening anything, then normalize duplicates/order independently.
     */
    private static List<ModConfig.Type> selectedTypes(List<String> names) {
        boolean client = false, common = false;
        for (String name : names) {
            if ("CLIENT".equals(name)) client = true;
            else if ("COMMON".equals(name)) common = true;
            else throw new IllegalArgumentException("unsupported early/late MinecraftForge config type: " + name);
        }
        List<ModConfig.Type> selected = new ArrayList<>(2);
        if (client) selected.add(ModConfig.Type.CLIENT);
        if (common) selected.add(ModConfig.Type.COMMON);
        return selected;
    }

    /** The private two-argument static method is Forge's own single-config loading transaction. */
    private static void openOne(ModConfig config, Path directory) throws ReflectiveOperationException {
        Method open = ConfigTracker.class.getDeclaredMethod("openConfig", ModConfig.class, Path.class);
        open.setAccessible(true);
        open.invoke(null, config, directory);
    }
}
