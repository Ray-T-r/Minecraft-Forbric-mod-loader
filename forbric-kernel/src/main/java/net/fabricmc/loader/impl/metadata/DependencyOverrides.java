/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.fabricmc.loader.impl.metadata;

import java.nio.file.Path;

/**
 * Fabric Loader's dependency-override table ({@code config/fabric_loader_dependencies.json}), which a mod constructs only to
 * hand to {@link ModMetadataParser#parseMetadata}. The kernel applies none to a mod registered at run time.
 */
public final class DependencyOverrides {
	public DependencyOverrides(Path configDir) {
	}
}
