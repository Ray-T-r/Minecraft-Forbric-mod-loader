/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.api;

import java.util.function.Function;

/**
 * An optional, independently discoverable SDK protocol adapter. Publish implementations in
 * {@code META-INF/services/net.forbric.api.ProtocolExtension}, or register them with
 * {@link ProtocolExtensions.Registry#register}. Providers are constructed before game classes load:
 * their initialization must not link game types. Game objects cross this boundary as Object and
 * must be resolved with the context's game loader. No SDK names are known to the dispatcher.
 */
public interface ProtocolExtension {
    String id();
    record Context(ClassLoader gameLoader, Side side) { }
    record TransformData(Side side, boolean development, String namespace, Ecosystem ecosystem, String modId) { }
    @FunctionalInterface interface BytecodeTransform { byte[] apply(String className, byte[] bytes, TransformData context); }
    @FunctionalInterface interface Transforms { void register(String phase, String name, BytecodeTransform transform); }
    enum ConfigEvent { LOADING, RELOADING, UNLOADING }
    record ConfigurationScreen(Function<Object, Object> factory) {
        public ConfigurationScreen { java.util.Objects.requireNonNull(factory); }
        public Object open(Object parent) { return factory.apply(parent); }
    }
    /** Offer absent API class bytes before consumers can link against them. */
    default void offerClasses(Context context) { }
    /** Register protocol transforms before the pipeline starts defining game classes. */
    default void registerTransformers(Context context, Transforms transforms) { }
    /** Runs in the open registration window, before the carrier loads configurations. */
    default void beforeConfigurationLoading(Context context) { }
    /** Query availability without constructing a screen; null means this protocol has no factory. */
    default ConfigurationScreen configurationScreen(Context context, ModCatalog.Entry mod) { return null; }
    default void configEvent(Context context, ConfigEvent event, Object config) { }
}
