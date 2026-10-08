/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop.protocol.modmenu;

import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;
import net.forbric.api.*;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;

/** Mod Menu's public config-screen protocol and optional API stand-in. */
public final class ModMenuProtocolExtension implements ProtocolExtension {
    private static final String MOD_MENU = "com.terraformersmc.modmenu.ModMenu";
    private static final String API = "com.terraformersmc.modmenu.api.ModMenuApi";
    private Method has, get;
    private ModMenuConfigFactories entrypoints;
    private boolean looked;
    @Override public String id() { return "modmenu-config-screens"; }
    @Override public void offerClasses(Context context) {
        if (context.side() == Side.CLIENT && context.gameLoader() instanceof ForbricClassLoader loader)
            ModMenuApiStandIn.install(loader);
    }
    @Override public synchronized ConfigurationScreen configurationScreen(Context context, ModCatalog.Entry mod) {
        if (mod.ecosystem() != Ecosystem.FABRIC) return null;
        if (!looked) look(context.gameLoader());
        try {
            if (has != null) {
                if (!(Boolean) has.invoke(null, mod.modId())) return null;
                return new ConfigurationScreen(parent -> {
                    try { return get.invoke(null, mod.modId(), parent); }
                    catch (ReflectiveOperationException failure) { throw new IllegalStateException("config factory threw", Reflect.unwrap(failure)); }
                });
            }
            if (entrypoints == null || !entrypoints.has(mod.modId())) return null;
            return new ConfigurationScreen(parent -> {
                try { return entrypoints.create(mod.modId(), parent); }
                catch (Exception failure) { throw new IllegalStateException("config factory threw", Reflect.unwrap(failure)); }
            });
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("config screen lookup failed", Reflect.unwrap(failure)); }
    }
    private void look(ClassLoader game) {
        try {
            Class<?> mm = Class.forName(MOD_MENU, false, game);
            has = mm.getMethod("hasConfigScreen", String.class);
            get = mm.getMethod("getConfigScreen", String.class, Class.forName("net.minecraft.client.gui.screens.Screen", false, game));
            ForbricLog.info("[Forbric/ModConfig] Fabric config screens come from installed Mod Menu");
        } catch (ReflectiveOperationException | LinkageError absent) {
            has = null; get = null;
            if (ModMenuApiStandIn.enabled()) try {
                Class<?> api = Class.forName(API, false, game);
                entrypoints = ModMenuConfigFactories.read(FabricLoader.getInstance(), api);
                ForbricLog.info("[Forbric/ModConfig] read %d declared Mod Menu entrypoint(s), %d broken", entrypoints.entrypoints(), entrypoints.broken());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                ForbricLog.debug("[Forbric/ModConfig] Mod Menu config contract is unavailable: %s", String.valueOf(unavailable));
            }
        }
        looked = true;
    }
}
