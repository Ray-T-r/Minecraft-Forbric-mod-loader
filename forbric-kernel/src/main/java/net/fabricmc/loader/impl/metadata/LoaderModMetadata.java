/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.fabricmc.loader.impl.metadata;

import java.util.Collection;
import java.util.List;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.metadata.ModMetadata;

/**
 * The part of Fabric Loader's internal mod metadata a mod reads when it registers a mod of its own at run time: Essential's
 * loader parses the fabric.mod.json of each jar it unpacks ({@link ModMetadataParser}), then reads the entrypoints and
 * mixin configs back from this to register them. Every {@code KernelModMetadata} is one.
 *
 * <p>Only what such mods are observed to call is declared; anything else fails by name at link time.
 */
public interface LoaderModMetadata extends ModMetadata {
	/** The entrypoint keys the mod declares. */
	Collection<String> getEntrypointKeys();

	/** The entrypoints the mod declares under {@code type}, in declaration order. */
	List<EntrypointMetadata> getEntrypoints(String type);

	/** The mixin configs the mod declares for {@code type}'s side. */
	Collection<String> getMixinConfigs(EnvType type);
}
