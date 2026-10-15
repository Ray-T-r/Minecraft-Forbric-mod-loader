/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.fabricmc.loader.impl.metadata;

import java.io.InputStream;
import java.util.List;

import net.forbric.kernel.fabric.FabricModMetadataParser;

/**
 * Fabric Loader's fabric.mod.json parser, for a mod that registers a mod of its own at run time: Essential's loader parses
 * each jar it unpacks at {@code preLaunch} with this, then injects the result as a mod. Parsed by the kernel's own reader
 * ({@link FabricModMetadataParser}), which is what every Fabric mod's metadata is read with at boot; the overrides are
 * Fabric Loader's development tables and change nothing here.
 */
public final class ModMetadataParser {
	private ModMetadataParser() {
	}

	public static LoaderModMetadata parseMetadata(InputStream is, String modPath, List<String> modParentPaths,
			VersionOverrides versionOverrides, DependencyOverrides depOverrides, boolean isDevelopment) {
		return FabricModMetadataParser.read(is);
	}
}
