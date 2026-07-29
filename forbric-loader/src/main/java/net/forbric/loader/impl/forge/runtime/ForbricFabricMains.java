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

package net.forbric.loader.impl.forge.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

import net.forbric.loader.impl.util.ForbricLog;

/**
 * Process-wide, run-once invoker of the Fabric {@code "main"} entrypoints, shared by BOTH ecosystems' registration
 * windows ({@code ForbricFabricWindow} for traditional-MinecraftForge, {@code ForbricNeoFabricWindow} for
 * NeoForge). Each window still opens/closes its own registry lock the ecosystem-specific way and calls
 * {@link #runOnce()} inside that span — but the actual {@code onInitialize()} calls happen at most once for the
 * whole JVM, guarded here.
 *
 * <p><b>Why a SHARED guard.</b> On the tri-in-one merged base BOTH lifecycles run (NeoForge's genuine lifecycle,
 * then Forge's via {@code ForbricDualLifecycle}), so BOTH bridge mods' {@code RegisterEvent} listeners fire, each
 * opening its own Fabric window. With a per-window guard the Fabric mods' {@code onInitialize()} would run twice —
 * double-registering their content and crashing. Whichever window opens first runs the mains (inside its unlock
 * span); the second finds the guard set and skips them, doing only its own ecosystem's bus/handler setup. On a
 * single-ecosystem instance only one window ever exists, so behaviour is identical to the old per-window guard.
 */
public final class ForbricFabricMains {
	private static final AtomicBoolean RAN = new AtomicBoolean();

	private ForbricFabricMains() {
	}

	/**
	 * Runs every Fabric {@code "main"} entrypoint exactly once for the process. Returns {@code true} if THIS call
	 * ran them, {@code false} if they had already run (so the caller can skip its unlock/relock work). Must be
	 * called from inside whichever registration window has the registries writable.
	 */
	public static boolean runOnce() {
		if (!RAN.compareAndSet(false, true)) return false;
		for (EntrypointContainer<ModInitializer> c
				: FabricLoader.getInstance().getEntrypointContainers("main", ModInitializer.class)) {
			String id = c.getProvider().getMetadata().getId();
			try {
				c.getEntrypoint().onInitialize();
				ForbricLog.info("[Forbric/Bridge] invoked Fabric main entrypoint of " + id);
			} catch (Throwable t) {
				ForbricLog.error("[Forbric/Bridge] Fabric main entrypoint of " + id + " failed", t);
			}
		}
		return true;
	}

	/** Whether the Fabric {@code "main"} entrypoints have already run (either window). */
	public static boolean alreadyRan() {
		return RAN.get();
	}
}
