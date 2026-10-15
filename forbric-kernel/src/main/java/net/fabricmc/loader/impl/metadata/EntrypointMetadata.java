/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.fabricmc.loader.impl.metadata;

/** One declared entrypoint, as Fabric Loader's internal metadata hands it out: its language adapter and its value. */
public interface EntrypointMetadata {
	String getAdapter();

	String getValue();
}
