/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop.protocol.configapi;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.forbric.api.*;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;

/** The optional Forge Config API Port public v5 event protocol. */
public final class ConfigApiPortProtocolExtension implements ProtocolExtension {
    private static final String API = "fuzs.forgeconfigapiport.fabric.api.v5.ModConfigEvents";
    private record Sink(Method event, Method invoker, Method callback) { }
    private final EnumMap<ConfigEvent, Sink> sinks = new EnumMap<>(ConfigEvent.class);
    private final AtomicBoolean announced = new AtomicBoolean();
    private boolean looked;
    @Override public String id() { return "forge-config-api-port-v5"; }
    @Override public void configEvent(Context context, ConfigEvent event, Object config) {
        Sink sink = sink(context.gameLoader(), event);
        if (sink == null) return;
        try {
            String modId = (String) config.getClass().getMethod("getModId").invoke(config);
            Object declared = sink.event.invoke(null, modId);
            sink.callback.invoke(sink.invoker.invoke(declared), config);
            if (announced.compareAndSet(false, true)) ForbricLog.info("[Forbric/ConfigApi] config events reach their declared public callbacks");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("config callback failed", Reflect.unwrap(failure)); }
    }
    private synchronized Sink sink(ClassLoader game, ConfigEvent event) {
        if (!looked) {
            try {
                Class<?> api = Class.forName(API, false, game);
                Method invoker = Class.forName("net.fabricmc.fabric.api.event.Event", false, game).getMethod("invoker");
                Class<?> config = Class.forName("net.neoforged.fml.config.ModConfig", false, game);
                bind(api, invoker, config, ConfigEvent.LOADING, "loading", "Loading", "onModConfigLoading");
                bind(api, invoker, config, ConfigEvent.RELOADING, "reloading", "Reloading", "onModConfigReloading");
                bind(api, invoker, config, ConfigEvent.UNLOADING, "unloading", "Unloading", "onModConfigUnloading");
            } catch (ClassNotFoundException absent) {
                // This protocol is optional; no installed API means there is no subscriber to deliver to.
            } catch (ReflectiveOperationException | LinkageError incompatible) {
                ForbricLog.warn("[Forbric/ConfigApi] public config event contract is unavailable", incompatible);
            }
            looked = true;
        }
        return sinks.get(event);
    }
    private void bind(Class<?> api, Method invoker, Class<?> config, ConfigEvent event, String accessor,
            String callbackType, String callbackMethod) {
        try {
            sinks.put(event, new Sink(api.getMethod(accessor, String.class), invoker,
                Class.forName(API + "$" + callbackType, false, api.getClassLoader()).getMethod(callbackMethod, config)));
        } catch (ReflectiveOperationException | LinkageError incompatible) {
            ForbricLog.warn("[Forbric/ConfigApi] public " + event + " event contract is unavailable", incompatible);
        }
    }
}
