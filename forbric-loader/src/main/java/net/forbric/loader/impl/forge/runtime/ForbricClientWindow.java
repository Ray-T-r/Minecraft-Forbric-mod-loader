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

import net.forbric.loader.impl.util.ForbricLog;

/**
 * Ecosystem dispatcher for the client-side Fabric-content window. Substrate patch 0005 ({@code Hooks.java})
 * calls this ONE stable entry when {@code -Dforbric.fabricMainDeferred=true}; it routes to the active Forge
 * family's window so the client path works on either the traditional-MinecraftForge base or the NeoForge base.
 *
 * <p>The family is detected by which runtime is staged (resource probe — the same signal the drivers gate on):
 * {@code net/minecraftforge/fml/loading/FMLLoader.class} &rarr; MinecraftForge window (unlock NamespacedWrappers
 * across the whole client init); {@code net/neoforged/fml/loading/FMLLoader.class} &rarr; NeoForge window (run
 * deferred mains inside NeoForge's ambient unfreeze). Both live in the Knot-loaded {@code forbricruntime} jar,
 * so this parent-loaded... no — this class ships in the runtime jar too; it is reached reflectively by the
 * parent-loaded Hooks via the thread-context (Knot) classloader.
 */
public final class ForbricClientWindow {
	private ForbricClientWindow() {
	}

	/**
	 * Run the client-init stage inside the active family's registration-unlock span. On a base with neither
	 * runtime staged (pure Fabric), just runs {@code clientInit} directly.
	 */
	public static void runClientInitUnlocked(Runnable clientInit) {
		ClassLoader cl = ForbricClientWindow.class.getClassLoader();

		if (cl.getResource("net/minecraftforge/fml/loading/FMLLoader.class") != null) {
			net.forbric.loader.impl.forge.minecraftforge.ForbricFabricWindow.runClientInitUnlocked(clientInit);
			return;
		}

		if (cl.getResource("net/neoforged/fml/loading/FMLLoader.class") != null) {
			net.forbric.loader.impl.forge.neoforge.ForbricNeoFabricWindow.runClientInitUnlocked(clientInit);
			return;
		}

		ForbricLog.info("[Forbric] no Forge-family runtime staged — running Fabric client init directly");
		clientInit.run();
	}
}
