/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.impl;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.forbric.kernel.fabric.KernelModContainer;
import net.forbric.kernel.fabric.KernelModMetadata;

/**
 * The type Fabric Loader's mod containers have, so a mod that casts to it succeeds.
 *
 * <p>SuperMartijn642's Core Lib looks its own container up with
 * {@code getModContainer(id).map(ModContainerImpl.class::cast)} and hands the result to
 * {@code EntrypointStorage$NewEntry}'s constructor, whose parameter is this type. Every container the kernel hands out
 * is a {@code KernelModContainer}, which extends this, so that cast holds for every mod as it does on Fabric.
 *
 * <p>And a mod can build one: Essential's loader registers each jar it unpacks at {@code preLaunch} as a mod with
 * {@code new ModContainerImpl(ModCandidateImpl.createPlain(...))}, then adds it to the loader's {@code mods} and
 * {@code modMap}. Such a container is a kernel container of that jar and its metadata ({@link #kernelContainer}), which
 * answers for it.
 *
 * <p>Nothing else of Fabric's implementation is declared here: a mod calling one of its other methods fails at link time
 * by name, rather than getting an answer the kernel made up. {@code -Dforbric.fabricImpl=off} withholds it from mods.
 */
public class ModContainerImpl implements ModContainer {
	private final KernelModContainer kernel;

	protected ModContainerImpl() {
		this.kernel = null;
	}

	public ModContainerImpl(ModCandidateImpl candidate) {
		if (!(candidate.getMetadata() instanceof KernelModMetadata metadata) || candidate.getPaths().isEmpty()) {
			throw new IllegalArgumentException("a mod candidate the kernel did not parse: " + candidate.getMetadata());
		}
		this.kernel = new KernelModContainer(metadata, candidate.getPaths().getFirst(), null);
		// Its mixin configs are its own: the mod that built it registers them with Mixin next, and the kernel reads each
		// as this mod's (a Fabric mod's), as it reads a boot-time mod's.
		for (KernelModMetadata.MixinConfigDecl config : metadata.getMixinConfigs())
			net.forbric.kernel.mixin.MixinConfigOwners.claimLate(config.config(), metadata.getId(), net.forbric.api.Ecosystem.FABRIC);
	}

	/** The kernel container a mod-built container stands for; null for the kernel's own (which are themselves). */
	public KernelModContainer kernelContainer() {
		return kernel;
	}

	private KernelModContainer kernel() {
		if (kernel == null) throw new UnsupportedOperationException(getClass().getName() + " must answer for itself");
		return kernel;
	}

	@Override
	public ModMetadata getMetadata() {
		return kernel().getMetadata();
	}

	@Override
	public List<Path> getRootPaths() {
		return kernel().getRootPaths();
	}

	@Override
	public ModOrigin getOrigin() {
		return kernel().getOrigin();
	}

	@Override
	public Optional<ModContainer> getContainingMod() {
		return kernel().getContainingMod();
	}

	@Override
	public Collection<ModContainer> getContainedMods() {
		return kernel().getContainedMods();
	}

	@Override
	@Deprecated
	public Path getRootPath() {
		return kernel().getRootPath();
	}

	@Override
	@Deprecated
	public Path getPath(String file) {
		return kernel().getPath(file);
	}

	@Override
	public String toString() {
		return kernel == null ? super.toString() : kernel.toString();
	}
}
