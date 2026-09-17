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

package forbric.bridge;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.RegisterEvent;

/**
 * The Forbric bridge mod: a genuine, minimal traditional-Forge {@code @Mod} whose only job is to open the
 * Fabric-content window from INSIDE Forge's real registration span. Its {@code RegisterEvent} listener fires
 * while {@code GameData.postRegisterEvents} runs (registries genuinely writable); the callback into the
 * Knot-loaded forbricruntime is reflective because this jar is compiled only against the Forge runtime.
 *
 * <p>Compiled at assemble time by run/assemble-minecraftforge-runtime.sh (never redistributed); enters the
 * game like any other raw Forge mod - discovered, wrapped, layered, and constructed by the REAL ModLoader.
 */
@Mod("forbric")
public class ForbricBridgeMod {
	public ForbricBridgeMod(FMLJavaModLoadingContext ctx) {
		System.out.println("[ForbricBridge] @Mod(\"forbric\") constructed by the real ModLoader");
		RegisterEvent.getBus(ctx.getModBusGroup()).addListener(event -> {
			try {
				Class.forName("net.forbric.loader.impl.forge.minecraftforge.ForbricFabricWindow",
						true, ForbricBridgeMod.class.getClassLoader())
						.getMethod("openWindowAndRunFabricMains").invoke(null);
			} catch (ReflectiveOperationException e) {
				System.out.println("[ForbricBridge] window callback failed: " + e);
				e.printStackTrace(System.out);
			}
		});
	}
}
