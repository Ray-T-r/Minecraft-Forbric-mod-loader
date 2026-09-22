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

import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraftforge.event.ForgeEventFactory;
import net.neoforged.neoforge.resource.ResourcePackLoader;

/**
 * Lets a MinecraftForge mod add its own pack finder, which the merged base asked only NeoForge for.
 *
 * <h2>What the merge did</h2>
 *
 * <p>MinecraftForge's {@code addPackFindersServer} had one call site in its own patched game and has none in the
 * merged base; NeoForge's {@code ResourcePackLoader.populatePackRepository} survived at the same places. So a
 * MinecraftForge mod that contributes a data pack — {@code collective}, in the test pack, subscribes to
 * {@code AddPackFindersEvent} — is never asked for it, and its pack is simply not in the repository.
 *
 * <p>NeoForge has no counterpart event to listen to here, so there is nothing to bridge; the surviving call is
 * redirected instead, with NeoForge's exact signature so the rewrite is an owner and the stack is untouched.
 *
 * <h2>Server data only</h2>
 *
 * <p>MinecraftForge declares only the server half of this pair in 26.2 — there is no {@code addPackFindersClient}
 * to call — so the forward is gated on {@link PackType#SERVER_DATA}. Calling the server hook while a resource
 * repository is being built would hand a mod a sink for the wrong kind of pack.
 *
 * <p>The sink is the repository's own {@code addPackFinder}, which is what the call site would have done with
 * the sources anyway.
 */
public final class KernelPackFinders {
	private static final AtomicBoolean WARNED = new AtomicBoolean();
	private static final AtomicBoolean PROVED = new AtomicBoolean();

	private KernelPackFinders() {
	}

	/** NeoForge's signature exactly, so the redirect is an owner and a name. */
	public static void populatePackRepository(PackRepository repository, PackType type, boolean trusted) {
		ResourcePackLoader.populatePackRepository(repository, type, trusted);
		try {
			if (type != PackType.SERVER_DATA || repository == null) return;
			ForgeEventFactory.addPackFindersServer(repository::addPackFinder);
			if (PROVED.compareAndSet(false, true)) {
				ForbricLog.info("[Forbric/Packs] MinecraftForge mods can add data-pack finders again — the merged "
						+ "base asked only NeoForge, so a Forge-family mod's own data pack was never offered");
			}
		} catch (Throwable t) {
			if (WARNED.compareAndSet(false, true)) {
				ForbricLog.warn("[Forbric/Packs] MinecraftForge's pack-finder hook failed — a Forge-family mod's "
						+ "own data pack will not be in this repository", Reflect.unwrap(t));
			}
		}
	}
}
