/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.fabricmc.loader.impl.metadata;

/**
 * Fabric Loader's version-override table, which a mod constructs only to hand to {@link ModMetadataParser#parseMetadata}.
 * The kernel reads no overrides (Fabric Loader's come from a system property for development), so it holds none.
 */
public final class VersionOverrides {
	public VersionOverrides() {
	}
}
