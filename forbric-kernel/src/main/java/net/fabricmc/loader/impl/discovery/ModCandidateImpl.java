/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.fabricmc.loader.impl.discovery;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import net.fabricmc.loader.impl.metadata.LoaderModMetadata;

/**
 * Fabric Loader's discovered-mod candidate, as a mod that registers a mod of its own at run time builds one: Essential's
 * loader calls {@code createPlain(paths, metadata, requiresRemap, nestedMods)} and hands the candidate to
 * {@code new ModContainerImpl(candidate)}. It carries the jar and the metadata; nothing is discovered or remapped here.
 */
public final class ModCandidateImpl {
	private final List<Path> paths;
	private final LoaderModMetadata metadata;

	private ModCandidateImpl(List<Path> paths, LoaderModMetadata metadata) {
		this.paths = List.copyOf(paths);
		this.metadata = metadata;
	}

	static ModCandidateImpl createPlain(List<Path> paths, LoaderModMetadata metadata, boolean requiresRemap,
			Collection<ModCandidateImpl> nestedMods) {
		return new ModCandidateImpl(paths, metadata);
	}

	public List<Path> getPaths() {
		return paths;
	}

	public LoaderModMetadata getMetadata() {
		return metadata;
	}
}
