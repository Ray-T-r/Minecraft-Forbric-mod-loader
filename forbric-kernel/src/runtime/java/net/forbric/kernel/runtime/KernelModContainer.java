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

package net.forbric.kernel.runtime;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * A concrete {@code ModContainer} over a kernel-owned event bus.
 *
 * <p>{@code ModContainer} is abstract with exactly one abstract method, {@code getEventBus()}, and a public
 * constructor taking {@code IModInfo}. The kernel used to emit this subclass with an ASM {@code ClassWriter} at
 * boot — about forty visitor calls spelling out a constructor and a getter — because before the game side had a
 * delivery path there was nowhere to compile it.
 *
 * <p>This is the FALLBACK container. {@link KernelContainers} prefers a genuine {@code FMLModContainer}, because
 * real NeoForge library mods resolve their own bus with {@code ModList.get().getModContainerById(id)} and then
 * narrow to that type — Bookshelf does exactly this and threw "Mod 'bookshelf' is not an FML mod!" against a
 * kernel-generated type, aborting its construction. A subclass satisfies every abstract-typed call but not an
 * {@code instanceof}, so this one is reached only where the genuine class cannot be built.
 */
public final class KernelModContainer extends ModContainer {
	private final IEventBus bus;

	public KernelModContainer(IModInfo modInfo, IEventBus bus) {
		super(modInfo);
		this.bus = bus;
	}

	@Override
	public IEventBus getEventBus() {
		return bus;
	}
}
