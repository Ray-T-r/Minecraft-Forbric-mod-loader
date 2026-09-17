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

package forbric.bridge.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * The Forbric NeoForge bridge mod: a genuine, minimal NeoForge {@code @Mod} whose only job is to open the
 * Fabric-content window from INSIDE NeoForge's real registration span. Its {@code RegisterEvent} listener fires
 * while {@code GameData.postRegisterEvents} runs (registries genuinely writable, globally unfrozen); the
 * callback into the Knot-loaded forbricruntime is reflective because this jar is compiled only against the
 * NeoForge runtime.
 *
 * <p>NeoForge injects the mod bus into the {@code @Mod} constructor by parameter type ({@code IEventBus}).
 * Compiled at assemble time by run/assemble-neoforge-runtime.sh (never redistributed); enters the game like any
 * other NeoForge mod — discovered, layered, and constructed by the REAL ModLoader.
 */
@Mod("forbricneo")
public class ForbricNeoBridgeMod {
	public ForbricNeoBridgeMod(IEventBus modBus) {
		System.out.println("[ForbricNeoBridge] @Mod(\"forbricneo\") constructed by the real NeoForge ModLoader");
		modBus.addListener(RegisterEvent.class, event -> {
			try {
				Class.forName("net.forbric.loader.impl.forge.neoforge.ForbricNeoFabricWindow",
						true, ForbricNeoBridgeMod.class.getClassLoader())
						.getMethod("openWindowAndRunFabricMains").invoke(null);
			} catch (ReflectiveOperationException e) {
				System.out.println("[ForbricNeoBridge] window callback failed: " + e);
				e.printStackTrace(System.out);
			}
		});
	}
}
