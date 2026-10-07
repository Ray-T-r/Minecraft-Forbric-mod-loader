/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

/** The executable no-argument initialization contract of a declared configuration entrypoint. */
public interface ConfigEntrypointInitializer {
    void onInitializeConfig();
}
