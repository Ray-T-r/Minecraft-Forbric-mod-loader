/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop.protocol.spectre;
import net.forbric.api.ProtocolExtension;
import net.forbric.kernel.boot.KernelFabricEcosystem;
import net.forbric.kernel.interop.ConfigEntrypointInitializer;
import net.forbric.kernel.transform.*;
import net.forbric.kernel.util.ForbricLog;

/** The optional SpectreLib config entrypoint protocol. */
public final class SpectreProtocolExtension implements ProtocolExtension {
    public static final String API = "com/illusivesoulworks/spectrelib/config/SpectreConfigInitializer";
    private static final String KEY = "spectrelib-config";
    @Override public String id() { return "spectre-config"; }
    @Override public void registerTransformers(Context context, Transforms transforms) {
        transforms.register("COREMOD", "absent-entrypoint", net.forbric.kernel.interop.protocol.ProtocolTransformAdapters.transform(new MissingEntrypointInterfaceInjector(API,
            ConfigEntrypointInitializer.class, () -> context.gameLoader().getResource(API + ".class") != null)));
    }
    @Override public void beforeConfigurationLoading(Context context) {
        if (context.gameLoader().getResource(API + ".class") != null) return;
        int count = KernelFabricEcosystem.invokeEntrypoints(KEY, ConfigEntrypointInitializer.class,
            ConfigEntrypointInitializer::onInitializeConfig);
        if (count > 0) ForbricLog.info("[Forbric/Config] initialized %d declared config entrypoint(s) before global configuration loading", count);
    }
}
