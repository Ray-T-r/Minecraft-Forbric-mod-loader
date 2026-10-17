/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.api;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import net.forbric.kernel.util.Caught;

/** One extensible protocol registry per game loader, shared by boot and game-side seams. */
public final class ProtocolExtensions {
    private ProtocolExtensions() { }
    private static final Map<ClassLoader, Registry> REGISTRIES = new IdentityHashMap<>();
    public static synchronized Registry discover(ClassLoader loader, Side side) {
        java.util.Objects.requireNonNull(loader, "game loader");
        Registry present = REGISTRIES.get(loader);
        if (present != null) {
            present.bindSide(side);
            return present;
        }
        Registry registry = new Registry(new ProtocolExtension.Context(loader, side));
        // Publish only after discovery succeeds. A broken declaration must not leave a partial registry.
        for (ProtocolExtension extension : ServiceLoader.load(ProtocolExtension.class, loader)) registry.register(extension);
        REGISTRIES.put(loader, registry);
        return registry;
    }
    public static Registry forLoader(ClassLoader loader) { return discover(loader, null); }
    /** Releases extension state when an embedding application disposes a game loader. */
    public static synchronized void release(ClassLoader loader) { REGISTRIES.remove(loader); }

    public static final class Registry {
        private volatile ProtocolExtension.Context context;
        private final Map<String, ProtocolExtension> extensions = new LinkedHashMap<>();
        public Registry(ProtocolExtension.Context context) { this.context = java.util.Objects.requireNonNull(context); }
        private synchronized void bindSide(Side side) {
            if (side == null) return;
            if (context.side() != null && context.side() != side) throw new IllegalStateException("game loader already has physical side " + context.side());
            context = new ProtocolExtension.Context(context.gameLoader(), side);
        }
        public synchronized void register(ProtocolExtension extension) {
            java.util.Objects.requireNonNull(extension, "protocol extension");
            String id = extension.id();
            if (id == null || id.isBlank()) throw new IllegalArgumentException("protocol extension has no id");
            if (extensions.putIfAbsent(id, extension) != null) throw new IllegalArgumentException("duplicate protocol extension " + id);
        }
        public synchronized List<String> ids() { return List.copyOf(extensions.keySet()); }
        private synchronized List<ProtocolExtension> snapshot() { return List.copyOf(extensions.values()); }
        public void offerClasses() { for (ProtocolExtension extension : snapshot()) extension.offerClasses(context); }
        public void registerTransformers(ProtocolExtension.Transforms transforms) {
            for (ProtocolExtension extension : snapshot()) extension.registerTransformers(context,
                (phase, name, transform) -> transforms.register(phase, extension.id() + "/" + name, transform));
        }
        public void beforeConfigurationLoading() {
            for (ProtocolExtension extension : snapshot()) try { extension.beforeConfigurationLoading(context); }
            catch (Throwable failure) { Caught.warn(failure, "[Forbric/Protocols] %s failed while initializing configurations", extension.id()); }
        }
        public ProtocolExtension.ConfigurationScreen configurationScreen(ModCatalog.Entry mod) {
            for (ProtocolExtension extension : snapshot()) try {
                ProtocolExtension.ConfigurationScreen screen = extension.configurationScreen(context, mod);
                if (screen != null) return screen;
            } catch (Throwable failure) { Caught.warn(failure, "[Forbric/Protocols] %s failed while reading configuration screen", extension.id()); }
            return null;
        }
        public void configEvent(ProtocolExtension.ConfigEvent event, Object config) {
            for (ProtocolExtension extension : snapshot()) try { extension.configEvent(context, event, config); }
            catch (Throwable failure) { Caught.warn(failure, "[Forbric/Protocols] %s failed while delivering %s", extension.id(), event); }
        }
    }
}
