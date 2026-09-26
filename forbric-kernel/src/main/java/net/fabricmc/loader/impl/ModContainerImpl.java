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

import net.fabricmc.loader.api.ModContainer;

/**
 * The type Fabric Loader's mod containers have, so a mod that casts to it succeeds.
 *
 * <p>SuperMartijn642's Core Lib looks its own container up with
 * {@code getModContainer(id).map(ModContainerImpl.class::cast)} and hands the result to
 * {@code EntrypointStorage$NewEntry}'s constructor, whose parameter is this type. Every container the kernel hands out
 * is a {@code KernelModContainer}, which extends this, so that cast holds for every mod as it does on Fabric.
 *
 * <p>Nothing of Fabric's implementation is declared here: a mod calling one of its methods fails at link time by
 * name, rather than getting an answer the kernel made up. {@code -Dforbric.fabricImpl=off} withholds it from mods.
 */
public abstract class ModContainerImpl implements ModContainer {
	protected ModContainerImpl() {
	}
}
